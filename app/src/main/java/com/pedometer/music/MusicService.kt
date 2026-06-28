package com.pedometer.music

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import com.pedometer.bt.ProtocolHandler
import com.pedometer.proto.XiaomiProto

class MusicService(
    private val context: Context,
    private val protocolHandler: ProtocolHandler,
) {
    companion object {
        private const val TAG = "MusicService"
        const val COMMAND_TYPE = 20
        const val CMD_MUSIC_INFO = 1
        const val CMD_MEDIA_KEY = 2
    }

    private var activeController: MediaController? = null
    private val sessionManager by lazy {
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    }

    fun handleCommand(cmd: XiaomiProto.Command) {
        when (cmd.subtype) {
            CMD_MEDIA_KEY -> {
                if (cmd.hasMusic() && cmd.music.hasMediaKey()) {
                    handleMediaKey(cmd.music.mediaKey)
                }
            }
            else -> Log.d(TAG, "Unhandled music subtype: ${cmd.subtype}")
        }
    }

    private fun handleMediaKey(mediaKey: XiaomiProto.MediaKey) {
        val controller = getActiveController() ?: return
        val transport = controller.transportControls ?: return

        when (mediaKey.key) {
            0 -> transport.play()       // play
            1 -> transport.pause()      // pause
            3 -> transport.skipToPrevious()  // prev
            4 -> transport.skipToNext()      // next
            5 -> {
                // volume: 100=up, 0=down
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                if (mediaKey.volume >= 50) {
                    audioManager.adjustVolume(android.media.AudioManager.ADJUST_RAISE, 0)
                } else {
                    audioManager.adjustVolume(android.media.AudioManager.ADJUST_LOWER, 0)
                }
            }
            else -> Log.d(TAG, "Unknown media key: ${mediaKey.key}")
        }
    }

    fun sendCurrentMusicInfo() {
        val controller = getActiveController()
        val metadata = controller?.metadata
        val playbackState = controller?.playbackState

        val state = when (playbackState?.state) {
            PlaybackState.STATE_PLAYING -> 1
            PlaybackState.STATE_PAUSED -> 2
            else -> 0
        }

        val builder = XiaomiProto.MusicInfo.newBuilder().setState(state)

        metadata?.let { m ->
            m.getString(MediaMetadata.METADATA_KEY_TITLE)?.let { builder.setTrack(it) }
            m.getString(MediaMetadata.METADATA_KEY_ARTIST)?.let { builder.setArtist(it) }
            m.getLong(MediaMetadata.METADATA_KEY_DURATION).let {
                if (it > 0) builder.setDuration((it / 1000).toInt())
            }
        }

        playbackState?.position?.let {
            builder.setPosition((it / 1000).toInt())
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val maxVol = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val curVol = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        if (maxVol > 0) {
            builder.setVolume(curVol * 100 / maxVol)
        }

        val cmd = XiaomiProto.Command.newBuilder()
            .setType(COMMAND_TYPE)
            .setSubtype(CMD_MUSIC_INFO)
            .setMusic(XiaomiProto.Music.newBuilder().setMusicInfo(builder))
            .build()

        protocolHandler.sendCommand(cmd)
    }

    private fun getActiveController(): MediaController? {
        try {
            val component = ComponentName(context, MediaListenerService::class.java)
            val controllers = sessionManager.getActiveSessions(component)
            activeController = controllers.firstOrNull()
        } catch (e: SecurityException) {
            Log.w(TAG, "No notification listener permission", e)
        }
        return activeController
    }
}
