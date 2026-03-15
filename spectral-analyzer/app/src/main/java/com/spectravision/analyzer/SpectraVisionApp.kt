package com.spectravision.analyzer

import android.app.Application
import com.spectravision.analyzer.data.AppDatabase

class SpectraVisionApp : Application() {

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }
}
