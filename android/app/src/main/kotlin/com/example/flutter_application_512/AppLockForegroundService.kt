package com.example.flutter_application_512

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.app.AlarmManager
import org.json.JSONObject

class AppLockForegroundService : Service() {
    
    private val TAG = "AppLockFgService"
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "app_lock_channel"
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var serviceCheckRunnable: Runnable? = null
    
    companion object {
        const val ACTION_START_SERVICE = "start_service"
        const val ACTION_STOP_SERVICE = "stop_service"
        const val CHECK_INTERVAL = 60000L // 1 minute
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AppLockForegroundService created")
        prefs = getSharedPreferences(AppLockAccessibilityService.PREFS_NAME, Context.MODE_PRIVATE)
        
        // ثبت وضعیت فعال بودن سرویس
        prefs.edit()
            .putBoolean("foreground_service_active", true)
            .putLong("foreground_service_started_at", System.currentTimeMillis())
            .apply()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVICE -> startForegroundService()
            ACTION_STOP_SERVICE -> stopService()
        }
        
        // حتی اگر سرویس کشته شود، اندروید آن را دوباره راه‌اندازی می‌کند
        return START_STICKY
    }
    
    private fun startForegroundService() {
        // Create notification channel for Android 8.0+
        createNotificationChannel()
        
        // Create intent to open main activity when notification is tapped
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Create intent to open accessibility settings
        val accessibilityIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Build the notification
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.monitoring_app_usage))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .addAction(
                android.R.drawable.ic_menu_manage,
                "تنظیمات دسترسی",
                accessibilityIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        
        // Start as a foreground service with the notification
        startForeground(NOTIFICATION_ID, notification)
        
        // شروع بررسی‌های دوره‌ای وضعیت سرویس دسترسی‌پذیری
        startServiceChecking()
        
        // Check if accessibility service is running
        if (!isAccessibilityServiceEnabled()) {
            // Prompt user to enable accessibility service
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(this)
                
                // نمایش پیام راهنما
                handler.postDelayed({
                    Toast.makeText(
                        this@AppLockForegroundService,
                        "لطفاً سرویس \"App Lock\" را فعال کنید",
                        Toast.LENGTH_LONG
                    ).show()
                }, 1000)
            }
        } else if (!AppLockAccessibilityService.isServiceRunning) {
            // سرویس دسترسی‌پذیری در تنظیمات فعال است اما در حال اجرا نیست
            // تلاش برای راه‌اندازی مجدد
            restartAccessibilityService()
        }
    }
    
    private fun startServiceChecking() {
        // توقف بررسی‌های قبلی
        stopServiceChecking()
        
        serviceCheckRunnable = Runnable {
            checkServiceStatus()
            // برنامه‌ریزی بررسی بعدی
            handler.postDelayed(serviceCheckRunnable!!, CHECK_INTERVAL)
        }
        
        // شروع اولین بررسی
        handler.post(serviceCheckRunnable!!)
        Log.d(TAG, "Started periodic service checking")
    }
    
    private fun stopServiceChecking() {
        serviceCheckRunnable?.let {
            handler.removeCallbacks(it)
            serviceCheckRunnable = null
        }
    }
    
    private fun checkServiceStatus() {
        try {
            // بررسی وضعیت سرویس دسترسی‌پذیری
            if (isAccessibilityServiceEnabled()) {
                // سرویس در تنظیمات فعال است، حالا بررسی کنیم که آیا در حال اجراست
                val lastHeartbeat = prefs.getLong("accessibility_service_last_heartbeat", 0)
                val currentTime = System.currentTimeMillis()
                val heartbeatAge = currentTime - lastHeartbeat
                
                // اگر آخرین ضربان قلب بیش از 2 دقیقه قبل بوده، احتمالاً سرویس متوقف شده
                if (heartbeatAge > 2 * 60 * 1000) {
                    Log.d(TAG, "Accessibility service heartbeat is too old (${heartbeatAge/1000}s), attempting restart")
                    restartAccessibilityService()
                } else {
                    // سرویس در حال اجراست، بررسی وضعیت قفل برنامه‌ها
                    verifyAppLocks()
                }
            } else {
                // سرویس دسترسی‌پذیری غیرفعال است، اگر قبلاً خیلی اعلان نداده‌ایم، به کاربر یادآوری کن
                val lastPrompt = prefs.getLong("last_accessibility_prompt", 0)
                val currentTime = System.currentTimeMillis()
                
                // حداکثر هر 2 ساعت یکبار یادآوری کن
                if (currentTime - lastPrompt > 2 * 60 * 60 * 1000) {
                    Log.d(TAG, "Accessibility service not enabled, prompting user")
                    promptEnableAccessibility()
                    prefs.edit().putLong("last_accessibility_prompt", currentTime).apply()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking service status", e)
        }
    }
    
    private fun isAccessibilityServiceEnabled(): Boolean {
        try {
            val accessibilityServiceName = packageName + "/.AppLockAccessibilityService"
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            
            return enabledServices.contains(accessibilityServiceName)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if accessibility service is enabled", e)
            return false
        }
    }
    
    private fun verifyAppLocks() {
        try {
            // دریافت لیست برنامه‌های قفل شده
            val timeLimitsJson = prefs.getString(AppLockAccessibilityService.TIME_LIMITS_KEY, "{}")
            val timeLimits = JSONObject(timeLimitsJson ?: "{}")
            
            // دریافت داده‌های استفاده
            val usageDataJson = prefs.getString(AppLockAccessibilityService.APP_USAGE_DATA_KEY, "{}")
            val usageData = JSONObject(usageDataJson ?: "{}")
            
            // بررسی هر برنامه‌ای که محدودیت زمانی دارد
            val iterator = timeLimits.keys()
            while (iterator.hasNext()) {
                val packageName = iterator.next()
                val limitInMinutes = timeLimits.getLong(packageName)
                
                // اگر محدودیت زمانی صفر باشد، یعنی محدودیتی نداریم
                if (limitInMinutes <= 0) continue
                
                // بررسی زمان استفاده
                val usageInMillis = if (usageData.has(packageName)) usageData.getLong(packageName) else 0
                val limitInMillis = limitInMinutes * 60 * 1000
                
                // اگر زمان استفاده از محدودیت بیشتر است، مطمئن شو که برنامه قفل است
                if (usageInMillis >= limitInMillis) {
                    // تنظیم وضعیت قفل
                    if (!prefs.getBoolean("app_locked_$packageName", false)) {
                        Log.d(TAG, "Setting lock state for $packageName (usage: ${usageInMillis/1000}s, limit: ${limitInMillis/1000}s)")
                        prefs.edit().putBoolean("app_locked_$packageName", true).apply()
                    }
                }
            }
            
            // برنامه‌ریزی بررسی بعدی
            handler.postDelayed({
                verifyAppLocks()
            }, 30000) // هر 30 ثانیه بررسی می‌کنیم
            
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying app locks", e)
        }
    }
    
    private fun promptEnableAccessibility() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            
            // نمایش پیام به کاربر
            handler.post {
                Toast.makeText(
                    this,
                    "برای قفل خودکار برنامه‌ها، لطفاً سرویس App Lock را فعال کنید",
                    Toast.LENGTH_LONG
                ).show()
            }
            
            // باز کردن صفحه تنظیمات
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error prompting for accessibility", e)
        }
    }
    
    private fun restartAccessibilityService() {
        try {
            // ارسال درخواست راه‌اندازی مجدد به ServiceRestartReceiver
            val restartIntent = Intent(ServiceRestartReceiver.ACTION_RESTART_ACCESSIBILITY)
            sendBroadcast(restartIntent)
            Log.d(TAG, "Sent restart request for accessibility service")
        } catch (e: Exception) {
            Log.e(TAG, "Error restarting accessibility service", e)
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App Lock Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "نظارت بر استفاده از برنامه‌ها"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun stopService() {
        // ثبت وضعیت غیرفعال بودن سرویس
        prefs.edit().putBoolean("foreground_service_active", false).apply()
        
        stopServiceChecking()
        stopForeground(true)
        stopSelf()
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "AppLockForegroundService destroyed")
        
        // ثبت وضعیت غیرفعال بودن سرویس
        prefs.edit().putBoolean("foreground_service_active", false).apply()
        
        // توقف بررسی‌های دوره‌ای
        stopServiceChecking()
        
        // برنامه‌ریزی راه‌اندازی مجدد سرویس با آلارم منیجر
        if (prefs.getBoolean("auto_restart_service", true)) {
            try {
                val restartIntent = Intent(this, ServiceRestartReceiver::class.java)
                restartIntent.action = "com.example.flutter_application_512.RESTART_SERVICE"
                
                val pendingIntent = PendingIntent.getBroadcast(
                    this, 
                    0, 
                    restartIntent, 
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
                
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + 5000, // 5 seconds
                        pendingIntent
                    )
                } else {
                    alarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + 5000,
                        pendingIntent
                    )
                }
                
                Log.d(TAG, "Scheduled service restart in 5 seconds")
            } catch (e: Exception) {
                Log.e(TAG, "Error scheduling service restart", e)
            }
        }
    }
} 