package com.elpollodiablo.hotspotapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        if (!Prefs.listening(context)) return
        try {
            context.startForegroundService(Intent(context, HotspotService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "could not restart listener after boot", e)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
