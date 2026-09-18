package com.ssain3d.gptvoicereceiver

import android.app.Application
import com.ssain3d.gptvoicereceiver.core.log.EventResult
import com.ssain3d.gptvoicereceiver.core.log.SpikeId

class SpikeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Spike.init(this)
        Spike.log.log(
            SpikeId.APP,
            "Application",
            "onCreate",
            EventResult.INFO,
            detail = "pid=${android.os.Process.myPid()} " +
                "device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
                "sdk=${android.os.Build.VERSION.SDK_INT} build=${android.os.Build.DISPLAY}",
        )
    }
}
