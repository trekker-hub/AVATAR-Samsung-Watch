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
class DataLogger(context: Context, autoRun: Boolean) {

    val sessionId: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val file: File

    private val writer: BufferedWriter

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
    fun event(type: String, sensor: String, detail: String = "") {
        writeLine(mapOf(
            "type"    to type,
            "ts"      to System.currentTimeMillis(),
            "session" to sessionId,
            "sensor"  to sensor,
            "detail"  to detail
        ))
    }

    /** Close the file and return it — call once at session end. */
    fun finish(): File {
        writeLine(mapOf(
            "type"    to "session_end",
            "ts"      to System.currentTimeMillis(),
            "session" to sessionId
        ))
        writer.flush()
        writer.close()
        return file
    }

    private fun writeLine(map: Map<String, Any?>) {
        writer.write(toJson(map))
        writer.newLine()
        writer.flush()   // flush each line: safe vs crash, fast enough for <500 Hz
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
