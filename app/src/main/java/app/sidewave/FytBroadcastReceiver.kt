package app.sidewave

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Перехватывает широковещательные интенты от стоковых приложений FYT и Android AVRCP.
 */
class FytBroadcastReceiver : BroadcastReceiver() {
    companion object {
        var activeBridge: FytIpcBridge? = null
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) return
        val extras = intent.extras ?: return

        val title = extras.getString("title")
            ?: extras.getString("track")
            ?: extras.getString("music_title")
            ?: extras.getString("android.intent.extra.TITLE")

        val artist = extras.getString("artist")
            ?: extras.getString("music_artist")
            ?: extras.getString("android.intent.extra.ARTIST")

        val album = extras.getString("album")
            ?: extras.getString("music_album")

        if (!title.isNullOrBlank() || !artist.isNullOrBlank()) {
            activeBridge?.updateFromIntent(title, artist, album)
        }
    }
}
