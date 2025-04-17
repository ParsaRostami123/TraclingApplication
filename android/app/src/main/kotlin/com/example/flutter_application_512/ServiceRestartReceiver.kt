package com.example.flutter_application_512

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.os.Handler
import android.os.Looper
import android.content.SharedPreferences
import android.widget.Toast
import android.app.PendingIntent
import android.app.AlarmManager
import android.os.Build

class ServiceRestartReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ServiceRestartReceiver"
        const val RESTART_ALARM_DELAY = 60000L // 1 minute
        const val ACTION_RESTART_ACCESSIBILITY = "com.example.flutter_application_512.RESTART_ACCESSIBILITY_SERVICE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received intent: ${intent.action}")
        
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "Device booted, starting services")
                checkAndStartAccessibilityService(context)
                startAppLockForegroundService(context)
            }
            "com.example.flutter_application_512.RESTART_SERVICE", 
            ACTION_RESTART_ACCESSIBILITY -> {
                Log.d(TAG, "Received explicit restart request")
                checkAndStartAccessibilityService(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d(TAG, "App updated, restarting services")
                checkAndStartAccessibilityService(context)
                startAppLockForegroundService(context)
            }
            Intent.ACTION_SCREEN_ON -> {
                // Check service status when screen turns on
                verifyAccessibilityServiceRunning(context)
            }
        }
    }
    
    private fun checkAndStartAccessibilityService(context: Context) {
        // Get prefs to check if we should auto-restart
        val prefs = context.getSharedPreferences(AppLockAccessibilityService.PREFS_NAME, Context.MODE_PRIVATE)
        val shouldAutoRestart = prefs.getBoolean("auto_restart_service", true)
        
        if (!shouldAutoRestart) {
            Log.d(TAG, "Auto-restart is disabled, skipping")
            return
        }
        
        // If service is not enabled, prompt user
        if (!isAccessibilityServiceEnabled(context)) {
            Log.d(TAG, "Accessibility service is not enabled, prompting user")
            
            // Save that we need to enable the service
            prefs.edit().putBoolean("needs_accessibility_service", true).apply()
            
            // Show a toast message
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context, 
                    "برای استفاده از قفل برنامه‌ها، لطفاً دسترسی‌پذیری را فعال کنید", 
                    Toast.LENGTH_LONG
                ).show()
            }
            
            // Prompt user after a short delay (to ensure UI is ready)
            Handler(Looper.getMainLooper()).postDelayed({
                promptEnableAccessibilityService(context)
            }, 1000)
        } else {
            Log.d(TAG, "Accessibility service is already enabled")
            
            // Mark that service is enabled
            prefs.edit().putBoolean("needs_accessibility_service", false).apply()
            
            // Start the foreground service to keep things running
            startAppLockForegroundService(context)
        }
    }
    
    private fun startAppLockForegroundService(context: Context) {
        try {
            val serviceIntent = Intent(context, AppLockForegroundService::class.java).apply {
                action = AppLockForegroundService.ACTION_START_SERVICE
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            
            Log.d(TAG, "Started AppLockForegroundService")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service", e)
        }
    }
    
    private fun verifyAccessibilityServiceRunning(context: Context) {
        val prefs = context.getSharedPreferences(AppLockAccessibilityService.PREFS_NAME, Context.MODE_PRIVATE)
        val lastHeartbeat = prefs.getLong("accessibility_service_last_heartbeat", 0)
        val currentTime = System.currentTimeMillis()
        
        // If no heartbeat in 5 minutes, service is probably dead
        if (lastHeartbeat > 0 && (currentTime - lastHeartbeat) > 5 * 60 * 1000) {
            Log.d(TAG, "No recent heartbeat from accessibility service, attempting restart")
            
            // Only try restart if auto-restart is enabled
            if (prefs.getBoolean("auto_restart_service", true)) {
                scheduleServiceRestart(context)
            }
        }
    }
    
    private fun scheduleServiceRestart(context: Context) {
        try {
            val restartIntent = Intent(context, ServiceRestartReceiver::class.java).apply {
                action = ACTION_RESTART_ACCESSIBILITY
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context, 
                0, 
                restartIntent, 
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + RESTART_ALARM_DELAY,
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + RESTART_ALARM_DELAY,
                    pendingIntent
                )
            }
            
            Log.d(TAG, "Scheduled accessibility service restart in ${RESTART_ALARM_DELAY/1000} seconds")
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling service restart", e)
        }
    }
    
    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        
        val componentName = "${context.packageName}/.AppLockAccessibilityService"
        return enabledServices.contains(componentName)
    }
    
    private fun promptEnableAccessibilityService(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
            
            // Show helper toast
            Handler(Looper.getMainLooper()).postDelayed({
                Toast.makeText(
                    context, 
                    "لطفاً در صفحه تنظیمات، سرویس \"App Lock\" را فعال کنید", 
                    Toast.LENGTH_LONG
                ).show()
            }, 1000)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening accessibility settings", e)
        }
    }
} 