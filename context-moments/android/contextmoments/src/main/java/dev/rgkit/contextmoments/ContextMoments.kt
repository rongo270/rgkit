package dev.rgkit.contextmoments

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.CalendarContract
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.Calendar
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Coarse motion state derived from a short accelerometer burst. */
enum class MotionState { STILL, WALKING, RUNNING, IN_VEHICLE, UNKNOWN }

/** The user's current life-moment, as far as on-device signals can tell. */
enum class Moment(val label: String) {
    SLEEPING("Sleeping"),
    JUST_WOKE_UP("Just woke up"),
    COMMUTING("Commuting / driving"),
    WALKING("Walking"),
    WORKING_OUT("Working out"),
    WORKING("Working"),
    IN_MEETING("In a meeting"),
    WATCHING_TV("Watching TV / relaxing screen time"),
    RELAXING("Relaxing at home"),
    OUT_AND_ABOUT("Out and about"),
    UNKNOWN("Unknown"),
}

/** Raw signal values that went into a fusion round. All cheap, all on-device. */
data class SignalState(
    val motion: MotionState,
    /** Accelerometer magnitude standard deviation for the burst (m/s²). */
    val motionEnergy: Double,
    /** 0–1 (system screen brightness setting). */
    val screenBrightness: Double,
    val charging: Boolean,
    val batteryPercent: Int,
    val ringerSilentOrVibrate: Boolean,
    val dndOn: Boolean,
    val wiredHeadset: Boolean,
    val bluetoothAudio: Boolean,
    val onWifi: Boolean,
    val hourOfDay: Double,
    val weekday: Boolean,
    /** Minutes since the last screen-on after a 4h+ dark gap; -1 if not recent. */
    val minutesSinceLongSleepWake: Long,
    /** Null when the calendar provider is disabled or permission not granted. */
    val calendarBusy: Boolean?,
    /** Rough ambient loudness in dB-ish units; null when disabled/not granted. */
    val ambientDb: Double?,
)

/** One fused result: the winning moment, its confidence, and the full scoreboard. */
data class MomentSnapshot(
    val moment: Moment,
    /** 0–1. Below ~0.45 treat as a weak hint only. */
    val confidence: Double,
    val scores: Map<Moment, Double>,
    val signals: SignalState,
    val at: Long = System.currentTimeMillis(),
)

data class MomentsConfig(
    /** How often to re-evaluate while [ContextMoments.start]ed. */
    val intervalMs: Long = 30_000,
    /** Accelerometer burst length per evaluation. */
    val burstMs: Long = 2_500,
    /** Opt-in: sample ~0.5 s of microphone loudness (needs RECORD_AUDIO granted).
     *  Only an RMS level is computed; audio is never stored or sent anywhere. */
    val enableAmbientAudio: Boolean = false,
    /** Opt-in: check the calendar for a busy event right now (needs READ_CALENDAR). */
    val enableCalendar: Boolean = false,
    /** A new moment must win this many consecutive rounds before listeners fire. */
    val stabilityRounds: Int = 2,
)

/**
 * ContextMoments — understands the user's current context, not just events.
 *
 * Fuses motion (duty-cycled accelerometer bursts), screen/brightness, audio
 * routes, ringer/DND, network, charging, time-of-day, and (opt-in) calendar
 * busy state and ambient loudness into a single [MomentSnapshot]:
 * driving, walking, working, in a meeting, just woke up, watching TV…
 *
 * Battery friendly by design: sensors run ~2.5 s out of every 30 s, only
 * while [start]ed. For background decisions (e.g. "is now a good time for
 * this notification?") call [sampleNow] from a worker instead of running
 * continuously.
 */
object ContextMoments {

    private const val TAG = "ContextMoments"
    private const val FILE_NAME = "context_moments.json"
    private const val HISTORY_LIMIT = 500

    var config: MomentsConfig = MomentsConfig()

    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(MomentSnapshot) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    // Separate delivery thread: the worker blocks during a burst, so sensor
    // events must arrive on a different looper or they'd queue until the
    // burst is over and be lost.
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    private var running = false
    private var loaded = false

    @Volatile private var lastSnapshot: MomentSnapshot? = null
    private var candidate: Moment? = null
    private var candidateRounds = 0

    // Screen on/off tracking (for SLEEPING / JUST_WOKE_UP).
    @Volatile private var lastScreenOffAt = 0L
    @Volatile private var lastLongWakeAt = 0L   // screen-on after a 4h+ gap
    private var screenReceiverRegistered = false

    // Persisted: moment transition history + per-day sample counts per moment.
    private data class Transition(val at: Long, val moment: Moment, val confidence: Double)
    private val history = ArrayDeque<Transition>()

    // ------------------------------------------------------------------ init

    /** Call once, e.g. in Application.onCreate(). */
    fun init(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (appContext != null) return
            appContext = app
        }
        registerScreenReceiver(app)
        workerHandler().post { load(app) }
    }

    /** Start continuous evaluation (one fusion round every `config.intervalMs`). */
    fun start() {
        val h = workerHandler()
        synchronized(lock) {
            if (running) return
            running = true
        }
        h.post(evalRunnable)
    }

    /** Stop continuous evaluation. Cheap to start again. */
    fun stop() {
        synchronized(lock) { running = false }
        handler?.removeCallbacks(evalRunnable)
    }

    /**
     * One-shot evaluation (~3 s async) — ideal from WorkManager before deciding
     * to show a notification. Callback arrives on the main thread.
     */
    fun sampleNow(callback: (MomentSnapshot) -> Unit) {
        workerHandler().post {
            val snapshot = evaluateOnce()
            mainHandler.post { callback(snapshot) }
        }
    }

    /** The most recent snapshot, if any evaluation has run. */
    fun current(): MomentSnapshot? = lastSnapshot

    /** Fired on the main thread whenever the stable moment changes. */
    fun addListener(listener: (MomentSnapshot) -> Unit): (MomentSnapshot) -> Unit {
        listeners.add(listener); return listener
    }

    fun removeListener(listener: (MomentSnapshot) -> Unit) { listeners.remove(listener) }

    /** Recent moment transitions, newest first. */
    fun history(limit: Int = 50): List<Triple<Long, Moment, Double>> = synchronized(lock) {
        history.toList().takeLast(limit).reversed().map { Triple(it.at, it.moment, it.confidence) }
    }

    fun exportJson(): String = synchronized(lock) { toJson().toString(2) }

    fun reset() {
        synchronized(lock) { history.clear() }
        save()
    }

    // ------------------------------------------------------------ evaluation

    private val evalRunnable = object : Runnable {
        override fun run() {
            val snapshot = evaluateOnce()
            handleStability(snapshot)
            synchronized(lock) { if (!running) return }
            handler?.postDelayed(this, config.intervalMs)
        }
    }

    /** Runs on the worker thread. Blocks for the burst duration. */
    private fun evaluateOnce(): MomentSnapshot {
        val context = appContext ?: run {
            Log.w(TAG, "sampleNow/start called before init(context)")
            return MomentSnapshot(
                Moment.UNKNOWN, 0.0, emptyMap(),
                SignalState(
                    MotionState.UNKNOWN, 0.0, 0.5, charging = false, batteryPercent = -1,
                    ringerSilentOrVibrate = false, dndOn = false, wiredHeadset = false,
                    bluetoothAudio = false, onWifi = false, hourOfDay = 0.0, weekday = true,
                    minutesSinceLongSleepWake = -1, calendarBusy = null, ambientDb = null,
                ),
            ).also { lastSnapshot = it }
        }
        val motion = sampleMotion(context)
        val signals = readSignals(context, motion.first, motion.second)
        val scores = fuse(signals)
        val best = scores.maxByOrNull { it.value }
        val bestMoment = best?.key ?: Moment.UNKNOWN
        val bestScore = best?.value ?: 0.0
        val second = scores.filterKeys { it != bestMoment }.values.maxOrNull() ?: 0.0
        val margin = bestScore - second
        val confidence = min(1.0, max(0.0, bestScore * 0.75 + margin * 0.5))
        val moment = if (bestScore < 0.35) Moment.UNKNOWN else bestMoment
        val snapshot = MomentSnapshot(
            moment = moment,
            confidence = (confidence * 100).toInt() / 100.0,
            scores = scores.mapValues { (it.value * 100).toInt() / 100.0 },
            signals = signals,
        )
        lastSnapshot = snapshot
        return snapshot
    }

    private fun handleStability(snapshot: MomentSnapshot) {
        var fire = false
        synchronized(lock) {
            val stable = history.lastOrNull()?.moment
            if (snapshot.moment == stable) {
                candidate = null; candidateRounds = 0
            } else if (snapshot.moment == candidate) {
                candidateRounds++
                if (candidateRounds >= config.stabilityRounds) {
                    history.addLast(Transition(snapshot.at, snapshot.moment, snapshot.confidence))
                    while (history.size > HISTORY_LIMIT) history.removeFirst()
                    candidate = null; candidateRounds = 0
                    fire = true
                }
            } else {
                candidate = snapshot.moment; candidateRounds = 1
                if (config.stabilityRounds <= 1) {
                    history.addLast(Transition(snapshot.at, snapshot.moment, snapshot.confidence))
                    while (history.size > HISTORY_LIMIT) history.removeFirst()
                    candidate = null; candidateRounds = 0
                    fire = true
                }
            }
        }
        if (fire) {
            save()
            mainHandler.post { for (l in listeners) runCatching { l(snapshot) } }
        }
    }

    // ----------------------------------------------------------- raw signals

    /** Blocking accelerometer burst → (motion state, magnitude std dev). */
    private fun sampleMotion(context: Context): Pair<MotionState, Double> {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return MotionState.UNKNOWN to 0.0
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: return MotionState.UNKNOWN to 0.0

        val magnitudes = ArrayList<Double>(128)
        val times = ArrayList<Long>(128)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val (x, y, z) = event.values
                synchronized(magnitudes) {
                    magnitudes.add(sqrt((x * x + y * y + z * z).toDouble()))
                    times.add(event.timestamp)
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        try {
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME, sensorDeliveryHandler())
            Thread.sleep(config.burstMs)
        } catch (e: Exception) {
            Log.w(TAG, "Motion burst failed", e)
        } finally {
            sm.unregisterListener(listener)
        }

        val mags: List<Double>
        val stamps: List<Long>
        synchronized(magnitudes) { mags = magnitudes.toList(); stamps = times.toList() }
        if (mags.size < 10) return MotionState.UNKNOWN to 0.0

        val mean = mags.average()
        val variance = mags.sumOf { (it - mean) * (it - mean) } / mags.size
        val std = sqrt(variance)

        // Dominant frequency estimate via hysteresis zero-crossing of the
        // detrended magnitude (crossings/2 per second ≈ Hz).
        val threshold = max(0.25, std * 0.4)
        var crossings = 0
        var sign = 0
        for (m in mags) {
            val v = m - mean
            val s = when {
                v > threshold -> 1
                v < -threshold -> -1
                else -> 0
            }
            if (s != 0 && sign != 0 && s != sign) crossings++
            if (s != 0) sign = s
        }
        val durationSec = if (stamps.size > 1) {
            max(0.5, (stamps.last() - stamps.first()) / 1e9)
        } else config.burstMs / 1000.0
        val freq = crossings / 2.0 / durationSec

        val state = when {
            std < 0.25 -> MotionState.STILL
            std > 3.2 && freq in 2.0..4.0 -> MotionState.RUNNING
            std in 0.55..3.2 && freq in 1.1..2.8 -> MotionState.WALKING
            std in 0.25..1.6 && freq < 1.1 -> MotionState.IN_VEHICLE
            else -> MotionState.UNKNOWN
        }
        return state to std
    }

    private fun readSignals(context: Context, motion: MotionState, energy: Double): SignalState {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val weekday = dow != Calendar.SATURDAY && dow != Calendar.SUNDAY

        val brightness = try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255.0
        } catch (e: Exception) { 0.5 }

        var charging = false
        var battery = -1
        try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) {
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) battery = level * 100 / scale
            }
        } catch (e: Exception) { /* keep defaults */ }

        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val silent = audio?.ringerMode.let {
            it == AudioManager.RINGER_MODE_SILENT || it == AudioManager.RINGER_MODE_VIBRATE
        }
        var wired = false
        var bt = false
        try {
            audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.forEach { d ->
                when (d.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET -> wired = true
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> bt = true
                }
            }
        } catch (e: Exception) { /* keep defaults */ }

        val dnd = try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as? android.app.NotificationManager
            val filter = nm?.currentInterruptionFilter
                ?: android.app.NotificationManager.INTERRUPTION_FILTER_ALL
            filter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL &&
                filter != android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        } catch (e: Exception) { false }

        val wifi = try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } catch (e: Exception) { false }

        val wakeMinutes = if (lastLongWakeAt > 0) {
            (System.currentTimeMillis() - lastLongWakeAt) / 60_000
        } else -1L

        return SignalState(
            motion = motion,
            motionEnergy = (energy * 100).toInt() / 100.0,
            screenBrightness = (brightness * 100).toInt() / 100.0,
            charging = charging,
            batteryPercent = battery,
            ringerSilentOrVibrate = silent,
            dndOn = dnd,
            wiredHeadset = wired,
            bluetoothAudio = bt,
            onWifi = wifi,
            hourOfDay = (hour * 10).toInt() / 10.0,
            weekday = weekday,
            minutesSinceLongSleepWake = wakeMinutes,
            calendarBusy = if (config.enableCalendar) readCalendarBusy(context) else null,
            ambientDb = if (config.enableAmbientAudio) readAmbientDb(context) else null,
        )
    }

    /** Null if permission missing; otherwise whether a busy, non-all-day event overlaps now. */
    private fun readCalendarBusy(context: Context): Boolean? {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        return try {
            val now = System.currentTimeMillis()
            val projection = arrayOf(
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.AVAILABILITY,
            )
            var busy = false
            CalendarContract.Instances.query(
                context.contentResolver, projection, now - 5 * 60_000, now + 5 * 60_000
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val allDay = cursor.getInt(2) == 1
                    val availability = cursor.getInt(3)
                    if (!allDay && availability == CalendarContract.Events.AVAILABILITY_BUSY) {
                        busy = true; break
                    }
                }
            }
            busy
        } catch (e: Exception) {
            Log.w(TAG, "Calendar read failed", e)
            null
        }
    }

    /**
     * Null if permission missing. ~0.5 s mic RMS mapped to a rough dB scale
     * (≈30 quiet room … ≈75 loud). The raw audio never leaves this method.
     */
    @SuppressLint("MissingPermission") // guarded by the checkSelfPermission early-return below
    private fun readAmbientDb(context: Context): Double? {
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        var record: AudioRecord? = null
        return try {
            val sampleRate = 16_000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return null
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, sampleRate)
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) return null
            record.startRecording()
            val buf = ShortArray(sampleRate / 2) // 0.5 s
            var read = 0
            while (read < buf.size) {
                val n = record.read(buf, read, buf.size - read)
                if (n <= 0) break
                read += n
            }
            record.stop()
            if (read < sampleRate / 10) return null
            var sum = 0.0
            for (i in 0 until read) sum += buf[i].toDouble() * buf[i]
            val rms = sqrt(sum / read)
            if (rms <= 1.0) 20.0 else (20 * log10(rms / 32768.0) + 96.0).coerceIn(10.0, 100.0)
        } catch (e: Exception) {
            Log.w(TAG, "Ambient sample failed", e)
            null
        } finally {
            runCatching { record?.release() }
        }
    }

    // ---------------------------------------------------------------- fusion

    /** Rule-based scorer. Each moment collects weighted evidence into 0..~1. */
    private fun fuse(s: SignalState): Map<Moment, Double> {
        val h = s.hourOfDay
        val scores = HashMap<Moment, Double>()
        fun add(m: Moment, w: Double, on: Boolean) {
            if (on) scores[m] = (scores[m] ?: 0.0) + w
        }

        val night = h >= 23 || h < 6
        val morning = h in 4.0..11.0
        val workHours = s.weekday && h in 9.0..17.5
        val evening = h in 19.0..23.0
        val commuteHours = s.weekday && (h in 6.5..9.5 || h in 16.0..19.0)
        val justWoke = s.minutesSinceLongSleepWake in 0..15
        val still = s.motion == MotionState.STILL

        // SLEEPING — realistically only seen from background sampling.
        add(Moment.SLEEPING, 0.45, night)
        add(Moment.SLEEPING, 0.20, night && still)
        add(Moment.SLEEPING, 0.15, night && s.charging)
        add(Moment.SLEEPING, 0.10, night && s.dndOn)

        // JUST_WOKE_UP — first minutes after a screen-on that followed a 4h+ gap.
        add(Moment.JUST_WOKE_UP, 0.70, justWoke)
        add(Moment.JUST_WOKE_UP, 0.20, justWoke && morning)
        add(Moment.JUST_WOKE_UP, 0.05, justWoke && s.charging)

        // COMMUTING / driving.
        add(Moment.COMMUTING, 0.60, s.motion == MotionState.IN_VEHICLE)
        add(Moment.COMMUTING, 0.15, s.motion == MotionState.IN_VEHICLE && commuteHours)
        add(Moment.COMMUTING, 0.10, s.motion == MotionState.IN_VEHICLE && !s.onWifi)
        add(Moment.COMMUTING, 0.10, s.motion == MotionState.IN_VEHICLE && s.bluetoothAudio)

        // WALKING.
        add(Moment.WALKING, 0.65, s.motion == MotionState.WALKING)
        add(Moment.WALKING, 0.10, s.motion == MotionState.WALKING && !s.onWifi)

        // WORKING_OUT.
        add(Moment.WORKING_OUT, 0.60, s.motion == MotionState.RUNNING)
        add(Moment.WORKING_OUT, 0.20, s.motion == MotionState.RUNNING && (s.wiredHeadset || s.bluetoothAudio))
        add(Moment.WORKING_OUT, 0.15, s.motion == MotionState.WALKING && s.motionEnergy > 2.2)

        // IN_MEETING — calendar is the strong signal; silence + stillness back it up.
        add(Moment.IN_MEETING, 0.50, s.calendarBusy == true)
        add(Moment.IN_MEETING, 0.15, s.calendarBusy == true && workHours)
        add(Moment.IN_MEETING, 0.15, s.calendarBusy == true && still)
        add(Moment.IN_MEETING, 0.15, (s.ringerSilentOrVibrate || s.dndOn) && workHours && still)

        // WORKING — weekday daytime, still, on wifi, no busy meeting.
        add(Moment.WORKING, 0.30, workHours && s.calendarBusy != true)
        add(Moment.WORKING, 0.15, workHours && still)
        add(Moment.WORKING, 0.15, workHours && s.onWifi)
        add(Moment.WORKING, 0.05, workHours && s.charging)

        // WATCHING_TV — evening, still, home wifi, often dim room / TV audio route.
        add(Moment.WATCHING_TV, 0.25, evening && still && s.onWifi)
        add(Moment.WATCHING_TV, 0.15, evening && s.screenBrightness < 0.35)
        add(Moment.WATCHING_TV, 0.15, evening && (s.ambientDb ?: 0.0) > 55.0)
        add(Moment.WATCHING_TV, 0.10, evening && s.charging)

        // RELAXING — home-ish and unhurried, not clearly TV.
        add(Moment.RELAXING, 0.25, (evening || !s.weekday) && s.onWifi && still)
        add(Moment.RELAXING, 0.10, (evening || !s.weekday) && s.charging)
        add(Moment.RELAXING, 0.10, !s.weekday && h in 8.0..22.0 && still)

        // OUT_AND_ABOUT — off wifi in the daytime, moving or not.
        add(Moment.OUT_AND_ABOUT, 0.30, !s.onWifi && h in 8.0..22.0 && !night)
        add(Moment.OUT_AND_ABOUT, 0.15, !s.onWifi && s.motion == MotionState.WALKING)
        add(Moment.OUT_AND_ABOUT, 0.10, !s.onWifi && (s.ambientDb ?: 0.0) > 60.0)

        for (m in Moment.entries) if (m != Moment.UNKNOWN) scores.putIfAbsent(m, 0.0)
        return scores.mapValues { min(1.0, it.value) }
    }

    // -------------------------------------------------------- screen tracking

    private fun registerScreenReceiver(context: Context) {
        synchronized(lock) {
            if (screenReceiverRegistered) return
            screenReceiverRegistered = true
        }
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    val now = System.currentTimeMillis()
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF -> lastScreenOffAt = now
                        Intent.ACTION_SCREEN_ON -> {
                            if (lastScreenOffAt > 0 && now - lastScreenOffAt > 4 * 3_600_000) {
                                lastLongWakeAt = now
                            }
                        }
                    }
                }
            }, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Screen receiver failed", e)
        }
    }

    // ------------------------------------------------------------ persistence

    private fun workerHandler(): Handler {
        synchronized(lock) {
            if (handler == null) {
                thread = HandlerThread("ContextMoments").also { it.start() }
                handler = Handler(thread!!.looper)
            }
            return handler!!
        }
    }

    private fun sensorDeliveryHandler(): Handler {
        synchronized(lock) {
            if (sensorHandler == null) {
                sensorThread = HandlerThread("ContextMoments-sensor").also { it.start() }
                sensorHandler = Handler(sensorThread!!.looper)
            }
            return sensorHandler!!
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
                val arr = root.optJSONArray("history") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    runCatching {
                        history.addLast(
                            Transition(
                                o.getLong("at"),
                                Moment.valueOf(o.getString("moment")),
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
        val arr = JSONArray()
        for (t in history) {
            arr.put(
                JSONObject().put("at", t.at).put("moment", t.moment.name)
                    .put("confidence", t.confidence)
            )
        }
        return JSONObject().put("history", arr)
    }
}
