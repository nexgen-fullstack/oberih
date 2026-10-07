package com.uberbro.oberih

import android.app.Application
import com.uberbro.oberih.work.DailyWorker

class OberihApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DailyWorker.schedule(this)
    }
}
