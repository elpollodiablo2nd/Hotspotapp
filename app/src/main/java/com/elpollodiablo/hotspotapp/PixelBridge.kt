package com.elpollodiablo.hotspotapp

import android.Manifest
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
            val manager = app.getSystemService(BluetoothManager::class.java) ?: return result.toString()
            val adapter = manager.adapter ?: return result.toString()
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

    private fun isBluetoothEnabled(): Boolean = try {
        val manager = app.getSystemService(BluetoothManager::class.java)
        manager?.adapter?.isEnabled == true
    } catch (e: Exception) {
        false
    }

    companion object {
        private const val TAG = "PixelBridge"
    }
}
