package com.example.flutter_application_512

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    private val TAG = "BootReceiver"
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Device boot completed, starting services")
            
            // Check if we should auto-start services on boot
            val prefs = context.getSharedPreferences(AppLockAccessibilityService.PREFS_NAME, Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("auto_start_on_boot", true)
            
            if (autoStart) {
                // Start the foreground service which will handle checking accessibility status
                try {
                    val serviceIntent = Intent(context, AppLockForegroundService::class.java).apply {
                        action = AppLockForegroundService.ACTION_START_SERVICE
                    }
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    
                    Log.d(TAG, "Started foreground service on boot")
                    
                    // Also send a broadcast to ServiceRestartReceiver to check accessibility service
                    val restartIntent = Intent(ServiceRestartReceiver.ACTION_RESTART_ACCESSIBILITY)
                    context.sendBroadcast(restartIntent)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting services on boot", e)
                }
            } else {
                Log.d(TAG, "Auto-start is disabled, not starting services on boot")
            }
        }
    }
} 