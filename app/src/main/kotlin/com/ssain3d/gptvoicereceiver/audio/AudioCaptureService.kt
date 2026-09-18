package com.ssain3d.gptvoicereceiver.audio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import com.ssain3d.gptvoicereceiver.R
import com.ssain3d.gptvoicereceiver.Spike
import com.ssain3d.gptvoicereceiver.core.log.EventResult
import com.ssain3d.gptvoicereceiver.core.log.SpikeId
import com.ssain3d.gptvoicereceiver.core.wakeword.WakeWordEngine
import com.ssain3d.gptvoicereceiver.core.wakeword.WakeWordEvent
import com.ssain3d.gptvoicereceiver.core.wakeword.WakeWordStatus
import com.ssain3d.gptvoicereceiver.debug.DashboardActivity
import com.ssain3d.gptvoicereceiver.wakeword.DisabledWakeWordEngine
import com.ssain3d.gptvoicereceiver.wakeword.PorcupineWakeWordEngine
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * INV-1, enforced by construction: **this is the only class in the app that
 * constructs an AudioRecord.** Everything else is a consumer of the stream it
 * publishes. In Mode H, where the recognizer must own the microphone instead, this
 * service releases it first and reacquires afterwards — the two never overlap, which
 * is what Android 10+ input sharing requires (ANDROID_CONSTRAINTS.md §4).
 *
 * Started from [com.ssain3d.gptvoicereceiver.assistant.GptVoiceInteractionService.onReady]
 * so that it falls inside the documented while-in-use microphone exception for apps
 * that provide a VoiceInteractionService (ADR-001).
 */
class AudioCaptureService : Service() {

    /** A consumer of live PCM. Registered by S-2's Mode P probe to feed the pipe. */
    fun interface PcmSink {
        fun onPcmFrame(frame: ShortArray, sampleCount: Int)
    }

    private val sinks = CopyOnWriteArrayList<PcmSink>()

    @Volatile private var captureThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var handoffPaused = false

    private var wakeEngine: WakeWordEngine = DisabledWakeWordEngine("not started")
    private var ringBuffer: PcmRingBuffer? = null

    @Volatile private var state = CaptureState()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture("explicit stop")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val reason = intent?.getStringExtra(EXTRA_REASON) ?: "unspecified"
        startForegroundSafely()
        startCapture(reason)
        return START_STICKY
    }

    override fun onDestroy() {
        stopCapture("onDestroy")
        if (instance === this) instance = null
        Spike.log.log(SpikeId.S1, "AudioCaptureService", "onDestroy", EventResult.WARN)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ foreground

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.fgs_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
        )
    }

    private fun startForegroundSafely() {
        val content = PendingIntent.getActivity(
            this, 0,
            Intent(this, DashboardActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.fgs_notification_title))
            .setContentText(getString(R.string.fgs_notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(content)
            .setOngoing(true)
            .build()

        val result = runCatching {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        }
        Spike.log.log(
            SpikeId.S1, "AudioCaptureService", "startForeground",
            if (result.isSuccess) EventResult.OK else EventResult.FAIL,
            error = result.exceptionOrNull()?.toString(),
            detail = "type=microphone",
        )
    }

    // --------------------------------------------------------------------- capture

    private fun startCapture(reason: String) {
        if (running) {
            Spike.log.log(
                SpikeId.S1, "AudioCaptureService", "StartIgnored",
                detail = "already running (reason=$reason)",
            )
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            state = state.copy(running = false, lastError = "RECORD_AUDIO not granted")
            Spike.log.log(
                SpikeId.S1, "AudioCaptureService", "StartFailed", EventResult.FAIL,
                error = "RECORD_AUDIO not granted",
            )
            return
        }

        wakeEngine = buildWakeEngine()
        val wakeStatus = wakeEngine.start()

        val cfg = Spike.config
        ringBuffer = PcmRingBuffer(
            capacitySamples = maxOf(cfg.ringBufferSamples, wakeEngine.frameLength * 4)
        )

        running = true
        handoffPaused = false
        state = CaptureState(
            running = true,
            sampleRateHz = wakeEngine.sampleRate,
            frameLength = wakeEngine.frameLength,
            wakeEngineName = wakeEngine.name,
            wakeStatus = wakeStatus.describe(),
            startedAtEpochMs = System.currentTimeMillis(),
        )

        Spike.log.log(
            SpikeId.S1, "AudioCaptureService", "StartCapture", EventResult.OK,
            detail = "reason=$reason wakeEngine=${wakeEngine.name} " +
                "wakeStatus=${wakeStatus.describe()} " +
                "frameLength=${wakeEngine.frameLength} sampleRate=${wakeEngine.sampleRate} " +
                "ringBuffer=${ringBuffer?.capacitySamples}samples preRoll=${cfg.preRollMs}ms",
        )

        captureThread = Thread({ captureLoop() }, "aura-audio").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopCapture(reason: String) {
        if (!running) return
        running = false
        captureThread?.join(1500)
        captureThread = null
        wakeEngine.release()
        state = state.copy(running = false)
        Spike.log.log(
            SpikeId.S1, "AudioCaptureService", "StopCapture", EventResult.INFO,
            detail = "reason=$reason framesRead=${state.framesRead}",
        )
    }

    private fun buildWakeEngine(): WakeWordEngine {
        val missing = PorcupineWakeWordEngine.describeMissing(this)
        return if (missing != null) {
            Spike.log.log(
                SpikeId.S1, "WakeWord", "EngineUnavailable", EventResult.WARN,
                detail = "$missing — capture and screen-off liveness (GV-03) can still " +
                    "be tested; detection rate (GV-02) cannot",
            )
            DisabledWakeWordEngine(missing)
        } else {
            PorcupineWakeWordEngine(this)
        }
    }

    /**
     * The audio thread.
     *
     * Runs at URGENT_AUDIO priority (ARCHITECTURE.md §7.3) and does only bounded work:
     * a read, a wake-word inference, a ring-buffer write and a non-blocking fan-out to
     * sinks. Nothing here may block on I/O — the Mode P pipe writer has its own thread
     * and drops frames rather than stalling this loop.
     */
    @Suppress("MissingPermission")
    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val sampleRate = wakeEngine.sampleRate
        val frameLength = wakeEngine.frameLength
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            fail("AudioRecord.getMinBufferSize returned $minBuffer")
            return
        }
        val bufferBytes = maxOf(minBuffer, frameLength * 2 * 8)

        var record: AudioRecord? = null
        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                fail("AudioRecord state=${record.state} (not INITIALIZED)")
                return
            }
            record.startRecording()
            state = state.copy(
                audioRecordState = "INITIALIZED",
                recordingState = if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "RECORDING"
                } else {
                    "STOPPED"
                },
            )
            Spike.log.log(
                SpikeId.S1, "AudioRecord", "Started", EventResult.OK,
                detail = "source=VOICE_RECOGNITION rate=$sampleRate mono PCM16 " +
                    "bufferBytes=$bufferBytes minBuffer=$minBuffer",
            )

            val frame = ShortArray(frameLength)
            var lastTelemetryMs = SystemClock.elapsedRealtime()
            var lastWakeMs = 0L

            while (running) {
                if (handoffPaused) {
                    // Mode H: the recognizer owns the mic. Release ours completely —
                    // two simultaneous captures is exactly what makes one of them
                    // silent (ANDROID_CONSTRAINTS.md §4).
                    runCatching { record?.stop() }
                    runCatching { record?.release() }
                    record = null
                    state = state.copy(recordingState = "RELEASED", micReleasedForHandoff = true)
                    while (running && handoffPaused) Thread.sleep(50)
                    if (!running) break

                    record = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        sampleRate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufferBytes,
                    )
                    if (record.state != AudioRecord.STATE_INITIALIZED) {
                        fail("reacquire failed: AudioRecord state=${record.state}")
                        return
                    }
                    record.startRecording()
                    state = state.copy(recordingState = "RECORDING", micReleasedForHandoff = false)
                    Spike.log.log(
                        SpikeId.S1, "AudioRecord", "Reacquired", EventResult.OK,
                        detail = "after Mode H handoff",
                    )
                    continue
                }

                val read = record?.read(frame, 0, frameLength) ?: break
                if (read < 0) {
                    state = state.copy(readErrors = state.readErrors + 1, lastError = "read=$read")
                    Spike.log.log(
                        SpikeId.S1, "AudioRecord", "ReadError", EventResult.FAIL,
                        error = "read returned $read",
                    )
                    Thread.sleep(50)
                    continue
                }
                if (read < frameLength) continue

                ringBuffer?.write(frame, read)
                fanOut(frame, read)
                val metrics = measure(frame, read)
                accumulate(metrics)

                if (wakeEngine.status is WakeWordStatus.Running) {
                    val index = wakeEngine.process(frame)
                    val nowMs = SystemClock.elapsedRealtime()
                    if (index >= 0 && nowMs - lastWakeMs > WAKE_DEBOUNCE_MS) {
                        lastWakeMs = nowMs
                        onWakeDetected(index, nowMs)
                    }
                }

                val now = SystemClock.elapsedRealtime()
                if (now - lastTelemetryMs >= TELEMETRY_INTERVAL_MS) {
                    lastTelemetryMs = now
                    emitTelemetry()
                }
            }
        } catch (t: Throwable) {
            fail(t.message ?: t.toString())
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
            state = state.copy(recordingState = "STOPPED", audioRecordState = "RELEASED")
        }
    }

    private fun fanOut(frame: ShortArray, count: Int) {
        if (sinks.isEmpty()) return
        // Copy: the frame buffer is reused on the next read.
        val copy = frame.copyOf(count)
        sinks.forEach { sink ->
            runCatching { sink.onPcmFrame(copy, count) }
                .onFailure { state = state.copy(lastError = "sink: ${it.message}") }
        }
    }

    private data class FrameMetrics(val rms: Double, val peak: Int, val allZero: Boolean)

    private fun measure(frame: ShortArray, count: Int): FrameMetrics {
        var sumSquares = 0.0
        var peak = 0
        for (i in 0 until count) {
            val s = frame[i].toInt()
            sumSquares += (s * s).toDouble()
            val a = abs(s)
            if (a > peak) peak = a
        }
        return FrameMetrics(sqrt(sumSquares / count), peak, peak == 0)
    }

    private fun accumulate(m: FrameMetrics) {
        val quiet = m.peak < QUIET_PEAK_THRESHOLD
        state = state.copy(
            framesRead = state.framesRead + 1,
            lastRms = m.rms,
            lastPeak = m.peak,
            allZeroFrames = state.allZeroFrames + if (m.allZero) 1 else 0,
            quietFrames = state.quietFrames + if (quiet) 1 else 0,
            consecutiveAllZeroFrames = if (m.allZero) state.consecutiveAllZeroFrames + 1 else 0,
        )
    }

    /**
     * Periodic S-1 evidence. Each line pins the audio level to the screen and keyguard
     * state at that instant, so a screen-off run can be read straight off the log.
     */
    private fun emitTelemetry() {
        val pm = getSystemService(PowerManager::class.java)
        val km = getSystemService(KeyguardManager::class.java)
        val screenOn = pm?.isInteractive ?: true
        val locked = km?.isKeyguardLocked ?: false
        state = state.copy(screenOn = screenOn, keyguardLocked = locked)

        val s = state
        Spike.log.log(
            SpikeId.S1, "AudioLevel", "Telemetry",
            if (s.consecutiveAllZeroFrames > 50) EventResult.FAIL else EventResult.OK,
            detail = "screenOn=$screenOn keyguard=$locked " +
                "rms=${"%.1f".format(s.lastRms)} peak=${s.lastPeak} " +
                "frames=${s.framesRead} zeroFrames=${s.allZeroFrames} " +
                "consecZero=${s.consecutiveAllZeroFrames} liveness=${s.liveness}",
        )
    }

    private fun onWakeDetected(keywordIndex: Int, elapsedMs: Long) {
        val event = WakeWordEvent(keywordIndex, elapsedMs, System.currentTimeMillis())
        state = state.copy(
            wakeDetections = state.wakeDetections + 1,
            lastWakeAtEpochMs = event.detectedAtEpochMs,
        )
        val pm = getSystemService(PowerManager::class.java)
        val km = getSystemService(KeyguardManager::class.java)
        Spike.log.log(
            SpikeId.S1, "WakeWord", "Detected", EventResult.OK,
            detail = "keywordIndex=$keywordIndex screenOn=${pm?.isInteractive} " +
                "keyguard=${km?.isKeyguardLocked} count=${state.wakeDetections}",
        )
        WakeFeedback.signal(this)
    }

    private fun fail(message: String) {
        running = false
        state = state.copy(running = false, lastError = message)
        Spike.log.log(
            SpikeId.S1, "AudioCaptureService", "CaptureFailed", EventResult.FAIL,
            error = message,
        )
    }

    private fun WakeWordStatus.describe(): String = when (this) {
        is WakeWordStatus.Running -> "Running"
        is WakeWordStatus.Stopped -> "Stopped"
        is WakeWordStatus.Unavailable -> "Unavailable: $reason"
        is WakeWordStatus.Failed -> "Failed: $reason"
    }

    companion object {
        private const val CHANNEL_ID = "aura_capture"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_REASON = "reason"

        const val ACTION_STOP = "com.ssain3d.gptvoicereceiver.STOP_CAPTURE"

        /** DEVICE_TUNABLE. ARCHITECTURE.md §6.3 debounce, shortened for the Spike. */
        private const val WAKE_DEBOUNCE_MS = 2000L

        private const val TELEMETRY_INTERVAL_MS = 15_000L

        /** ~ -60 dBFS. Below this a frame is "quiet", which is NOT the same as zeroed. */
        private const val QUIET_PEAK_THRESHOLD = 32

        @Volatile
        private var instance: AudioCaptureService? = null

        fun start(context: Context, reason: String) {
            val intent = Intent(context, AudioCaptureService::class.java)
                .putExtra(EXTRA_REASON, reason)
            runCatching {
                context.startForegroundService(intent)
            }.onFailure {
                Spike.log.log(
                    SpikeId.S1, "AudioCaptureService", "StartServiceFailed", EventResult.FAIL,
                    error = it.toString(), detail = "reason=$reason",
                )
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AudioCaptureService::class.java).setAction(ACTION_STOP)
            )
        }

        fun snapshot(): CaptureState = instance?.state ?: CaptureState()

        fun isAlive(): Boolean = instance != null

        fun attachSink(sink: PcmSink): Boolean {
            val svc = instance ?: return false
            svc.sinks.add(sink)
            svc.state = svc.state.copy(pcmSinkAttached = true)
            return true
        }

        fun detachSink(sink: PcmSink) {
            val svc = instance ?: return
            svc.sinks.remove(sink)
            svc.state = svc.state.copy(pcmSinkAttached = svc.sinks.isNotEmpty())
        }

        /** Most recent [samples] from the ring buffer — the Mode P pre-roll. */
        fun readPreRoll(samples: Int): ShortArray =
            instance?.ringBuffer?.readMostRecent(samples) ?: ShortArray(0)

        /**
         * Mode H. Fully releases our AudioRecord so the recognizer can open the mic.
         * Must be paired with [resumeAfterHandoff].
         */
        fun releaseForHandoff(): Boolean {
            val svc = instance ?: return false
            svc.handoffPaused = true
            Spike.log.log(SpikeId.S2, "AudioCaptureService", "ReleaseForHandoff", EventResult.INFO)
            return true
        }

        fun resumeAfterHandoff(): Boolean {
            val svc = instance ?: return false
            svc.handoffPaused = false
            Spike.log.log(SpikeId.S2, "AudioCaptureService", "ResumeAfterHandoff", EventResult.INFO)
            return true
        }

        fun buildInfo(): String =
            "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) " +
                "${Build.MANUFACTURER} ${Build.MODEL} / ${Build.DISPLAY}"
    }
}
