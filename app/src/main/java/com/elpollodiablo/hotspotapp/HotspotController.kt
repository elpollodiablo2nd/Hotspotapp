package com.elpollodiablo.hotspotapp

import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executor

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
            val method = wm.javaClass.getMethod("isWifiApEnabled")
            method.invoke(wm) == true
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
            if (Build.VERSION.SDK_INT >= 30) startLocalOnly(context) else startLocalOnlyLegacy(context)
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
        if (Build.VERSION.SDK_INT < 30) {
            try {
                val wm = wifiManager(context)
                wm.javaClass.getMethod("cancelLocalOnlyHotspotRequest").invoke(wm)
            } catch (e: Exception) {
                Log.w(TAG, "cancelLocalOnlyHotspotRequest failed", e)
            }
        }
        setApEnabledLegacy(context, false)
        AppState.hotspotOn = false
    }

    private fun startLocalOnly(context: Context) {
        val wm = wifiManager(context)
        val config = currentSoftApConfig(wm) ?: SoftApConfiguration.Builder().build()
        val executor = Executor { command -> Handler(Looper.getMainLooper()).post(command) }
        wm.startLocalOnlyHotspot(config, executor, callback)
    }

    private fun startLocalOnlyLegacy(context: Context) {
        val wm = wifiManager(context)
        val handler = Handler(Looper.getMainLooper())
        val method = wm.javaClass.getMethod(
            "startLocalOnlyHotspot",
            WifiManager.LocalOnlyHotspotCallback::class.java,
            Handler::class.java
        )
        method.invoke(wm, callback, handler)
    }

    private fun currentSoftApConfig(wm: WifiManager): SoftApConfiguration? {
        if (Build.VERSION.SDK_INT < 30) return null
        try {
            val method = wm.javaClass.getMethod("getSoftApConfiguration")
            return method.invoke(wm) as? SoftApConfiguration
        } catch (e: Exception) {
            Log.w(TAG, "getSoftApConfiguration reflection failed", e)
        }
        return try {
            @Suppress("DEPRECATION")
            val legacy = wm.wifiApConfiguration ?: return null
            val builder = SoftApConfiguration.Builder()
            legacy.SSID?.removeSurrounding("\"")?.let { builder.setSsid(it) }
            legacy.preSharedKey?.removeSurrounding("\"")?.let { builder.setPassphrase(it) }
            builder.build()
        } catch (e: Exception) {
            Log.w(TAG, "building SoftApConfiguration from legacy config failed", e)
            null
        }
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
        return try {
            val method = reservation.javaClass.getMethod("getSoftApConfiguration")
            (method.invoke(reservation) as? SoftApConfiguration)?.ssid
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
