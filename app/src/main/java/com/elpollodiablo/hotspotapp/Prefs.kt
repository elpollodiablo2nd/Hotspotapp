package com.elpollodiablo.hotspotapp

import android.content.Context

object Prefs {
    private const val FILE = "hotspot_prefs"
    private const val KEY_TARGET_ADDRESS = "target_address"
    private const val KEY_TARGET_NAME = "target_name"
    private const val KEY_LISTENING = "listening"
    private const val KEY_AP_SSID = "ap_ssid"
    private const val KEY_AP_PASS = "ap_pass"
    private const val KEY_AP_CUSTOM = "ap_custom"

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun targetAddress(context: Context): String? =
        sp(context).getString(KEY_TARGET_ADDRESS, null)

    fun targetName(context: Context): String? =
        sp(context).getString(KEY_TARGET_NAME, null)

    fun setTarget(context: Context, address: String, name: String) {
        sp(context).edit()
            .putString(KEY_TARGET_ADDRESS, address)
            .putString(KEY_TARGET_NAME, name)
            .apply()
    }

    fun listening(context: Context): Boolean =
        sp(context).getBoolean(KEY_LISTENING, false)

    fun setListening(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_LISTENING, value).apply()
    }

    fun hotspotSsid(context: Context): String? =
        sp(context).getString(KEY_AP_SSID, null)

    fun hotspotPassphrase(context: Context): String? =
        sp(context).getString(KEY_AP_PASS, null)

    fun setHotspotConfig(context: Context, ssid: String, passphrase: String, custom: Boolean) {
        sp(context).edit()
            .putString(KEY_AP_SSID, ssid)
            .putString(KEY_AP_PASS, passphrase)
            .putBoolean(KEY_AP_CUSTOM, custom)
            .apply()
    }

    fun apCustom(context: Context): Boolean =
        sp(context).getBoolean(KEY_AP_CUSTOM, false)
}
