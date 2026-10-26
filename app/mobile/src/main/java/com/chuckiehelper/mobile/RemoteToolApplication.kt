package com.chuckiehelper.mobile

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.chuckiehelper.mobile.diagnostics.CrashReports

class RemoteToolApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        CrashReports.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.PHONE_COMPANION && !getSharedPreferences("phone-tools", MODE_PRIVATE).contains("port")) {
            com.chuckiehelper.mobile.phonecontrol.PhoneSettings(this).port = 18792
        }
        com.chuckiehelper.mobile.phonecontrol.PhoneControlHost.apply {
            bootRestorePaused = { com.chuckiehelper.mobile.startup.StartupService.phonePaused }
            rememberForBoot = { context, base -> com.chuckiehelper.mobile.startup.StartupSettings(context).rememberPhone(base) }
            pauseBootRestore = { com.chuckiehelper.mobile.startup.StartupService.pausePhone() }
            resumeBootRestore = { com.chuckiehelper.mobile.startup.StartupService.resumePhone() }
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) = CrashReports.breadcrumb("${activity.javaClass.simpleName}: created")
            override fun onActivityResumed(activity: Activity) {
                CrashReports.breadcrumb("${activity.javaClass.simpleName}: resumed")
                com.chuckiehelper.mobile.startup.PhoneAutoRestore.request(activity)
                com.chuckiehelper.mobile.phonecontrol.PhoneAccessibilityControl.request(activity)
            }
            override fun onActivityPaused(activity: Activity) = CrashReports.breadcrumb("${activity.javaClass.simpleName}: paused")
            override fun onActivityDestroyed(activity: Activity) = CrashReports.breadcrumb("${activity.javaClass.simpleName}: destroyed")
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
        CrashReports.collectSystemExits(this)
        com.chuckiehelper.mobile.startup.PhoneAutoRestore.request(this)
        com.chuckiehelper.mobile.phonecontrol.PhoneAccessibilityControl.request(this)
    }
}
