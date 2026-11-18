package com.bigsammy.sammessagescheduler.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class LocationGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError() || event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return
        event.triggeringGeofences.orEmpty().forEach { fence ->
            val id = fence.requestId.toLongOrNull() ?: return@forEach
            LocationGeofenceManager.remove(context, id)
            MessageScheduler.schedule(context, id, System.currentTimeMillis() + 2_000L)
        }
    }
}
