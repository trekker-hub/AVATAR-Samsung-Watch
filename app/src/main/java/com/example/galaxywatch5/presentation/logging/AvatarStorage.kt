package com.example.galaxywatch5.presentation.logging

import android.content.Context
import java.io.File

/**
 * Single source of truth for WHERE session logs live, so DataLogger (writer) and
 * SessionStore (reader) can never drift apart.
 *
 * On-device path: /sdcard/Android/data/com.example.galaxywatch5/files/AVATAR/
 * Pull to a computer with:
 *   adb pull /sdcard/Android/data/com.example.galaxywatch5/files/AVATAR/ ./avatar_logs
 */
object AvatarStorage {
    fun dir(context: Context): File =
        (context.getExternalFilesDir("AVATAR") ?: context.filesDir).apply { mkdirs() }
}
