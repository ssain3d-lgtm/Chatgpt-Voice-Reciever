package com.ssain3d.gptvoicereceiver.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.ssain3d.gptvoicereceiver.Spike
import com.ssain3d.gptvoicereceiver.audio.AudioCaptureService
import com.ssain3d.gptvoicereceiver.audio.WakeFeedback
import com.ssain3d.gptvoicereceiver.core.log.EventResult
import com.ssain3d.gptvoicereceiver.core.log.SpikeId
import com.ssain3d.gptvoicereceiver.core.session.TurnGate
import com.ssain3d.gptvoicereceiver.core.session.TurnId
import com.ssain3d.gptvoicereceiver.core.speech.SpeechEvent
import com.ssain3d.gptvoicereceiver.core.speech.SpeechMode
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Spike S-2. Answers one question: can we get audio from *our* AudioRecord into the
 * device's SpeechRecognizer, and if not, how bad is the fallback?
 *
 * Mode P (ARCHITECTURE.md §4.3): we keep the microphone and hand the recognizer a pipe
 * via `RecognizerIntent.EXTRA_AUDIO_SOURCE`. The official documentation is explicit
 * that a recognizer MAY ignore this and open the mic itself — so Mode P support is a
 * measurement (GV-09), never an assumption.
 *
 * Mode H: we release the microphone, cue the user, and let the recognizer take it.
 *
 * Scope discipline (brief §8-5, INV-6): `onResults` is displayed and timestamped. It
 * is NOT connected to anything that sends, and no EndpointDetector exists yet.
 */
class SpikeSpeechProbe(private val context: Context) {

    data class Snapshot(
        val mode: SpeechMode = SpeechMode.MODE_P_PIPE,
        val running: Boolean = false,
        /** Which recognizer this run was measured against. null = system default. */
        val recognizer: ComponentName? = null,
        val partial: String = "",
        val finalText: String = "",
        val segments: List<String> = emptyList(),
        val error: String = "",
        val timeline: List<String> = emptyList(),
        val pipeBytesWritten: Long = 0,
        val pipeFramesDropped: Long = 0,
        val startLatencyMs: Long? = null,
        /** Mode H only: whether the microphone release was actually confirmed. */
        val handoffNote: String = "",
        val firstPartialLatencyMs: Long? = null,
        val finalLatencyMs: Long? = null,
        val modePVerdict: String = "not run",
    )

    private val handler = Handler(Looper.getMainLooper())
    private val gate = TurnGate()

    private var recognizer: SpeechRecognizer? = null
    private var recognizerComponent: ComponentName? = null
    private var turnId: TurnId = TurnId.NONE
    private var startedAtMs: Long = 0
    private var partialCount = 0

    @Volatile private var snapshot = Snapshot()
    private var onChange: ((Snapshot) -> Unit)? = null

    // Mode P plumbing
    private var writeSide: ParcelFileDescriptor? = null
    private var pipeWriter: PipeWriter? = null
    private var sink: AudioCaptureService.PcmSink? = null

    fun observe(listener: (Snapshot) -> Unit) {
        onChange = listener
        listener(snapshot)
    }

    fun current(): Snapshot = snapshot

    // ------------------------------------------------------------------- lifecycle

    /**
     * @param component pin the run to this recognizer, or null to use the system
     *        default. Pinning exists because AURA's own RecognitionService stub can
     *        become the system default once AURA is the assistant — see
     *        [RecognizerInfo]. Measuring against it would answer a question nobody
     *        asked.
     */
    fun start(mode: SpeechMode, component: ComponentName? = null) {
        if (snapshot.running) {
            Spike.log.log(SpikeId.S2, "Probe", "StartIgnored", EventResult.WARN, detail = "already running")
            return
        }

        turnId = Spike.turnIds.next()
        gate.open(turnId)
        partialCount = 0
        startedAtMs = SystemClock.elapsedRealtime()
        recognizerComponent = component
        snapshot = Snapshot(
            mode = mode,
            running = true,
            recognizer = component,
            modePVerdict = snapshot.modePVerdict,
        )
        publish()

        // Refuse to measure our own stub, whether it was chosen explicitly or is
        // simply the current system default. A Mode P failure against it would look
        // exactly like "the Galaxy recognizer ignores EXTRA_AUDIO_SOURCE" (GV-09),
        // and that wrong answer would be acted on.
        val ourPackage = context.packageName
        if (component != null && component.packageName == ourPackage) {
            failFast(
                "refusing to run against ${component.flattenToShortString()} — that is " +
                    "AURA's own RecognitionService stub, which always returns ERROR_CLIENT"
            )
            return
        }
        if (component == null) {
            val info = RecognizerInfo.read(context)
            if (info.defaultIsOurStub) {
                failFast(
                    "the system default recognizer is AURA's own stub " +
                        "(${info.defaultRaw}). Pin an external recognizer on this " +
                        "screen before running S-2 — otherwise Mode P/H would be " +
                        "measuring our deliberate ERROR_CLIENT, not the Galaxy provider."
                )
                return
            }
        }

        Spike.log.log(
            SpikeId.S2, "Probe", "Start", EventResult.INFO, turnId = turnId,
            detail = "mode=$mode captureAlive=${AudioCaptureService.isAlive()} " +
                "recognizer=${component?.flattenToShortString() ?: "system default"}",
        )

        when (mode) {
            SpeechMode.MODE_P_PIPE -> startModeP()
            SpeechMode.MODE_H_HANDOFF -> startModeH()
        }
    }

    fun stop() {
        val t = turnId
        mark("stop() requested")
        runCatching { recognizer?.stopListening() }
        // Mode P: closing the write side is what ends a segmented session
        // ("the recognition session will end when and only when the audio is closed").
        closePipe()
        handler.postDelayed({ finish(t, "stopped by user") }, 500)
    }

    fun cancel() {
        val t = turnId
        runCatching { recognizer?.cancel() }
        closePipe()
        finish(t, "cancelled")
    }

    // ---------------------------------------------------------------------- Mode P

    private fun startModeP() {
        if (!AudioCaptureService.isAlive()) {
            failFast("Mode P needs AudioCaptureService running — start capture on the S-1 screen first")
            return
        }

        val cfg = Spike.config
        val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrElse {
            failFast("createPipe failed: $it")
            return
        }
        val readSide = pipe[0]
        writeSide = pipe[1]

        val intent = baseIntent().apply {
            // API 33. Symbols are referenced from the SDK, never hand-written strings,
            // so a wrong name fails the build instead of silently doing nothing.
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, cfg.sampleRateHz)
            // The value is the NAME of the extra that defines the end condition; with
            // EXTRA_AUDIO_SOURCE the session ends exactly when the audio is closed.
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }

        mark("Mode P: pipe created, extras set")
        createRecognizer()

        val ok = runCatching { recognizer?.startListening(intent) }
        if (ok.isFailure) {
            failFast("startListening threw: ${ok.exceptionOrNull()}")
            return
        }
        snapshot = snapshot.copy(startLatencyMs = SystemClock.elapsedRealtime() - startedAtMs)

        // Our copy of the read end is no longer needed; the recognizer received a dup
        // across the binder. Holding it open would prevent it from ever seeing EOF.
        handler.postDelayed({ runCatching { readSide.close() } }, 200)

        startPipeFeed(cfg.preRollSamples)

        // GV-09 verdict: if nothing at all comes back in the budget, the recognizer is
        // not consuming our pipe.
        handler.postDelayed({ judgeModeP(turnId) }, cfg.modePProbeTimeoutMs.toLong())
    }

    private fun startPipeFeed(preRollSamples: Int) {
        val write = writeSide ?: run {
            failFast("pipe write end missing")
            return
        }
        val out = ParcelFileDescriptor.AutoCloseOutputStream(write)
        val writer = PipeWriter(out).also { pipeWriter = it; it.start() }

        // Pre-roll first: audio from BEFORE this moment, so the first syllable of the
        // question survives the wake (R-05). This is the whole reason Mode P is
        // preferred over Mode H.
        val preRoll = AudioCaptureService.readPreRoll(preRollSamples)
        if (preRoll.isNotEmpty()) {
            writer.offer(preRoll)
            mark("pre-roll queued: ${preRoll.size} samples (${preRollSamples * 1000 / Spike.config.sampleRateHz} ms)")
        }

        val s = AudioCaptureService.PcmSink { frame, count ->
            writer.offer(if (count == frame.size) frame else frame.copyOf(count))
        }
        sink = s
        if (!AudioCaptureService.attachSink(s)) {
            failFast("could not attach PCM sink — capture service not running")
        }
    }

    private fun judgeModeP(forTurn: TurnId) {
        if (!gate.accepts(forTurn)) return
        val s = snapshot
        val sawAnything = s.partial.isNotEmpty() || s.finalText.isNotEmpty() ||
            s.segments.isNotEmpty() || s.error.isNotEmpty()
        val verdict = when {
            s.firstPartialLatencyMs != null ->
                "Mode P LOOKS SUPPORTED — recognizer produced partials from our pipe"
            s.error.isNotEmpty() ->
                "Mode P FAILED with ${s.error} — record as GV-09 fail, fall back to Mode H"
            !sawAnything ->
                "Mode P INCONCLUSIVE — no callback within ${Spike.config.modePProbeTimeoutMs} ms. " +
                    "Check whether the recognizer opened the mic itself (dumpsys audio)"
            else -> "Mode P partial evidence — see timeline"
        }
        snapshot = snapshot.copy(modePVerdict = verdict)
        Spike.log.log(
            SpikeId.S2, "ModeP", "Verdict",
            if (s.firstPartialLatencyMs != null) EventResult.OK else EventResult.WARN,
            turnId = forTurn, detail = verdict,
        )
        publish()
    }

    // ---------------------------------------------------------------------- Mode H

    /**
     * Mode H must not hand the microphone over on a timer.
     *
     * Under Android 10+ input sharing, two simultaneous captures leave one of them
     * silent — so if the recognizer opens the mic while our AudioRecord is still
     * closing, the run produces a plausible-looking but meaningless result. Waiting a
     * fixed interval assumes the audio thread finishes within it, which nothing
     * guarantees: it is inside a blocking read when the request arrives, and a loaded
     * device can take far longer.
     *
     * So we wait for the capture service to CONFIRM the release, and if the
     * confirmation never comes the run is ABORTED rather than continued.
     *
     * Continuing would be worse than losing the attempt. The recognizer would very
     * likely still produce partials and a final, so the screen would show a result
     * that looks like a clean Mode H run — and a tester reading it would reasonably
     * record "Mode H PASS". A run that cannot be trusted must not be able to look
     * like a passing one; the same rule the rest of this spike follows.
     */
    private fun startModeH() {
        val forTurn = turnId

        if (!AudioCaptureService.isCapturing()) {
            mark("Mode H: no capture running — the microphone is already free")
            snapshot = snapshot.copy(handoffNote = "no capture to release (mic already free)")
            beginModeHListening(forTurn)
            return
        }

        mark("Mode H: releasing our AudioRecord before startListening")
        val requestedAt = SystemClock.elapsedRealtime()
        AudioCaptureService.releaseForHandoff()
        awaitMicRelease(forTurn, requestedAt)
    }

    private fun awaitMicRelease(forTurn: TurnId, requestedAt: Long) {
        val timeoutMs = Spike.config.handoffReleaseTimeoutMs

        fun poll() {
            if (forTurn != turnId || !snapshot.running) return
            val waited = SystemClock.elapsedRealtime() - requestedAt

            if (AudioCaptureService.isReleasedForHandoff()) {
                snapshot = snapshot.copy(handoffNote = "release CONFIRMED after $waited ms")
                Spike.log.log(
                    SpikeId.S2, "Handoff", "ReleaseConfirmed", EventResult.OK,
                    turnId = forTurn, latencyMs = waited,
                )
                mark("$waited ms  microphone release confirmed")
                beginModeHListening(forTurn)
                return
            }

            if (waited >= timeoutMs) {
                // Abort. Do NOT start the recognizer: a run that cannot be trusted
                // must not be able to produce output that looks like a pass.
                snapshot = snapshot.copy(
                    handoffNote = "release NOT confirmed within $timeoutMs ms — " +
                        "run ABORTED, this is INVALID, never record it as PASS",
                )
                Spike.log.log(
                    SpikeId.S2, "Handoff", "ReleaseTimeout", EventResult.FAIL,
                    turnId = forTurn, latencyMs = waited,
                    error = "capture service did not confirm microphone release; " +
                        "recognizer was NOT started",
                )
                mark("$waited ms  release NOT confirmed — run aborted, no STT started")
                failFast(
                    "Mode H aborted: AudioCaptureService did not confirm the microphone " +
                        "release within $timeoutMs ms, so the recognizer was not started. " +
                        "Handing the mic over while ours is still closing can leave one of " +
                        "two simultaneous captures silent, and the run would look valid " +
                        "while measuring nothing. Retry, or raise handoffReleaseTimeoutMs " +
                        "if this device is genuinely slower."
                )
                return
            }

            handler.postDelayed({ poll() }, HANDOFF_POLL_MS)
        }

        poll()
    }

    private fun beginModeHListening(forTurn: TurnId) {
        if (forTurn != turnId || !snapshot.running) return

        // The cue tells the user the handoff is done. Anything said before it is the
        // loss Mode H is being measured for, so it fires only once the mic is free.
        WakeFeedback.beep()
        mark("cue tone — speak after this")

        createRecognizer()
        val ok = runCatching { recognizer?.startListening(baseIntent()) }
        if (ok.isFailure) {
            failFast("startListening threw: ${ok.exceptionOrNull()}")
            return
        }
        snapshot = snapshot.copy(startLatencyMs = SystemClock.elapsedRealtime() - startedAtMs)
        publish()
    }

    // ------------------------------------------------------------------- recognizer

    private fun baseIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, RecognizerInfo.LANGUAGE_KO)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    private fun createRecognizer() {
        runCatching { recognizer?.destroy() }
        val component = recognizerComponent
        recognizer = if (component == null) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            // createSpeechRecognizer(Context, ComponentName) — API 8, public.
            SpeechRecognizer.createSpeechRecognizer(context, component)
        }
        recognizer?.setRecognitionListener(Listener(turnId))
    }

    /**
     * Every callback is stamped and gated on the turn.
     *
     * The timestamps are the deliverable of brief §12: speech start, each partial,
     * final, recognizer end. They are what a real EndpointDetector will later be tuned
     * against (ENDPOINT_ENGINE.md §9 dataset D6). No endpoint decision is taken here.
     */
    private inner class Listener(private val forTurn: TurnId) : RecognitionListener {

        private fun at(): Long = SystemClock.elapsedRealtime() - startedAtMs

        private fun emit(event: SpeechEvent, note: String, result: EventResult = EventResult.INFO) {
            if (!gate.accepts(forTurn)) {
                Spike.log.log(
                    SpikeId.S2, "Probe", "StaleEvent", EventResult.WARN, turnId = forTurn,
                    detail = "dropped ${event::class.simpleName} from a finished turn (INV-3)",
                )
                return
            }
            Spike.log.log(
                SpikeId.S2, "Recognizer", event::class.simpleName ?: "Event", result,
                turnId = forTurn, latencyMs = event.atElapsedMs, detail = note,
            )
            mark("${event.atElapsedMs} ms  $note")
        }

        override fun onReadyForSpeech(params: Bundle?) =
            emit(SpeechEvent.ReadyForSpeech(forTurn, at()), "onReadyForSpeech", EventResult.OK)

        override fun onBeginningOfSpeech() =
            emit(SpeechEvent.SpeechStart(forTurn, at()), "onBeginningOfSpeech")

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() =
            emit(SpeechEvent.SpeechEnd(forTurn, at()), "onEndOfSpeech")

        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstResult(partialResults) ?: return
            val now = at()
            partialCount++
            if (snapshot.firstPartialLatencyMs == null) {
                snapshot = snapshot.copy(firstPartialLatencyMs = now)
            }
            snapshot = snapshot.copy(partial = text)
            emit(
                SpeechEvent.Partial(forTurn, now, text, partialCount),
                "partial#$partialCount ${Spike.log.transcript(text)}",
            )
            publish()
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            val text = firstResult(segmentResults) ?: return
            snapshot = snapshot.copy(segments = snapshot.segments + text)
            emit(
                SpeechEvent.Segment(forTurn, at(), text, snapshot.segments.size),
                "onSegmentResults#${snapshot.segments.size} ${Spike.log.transcript(text)} " +
                    "(segmented session IS working — GV-10)",
                EventResult.OK,
            )
            publish()
        }

        override fun onEndOfSegmentedSession() =
            emit(SpeechEvent.RecognizerEnd(forTurn, at()), "onEndOfSegmentedSession")

        override fun onResults(results: Bundle?) {
            val text = firstResult(results).orEmpty()
            val now = at()
            snapshot = snapshot.copy(finalText = text, finalLatencyMs = now)
            // INV-6: displayed, timestamped, and deliberately connected to nothing.
            emit(
                SpeechEvent.Final(forTurn, now, text),
                "onResults ${Spike.log.transcript(text)} — SEGMENT result, not a send signal",
                EventResult.OK,
            )
            finish(forTurn, "final received")
        }

        override fun onError(error: Int) {
            val name = RecognizerInfo.errorName(error)
            snapshot = snapshot.copy(error = "$name($error)")
            emit(
                SpeechEvent.Error(forTurn, at(), error, name),
                "onError $name",
                EventResult.FAIL,
            )
            finish(forTurn, "error $name")
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        private fun firstResult(bundle: Bundle?): String? =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
    }

    // ---------------------------------------------------------------------- helpers

    private fun finish(forTurn: TurnId, reason: String) {
        if (forTurn != turnId) return
        if (!snapshot.running) return

        sink?.let { AudioCaptureService.detachSink(it) }
        sink = null
        pipeWriter?.let {
            it.shutdown()
            snapshot = snapshot.copy(
                pipeBytesWritten = it.bytesWritten(),
                pipeFramesDropped = it.dropped(),
            )
        }
        pipeWriter = null
        closePipe()

        if (snapshot.mode == SpeechMode.MODE_H_HANDOFF) {
            AudioCaptureService.resumeAfterHandoff()
            mark("Mode H: microphone returned to AudioCaptureService")
        }

        runCatching { recognizer?.destroy() }
        recognizer = null
        gate.close()

        snapshot = snapshot.copy(running = false)
        Spike.log.log(
            SpikeId.S2, "Probe", "Finish", EventResult.INFO, turnId = forTurn,
            detail = "reason=$reason recognizer=" +
                "${snapshot.recognizer?.flattenToShortString() ?: "system default"} " +
                "pipeBytes=${snapshot.pipeBytesWritten} " +
                "dropped=${snapshot.pipeFramesDropped} stale=${gate.staleCount}",
        )
        publish()
    }

    private fun closePipe() {
        runCatching { writeSide?.close() }
        writeSide = null
    }

    /**
     * Abort the current run.
     *
     * Routed through [finish] rather than just flipping a flag, because the teardown
     * is not optional: a Mode H abort must hand the microphone back to
     * AudioCaptureService. Without that, a single failed handoff would leave capture
     * paused for the rest of the session and every later S-1 reading would show a
     * dead microphone for a reason that has nothing to do with S-1.
     */
    private fun failFast(message: String) {
        val forTurn = turnId
        snapshot = snapshot.copy(error = message)
        Spike.log.log(SpikeId.S2, "Probe", "Failed", EventResult.FAIL, turnId = forTurn, error = message)
        mark("FAILED: $message")
        finish(forTurn, "failed: $message")
    }

    private fun mark(line: String) {
        snapshot = snapshot.copy(timeline = (snapshot.timeline + line).takeLast(MAX_TIMELINE))
        publish()
    }

    private fun publish() {
        val s = snapshot
        handler.post { onChange?.invoke(s) }
    }

    /**
     * Writes PCM to the recognizer's pipe on its own thread.
     *
     * The audio thread must never block on this: a slow or stalled reader would cause
     * capture overruns and corrupt the very measurement we are taking. So the queue is
     * bounded and overflow DROPS frames and counts them — a visible, quantified loss is
     * far better than a silently distorted result.
     */
    private class PipeWriter(private val out: OutputStream) : Thread("aura-pipe") {
        private val queue = ArrayBlockingQueue<ShortArray>(QUEUE_DEPTH)
        @Volatile private var running = true
        @Volatile private var bytes = 0L
        @Volatile private var droppedFrames = 0L

        init {
            isDaemon = true
        }

        fun offer(frame: ShortArray) {
            if (!queue.offer(frame)) droppedFrames++
        }

        fun bytesWritten(): Long = bytes
        fun dropped(): Long = droppedFrames

        fun shutdown() {
            running = false
            interrupt()
        }

        override fun run() {
            val scratch = ByteArray(4096)
            try {
                while (running || queue.isNotEmpty()) {
                    val frame = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    val needed = frame.size * 2
                    val buf = if (needed <= scratch.size) scratch else ByteArray(needed)
                    var bi = 0
                    for (sample in frame) {
                        val v = sample.toInt()
                        buf[bi++] = (v and 0xFF).toByte()          // little-endian
                        buf[bi++] = ((v shr 8) and 0xFF).toByte()
                    }
                    out.write(buf, 0, needed)
                    bytes += needed
                }
                out.flush()
            } catch (e: InterruptedException) {
                // shutdown
            } catch (e: Exception) {
                Spike.log.log(
                    SpikeId.S2, "PipeWriter", "WriteFailed", EventResult.WARN,
                    error = e.toString(),
                    detail = "the recognizer probably closed its end — usually means " +
                        "EXTRA_AUDIO_SOURCE is not supported",
                )
            } finally {
                runCatching { out.close() }
            }
        }

        private companion object {
            const val QUEUE_DEPTH = 64
        }
    }

    private companion object {
        const val MAX_TIMELINE = 60

        /** Handshake polling interval. The wait itself is bounded by config. */
        const val HANDOFF_POLL_MS = 20L
    }
}
