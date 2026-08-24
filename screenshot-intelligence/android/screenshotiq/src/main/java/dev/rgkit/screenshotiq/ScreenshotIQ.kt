package dev.rgkit.screenshotiq

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.min

/** What the screenshot most likely contains. */
enum class ScreenshotKind(val label: String) {
    RECEIPT("Receipt / invoice"),
    PRODUCT("Product / shopping"),
    ERROR("Error message"),
    CHAT("Chat conversation"),
    TICKET("Ticket / booking"),
    MAP("Map / directions"),
    CODE("Code / terminal"),
    DOCUMENT("Document / article"),
    SOCIAL("Social media post"),
    OTHER("Other"),
}

/** An action your app could offer right after the screenshot. */
data class SuggestedAction(val id: String, val label: String)

/** The result of analyzing one screenshot (or any image via [ScreenshotIQ.analyze]). */
data class ScreenshotInsight(
    val kind: ScreenshotKind,
    /** 0–1. OTHER always has low confidence. */
    val confidence: Double,
    /** True when the pixels were readable and OCR ran; false = event-only. */
    val analyzed: Boolean,
    /** Extracted entities, e.g. "total" -> "$42.90", "date", "code", "url". */
    val entities: Map<String, String>,
    /** First few hundred chars of recognized text. Never persisted. */
    val textSample: String,
    val suggestions: List<SuggestedAction>,
    /** Content uri of the image when it was accessible, else null. */
    val uri: Uri?,
    val at: Long = System.currentTimeMillis(),
)

data class ScreenshotConfig(
    /**
     * When true (and the app holds READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE),
     * new screenshots are opened and classified with on-device OCR.
     * When false, you still get "a screenshot happened" events on Android 14+.
     */
    val enableContentAnalysis: Boolean = true,
    /** Only images added within this window are treated as "the screenshot". */
    val freshnessMs: Long = 20_000,
)

/**
 * ScreenshotIQ — knows what the user just screenshotted, so the app can react
 * with the right action ("Add this receipt to expenses?", "Report this error?").
 *
 * Two detection paths, both automatic after [init]:
 *  - Android 14+: `Activity.ScreenCaptureCallback` (event even without storage
 *    access — needs only the normal DETECT_SCREEN_CAPTURE permission).
 *  - All versions: a MediaStore observer that notices new images in the
 *    Screenshots folder (requires the app to hold read-images permission).
 *
 * Classification is fully on-device (bundled ML Kit OCR + heuristics).
 * Recognized text is delivered to your listener once and never written to disk;
 * only kind + confidence + timestamp are persisted for stats.
 */
object ScreenshotIQ {

    private const val TAG = "ScreenshotIQ"
    private const val FILE_NAME = "screenshot_iq.json"
    private const val HISTORY_LIMIT = 200

    var config: ScreenshotConfig = ScreenshotConfig()

    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(ScreenshotInsight) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var worker: Handler? = null
    private var observerRegistered = false
    private var lastHandledId = -1L
    private var lastEventAt = 0L
    private var loaded = false

    private data class HistoryEntry(val at: Long, val kind: ScreenshotKind, val confidence: Double)
    private val history = ArrayDeque<HistoryEntry>()
    private val kindTotals = HashMap<ScreenshotKind, Int>()

    // Android 14+ per-activity capture callbacks.
    private val captureCallbacks = HashMap<Activity, Any>()

    // ------------------------------------------------------------------ init

    /** Call once in Application.onCreate(). Sets up both detection paths. */
    fun init(application: Application) {
        synchronized(lock) {
            if (appContext != null) return
            appContext = application
        }
        workerHandler().post { load(application) }
        registerMediaObserver(application)
        application.registerActivityLifecycleCallbacks(object :
            Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = registerCapture(activity)
            override fun onActivityPaused(activity: Activity) = unregisterCapture(activity)
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    /** Fired on the main thread for every detected screenshot. */
    fun addListener(listener: (ScreenshotInsight) -> Unit): (ScreenshotInsight) -> Unit {
        listeners.add(listener); return listener
    }

    fun removeListener(listener: (ScreenshotInsight) -> Unit) { listeners.remove(listener) }

    /**
     * Classify any image through the same pipeline — e.g. an image shared into
     * the app, or a screenshot you obtained yourself. Callback on main thread.
     */
    fun analyze(uri: Uri, callback: (ScreenshotInsight) -> Unit) {
        val context = appContext ?: return
        runOcr(context, uri) { insight -> mainHandler.post { callback(insight) } }
    }

    /** Lifetime counts per kind. */
    fun stats(): Map<ScreenshotKind, Int> = synchronized(lock) { kindTotals.toMap() }

    /** Recent detections (kind/confidence/time only), newest first. */
    fun recent(limit: Int = 50): List<Triple<Long, ScreenshotKind, Double>> =
        synchronized(lock) {
            history.toList().takeLast(limit).reversed()
                .map { Triple(it.at, it.kind, it.confidence) }
        }

    fun exportJson(): String = synchronized(lock) { toJson().toString(2) }

    fun reset() {
        synchronized(lock) { history.clear(); kindTotals.clear() }
        save()
    }

    // ----------------------------------------------------- detection: API 34+

    private fun registerCapture(activity: Activity) {
        if (Build.VERSION.SDK_INT < 34) return
        try {
            val callback = Activity.ScreenCaptureCallback { onScreenshotSignal("capture_callback") }
            synchronized(lock) { captureCallbacks[activity] = callback }
            activity.registerScreenCaptureCallback(activity.mainExecutor, callback)
        } catch (e: Exception) {
            Log.w(TAG, "registerScreenCaptureCallback failed", e)
        }
    }

    private fun unregisterCapture(activity: Activity) {
        if (Build.VERSION.SDK_INT < 34) return
        val callback = synchronized(lock) { captureCallbacks.remove(activity) } ?: return
        try {
            activity.unregisterScreenCaptureCallback(callback as Activity.ScreenCaptureCallback)
        } catch (e: Exception) {
            Log.w(TAG, "unregisterScreenCaptureCallback failed", e)
        }
    }

    // ------------------------------------------------- detection: MediaStore

    private fun registerMediaObserver(context: Context) {
        synchronized(lock) {
            if (observerRegistered) return
            observerRegistered = true
        }
        try {
            context.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                object : ContentObserver(workerHandler()) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        checkForNewScreenshot()
                    }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Media observer failed", e)
        }
    }

    private fun hasReadImages(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    /** Runs on the worker. Finds the newest image and checks it's a fresh screenshot. */
    private fun checkForNewScreenshot() {
        val context = appContext ?: return
        if (!hasReadImages(context)) return
        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            )
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return
                val id = cursor.getLong(0)
                val name = (cursor.getString(1) ?: "").lowercase(Locale.US)
                val addedSec = cursor.getLong(2)
                val bucket = (cursor.getString(3) ?: "").lowercase(Locale.US)

                val isScreenshot = name.contains("screenshot") || bucket.contains("screenshot")
                val fresh = System.currentTimeMillis() / 1000 - addedSec <= config.freshnessMs / 1000
                val alreadySeen = synchronized(lock) {
                    if (id == lastHandledId) true else { lastHandledId = id; false }
                }
                if (!isScreenshot || !fresh || alreadySeen) return

                val uri = Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                )
                if (config.enableContentAnalysis) {
                    runOcr(context, uri) { insight -> deliver(insight) }
                } else {
                    deliver(eventOnlyInsight(uri))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Screenshot query failed", e)
        }
    }

    /** API 34 event with no readable image: try MediaStore shortly after; else event-only. */
    private fun onScreenshotSignal(source: String) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (now - lastEventAt < 1_500) return // capture callback + observer double-fire
            lastEventAt = now
        }
        val context = appContext ?: return
        if (config.enableContentAnalysis && hasReadImages(context)) {
            // The file usually lands in MediaStore within a second of the event.
            workerHandler().postDelayed({ checkForNewScreenshot() }, 900)
        } else {
            deliver(eventOnlyInsight(null))
        }
    }

    private fun eventOnlyInsight(uri: Uri?) = ScreenshotInsight(
        kind = ScreenshotKind.OTHER,
        confidence = 0.0,
        analyzed = false,
        entities = emptyMap(),
        textSample = "",
        suggestions = listOf(SuggestedAction("share", "Share screenshot")),
        uri = uri,
    )

    // -------------------------------------------------------- classification

    private fun runOcr(context: Context, uri: Uri, done: (ScreenshotInsight) -> Unit) {
        try {
            val image = InputImage.fromFilePath(context, uri)
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                .process(image)
                .addOnSuccessListener { visionText ->
                    val insight = classify(visionText.text, visionText.textBlocks.size, uri)
                    done(insight)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "OCR failed", e)
                    done(eventOnlyInsight(uri))
                }
        } catch (e: Exception) {
            Log.w(TAG, "Could not open screenshot", e)
            done(eventOnlyInsight(uri))
        }
    }

    /** Keyword/structure scoring over the OCR text. Visible for testing. */
    internal fun classify(rawText: String, blockCount: Int, uri: Uri?): ScreenshotInsight {
        val text = rawText.take(4_000)
        val lower = text.lowercase(Locale.US)
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val words = lower.split(Regex("\\s+")).filter { it.isNotBlank() }

        val moneyRegex = Regex("""(?:[$€£₪]\s?\d[\d,]*(?:[.,]\d{2})?)|(?:\d[\d,]*[.,]\d{2}\s?(?:[$€£₪]|usd|eur|ils))""")
        val timeRegex = Regex("""\b\d{1,2}:\d{2}(?::\d{2})?\s?(?:am|pm)?\b""", RegexOption.IGNORE_CASE)
        val dateRegex = Regex("""\b(?:\d{1,2}[./-]\d{1,2}[./-]\d{2,4}|(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+\d{1,2})\b""", RegexOption.IGNORE_CASE)
        val codeTokenRegex = Regex("""[{};]|=>|==|!=|\bfun\b|\bdef\b|\bvoid\b|\breturn\b|\bimport\b|\bclass\b""")
        val urlRegex = Regex("""https?://\S+|www\.\S+\.\S+""")
        val bookingCodeRegex = Regex("""\b[A-Z0-9]{6}\b""")

        val moneyMatches = moneyRegex.findAll(text).map { it.value }.toList()
        val timeMatches = timeRegex.findAll(text).count()
        val shortLineRatio =
            if (lines.isEmpty()) 0.0 else lines.count { it.length <= 42 }.toDouble() / lines.size

        fun hits(vararg keys: String): Int = keys.sumOf { key ->
            var count = 0
            var index = lower.indexOf(key)
            while (index >= 0) { count++; index = lower.indexOf(key, index + key.length) }
            count
        }

        val scores = HashMap<ScreenshotKind, Double>()
        scores[ScreenshotKind.RECEIPT] =
            moneyMatches.size * 1.2 +
                hits("total", "subtotal", "tax", "vat", "receipt", "invoice", "order #", "payment", "paid", "amount due", "change") * 1.5
        scores[ScreenshotKind.PRODUCT] =
            (if (moneyMatches.isNotEmpty()) 2.0 else 0.0) +
                hits("add to cart", "buy now", "in stock", "free shipping", "reviews", "rating", "sold by", "checkout", "wishlist") * 2.0 +
                hits("★", "⭐") * 1.0
        scores[ScreenshotKind.ERROR] =
            hits("error", "exception", "failed", "failure", "crash", "oops", "something went wrong",
                "unable to", "couldn't", "could not", "try again", "not responding", "denied") * 2.0 +
                hits("404", "500", "403", "timeout", "stacktrace", "at java.", "at kotlin.") * 2.5
        scores[ScreenshotKind.CHAT] =
            timeMatches * 0.8 +
                hits("typing", "online", "last seen", "delivered", "read", "reply", "message") * 1.5 +
                (if (shortLineRatio > 0.7 && lines.size >= 6) 3.0 else 0.0)
        scores[ScreenshotKind.TICKET] =
            hits("boarding", "gate", "seat", "row", "flight", "ticket", "admit", "pnr",
                "confirmation", "booking", "reservation", "check-in", "departure", "arrival", "platform") * 2.0 +
                (if (dateRegex.containsMatchIn(text)) 1.0 else 0.0)
        scores[ScreenshotKind.MAP] =
            hits(" min", " km", " mi ", "route", "directions", "eta", "traffic", "toll",
                " st,", " ave", " rd", " blvd", "hwy", "exit") * 1.2 +
                (if (blockCount in 1..8 && words.size < 60) 2.0 else 0.0)
        scores[ScreenshotKind.CODE] =
            codeTokenRegex.findAll(text).count() * 0.9 +
                hits("console", "terminal", "npm", "gradle", "git ", "stack overflow") * 1.5
        scores[ScreenshotKind.SOCIAL] =
            hits("like", "comment", "share", "follow", "followers", "retweet", "repost", "upvote", "subscribe", "views") * 1.2
        scores[ScreenshotKind.DOCUMENT] =
            (if (words.size > 120 && shortLineRatio < 0.5) 3.0 else 0.0) +
                (if (words.size > 250) 2.0 else 0.0)

        val best = scores.maxByOrNull { it.value }
        val kind = if (best == null || best.value < 2.5) ScreenshotKind.OTHER else best.key
        val second = scores.filterKeys { it != best?.key }.values.maxOrNull() ?: 0.0
        val confidence = if (kind == ScreenshotKind.OTHER) 0.3 else {
            min(0.95, 0.45 + 0.06 * (best!!.value - second) + 0.02 * best.value)
        }

        // ---- entities ----
        val entities = LinkedHashMap<String, String>()
        if (moneyMatches.isNotEmpty()) {
            val totalLine = lines.lastOrNull { it.lowercase(Locale.US).contains("total") && moneyRegex.containsMatchIn(it) }
            entities["total"] = totalLine?.let { moneyRegex.find(it)?.value } ?: moneyMatches.last()
        }
        dateRegex.find(text)?.let { entities["date"] = it.value }
        urlRegex.find(text)?.let { entities["url"] = it.value.take(120) }
        if (kind == ScreenshotKind.TICKET) {
            bookingCodeRegex.find(text)?.let { entities["code"] = it.value }
        }
        if (kind == ScreenshotKind.ERROR) {
            lines.firstOrNull {
                it.lowercase(Locale.US).let { l ->
                    l.contains("error") || l.contains("exception") || l.contains("failed")
                }
            }?.let { entities["error_line"] = it.take(140) }
        }

        val suggestions = when (kind) {
            ScreenshotKind.RECEIPT -> listOf(
                SuggestedAction("save_expense", "Save to expenses"),
                SuggestedAction("extract_total", "Use total ${entities["total"] ?: ""}".trim()),
            )
            ScreenshotKind.PRODUCT -> listOf(
                SuggestedAction("save_wishlist", "Save to wishlist"),
                SuggestedAction("price_watch", "Watch this price"),
            )
            ScreenshotKind.ERROR -> listOf(
                SuggestedAction("report_bug", "Report this error"),
                SuggestedAction("search_help", "Search help for this error"),
            )
            ScreenshotKind.CHAT -> listOf(SuggestedAction("save_note", "Save as note"))
            ScreenshotKind.TICKET -> listOf(
                SuggestedAction("add_calendar", "Add to calendar"),
                SuggestedAction("save_ticket", "Save ticket"),
            )
            ScreenshotKind.MAP -> listOf(SuggestedAction("open_maps", "Open in Maps"))
            ScreenshotKind.CODE -> listOf(SuggestedAction("share_snippet", "Share snippet"))
            ScreenshotKind.DOCUMENT -> listOf(SuggestedAction("save_pdf", "Save as PDF"))
            ScreenshotKind.SOCIAL -> listOf(SuggestedAction("save_bookmark", "Bookmark post"))
            ScreenshotKind.OTHER -> listOf(SuggestedAction("share", "Share screenshot"))
        }

        return ScreenshotInsight(
            kind = kind,
            confidence = (confidence * 100).toInt() / 100.0,
            analyzed = true,
            entities = entities,
            textSample = text.take(400),
            suggestions = suggestions,
            uri = uri,
        )
    }

    // ----------------------------------------------------------- deliver/io

    /** Records an insight and fans it out. Internal so tests can stage one. */
    internal fun deliver(insight: ScreenshotInsight) {
        synchronized(lock) {
            kindTotals[insight.kind] = (kindTotals[insight.kind] ?: 0) + 1
            history.addLast(HistoryEntry(insight.at, insight.kind, insight.confidence))
            while (history.size > HISTORY_LIMIT) history.removeFirst()
        }
        save()
        mainHandler.post { for (l in listeners) runCatching { l(insight) } }
    }

    private var workerThread: HandlerThread? = null

    private fun workerHandler(): Handler {
        synchronized(lock) {
            if (worker == null) {
                workerThread = HandlerThread("ScreenshotIQ").also { it.start() }
                worker = Handler(workerThread!!.looper)
            }
            return worker!!
        }
    }

    private fun load(context: Context) {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            try {
                val file = File(context.filesDir, FILE_NAME)
                if (!file.exists()) return
                val root = JSONObject(file.readText())
                val totals = root.optJSONObject("totals") ?: JSONObject()
                for (name in totals.keys()) {
                    runCatching { kindTotals[ScreenshotKind.valueOf(name)] = totals.getInt(name) }
                }
                val arr = root.optJSONArray("history") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    runCatching {
                        history.addLast(
                            HistoryEntry(
                                o.getLong("at"),
                                ScreenshotKind.valueOf(o.getString("kind")),
                                o.getDouble("confidence"),
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load state", e)
            }
        }
    }

    private fun save() {
        val context = appContext ?: return
        val json = synchronized(lock) { toJson().toString() }
        workerHandler().post {
            try {
                File(context.filesDir, FILE_NAME).writeText(json)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save state", e)
            }
        }
    }

    private fun toJson(): JSONObject {
        val totals = JSONObject()
        for ((kind, count) in kindTotals) totals.put(kind.name, count)
        val arr = JSONArray()
        for (h in history) {
            arr.put(
                JSONObject().put("at", h.at).put("kind", h.kind.name)
                    .put("confidence", h.confidence)
            )
        }
        return JSONObject().put("totals", totals).put("history", arr)
    }
}
