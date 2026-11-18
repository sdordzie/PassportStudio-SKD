package com.bigsammy.sammessagescheduler.scheduling

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task

object LocationGeofenceManager {
    fun register(context: Context, messageId: Long, latitude: Double, longitude: Double, radiusMeters: Float): Task<Void> {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val background = Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        require(fine || coarse) { "Location permission is required for an arrival reminder." }
        require(background) { "Allow Location access all the time in Android Settings to use arrival reminders while SAM is closed." }
        val geofence = Geofence.Builder()
            .setRequestId(messageId.toString())
            .setCircularRegion(latitude, longitude, radiusMeters.coerceIn(100f, 1000f))
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .setLoiteringDelay(0)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .build()
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()
        return LocationServices.getGeofencingClient(context).addGeofences(request, pendingIntent(context))
    }

    fun remove(context: Context, messageId: Long) {
        runCatching { LocationServices.getGeofencingClient(context).removeGeofences(listOf(messageId.toString())) }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, LocationGeofenceReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, 7102, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }
}
