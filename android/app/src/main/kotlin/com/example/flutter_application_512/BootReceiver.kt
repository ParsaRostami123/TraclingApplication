package com.example.flutter_application_512

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import android.os.Handler
import android.os.Looper

class BootReceiver : BroadcastReceiver() {

    private val TAG = "BootReceiver"
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Device boot completed, starting services")
            
            // Check if we should auto-start services on boot
            val prefs = context.getSharedPreferences(AppLockAccessibilityService.PREFS_NAME, Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("auto_start_on_boot", true)
            
            if (autoStart) {
                // زمان تاخیر برای اطمینان از آماده بودن سیستم
                Handler(Looper.getMainLooper()).postDelayed({
                    startServices(context)
                }, 10000) // تاخیر 10 ثانیه برای اطمینان از آماده بودن سیستم
            } else {
                Log.d(TAG, "Auto-start is disabled, not starting services on boot")
            }
        }
    }
    
    private fun startServices(context: Context) {
        // Start the foreground service which will handle checking accessibility status
        try {
            // 1. راه‌اندازی سرویس Foreground
            val serviceIntent = Intent(context, AppLockForegroundService::class.java).apply {
                action = AppLockForegroundService.ACTION_START_SERVICE
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            
            Log.d(TAG, "Started foreground service on boot")
            
            // 2. ارسال broadcast برای بررسی سرویس Accessibility
            val restartIntent = Intent(ServiceRestartReceiver.ACTION_RESTART_ACCESSIBILITY)
            context.sendBroadcast(restartIntent)
            
            // 3. تنظیم آلارم برای بررسی دوره‌ای سرویس‌ها
            setupPeriodicServiceCheck(context)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting services on boot", e)
        }
    }
    
    private fun setupPeriodicServiceCheck(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val checkIntent = Intent(context, ServiceRestartReceiver::class.java).apply {
                action = ServiceRestartReceiver.ACTION_CHECK_SERVICES
            }
            
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                0,
                checkIntent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0
            )
            
            // تنظیم آلارم برای اجرا هر 15 دقیقه
            val interval = 15 * 60 * 1000L // 15 دقیقه
            val startTime = System.currentTimeMillis() + interval
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    startTime,
                    pendingIntent
                )
            } else {
                alarmManager.setRepeating(
                    android.app.AlarmManager.RTC_WAKEUP,
                    startTime,
                    interval,
                    pendingIntent
                )
            }
            
            Log.d(TAG, "Set up periodic service check every 15 minutes")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up periodic service check", e)
        }
    }
} 