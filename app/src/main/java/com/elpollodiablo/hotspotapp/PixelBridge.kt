package com.elpollodiablo.hotspotapp

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

class PixelBridge(private val activity: MainActivity) {

    private val app = activity.applicationContext

    @JavascriptInterface
    fun pairedDevicesJson(): String {
        val result = JSONArray()
        try {
            if (Build.VERSION.SDK_INT >= 31 &&
                app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) return result.toString()
            val adapter = bluetoothAdapter() ?: return result.toString()
            for (device in adapter.bondedDevices) {
                result.put(
                    JSONObject()
                        .put("name", device.name ?: device.address)
                        .put("address", device.address)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "pairedDevicesJson failed", e)
        }
        return result.toString()
    }

    @JavascriptInterface
    fun savedDeviceJson(): String {
        val address = Prefs.targetAddress(app) ?: return "null"
        return JSONObject()
            .put("address", address)
            .put("name", Prefs.targetName(app) ?: address)
            .toString()
    }

    @JavascriptInterface
    fun saveDevice(address: String, name: String) {
        Prefs.setTarget(app, address, name)
    }

    @JavascriptInterface
    fun saveHotspotConfig(ssid: String, passphrase: String) {
        Prefs.setHotspotConfig(app, ssid.trim(), passphrase, custom = true)
    }

    @JavascriptInterface
    fun savedApJson(): String {
        val ssid = Prefs.hotspotSsid(app) ?: return "null"
        return JSONObject()
            .put("ssid", ssid)
            .put("passphrase", Prefs.hotspotPassphrase(app) ?: "")
            .toString()
    }

    @JavascriptInterface
    fun systemHotspotJson(): String {
        val config = HotspotController.systemHotspotConfig(app) ?: return "null"
        return JSONObject()
            .put("ssid", config.first)
            .put("passphrase", config.second)
            .toString()
    }

    @JavascriptInterface
    fun startListening() {
        Prefs.setListening(app, true)
        activity.runOnUiThread {
            try {
                app.startForegroundService(Intent(app, HotspotService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "start service failed", e)
                AppState.setEvent("start failed: ${e.message}")
            }
        }
    }

    @JavascriptInterface
    fun stopListening() {
        Prefs.setListening(app, false)
        HotspotController.stop(app)
        activity.runOnUiThread {
            AppState.listening = false
            app.stopService(Intent(app, HotspotService::class.java))
        }
    }

    @JavascriptInterface
    fun statusJson(): String {
        val status = JSONObject()
        status.put("listening", AppState.listening)
        status.put("wantListening", Prefs.listening(app))
        status.put("targetAddress", Prefs.targetAddress(app) ?: "")
        status.put("targetName", Prefs.targetName(app) ?: "")
        status.put("deviceConnected", AppState.deviceConnected)
        status.put("hotspotOn", AppState.hotspotOn || HotspotController.isOn(app))
        status.put("ssid", HotspotController.lastSsid ?: "")
        status.put("passphrase", HotspotController.lastPassphrase ?: "")
        status.put("mode", HotspotController.lastMode ?: "")
        status.put("apSsid", Prefs.hotspotSsid(app) ?: "")
        status.put("apPass", Prefs.hotspotPassphrase(app) ?: "")
        status.put("btEnabled", isBluetoothEnabled())
        status.put("lastEvent", AppState.lastEvent)
        status.put("error", HotspotController.lastError ?: "")
        return status.toString()
    }

    @JavascriptInterface
    fun openWifiPanel() {
        activity.runOnUiThread {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    activity.startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
                } else {
                    activity.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                }
            } catch (e: Exception) {
                try {
                    activity.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                } catch (e2: Exception) {
                    Log.w(TAG, "cannot open wifi panel", e2)
                }
            }
        }
    }

    private fun bluetoothAdapter(): BluetoothAdapter? = try {
        val manager = app.getSystemService(BluetoothManager::class.java) ?: return null
        manager.javaClass.getMethod("getAdapter").invoke(manager) as? BluetoothAdapter
    } catch (e: Exception) {
        Log.w(TAG, "getAdapter reflection failed", e)
        null
    }

    private fun isBluetoothEnabled(): Boolean = try {
        bluetoothAdapter()?.isEnabled == true
    } catch (e: Exception) {
        false
    }

    companion object {
        private const val TAG = "PixelBridge"
    }
}
