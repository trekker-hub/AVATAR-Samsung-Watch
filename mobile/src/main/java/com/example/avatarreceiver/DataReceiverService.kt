package com.example.avatarreceiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.File

class DataReceiverService : WearableListenerService() {

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (!channel.path.startsWith("/avatar/")) return

        val filename = channel.path.removePrefix("/avatar/")
        val channelClient = Wearable.getChannelClient(this)

        channelClient.getInputStream(channel).addOnSuccessListener { stream ->
            Thread {
                try {
                    val dir = File(getExternalFilesDir("AVATAR")!!, "")
                    dir.mkdirs()
                    val file = File(dir, filename)
                    stream.use { input ->
                        file.outputStream().use { out -> input.copyTo(out) }
                    }
                    channelClient.close(channel)
                    notify(filename, file.absolutePath)
                } catch (e: Exception) {
                    notify("Receive error", e.message ?: e.javaClass.simpleName)
                }
            }.start()
        }
    }

    private fun notify(title: String, body: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "avatar_data"
        nm.createNotificationChannel(
            NotificationChannel(channelId, "AVATAR Data", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        nm.notify(System.currentTimeMillis().toInt(), n)
    }
}
