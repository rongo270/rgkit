package dev.rgkit.formsense

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/** Friction analysis for one field of a form, worst problems surfaced first. */
data class FieldFriction(
    val fieldId: String,
    val visits: Int,
    /** Average active (focused) time per visit, ms. */
    val avgActiveMs: Long,
    /** Deleted chars / typed chars. 0.15 is normal; 0.4+ means struggle. */
    val correctionRatio: Double,
    /** Average number of times the field is re-entered per form attempt. */
    val refocusAvg: Double,
    val errorsShown: Int,
    /** Share of abandons where this was the last focused field. */
    val abandonShare: Double,
    /** 0–100. Higher = more friction. */
    val frictionScore: Int,
    /** Rule-based advice for this field. */
    val suggestion: String,
)

/** Full report for one form. */
data class FormReport(
    val formId: String,
    val starts: Int,
    val submits: Int,
    val abandons: Int,
    /** submits / starts, percent. */
    val conversionPercent: Int,
    /** Median time from first focus to submit, ms (successful attempts). */
    val medianCompletionMs: Long,
    /** Fields sorted by friction, worst first. */
    val fields: List<FieldFriction>,
)

/**
 * FormSense — finds exactly where your forms hurt.
 *
 * Wire each field's focus and text-length changes (one modifier in Compose)
 * and FormSense measures, per field: dwell time, correction ratio (deletes vs
 * types), refocus count, error count, and — most valuable — **which field
 * users were on when they gave up**. The report ranks fields by friction and
 * attaches a concrete suggestion to each.
 *
 * All aggregates are on-device; no keystrokes or text content are ever
 * recorded — only lengths and counts.
 */
object FormSense {

    private const val TAG = "FormSense"
    private const val FILE_NAME = "form_sense.json"

    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "FormSense-io") }

    private var appContext: Context? = null
    private var loaded = false

    // ---- persisted aggregates ----
    internal class FieldAgg {
        var visits = 0
        var activeMs = 0L
        var charsTyped = 0L
        var charsDeleted = 0L
        var errors = 0
        var abandonsHere = 0
    }

    internal class FormAgg {
        var starts = 0
        var submits = 0
        var abandons = 0
        val completionSamples = java.util.ArrayDeque<Long>() // last 30
        val fields = LinkedHashMap<String, FieldAgg>()
    }

    private val forms = HashMap<String, FormAgg>()
    private val liveTrackers = HashMap<String, FormTracker>()

    // ------------------------------------------------------------------ init

    /** Call once in Application.onCreate(). Auto-abandons live forms on background. */
    fun init(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (appContext != null) return
            appContext = app
        }
        io.execute { load(app) }
        (app as? Application)?.registerActivityLifecycleCallbacks(object :
            Application.ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) { started++ }
            override fun onActivityStopped(activity: Activity) {
                started--
                if (started <= 0) {
                    started = 0
                    val live = synchronized(lock) { liveTrackers.values.toList() }
                    for (tracker in live) tracker.abandoned()
                }
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    /**
     * Get (or resume) the live tracker for a form. The attempt starts counting
     * on the first field focus. Finish it with `submitted()` or `abandoned()`.
     */
    fun form(formId: String): FormTracker = synchronized(lock) {
        liveTrackers.getOrPut(formId) { FormTracker(formId) }
    }

    // --------------------------------------------------------------- reading

    /** Friction report for one form, or null if never seen. */
    fun report(formId: String): FormReport? {
        synchronized(lock) {
            val agg = forms[formId] ?: return null
            val abandonsTotal = max(1, agg.abandons)
            val fields = agg.fields.map { (fieldId, f) ->
                val visits = max(1, f.visits)
                val correction = if (f.charsTyped == 0L) 0.0
                    else f.charsDeleted.toDouble() / f.charsTyped
                val refocus = f.visits.toDouble() / max(1, agg.starts)
                val abandonShare = f.abandonsHere.toDouble() / abandonsTotal
                val avgActive = f.activeMs / visits
                val score = frictionScore(avgActive, correction, refocus, f.errors, abandonShare, agg.starts)
                FieldFriction(
                    fieldId = fieldId,
                    visits = f.visits,
                    avgActiveMs = avgActive,
                    correctionRatio = (correction * 100).toInt() / 100.0,
                    refocusAvg = (refocus * 10).toInt() / 10.0,
                    errorsShown = f.errors,
                    abandonShare = (abandonShare * 100).toInt() / 100.0,
                    frictionScore = score,
                    suggestion = suggestFor(avgActive, correction, refocus, f.errors, abandonShare),
                )
            }.sortedByDescending { it.frictionScore }
            val completion = agg.completionSamples.toList().sorted()
            return FormReport(
                formId = formId,
                starts = agg.starts,
                submits = agg.submits,
                abandons = agg.abandons,
                conversionPercent = if (agg.starts == 0) 0 else agg.submits * 100 / agg.starts,
                medianCompletionMs = if (completion.isEmpty()) 0 else completion[completion.size / 2],
                fields = fields,
            )
        }
    }

    /** Reports for every form seen, lowest conversion first. */
    fun reports(): List<FormReport> = synchronized(lock) { forms.keys.toList() }
        .mapNotNull { report(it) }
        .sortedBy { it.conversionPercent }

    fun exportJson(): String = synchronized(lock) { toJson().toString(2) }

    fun reset() {
        synchronized(lock) { forms.clear(); liveTrackers.clear() }
        save()
    }

    // ---------------------------------------------------------------- scoring

    private fun frictionScore(
        avgActiveMs: Long,
        correction: Double,
        refocus: Double,
        errors: Int,
        abandonShare: Double,
        starts: Int,
    ): Int {
        var score = 0.0
        score += min(30.0, max(0.0, (avgActiveMs - 8_000.0)) / 800.0)   // slow field
        score += min(25.0, max(0.0, (correction - 0.15)) * 80.0)        // heavy corrections
        score += min(15.0, max(0.0, (refocus - 1.3)) * 12.0)            // keeps coming back
        score += min(15.0, errors.toDouble() / max(1, starts) * 20.0)   // validation errors
        score += min(35.0, abandonShare * 45.0)                         // kills the form
        return min(100.0, score).toInt()
    }

    private fun suggestFor(
        avgActiveMs: Long,
        correction: Double,
        refocus: Double,
        errors: Int,
        abandonShare: Double,
    ): String {
        val advice = ArrayList<String>()
        if (abandonShare > 0.3) advice += "users give up here — make it optional, move it later, or explain why it's needed"
        if (correction > 0.35) advice += "heavy retyping — add the right keyboard type, input mask, or inline validation while typing"
        if (avgActiveMs > 15_000) advice += "takes very long — split it, add an example placeholder, or autofill it"
        if (refocus > 1.8) advice += "users keep returning to it — validation surprises or unclear requirements shown too late"
        if (errors > 0 && advice.isEmpty()) advice += "errors appear after submit — validate inline before the user leaves the field"
        if (advice.isEmpty()) return "looks healthy"
        return advice.joinToString("; ")
    }

    // --------------------------------------------------------- live tracking

    /** Live tracker for one form attempt. Get it via [FormSense.form]. */
    class FormTracker internal constructor(val formId: String) {
        private var startedAt = 0L
        private var currentField: String? = null
        private var focusedAt = 0L
        private val lastLength = HashMap<String, Int>()
        private var finished = false

        fun field(fieldId: String): FieldTracker = FieldTracker(this, fieldId)

        internal fun onFocus(fieldId: String) {
            val now = System.currentTimeMillis()
            synchronized(lock) {
                val agg = formAgg()
                if (startedAt == 0L) { startedAt = now; agg.starts++ }
                flushActiveLocked(now)
                currentField = fieldId
                focusedAt = now
                agg.fieldAgg(fieldId).visits++
            }
            save()
        }

        internal fun onBlur(fieldId: String) {
            val now = System.currentTimeMillis()
            synchronized(lock) {
                if (currentField == fieldId) {
                    flushActiveLocked(now)
                    currentField = null
                }
            }
        }

        internal fun onTextChanged(fieldId: String, newLength: Int) {
            synchronized(lock) {
                if (startedAt == 0L) { startedAt = System.currentTimeMillis(); formAgg().starts++ }
                val prev = lastLength[fieldId] ?: 0
                lastLength[fieldId] = newLength
                val delta = newLength - prev
                val agg = formAgg().fieldAgg(fieldId)
                if (delta > 0) agg.charsTyped += delta else agg.charsDeleted += -delta
            }
        }

        internal fun onError(fieldId: String) {
            synchronized(lock) { formAgg().fieldAgg(fieldId).errors++ }
            save()
        }

        /** The form was successfully submitted. Ends this attempt. */
        fun submitted() {
            val now = System.currentTimeMillis()
            synchronized(lock) {
                if (finished) return
                finished = true
                flushActiveLocked(now)
                val agg = formAgg()
                agg.submits++
                if (startedAt > 0) {
                    agg.completionSamples.addLast(now - startedAt)
                    while (agg.completionSamples.size > 30) agg.completionSamples.removeFirst()
                }
                liveTrackers.remove(formId)
            }
            save()
        }

        /** The user gave up (called automatically when the app backgrounds). */
        fun abandoned() {
            val now = System.currentTimeMillis()
            synchronized(lock) {
                if (finished || startedAt == 0L) {
                    liveTrackers.remove(formId)
                    return
                }
                finished = true
                flushActiveLocked(now)
                val agg = formAgg()
                agg.abandons++
                // Attribute the abandon to the last field the user touched.
                val last = currentField ?: lastLength.keys.lastOrNull()
                last?.let { agg.fieldAgg(it).abandonsHere++ }
                liveTrackers.remove(formId)
            }
            save()
        }

        /** Discard the attempt without counting it (e.g. programmatic close). */
        fun discard() {
            synchronized(lock) {
                finished = true
                liveTrackers.remove(formId)
            }
        }

        private fun formAgg(): FormAgg = forms.getOrPut(formId) { FormAgg() }

        private fun FormAgg.fieldAgg(fieldId: String): FieldAgg =
            fields.getOrPut(fieldId) { FieldAgg() }

        /** Must hold [lock]. */
        private fun flushActiveLocked(now: Long) {
            val field = currentField ?: return
            if (focusedAt > 0) {
                val active = now - focusedAt
                if (active in 1..600_000) formAgg().fieldAgg(field).activeMs += active
            }
            focusedAt = now
        }
    }

    /** Thin per-field handle. */
    class FieldTracker internal constructor(
        private val form: FormTracker,
        private val fieldId: String,
    ) {
        fun focused() = form.onFocus(fieldId)
        fun blurred() = form.onBlur(fieldId)
        /** Call with the new text length after every change (never the text itself). */
        fun textChanged(newLength: Int) = form.onTextChanged(fieldId, newLength)
        fun errorShown() = form.onError(fieldId)
    }

    // ------------------------------------------------------------ persistence

    private fun load(context: Context) {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            try {
                val file = File(context.filesDir, FILE_NAME)
                if (!file.exists()) return
                val root = JSONObject(file.readText())
                val f = root.optJSONObject("forms") ?: JSONObject()
                for (formId in f.keys()) {
                    val o = f.getJSONObject(formId)
                    val agg = FormAgg()
                    agg.starts = o.optInt("starts")
                    agg.submits = o.optInt("submits")
                    agg.abandons = o.optInt("abandons")
                    val samples = o.optJSONArray("completionSamples")
                    if (samples != null) {
                        for (i in 0 until samples.length()) {
                            agg.completionSamples.addLast(samples.getLong(i))
                        }
                    }
                    val fields = o.optJSONObject("fields") ?: JSONObject()
                    for (fieldId in fields.keys()) {
                        val fo = fields.getJSONObject(fieldId)
                        val fa = FieldAgg()
                        fa.visits = fo.optInt("visits")
                        fa.activeMs = fo.optLong("activeMs")
                        fa.charsTyped = fo.optLong("charsTyped")
                        fa.charsDeleted = fo.optLong("charsDeleted")
                        fa.errors = fo.optInt("errors")
                        fa.abandonsHere = fo.optInt("abandonsHere")
                        agg.fields[fieldId] = fa
                    }
                    forms[formId] = agg
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load state", e)
            }
        }
    }

    private fun save() {
        val context = appContext ?: return
        val json = synchronized(lock) { toJson().toString() }
        io.execute {
            try {
                File(context.filesDir, FILE_NAME).writeText(json)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save state", e)
            }
        }
    }

    private fun toJson(): JSONObject {
        val f = JSONObject()
        for ((formId, agg) in forms) {
            val fields = JSONObject()
            for ((fieldId, fa) in agg.fields) {
                fields.put(
                    fieldId,
                    JSONObject()
                        .put("visits", fa.visits)
                        .put("activeMs", fa.activeMs)
                        .put("charsTyped", fa.charsTyped)
                        .put("charsDeleted", fa.charsDeleted)
                        .put("errors", fa.errors)
                        .put("abandonsHere", fa.abandonsHere)
                )
            }
            val samples = org.json.JSONArray()
            for (v in agg.completionSamples) samples.put(v)
            f.put(
                formId,
                JSONObject()
                    .put("starts", agg.starts)
                    .put("submits", agg.submits)
                    .put("abandons", agg.abandons)
                    .put("completionSamples", samples)
                    .put("fields", fields)
            )
        }
        return JSONObject().put("forms", f)
    }
}
