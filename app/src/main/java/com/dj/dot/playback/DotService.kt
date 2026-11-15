package com.dj.dot.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Size
import androidx.annotation.OptIn
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.dj.dot.DotApp
import com.dj.dot.MainActivity
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
class DotService : MediaSessionService() {
    private var session: MediaSession? = null
    private var bitmapLoader: ThumbnailLoader? = null

    override fun onCreate() {
        super.onCreate()
        val engine = DotApp.instance.engine
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val loader = ThumbnailLoader(this)
        bitmapLoader = loader
        session = MediaSession.Builder(this, engine.sessionPlayer)
            .setSessionActivity(open)
            .setBitmapLoader(loader)
            .build()
        engine.onSessionPlayerChanged = { session?.player = it }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    /** Swiping Dot away from recents stops the music and clears the notification. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val engine = DotApp.instance.engine
        engine.pause(fade = false)
        engine.save()
        stopSelf()
    }

    override fun onDestroy() {
        DotApp.instance.engine.onSessionPlayerChanged = null
        session?.release()
        session = null
        bitmapLoader?.shutdown()
        bitmapLoader = null
        super.onDestroy()
    }
}

/** Notification and lock-screen art straight from the file's embedded cover. */
@OptIn(UnstableApi::class)
private class ThumbnailLoader(private val context: Context) : BitmapLoader {
    private val executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    override fun supportsMimeType(mimeType: String) = true

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = executor.submit(Callable {
        decodeByteArrayDownsampled(data)
    })

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = executor.submit(Callable {
        runCatching {
            context.contentResolver.loadThumbnail(uri, Size(512, 512), null)
        }.getOrNull() ?: context.contentResolver.openInputStream(uri)?.use { stream ->
            decodeByteArrayDownsampled(stream.readBytes())
        } ?: error("Could not load artwork from $uri")
    })

    fun shutdown() {
        executor.shutdown()
    }

    private fun decodeByteArrayDownsampled(data: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (maxDim / (sample * 2) >= 512) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(data, 0, data.size, decodeOpts) ?: error("Could not decode artwork")
    }
}
