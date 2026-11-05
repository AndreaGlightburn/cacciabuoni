package it.iz2oii.cacciabuoni

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Notifier.channels(this)
        DailyWorker.schedule(this)
    }
}
