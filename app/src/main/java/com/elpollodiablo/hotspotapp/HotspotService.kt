package com.elpollodiablo.hotspotapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

class HotspotService : Service() {

    private var btReceiver: BroadcastReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIF_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIF_ID, buildNotification())
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            AppState.setEvent("service start failed: ${e.message}")
            stopSelf()
            return
        }
        acquireWakeLock()
        registerBtReceiver()
        AppState.listening = true
        AppState.setEvent("listening for ${Prefs.targetName(this) ?: "saved device"}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Prefs.listening(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AppState.listening = false
        try {
            btReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            Log.w(TAG, "unregister receiver failed", e)
        }
        btReceiver = null
        releaseWakeLock()
        super.onDestroy()
    }

    private fun registerBtReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device = deviceFrom(intent) ?: return
                val target = Prefs.targetAddress(context) ?: return
                if (device.address != target) return
                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> onTargetConnected(context)
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> onTargetDisconnected(context)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        btReceiver = receiver
    }

    private fun onTargetConnected(context: Context) {
        AppState.deviceConnected = true
        val ok = HotspotController.start(context)
        if (!ok) AppState.setEvent("device connected, hotspot FAILED")
        updateNotification()
    }

    private fun onTargetDisconnected(context: Context) {
        AppState.deviceConnected = false
        HotspotController.stop(context)
        AppState.setEvent("device disconnected, hotspot OFF")
        updateNotification()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HotspotLink:listener")
            lock.acquire()
            wakeLock = lock
        } catch (e: Exception) {
            Log.w(TAG, "wake lock failed", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "wake lock release failed", e)
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = getString(R.string.channel_desc)
            channel.setShowBadge(false)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val name = Prefs.targetName(this) ?: ""
        val text = when {
            AppState.deviceConnected && AppState.hotspotOn -> getString(R.string.notif_connected_on, name)
            AppState.deviceConnected -> getString(R.string.notif_connected, name)
            else -> getString(R.string.notif_listening, name)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_hotspot)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification() {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            Log.w(TAG, "notification update failed", e)
        }
    }

    private fun deviceFrom(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    companion object {
        private const val TAG = "HotspotService"
        private const val NOTIF_ID = 1
        const val CHANNEL_ID = "hotspot_listener"
    }
}
