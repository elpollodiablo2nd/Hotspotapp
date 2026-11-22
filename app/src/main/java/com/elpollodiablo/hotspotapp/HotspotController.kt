package com.elpollodiablo.hotspotapp

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
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
    var lastPassphrase: String? = null

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
        val ssid = Prefs.hotspotSsid(context)
        val passphrase = Prefs.hotspotPassphrase(context)
        if (!ssid.isNullOrEmpty()) {
            try {
                startLocalOnlyWithConfig(context, ssid, passphrase ?: "")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "configured local-only hotspot failed, falling back to default", e)
                lastError = "custom AP config failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        return try {
            startLocalOnlyDefault(context)
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

    fun systemHotspotConfig(context: Context): Pair<String, String>? {
        return try {
            val wm = wifiManager(context)
            val conf = wm.javaClass.getMethod("getSoftApConfiguration").invoke(wm) ?: return null
            val ssid = confString(conf, "getSsid", "SSID")
            if (ssid.isNullOrEmpty()) return null
            ssid to (confString(conf, "getPassphrase", "preSharedKey") ?: "")
        } catch (e: Exception) {
            Log.w(TAG, "reading system SoftApConfiguration failed", e)
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun startLocalOnlyDefault(context: Context) {
        val wm = wifiManager(context)
        wm.startLocalOnlyHotspot(callback, Handler(Looper.getMainLooper()))
    }

    private fun startLocalOnlyWithConfig(context: Context, ssid: String, passphrase: String) {
        val wm = wifiManager(context)
        val configClass = Class.forName("android.net.wifi.SoftApConfiguration")
        val builderClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
        val builder = builderClass.getConstructor().newInstance()
        builderClass.getMethod("setSsid", String::class.java).invoke(builder, ssid)
        if (passphrase.length >= 8) {
            builderClass.getMethod("setPassphrase", String::class.java).invoke(builder, passphrase)
        }
        val config = builderClass.getMethod("build").invoke(builder)
        val executor = Executor { command -> Handler(Looper.getMainLooper()).post(command) }
        val method = wm.javaClass.getMethod(
            "startLocalOnlyHotspot",
            configClass,
            Executor::class.java,
            WifiManager.LocalOnlyHotspotCallback::class.java
        )
        method.invoke(wm, config, executor, callback)
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

    private fun credsOf(reservation: WifiManager.LocalOnlyHotspotReservation?): Pair<String?, String?> {
        if (reservation == null) return null to null
        for (methodName in listOf("getSoftApConfiguration", "getWifiConfiguration")) {
            try {
                val conf = reservation.javaClass.getMethod(methodName).invoke(reservation) ?: continue
                return confString(conf, "getSsid", "SSID") to confString(conf, "getPassphrase", "preSharedKey")
            } catch (e: Exception) {
                Log.w(TAG, "$methodName reflection failed", e)
            }
        }
        return null to null
    }

    private fun confString(conf: Any, getter: String, field: String): String? {
        try {
            val method = conf.javaClass.methods.firstOrNull { it.name == getter }
            if (method != null) {
                (method.invoke(conf) as? String)?.let { return it }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getter $getter failed", e)
        }
        return try {
            (conf.javaClass.getField(field).get(conf) as? String)?.removeSurrounding("\"")
        } catch (e: Exception) {
            null
        }
    }

    private val callback = object : WifiManager.LocalOnlyHotspotCallback() {
        override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
            this@HotspotController.reservation = reservation
            val (ssid, passphrase) = credsOf(reservation)
            lastSsid = ssid
            lastPassphrase = passphrase
            AppState.hotspotOn = true
            AppState.setEvent("hotspot ON" + (ssid?.let { " [$it]" } ?: ""))
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
