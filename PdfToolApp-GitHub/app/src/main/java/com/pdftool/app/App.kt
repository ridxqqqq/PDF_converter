package com.pdftool.app

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/**
 * Application entry point. PDFBox for Android must be initialized once before
 * any PDF operation; doing it here guarantees it runs before any Activity.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
    }
}
