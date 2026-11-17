package com.dj.dot

import android.app.Application
import android.provider.MediaStore
import com.dj.dot.data.Library
import com.dj.dot.data.PlaylistStore
import com.dj.dot.data.Settings
import com.dj.dot.playback.DotEngine
import com.dj.dot.playback.GlyphLights
import com.dj.dot.playback.Headphones
import com.dj.dot.ui.ArtLoader
import com.dj.dot.widget.DotTileService
import com.dj.dot.widget.DotWidget
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DotApp : Application() {
    lateinit var settings: Settings private set
    lateinit var store: PlaylistStore private set
    lateinit var library: Library private set
    lateinit var engine: DotEngine private set
    lateinit var glyph: GlyphLights private set
    lateinit var headphones: Headphones private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        val scope = MainScope()
        settings = Settings(this)
        store = PlaylistStore(filesDir, scope)
        // Tempos learned before 1.1.1 could be skewed by crossfades and playback speed; relearn them.
        if (settings.tempoVersion.value < 2) {
            store.clearBpm()
            settings.tempoVersion.set(2)
        }
        library = Library(this, scope, settings, onScanComplete = { ArtLoader.clearMissing() })
        scope.launch {
            library.rawSongIds.filterNotNull().first().let { rawIds ->
                if (rawIds.isNotEmpty()) {
                    val volumes = runCatching { MediaStore.getExternalVolumeNames(this@DotApp) }.getOrNull()?.size ?: 1
                    if (volumes >= settings.lastVolumeCount.value) {
                        settings.lastVolumeCount.set(volumes)
                        store.pruneOrphanStats(rawIds)
                    }
                }
            }
        }
        engine = DotEngine(this, settings, store, library)
        val playing = engine.state.map { it.playing }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, false)
        glyph = GlyphLights(this, engine.spectrum, settings, playing) { engine.progress }
        glyph.start()
        headphones = Headphones(this).also { it.start() }
        // Keep the home-screen widget and Quick Settings tile in step with playback.
        scope.launch {
            engine.state.map { it.current?.id to it.playing }.distinctUntilChanged().collect {
                DotWidget.push(this@DotApp)
                DotTileService.requestRefresh(this@DotApp)
            }
        }
    }

    companion object {
        lateinit var instance: DotApp private set
    }
}
