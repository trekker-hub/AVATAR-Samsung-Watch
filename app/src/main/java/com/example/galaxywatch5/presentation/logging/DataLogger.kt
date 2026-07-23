package com.example.galaxywatch5.presentation.logging

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes one JSONL file per session.
 * Each line is a complete JSON object — safe for partial files on crash.
 * Keys are sanitised (spaces/brackets → underscores) so the file opens cleanly
 * in pandas: pd.read_json("file.jsonl", lines=True)
 *
 * Files land in [AvatarStorage.dir]; read them back with [SessionStore].
 */
/**
 * @param lineSink optional additional sink for each written JSON line, called with the
 * IDENTICAL string that is written to the file (used to mirror lines to the network
 * stream). The file write is authoritative and never affected by the sink.
 */
class DataLogger(
    context: Context,
    autoRun: Boolean,
    private val lineSink: ((String) -> Unit)? = null,
) {

    val sessionId: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val file: File

    private val writer: BufferedWriter

    // In multi-sensor mode several per-tracker HandlerThreads write concurrently, so every write
    // path is synchronized; `closed` makes writes after finish() no-op instead of hitting a closed
    // writer.
    @Volatile private var closed = false

    init {
        val dir = AvatarStorage.dir(context)
        file = File(dir, "avatar_$sessionId.jsonl")
        writer = BufferedWriter(FileWriter(file, false))
        writeLine(mapOf(
            "type"     to "session_start",
            "ts"       to System.currentTimeMillis(),
            "session"  to sessionId,
            "auto_run" to autoRun
        ))
    }

    /** Log a sensor data point — pass the raw readings map or any field map. */
    @Synchronized
    fun log(sensor: String, fields: Map<String, Any?>) {
        val m = LinkedHashMap<String, Any?>()
        m["type"]    = "reading"
        m["ts"]      = System.currentTimeMillis()
        m["session"] = sessionId
        m["sensor"]  = sensor
        m.putAll(fields)
        writeLine(m)
    }

    /** Log a lifecycle event (sensor_start, sensor_end, error, etc.). */
    @Synchronized
    fun event(type: String, sensor: String, detail: String = "") {
        writeLine(mapOf(
            "type"    to type,
            "ts"      to System.currentTimeMillis(),
            "session" to sessionId,
            "sensor"  to sensor,
            "detail"  to detail
        ))
    }

    /** Close the file and return it — call once at session end. Idempotent. */
    @Synchronized
    fun finish(): File {
        if (!closed) {
            writeLine(mapOf(
                "type"    to "session_end",
                "ts"      to System.currentTimeMillis(),
                "session" to sessionId
            ))
            writer.flush()
            writer.close()
            closed = true
        }
        return file
    }

    private fun writeLine(map: Map<String, Any?>) {
        if (closed) return
        val json = toJson(map)
        writer.write(json)
        writer.newLine()
        writer.flush()   // flush each line: safe vs crash, fast enough for <500 Hz
        // Mirror the exact same line to the stream (if wired). Additive: never gates or
        // affects the file write above.
        lineSink?.invoke(json)
    }

    private fun toJson(map: Map<String, Any?>): String = buildString {
        append('{')
        var first = true
        map.forEach { (rawKey, v) ->
            if (!first) append(',')
            first = false
            val key = rawKey.trimStart(' ', '[')
                .replace(Regex("[^A-Za-z0-9]+"), "_")
                .trim('_')
            append('"').append(key).append("\":")
            when (v) {
                null       -> append("null")
                is Boolean -> append(v)
                is Number  -> append(v)
                else -> {
                    val s = v.toString()
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                    append('"').append(s).append('"')
                }
            }
        }
        append('}')
    }
}
