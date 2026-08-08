package ir.factory.entryexit

import android.app.Application
import ir.factory.entryexit.util.AppPreferences

class FactoryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppPreferences.applyThemeMode(AppPreferences.getThemeMode(this))
    }
}
