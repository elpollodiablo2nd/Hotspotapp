package com.elpollodiablo.hotspotapp

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log

object HotspotController {
    private const val TAG = "HotspotController"

    @Volatile
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    @Volatile
    var lastSsid: String? = null

    @Volatile
    var lastError: String? = null

    private fun wifiManager(context: Context): WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun isOn(context: Context): Boolean {
        if (reservation != null) return true
        return try {
            val wm = wifiManager(context)
            wm.javaClass.getMethod("isWifiApEnabled").invoke(wm) == true
        } catch (e: Exception) {
            false
        }
    }

    fun start(context: Context): Boolean {
        if (isOn(context)) {
            AppState.hotspotOn = true
            return true
        }
        lastError = null
        return try {
            startLocalOnly(context)
            true
        } catch (e: Exception) {
            Log.w(TAG, "local-only hotspot failed, trying legacy AP toggle", e)
            if (setApEnabledLegacy(context, true)) {
                AppState.hotspotOn = true
                true
            } else {
                AppState.hotspotOn = false
                lastError = "hotspot start failed (${e.message ?: e.javaClass.simpleName})"
                false
            }
        }
    }

    fun stop(context: Context) {
        try {
            reservation?.close()
        } catch (e: Exception) {
            Log.w(TAG, "closing reservation failed", e)
        }
        reservation = null
        try {
            val wm = wifiManager(context)
            wm.javaClass.getMethod("cancelLocalOnlyHotspotRequest").invoke(wm)
        } catch (e: Exception) {
            Log.w(TAG, "cancelLocalOnlyHotspotRequest failed", e)
        }
        setApEnabledLegacy(context, false)
        AppState.hotspotOn = false
    }

    @Suppress("DEPRECATION")
    private fun startLocalOnly(context: Context) {
        val wm = wifiManager(context)
        wm.startLocalOnlyHotspot(callback, Handler(Looper.getMainLooper()))
    }

    private fun setApEnabledLegacy(context: Context, enable: Boolean): Boolean = try {
        val wm = wifiManager(context)
        val method = wm.javaClass.getMethod(
            "setWifiApEnabled",
            WifiConfiguration::class.java,
            Boolean::class.javaPrimitiveType
        )
        method.invoke(wm, null, enable) == true
    } catch (e: Exception) {
        false
    }

    private fun ssidOf(reservation: WifiManager.LocalOnlyHotspotReservation?): String? {
        if (reservation == null) return null
        for (methodName in listOf("getSoftApConfiguration", "getWifiConfiguration")) {
            try {
                val conf = reservation.javaClass.getMethod(methodName).invoke(reservation) ?: continue
                ssidOfConf(conf)?.let { return it }
            } catch (e: Exception) {
                Log.w(TAG, "$methodName reflection failed", e)
            }
        }
        return null
    }

    private fun ssidOfConf(conf: Any): String? {
        for (method in conf.javaClass.methods) {
            if (method.name == "getSsid" || method.name == "getSSID") {
                try {
                    (method.invoke(conf) as? String)?.let { return it }
                } catch (e: Exception) {
                    Log.w(TAG, "ssid getter failed", e)
                }
            }
        }
        return try {
            conf.javaClass.getField("SSID").get(conf) as? String
        } catch (e: Exception) {
            null
        }
    }

    private val callback = object : WifiManager.LocalOnlyHotspotCallback() {
        override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
            this@HotspotController.reservation = reservation
            lastSsid = ssidOf(reservation)
            AppState.hotspotOn = true
            AppState.setEvent("hotspot ON" + (lastSsid?.let { " [$it]" } ?: ""))
        }

        override fun onStopped() {
            reservation = null
            AppState.hotspotOn = false
            AppState.setEvent("hotspot OFF")
        }

        override fun onFailed(reason: Int) {
            reservation = null
            AppState.hotspotOn = false
            lastError = "local-only hotspot failed (reason $reason)"
            AppState.setEvent("hotspot FAILED ($reason)")
        }
    }
}
