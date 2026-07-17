package com.example.galaxywatch5.presentation.logging

import android.content.Context
import java.io.File

/** Lightweight metadata about one on-watch session log file. */
data class SessionFile(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
)

/**
 * Read-side companion to [DataLogger]: lists, reads and deletes the JSONL session files
 * that DataLogger writes. Kept free of any UI so it can be reused by a phone-sync/export
 * step later.
 */
object SessionStore {

    /** Newest first. Only `.jsonl` files in the AVATAR dir. */
    fun listSessions(context: Context): List<SessionFile> =
        AvatarStorage.dir(context)
            .listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { f -> SessionFile(f, f.name, f.length(), f.lastModified()) }
            ?: emptyList()

    /** First [max] lines of a session file. Returns the lines and whether it was truncated. */
    fun readLines(file: File, max: Int = 400): ReadResult = runCatching {
        file.useLines { seq ->
            val taken = seq.take(max + 1).toList()
            ReadResult(taken.take(max), truncated = taken.size > max)
        }
    }.getOrElse { ReadResult(listOf("‹ could not read file: ${it.javaClass.simpleName}"), false) }

    fun delete(file: File): Boolean = runCatching { file.delete() }.getOrDefault(false)

    /** Delete every session file. Returns how many were removed. */
    fun deleteAll(context: Context): Int =
        listSessions(context).count { delete(it.file) }

    data class ReadResult(val lines: List<String>, val truncated: Boolean)
}
