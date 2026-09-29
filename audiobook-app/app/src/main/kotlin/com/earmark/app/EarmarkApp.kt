package com.earmark.app

import android.app.Application
import com.earmark.app.data.Library
import com.earmark.app.data.SettingsStore
import com.earmark.app.playback.PlayerHub
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class EarmarkApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var library: Library
        private set
    lateinit var hub: PlayerHub
        private set

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
        settings = SettingsStore(this)
        library = Library(this)
        hub = PlayerHub(this, library, settings)
    }
}

val android.content.Context.earmark: EarmarkApp get() = applicationContext as EarmarkApp
