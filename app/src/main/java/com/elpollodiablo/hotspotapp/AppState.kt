package com.elpollodiablo.hotspotapp

object AppState {
    @Volatile
    var listening: Boolean = false

    @Volatile
    var deviceConnected: Boolean = false

    @Volatile
    var hotspotOn: Boolean = false

    @Volatile
    var lastEvent: String = "idle"

    @Volatile
    var lastEventAt: Long = 0L

    fun setEvent(message: String) {
        lastEvent = message
        lastEventAt = System.currentTimeMillis()
    }
}
