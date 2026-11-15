package io.github.iufb.reach

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.core.td.TdLibInfo
import javax.inject.Inject

@HiltAndroidApp
class ReachApp : Application() {

    // Создаём клиент сразу при старте: TDLib начинает присылать апдейты (#14)
    @Inject lateinit var tdClient: TdClient

    override fun onCreate() {
        super.onCreate()
        Log.i("Reach", "TDLib ${TdLibInfo.version()}")
    }
}
