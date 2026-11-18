package com.bigsammy.sammessagescheduler.scheduling

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.bigsammy.sammessagescheduler.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Rebuilds pending WorkManager jobs and location geofences after device boot
 * or when Android replaces the installed application package.
 */
class ScheduleRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val messages = AppDatabase.get(appContext).messageDao().getAllSnapshot()
                messages.filter { it.status == "SCHEDULED" }.forEach { message ->
                    when (message.triggerType) {
                        "TIME" -> MessageScheduler.schedule(appContext, message.id, message.scheduledAt)
                        "LOCATION" -> {
                            val fine = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            val coarse = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            val background = Build.VERSION.SDK_INT < 29 ||
                                ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
                            if ((fine || coarse) && background && message.latitude != null && message.longitude != null) {
                                runCatching {
                                    LocationGeofenceManager.register(
                                        appContext, message.id, message.latitude, message.longitude, message.radiusMeters
                                    ).addOnFailureListener { error ->
                                        Log.e("SAMRestore", "Could not restore arrival reminder ${message.id}", error)
                                    }
                                }.onFailure { error ->
                                    Log.e("SAMRestore", "Could not restore arrival reminder ${message.id}", error)
                                }
                            } else {
                                Log.w("SAMRestore", "Skipped arrival reminder ${message.id}: required location permissions are not granted.")
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                Log.e("SAMRestore", "Could not restore pending schedules after system event.", error)
            } finally {
                pending.finish()
            }
        }
    }
}
