package com.example.galaxywatch5.presentation.streaming

import android.util.Log
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Streams newline-delimited JSON lines to a PC TCP server over localhost:9500
 * (tunneled from the watch via `adb reverse tcp:9500 tcp:9500`). The watch is the
 * TCP client.
 *
 * Fully self-contained: it knows nothing about sensors, the SDK, or the service —
 * it takes strings via [enqueue] and ships them. Streaming is additive and its
 * state never affects sensor collection or the local .jsonl file.
 *
 * Threading contract:
 *  - [enqueue] is the ONLY producer entry point and never touches the socket. It is
 *    safe to call from any thread (the per-tracker HandlerThreads).
 *  - A single background thread ("avatar-stream") owns the socket and is the ONLY
 *    place socket writes and connect()s happen.
 *
 * Resilience: the sender thread reconnects indefinitely (every [RETRY_MS]) while
 * enabled, so the PC can come and go freely. While disconnected the bounded queue
 * drops its oldest entries so memory stays capped; on reconnect it resumes with
 * whatever is currently queued.
 */
class StreamClient {

    companion object {
        const val HOST = "localhost"
        const val PORT = 9500

        // Bounded outgoing buffer. When full, the oldest line is dropped so a slow or
        // absent PC can never exhaust memory.
        private const val CAPACITY = 10_000

        // Wait between reconnect attempts, and cap on a single connect() so teardown
        // can't hang on a stuck connect.
        private const val RETRY_MS = 2_000L
        private const val CONNECT_TIMEOUT_MS = 3_000

        private const val TAG = "StreamClient"
    }

    private val queue = LinkedBlockingQueue<String>(CAPACITY)
    // Lines dropped because the queue was full (PC slow/absent). Warned on the first drop and
    // every 1000th so drops are visible in logcat without spamming at ~52 msg/s.
    private val dropCount = java.util.concurrent.atomic.AtomicLong()

    @Volatile private var running = false
    // The live socket, published so stop() can force-close a connect()/write() in flight.
    @Volatile private var socket: Socket? = null
    private var thread: Thread? = null

    /**
     * Buffer one line for sending. No-op while stopped. Drops the OLDEST queued line
     * if the buffer is full. Non-blocking and socket-free — safe on any thread.
     */
    fun enqueue(line: String) {
        if (!running) return
        var dropped = false
        while (!queue.offer(line)) { queue.poll(); dropped = true }
        if (dropped) {
            val total = dropCount.incrementAndGet()
            if (total == 1L || total % 1000L == 0L)
                Log.w(TAG, "queue full (cap $CAPACITY) — dropping oldest; total dropped: $total")
        }
    }

    /** Start the sender + reconnect loop. Idempotent; clears any stale queued lines. */
    @Synchronized
    fun start() {
        if (running) return
        queue.clear()
        running = true
        val t = Thread({ runLoop() }, "avatar-stream")
        thread = t
        t.start()
        Log.i(TAG, "streaming enabled — sender thread started")
    }

    /**
     * Stop the sender, close the socket, and stop reconnecting. Idempotent. Interrupts
     * the reconnect/poll wait and force-closes the socket so a blocked write or connect
     * unblocks immediately. No thread or socket leaks after this returns.
     */
    @Synchronized
    fun stop() {
        if (!running && thread == null) return
        running = false
        thread?.interrupt()
        runCatching { socket?.close() }
        socket = null
        thread = null
        Log.i(TAG, "streaming disabled — sender thread stopping")
    }

    private fun runLoop() {
        val me = Thread.currentThread()
        // Outer loop = reconnect loop. The identity guard ensures a stop()+start() race
        // can never leave two sender threads alive.
        while (running && thread === me) {
            try {
                val s = Socket()
                socket = s
                s.connect(InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS)
                Log.i(TAG, "connected to $HOST:$PORT")
                BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)).use { out ->
                    while (running && thread === me) {
                        // Block briefly for the next line so a stopped stream exits promptly.
                        val line = queue.poll(1, TimeUnit.SECONDS) ?: continue
                        out.write(line); out.write("\n")
                        // Drain whatever else is already queued, then flush once — efficient
                        // at ~27 msg/s while still bounding latency to one poll cycle.
                        var more = queue.poll()
                        while (more != null) {
                            out.write(more); out.write("\n")
                            more = queue.poll()
                        }
                        out.flush()
                    }
                }
            } catch (e: InterruptedException) {
                // stop() interrupted a poll(); exit cleanly.
                break
            } catch (e: IOException) {
                // PC not up yet, adb reverse missing, wireless ADB dropped, or server
                // restarted mid-stream. Sensors and the local file are unaffected — just retry.
                Log.w(TAG, "socket error (${e.message}); reconnecting in ${RETRY_MS}ms")
            } finally {
                runCatching { socket?.close() }
                socket = null
            }
            if (!running || thread !== me) break
            try {
                Thread.sleep(RETRY_MS)
            } catch (e: InterruptedException) {
                break  // stop() during the reconnect wait
            }
        }
        Log.i(TAG, "sender thread exited")
    }
}
