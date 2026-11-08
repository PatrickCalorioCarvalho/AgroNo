package com.mapadrone.app

import android.app.Application
import android.content.Context
import androidx.multidex.MultiDex
import com.cySdkyc.clx.Helper

class MapaApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        MultiDex.install(this)
        // Exigido pelo DJI Mobile SDK v4 (carrega as bibliotecas nativas protegidas).
        Helper.install(this)
    }
}
