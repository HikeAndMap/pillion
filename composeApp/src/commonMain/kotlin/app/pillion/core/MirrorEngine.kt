package app.pillion.core

import app.pillion.protocol.FRAME_TYPE_PHONE
import app.pillion.protocol.NaviLiteCodec
import app.pillion.protocol.PDT_POINTER
import app.pillion.protocol.ServiceType
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orchestrates a mirroring session: connect -> handshake -> stream screen frames -> report state.
 * Depends only on [ByteChannel] and [ScreenSource] (DIP) — knows nothing about RFCOMM, EASession,
 * MediaProjection or ReplayKit, so the same engine drives both platforms.
 */
class MirrorEngine(
    private val channel: ByteChannel,
    private val screen: ScreenSource,
    private val maxFps: Int = 15,
    private val imageType: Int = 3, // NAVIGATION_EXPANDED
    private val reconnectDelayMs: Long = 1500L, // the dash needs a moment to settle between attempts
    private val maxReconnectAttempts: Int = 10, // after a working session drops
    private val maxInitialAttempts: Int = 20, // waiting for the bike to come up after Start
) {
    private val _state = MutableStateFlow<MirrorState>(MirrorState.Idle)
    val state: StateFlow<MirrorState> = _state.asStateFlow()

    private val minIntervalMs: Long = if (maxFps in 1..59) 1000L / maxFps else 0L
    private var job: Job? = null
    @Volatile private var running = false
    @Volatile private var lastFrameKb = 0
    private var seq = 1
    @Volatile private var failedAttempts = 0
    @Volatile private var ackWaitStartedAt = 0L

    fun start(scope: CoroutineScope) {
        if (job != null) return
        running = true
        _state.value = MirrorState.Connecting
        job = scope.launch(Dispatchers.Default) {
            try {
                // Start capture FIRST: a MediaProjection token goes stale if the virtual display
                // isn't created promptly, so we must not defer it behind the Bluetooth handshake.
                Logger.d("session: starting screen capture")
                screen.start()
                // Reconnect loop: the dash can drop the Bluetooth link on its own (e.g. some dashes
                // terminate it when the phone locks — HCI peer-terminated), which breaks the RFCOMM
                // write mid-stream. Rather than failing the whole session, re-open + re-handshake and
                // resume, so a drop is a brief reconnect instead of a dead session. Only stop() ends it.
                var connectedOnce = false
                while (running) {
                    try {
                        _state.value = if (failedAttempts > 0) MirrorState.Reconnecting else MirrorState.Connecting
                        Logger.d("session: connecting transport")
                        channel.open()
                        val reader = FrameReader(channel)
                        Logger.d("session: handshake")
                        val dashSize = Handshake(channel, reader).perform()
                        Logger.d("session: dash size ${dashSize.width}x${dashSize.height}")
                        screen.resizeOutput(dashSize.width, dashSize.height)
                        connectedOnce = true
                        seq = 1 // fresh sequence per (re)connect, matching a new session
                        Logger.d("session: streaming")
                        streamLoop(reader)
                    } catch (t: Throwable) {
                        if (!running) break
                        // Before the first connection only "can't reach the dash" is worth retrying (bike
                        // still booting / not on yet); anything else is a setup problem — surface it now.
                        if (!connectedOnce && t !is DashUnreachableException) throw t
                        // Bike switched off / out of range: give up instead of retrying (and holding the
                        // wake lock) forever. Reset on every ACKed frame, so a healthy session that drops
                        // now and then is never cut off.
                        val limit = if (connectedOnce) maxReconnectAttempts else maxInitialAttempts
                        if (++failedAttempts > limit) {
                            throw IllegalStateException("Dash not reachable — is the bike on and in range?", t)
                        }
                        _state.value = MirrorState.Reconnecting
                        Logger.e("session dropped; reconnecting in ${reconnectDelayMs}ms", t)
                        runCatching { channel.close() }
                        sleepMs(reconnectDelayMs)
                    }
                }
            } catch (t: Throwable) {
                Logger.e("session failed", t)
                if (running) _state.value = MirrorState.Error(t.message ?: "connection lost")
            } finally {
                running = false
                runCatching { screen.stop() }
                runCatching { channel.close() }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { channel.close() } // unblocks the blocking reader
        job = null
        _state.value = MirrorState.Idle
    }

    /**
     * Runs [streamFrames] with a watchdog. The ACK read blocks with no timeout, so a dash that stays
     * connected but stops acknowledging (left the navigation screen, rejected the frame size) would
     * hang the session while it still looks alive. Closing the channel unblocks the read and sends
     * the session through the normal drop/reconnect path.
     */
    private suspend fun streamLoop(reader: FrameReader) = coroutineScope {
        val watchdog = launch {
            while (true) {
                delay(ACK_WATCHDOG_INTERVAL_MS)
                val since = ackWaitStartedAt
                if (since != 0L && nowMs() - since > ACK_TIMEOUT_MS) {
                    Logger.e("session: no IMAGE_ACK for ${ACK_TIMEOUT_MS}ms; dropping link")
                    runCatching { channel.close() }
                    break
                }
            }
        }
        try {
            streamFrames(reader)
        } finally {
            ackWaitStartedAt = 0L
            watchdog.cancel()
        }
    }

    private fun streamFrames(reader: FrameReader) {
        // Stop-and-wait: send the freshest frame, then block on its IMAGE_ACK before sending the
        // next, so exactly one frame is ever on the link. A sliding window (sending N+1 before N's
        // ACK returns) buffered a frame ahead and added a whole round-trip of latency on slower
        // dashes — visible as constant lag regardless of fps — which isn't worth the peak throughput.
        var lastSend = 0L
        var waitedForFrame = false
        var acks = 0
        var windowStart = nowMs()
        var ackMsTotal = 0L
        var ackMsMax = 0L
        while (running) {
            val jpeg = screen.latestFrame()
            if (jpeg == null) {
                if (!waitedForFrame) {
                    Logger.d("session: waiting for first screen frame")
                    waitedForFrame = true
                }
                sleepMs(15)
                continue
            }
            if (minIntervalMs > 0L) {
                val wait = minIntervalMs - (nowMs() - lastSend)
                if (wait > 0L) sleepMs(wait)
            }
            val sentAt = nowMs()
            lastSend = sentAt
            sendImage(jpeg)
            lastFrameKb = jpeg.size / 1024
            if (seq == 2) Logger.d("session: first image sent (${jpeg.size} bytes)")
            // Wait for this frame's ACK before capturing/sending the next one. channel.close() on
            // stop() unblocks the reader, so this can't hang a teardown.
            ackWaitStartedAt = sentAt
            while (running && reader.next().serviceType != ServiceType.IMAGE_ACK) { /* skip non-ACKs */ }
            ackWaitStartedAt = 0L
            if (!running) break
            failedAttempts = 0
            val ackMs = nowMs() - sentAt
            ackMsTotal += ackMs
            if (ackMs > ackMsMax) ackMsMax = ackMs
            acks++
            val elapsed = nowMs() - windowStart
            if (elapsed >= 1000) {
                val avgAckMs = if (acks > 0) ackMsTotal / acks else 0L
                Logger.d("session: $acks fps, $lastFrameKb KB/frame, ack ${avgAckMs}ms avg/${ackMsMax}ms max")
                _state.value = MirrorState.Streaming(acks * 1000.0 / elapsed, lastFrameKb)
                acks = 0
                ackMsTotal = 0L
                ackMsMax = 0L
                windowStart = nowMs()
            }
        }
    }

    private fun sendImage(jpeg: ByteArray) {
        val payload = ByteArray(3 + jpeg.size)
        payload[0] = imageType.toByte()
        payload[1] = (seq and 0xff).toByte()
        payload[2] = ((seq ushr 8) and 0xff).toByte()
        jpeg.copyInto(payload, 3)
        seq++
        channel.write(NaviLiteCodec.build(FRAME_TYPE_PHONE, ServiceType.IMAGE, PDT_POINTER, payload))
    }

    private companion object {
        /** How long a sent frame may go unacknowledged before the link is considered stalled. */
        const val ACK_TIMEOUT_MS = 10_000L
        const val ACK_WATCHDOG_INTERVAL_MS = 1_000L
    }
}
