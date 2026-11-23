package com.elpollodiablo.hotspotapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.util.Log
import java.security.SecureRandom
import java.util.concurrent.Executor

object HotspotController {
    private const val TAG = "HotspotController"

    private const val TETHERING_WIFI = 0
    private const val TETHER_ERROR_NO_ERROR = 0

    @Volatile
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    @Volatile
    var lastSsid: String? = null

    @Volatile
    var lastPassphrase: String? = null

    @Volatile
    var lastMode: String? = null

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
        if (tryStartTethering(context)) {
            return true
        }
        return startLocalOnlyChain(context)
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
        try {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.javaClass.getMethod("stopTethering", Int::class.javaPrimitiveType)
                .invoke(cm, TETHERING_WIFI)
        } catch (e: Exception) {
            Log.w(TAG, "stopTethering failed", e)
        }
        setApEnabledLegacy(context, false)
        lastMode = null
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

    private fun tryStartTethering(context: Context): Boolean {
        return try {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val method = cm.javaClass.getMethod(
                "startTethering",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                ResultReceiver::class.java,
                Handler::class.java
            )
            val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    if (resultCode == TETHER_ERROR_NO_ERROR) {
                        lastMode = "tethered"
                        AppState.hotspotOn = true
                        AppState.setEvent("tethered hotspot ON (internet shared)")
                    } else {
                        lastError = "tethering unavailable (code $resultCode), using local-only"
                        Log.w(TAG, "startTethering failed code=$resultCode, falling back to local-only")
                        startLocalOnlyChain(context)
                    }
                }
            }
            method.invoke(cm, TETHERING_WIFI, false, receiver, Handler(Looper.getMainLooper()))
            true
        } catch (e: Exception) {
            Log.w(TAG, "startTethering reflection failed", e)
            false
        }
    }

    private fun startLocalOnlyChain(context: Context): Boolean {
        val errors = mutableListOf<String>()
        if (Prefs.apCustom(context)) {
            val ssid = Prefs.hotspotSsid(context)
            if (!ssid.isNullOrEmpty()) {
                try {
                    startLocalOnlyWithConfig(context, ssid, Prefs.hotspotPassphrase(context) ?: "")
                    lastMode = "local-only"
                    return true
                } catch (e: Exception) {
                    errors += "custom AP: ${e.message ?: e.javaClass.simpleName}"
                    Log.w(TAG, "custom AP start failed", e)
                }
            }
        }
        try {
            if (startWithSystemConfig(context)) {
                lastMode = "local-only"
                return true
            }
        } catch (e: Exception) {
            errors += "system AP config: ${e.message ?: e.javaClass.simpleName}"
            Log.w(TAG, "system AP config start failed", e)
        }
        val (ssid, passphrase) = ensureApConfig(context)
        try {
            startLocalOnlyWithConfig(context, ssid, passphrase)
            lastMode = "local-only"
            return true
        } catch (e: Exception) {
            errors += "generated AP: ${e.message ?: e.javaClass.simpleName}"
            Log.w(TAG, "generated AP start failed", e)
        }
        return try {
            startLocalOnlyDefault(context)
            lastMode = "local-only"
            lastError = null
            true
        } catch (e: Exception) {
            Log.w(TAG, "default local-only hotspot failed, trying legacy AP toggle", e)
            if (setApEnabledLegacy(context, true)) {
                lastMode = "legacy-ap"
                AppState.hotspotOn = true
                true
            } else {
                AppState.hotspotOn = false
                lastError = "hotspot start failed (${errors.joinToString("; ")})"
                false
            }
        }
    }

    private fun ensureApConfig(context: Context): Pair<String, String> {
        Prefs.hotspotSsid(context)?.let { saved ->
            return saved to (Prefs.hotspotPassphrase(context) ?: "")
        }
        val generated = generateApConfig()
        Prefs.setHotspotConfig(context, generated.first, generated.second, custom = false)
        return generated
    }

    private fun generateApConfig(): Pair<String, String> {
        val random = SecureRandom()
        val passChars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        val passphrase = (1..12).map { passChars[random.nextInt(passChars.length)] }.joinToString("")
        val suffix = (1..4).map { "0123456789ABCDEF"[random.nextInt(16)] }.joinToString("")
        return "HotspotLink-$suffix" to passphrase
    }

    @Suppress("DEPRECATION")
    private fun startLocalOnlyDefault(context: Context) {
        val wm = wifiManager(context)
        wm.startLocalOnlyHotspot(callback, Handler(Looper.getMainLooper()))
    }

    private fun startWithSystemConfig(context: Context): Boolean {
        val wm = wifiManager(context)
        val config = wm.javaClass.getMethod("getSoftApConfiguration").invoke(wm) ?: return false
        startWithExistingConfig(context, config)
        return true
    }

    private fun startLocalOnlyWithConfig(context: Context, ssid: String, passphrase: String) {
        val builderClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
        val builder = builderClass.getConstructor().newInstance()
        builderClass.getMethod("setSsid", String::class.java).invoke(builder, ssid)
        if (passphrase.isNotEmpty()) {
            var passSet = false
            for (method in builderClass.methods.filter { it.name == "setPassphrase" }) {
                try {
                    when (method.parameterCount) {
                        1 -> {
                            method.invoke(builder, passphrase)
                            passSet = true
                        }
                        2 -> {
                            val securityType = try {
                                Class.forName("android.net.wifi.SoftApConfiguration")
                                    .getField("SECURITY_TYPE_WPA2_PSK").getInt(null)
                            } catch (e: Exception) {
                                1
                            }
                            method.invoke(builder, passphrase, securityType)
                            passSet = true
                        }
                    }
                    if (passSet) break
                } catch (e: Exception) {
                    Log.w(TAG, "setPassphrase variant blocked: ${method.parameterTypes.joinToString(",")}", e)
                }
            }
            if (!passSet) Log.w(TAG, "no setPassphrase variant worked, continuing SSID-only")
        }
        val config = builderClass.getMethod("build").invoke(builder)
        startWithExistingConfig(context, config)
    }

    private fun startWithExistingConfig(context: Context, config: Any) {
        val wm = wifiManager(context)
        val configClass = Class.forName("android.net.wifi.SoftApConfiguration")
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
            AppState.setEvent("local-only hotspot ON" + (ssid?.let { " [$it]" } ?: "") + " (no internet sharing)")
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
