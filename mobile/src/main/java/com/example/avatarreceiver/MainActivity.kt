package com.example.avatarreceiver

import android.os.Bundle
import android.app.Activity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(layout)
        setContentView(scroll)

        val dir = File(getExternalFilesDir("AVATAR")!!, "")
        dir.mkdirs()

        val header = TextView(this).apply {
            text = "AVATAR Receiver\nFiles saved to:\n${dir.absolutePath}\n"
            setPadding(24, 24, 24, 8)
            textSize = 14f
        }
        layout.addView(header)

        val files = dir.listFiles()
            ?.filter { it.name.endsWith(".jsonl") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        if (files.isEmpty()) {
            layout.addView(TextView(this).apply {
                text = "No files received yet.\nRun Auto Run All on the watch."
                setPadding(24, 8, 24, 8)
            })
        } else {
            files.forEach { f ->
                layout.addView(TextView(this).apply {
                    text = "• ${f.name}  (${f.length() / 1024} KB)"
                    setPadding(24, 6, 24, 6)
                    textSize = 13f
                })
            }
        }
    }
}
