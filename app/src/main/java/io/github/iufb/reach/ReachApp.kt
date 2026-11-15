package io.github.iufb.reach

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import io.github.iufb.reach.core.td.TdLibInfo

@HiltAndroidApp
class ReachApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Smoke-проверка подключения TDLib (#11)
        Log.i("Reach", "TDLib ${TdLibInfo.version()}")
    }
}
