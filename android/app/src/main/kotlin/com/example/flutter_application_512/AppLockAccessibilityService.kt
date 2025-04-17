package com.example.flutter_application_512

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.app.AlarmManager
import android.app.PendingIntent
import android.widget.Toast

class AppLockAccessibilityService : AccessibilityService() {
    
    private val TAG = "AppLockAccessibilityService"
    private lateinit var prefs: SharedPreferences
    private var currentForegroundApp: String? = null
    private var appStartTime: Long = 0
    private val handler = Handler(Looper.getMainLooper())
    private var checkingRunnable: Runnable? = null
    private var serviceStatusReceiver: BroadcastReceiver? = null
    private var heartbeatRunnable: Runnable? = null
    private val usageTimes = HashMap<String, Long>()
    private var broadcastReceiver: BroadcastReceiver? = null
    
    companion object {
        const val PREFS_NAME = "AppLockPrefs"
        const val TIME_LIMITS_KEY = "app_time_limits"
        const val APP_USAGE_DATA_KEY = "app_usage_data"
        const val CHECK_INTERVAL = 1000L // 1 second
        const val HEARTBEAT_INTERVAL = 30000L // هر 30 ثانیه یکبار
        const val SERVICE_RESTART_ACTION = "com.example.flutter_application_512.RESTART_ACCESSIBILITY_SERVICE"
        
        // Track if service is running
        var isServiceRunning = false
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AppLockAccessibilityService created")
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isServiceRunning = true
        
        // ثبت وضعیت فعال سرویس
        prefs.edit()
            .putBoolean("accessibility_service_active", true)
            .putLong("accessibility_service_created_at", System.currentTimeMillis())
            .apply()
        
        // شروع بررسی سلامت سرویس
        startHeartbeat()
        
        // ثبت گیرنده برای درخواست‌های راه‌اندازی مجدد
        registerServiceRestartReceiver()

        // ثبت heartbeat اولیه
        sendHeartbeat()
    }
    
    private fun startHeartbeat() {
        heartbeatRunnable = Runnable {
            // ثبت heartbeat
            sendHeartbeat()
            
            // برنامه‌ریزی بررسی بعدی
            handler.postDelayed(heartbeatRunnable!!, HEARTBEAT_INTERVAL)
            
            Log.d(TAG, "Accessibility service heartbeat sent")
        }
        
        // شروع اولین بررسی
        handler.post(heartbeatRunnable!!)
    }

    private fun sendHeartbeat() {
        try {
            // ثبت آخرین زمان فعالیت سرویس
            prefs.edit()
                .putLong("accessibility_service_last_heartbeat", System.currentTimeMillis())
                .putBoolean("accessibility_service_active", true)
                .apply()
                
            // Broadcast heartbeat for any receivers that might be listening
            val heartbeatIntent = Intent("com.example.flutter_application_512.ACCESSIBILITY_HEARTBEAT")
            sendBroadcast(heartbeatIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending heartbeat", e)
        }
    }
    
    private fun registerServiceRestartReceiver() {
        try {
            serviceStatusReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == SERVICE_RESTART_ACTION) {
                        Log.d(TAG, "Received restart request for accessibility service")
                        
                        // تلاش برای راه‌اندازی مجدد اگر نیاز باشد
                        if (!isServiceRunning) {
                            Log.d(TAG, "Service not running, trying to restart")
                            
                            // اگر سرویس غیرفعال شده باشد، کاربر باید به تنظیمات دسترسی‌پذیری برود
                            val accessibilityIntent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            accessibilityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(accessibilityIntent)
                            
                            // نمایش هشدار به کاربر
                            val toastMessage = "سرویس قفل برنامه‌ها متوقف شده است. لطفاً دوباره آن را فعال کنید."
                            android.widget.Toast.makeText(context, toastMessage, android.widget.Toast.LENGTH_LONG).show()
                        } else {
                            // If service is running, send a fresh heartbeat
                            sendHeartbeat()
                            Log.d(TAG, "Service is running, sent new heartbeat")
                        }
                    }
                }
            }
            
            val filter = IntentFilter().apply {
                addAction(SERVICE_RESTART_ACTION)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            registerReceiver(serviceStatusReceiver, filter)
            Log.d(TAG, "Service restart receiver registered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering service restart receiver", e)
        }
    }
    
    private fun stopHeartbeat() {
        heartbeatRunnable?.let {
            handler.removeCallbacks(it)
            heartbeatRunnable = null
        }
        Log.d(TAG, "Heartbeat monitoring stopped")
    }
    
    override fun onUnbind(intent: Intent?): Boolean {
        // چون onUnbind وقتی اتفاق می‌افتد که سرویس قصد دارد غیرفعال شود،
        // اینجا مناسب است که تلاش کنیم بلافاصله آن را دوباره راه‌اندازی کنیم
        Log.d(TAG, "Accessibility service is being unbound")
        
        // ثبت وضعیت غیرفعال سرویس
        prefs.edit().putBoolean("accessibility_service_active", false).apply()
        
        // تلاش برای راه‌اندازی مجدد خودکار سرویس (اگر کاربر بخواهد)
        if (prefs.getBoolean("auto_restart_service", true)) {
            scheduleServiceRestart()
        }
        
        // برای اینکه سیستم سعی کند سرویس را دوباره پیوند بزند
        return true // super.onUnbind(intent)
    }

    private fun scheduleServiceRestart() {
        try {
            handler.postDelayed({
                Log.d(TAG, "Attempting to restart accessibility service")
                val restartIntent = Intent(ServiceRestartReceiver.ACTION_RESTART_ACCESSIBILITY)
                sendBroadcast(restartIntent)
            }, 1000)

            // Schedule another restart after a longer delay for extra reliability
            val restartIntent = Intent(this, ServiceRestartReceiver::class.java)
            restartIntent.action = ServiceRestartReceiver.ACTION_RESTART_ACCESSIBILITY
            
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
    
    override fun onInterrupt() {
        Log.d(TAG, "AppLockAccessibilityService interrupted")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "AppLockAccessibilityService destroyed")
        isServiceRunning = false
        
        // ثبت وضعیت غیرفعال سرویس
        prefs.edit().putBoolean("accessibility_service_active", false).apply()
        
        // توقف تمام تایمرها
        stopPeriodicChecking()
        stopHeartbeat()
        
        // لغو ثبت گیرنده
        serviceStatusReceiver?.let {
            try {
                unregisterReceiver(it)
                serviceStatusReceiver = null
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering receiver", e)
            }
        }
        
        // برنامه‌ریزی راه‌اندازی مجدد سرویس (اگر کاربر بخواهد)
        if (prefs.getBoolean("auto_restart_service", true)) {
            try {
                val restartIntent = Intent(SERVICE_RESTART_ACTION)
                val pendingIntent = PendingIntent.getBroadcast(this, 0, restartIntent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
                
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + 5000,
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
        
        // لغو ثبت براودکست ریسیور
        if (broadcastReceiver != null) {
            try {
                unregisterReceiver(broadcastReceiver)
                Log.d(TAG, "Broadcast receiver unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering broadcast receiver", e)
            }
        }
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "AppLockAccessibilityService connected")
        
        // ثبت وضعیت فعال سرویس
        isServiceRunning = true
        prefs.edit()
            .putBoolean("accessibility_service_active", true)
            .putLong("accessibility_service_connected_at", System.currentTimeMillis())
            .apply()
        
        // اطمینان از غیرفعال بودن حالت touch exploration
        try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
            if (am.isTouchExplorationEnabled) {
                Log.d(TAG, "Touch exploration is enabled, trying to disable it")
                // ما نمی‌توانیم مستقیماً غیرفعال کنیم، اما می‌توانیم به کاربر اطلاع دهیم
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        this,
                        "برای عملکرد بهتر لمس، سرویس را غیرفعال و دوباره فعال کنید",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking touch exploration mode", e)
        }
        
        // ارسال heartbeat جدید
        sendHeartbeat()
        
        // بررسی اولیه برنامه‌های در حال اجرا
        handler.postDelayed({
            // اگر برنامه‌ای در حال اجراست، آن را بررسی کنیم
            val currentApp = getCurrentForegroundApp()
            if (currentApp != null && isAppLocked(currentApp)) {
                Log.d(TAG, "Found locked app running on service connection: $currentApp")
                performGlobalAction(GLOBAL_ACTION_HOME)
                handler.postDelayed({
                    showLockScreen(currentApp)
                }, 100)
            }
        }, 1000)
        
        // ثبت براودکست ریسیور برای دریافت رویدادهای مربوط به تنظیم محدودیت زمانی
        registerBroadcastReceiver()
        
        // Start the foreground service to ensure continuing reliability
        try {
            val serviceIntent = Intent(this, AppLockForegroundService::class.java)
            serviceIntent.action = AppLockForegroundService.ACTION_START_SERVICE
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            
            Log.d(TAG, "Started AppLockForegroundService from Accessibility Service")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service from accessibility service", e)
        }
    }
    
    // دریافت برنامه فعلی در فورگراند
    private fun getCurrentForegroundApp(): String? {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
                val time = System.currentTimeMillis()
                
                // بررسی 3 ثانیه اخیر
                val usageEvents = usageStatsManager.queryEvents(time - 3000, time)
                val event = android.app.usage.UsageEvents.Event()
                
                // بررسی آخرین برنامه فعال شده
                var lastForegroundApp: String? = null
                var lastEventTime = 0L
                
                while (usageEvents.hasNextEvent()) {
                    usageEvents.getNextEvent(event)
                    if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND && 
                        event.timeStamp > lastEventTime) {
                        lastForegroundApp = event.packageName
                        lastEventTime = event.timeStamp
                    }
                }
                
                return lastForegroundApp
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current foreground app", e)
        }
        
        return null
    }
    
    private fun resetAppUsageData() {
        try {
            prefs.edit().putString(APP_USAGE_DATA_KEY, "{}").apply()
            Log.d(TAG, "Reset all app usage data on service start")
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting app usage data", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            // فقط به رویدادهای تغییر وضعیت پنجره توجه می‌کنیم
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val packageName = event.packageName?.toString()
                
                // فیلتر کردن پکیج‌های سیستمی که نباید مسدود شوند
                if (packageName != null && 
                    packageName != "com.example.flutter_application_512" && 
                    packageName != "android" && 
                    !packageName.startsWith("com.android.systemui")) {
                    
                    Log.d(TAG, "Window state changed to: $packageName")
                    
                    // **مرحله 1: بررسی فوری قفل بودن اپلیکیشن**
                    if (isAppLocked(packageName)) {
                        Log.d(TAG, "BLOCKING APP: $packageName is locked!")
                        
                        // فوراً به هوم اسکرین بازمی‌گردیم - حتی قبل از اینکه اپ فرصت نمایش پیدا کند
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        
                        // برای اطمینان چند بار این کار را انجام می‌دهیم
                        for (i in 1..2) {
                            handler.postDelayed({
                                performGlobalAction(GLOBAL_ACTION_HOME)
                            }, 50L * i)
                        }
                        
                        // نمایش صفحه قفل با کمی تأخیر
                        handler.postDelayed({
                            showLockScreen(packageName)
                        }, 150)
                        
                        // توقف هرگونه ردیابی برای این اپ
                        if (currentForegroundApp == packageName) {
                            // زمان استفاده تا این لحظه را ثبت می‌کنیم
                            updateAppUsageTime(packageName)
                            currentForegroundApp = null
                        }
                        
                        return  // پردازش بیشتر را متوقف می‌کنیم
                    }
                    
                    // **مرحله 2: ردیابی استفاده از اپلیکیشن‌ها**
                    // فقط اگر اپلیکیشن قفل نیست، آن را ردیابی می‌کنیم
                    
                    // اگر اپلیکیشن عوض شده است
                    if (packageName != currentForegroundApp) {
                        // زمان استفاده از اپلیکیشن قبلی را به‌روزرسانی می‌کنیم
                        currentForegroundApp?.let { 
                            Log.d(TAG, "Updating usage time for previous app: $it")
                            updateAppUsageTime(it)
                        }
                        
                        // شروع ردیابی اپلیکیشن جدید
                        currentForegroundApp = packageName
                        appStartTime = System.currentTimeMillis()
                        
                        Log.d(TAG, "Started tracking usage for app: $packageName")
                        
                        // فوری بررسی می‌کنیم که آیا این اپلیکیشن محدودیت زمانی دارد
                        checkAppTimeLimit(packageName)
                        
                        // شروع بررسی دوره‌ای
                        startPeriodicChecking(packageName)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onAccessibilityEvent", e)
        }
    }

    private fun startPeriodicChecking(packageName: String) {
        // توقف بررسی‌های قبلی
        stopPeriodicChecking()
        
        // شروع بررسی جدید
        checkingRunnable = Runnable {
            if (currentForegroundApp == packageName) {
                // بررسی مجدد محدودیت زمانی
                checkAppTimeLimit(packageName)
                
                // همچنین با استفاده از روش دیگر بررسی می‌کنیم که برنامه همچنان در فورگراند است
                verifyForegroundApp(packageName)
                
                // ادامه بررسی دوره‌ای
                handler.postDelayed(checkingRunnable!!, CHECK_INTERVAL)
            }
        }
        
        // شروع اولین بررسی
        handler.postDelayed(checkingRunnable!!, CHECK_INTERVAL)
    }
    
    private fun verifyForegroundApp(expectedPackage: String) {
        try {
            // با استفاده از UsageStatsManager بررسی می‌کنیم که آیا برنامه مورد انتظار واقعاً در فورگراند است
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
                val time = System.currentTimeMillis()
                
                // بررسی 5 ثانیه اخیر
                val usageEvents = usageStatsManager.queryEvents(time - 5000, time)
                val event = android.app.usage.UsageEvents.Event()
                
                // بررسی آخرین برنامه فعال شده
                var lastForegroundApp: String? = null
                var lastEventTime = 0L
                
                while (usageEvents.hasNextEvent()) {
                    usageEvents.getNextEvent(event)
                    if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND && 
                        event.timeStamp > lastEventTime) {
                        lastForegroundApp = event.packageName
                        lastEventTime = event.timeStamp
                    }
                }
                
                // اگر برنامه فورگراند با انتظار ما متفاوت است، به‌روزرسانی می‌کنیم
                if (lastForegroundApp != null && lastForegroundApp != expectedPackage) {
                    Log.d(TAG, "Detected app switch: $expectedPackage -> $lastForegroundApp")
                    
                    // بررسی می‌کنیم که آیا برنامه جدید قفل شده است
                    if (isAppLocked(lastForegroundApp)) {
                        Log.d(TAG, "BLOCKING APP: $lastForegroundApp is locked!")
                        
                        // بستن فوری برنامه
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        
                        // نمایش صفحه قفل
                        handler.postDelayed({
                            showLockScreen(lastForegroundApp)
                        }, 100)
                        
                        return
                    }
                    
                    // به‌روزرسانی ردیابی اپلیکیشن
                    updateAppUsageTime(expectedPackage)
                    currentForegroundApp = lastForegroundApp
                    appStartTime = System.currentTimeMillis()
                    
                    // بررسی محدودیت زمانی برای اپلیکیشن جدید
                    checkAppTimeLimit(lastForegroundApp)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying foreground app", e)
        }
    }
    
    private fun stopPeriodicChecking() {
        checkingRunnable?.let {
            handler.removeCallbacks(it)
            checkingRunnable = null
        }
    }
    
    private fun checkAppTimeLimit(packageName: String) {
        try {
            // دریافت محدودیت‌های زمانی از تنظیمات
            val timeLimitsJson = prefs.getString(TIME_LIMITS_KEY, "{}")
            val timeLimits = JSONObject(timeLimitsJson ?: "{}")
            
            // دریافت داده‌های استفاده از تنظیمات
            val usageDataJson = prefs.getString(APP_USAGE_DATA_KEY, "{}")
            val usageData = JSONObject(usageDataJson ?: "{}")
            
            // بررسی می‌کنیم که آیا این اپلیکیشن محدودیت زمانی دارد
            if (timeLimits.has(packageName)) {
                val limitInMinutes = timeLimits.getLong(packageName)
                val limitInMillis = limitInMinutes * 60 * 1000
                
                // اگر محدودیت صفر باشد، برنامه نباید قفل شود
                if (limitInMinutes <= 0) {
                    // اگر قبلاً قفل بوده، قفل را برداریم
                    if (prefs.getBoolean("app_locked_$packageName", false)) {
                        setAppLocked(packageName, false)
                    }
                    return
                }
                
                // دریافت زمان استفاده کلی برای این اپلیکیشن
                val currentUsageMillis = if (usageData.has(packageName)) 
                                            usageData.getLong(packageName) 
                                         else 
                                            0L
                                            
                // اضافه کردن زمان سشن فعلی
                val currentSessionTime = System.currentTimeMillis() - appStartTime
                val totalUsageTime = currentUsageMillis + currentSessionTime
                
                Log.d(TAG, "App $packageName time check: used=${totalUsageTime/1000}s, limit=${limitInMillis/1000}s")
                
                // بررسی می‌کنیم که آیا از محدودیت فراتر رفته
                if (totalUsageTime >= limitInMillis) {
                    Log.d(TAG, "TIME LIMIT EXCEEDED for $packageName: ${totalUsageTime/1000}s >= ${limitInMillis/1000}s")
                    
                    // ذخیره زمان استفاده قبل از قفل کردن
                    updateAppUsageTime(packageName)
                    
                    // تنظیم فلگ قفل برای جلوگیری از باز شدن مجدد
                    setAppLocked(packageName, true)
                    
                    // بستن اپلیکیشن
                    returnToHome()
                    
                    // نمایش صفحه قفل
                    showLockScreen(packageName)
                    
                    // به‌روزرسانی وضعیت ردیابی
                    currentForegroundApp = null
                    
                    // توقف بررسی دوره‌ای
                    stopPeriodicChecking()
                }
            } else {
                // اگر محدودیت زمانی ندارد ولی قبلاً قفل بوده، قفل را برداریم
                if (prefs.getBoolean("app_locked_$packageName", false)) {
                    setAppLocked(packageName, false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking app time limit", e)
        }
    }
    
    private fun updateAppUsageTime(packageName: String) {
        try {
            val currentTime = System.currentTimeMillis()
            val sessionDuration = currentTime - appStartTime
            
            // Only update if the session is meaningful (more than 1 second)
            if (sessionDuration > 1000) {
                // Get current usage data
                val usageDataJson = prefs.getString(APP_USAGE_DATA_KEY, "{}")
                val usageData = JSONObject(usageDataJson ?: "{}")
                
                // Update usage time for this app
                val currentUsage = if (usageData.has(packageName)) usageData.getLong(packageName) else 0L
                val newUsage = currentUsage + sessionDuration
                usageData.put(packageName, newUsage)
                
                // Save updated usage data
                prefs.edit().putString(APP_USAGE_DATA_KEY, usageData.toString()).apply()
                
                // Reset start time
                appStartTime = currentTime
                
                Log.d(TAG, "Updated usage time for $packageName: $newUsage ms")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating app usage time", e)
        }
    }
    
    private fun showLockScreen(packageName: String) {
        try {
            if (AppLockOverlayActivity.isLockScreenShowing) {
                Log.d(TAG, "Lock screen is already showing, not showing again for $packageName")
                return
            }
            
            // اطلاعات برنامه را دریافت می‌کنیم
            val appName = try {
                val appInfo = packageManager.getApplicationInfo(packageName, 0)
                packageManager.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                "برنامه"
            }
            
            // استخراج زمان استفاده و محدودیت زمانی از SharedPreferences
            val usageMinutes = getAppUsageTime(packageName) / (60 * 1000)
            val timeLimitMinutes = getAppTimeLimit(packageName)
            
            // نمایش فعالیت قفل با اطلاعات کامل
            val lockIntent = Intent(this, AppLockOverlayActivity::class.java).apply {
                putExtra(AppLockOverlayActivity.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AppLockOverlayActivity.EXTRA_APP_NAME, appName)
                putExtra(AppLockOverlayActivity.EXTRA_TIME_USED, usageMinutes)
                putExtra(AppLockOverlayActivity.EXTRA_TIME_LIMIT, timeLimitMinutes)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or 
                         Intent.FLAG_ACTIVITY_CLEAR_TOP or 
                         Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            
            startActivity(lockIntent)
            AppLockOverlayActivity.isLockScreenShowing = true
            
            // فعال‌سازی مجدد بررسی‌کننده
            handler.postDelayed({
                AppLockOverlayActivity.isLockScreenShowing = false
            }, 2000) // زمان کافی برای نمایش صفحه قفل
            
            Log.d(TAG, "Lock screen shown for $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing lock screen for $packageName", e)
        }
    }
    
    // دریافت زمان استفاده از برنامه بر حسب میلی‌ثانیه
    private fun getAppUsageTime(packageName: String): Long {
        try {
            val usageDataJson = prefs.getString(APP_USAGE_DATA_KEY, "{}")
            val usageData = JSONObject(usageDataJson ?: "{}")
            
            return if (usageData.has(packageName)) {
                usageData.getLong(packageName)
            } else {
                0L
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting app usage time", e)
            return 0L
        }
    }
    
    // دریافت محدودیت زمانی برنامه بر حسب دقیقه
    private fun getAppTimeLimit(packageName: String): Long {
        try {
            val timeLimitsJson = prefs.getString(TIME_LIMITS_KEY, "{}")
            val timeLimits = JSONObject(timeLimitsJson ?: "{}")
            
            return if (timeLimits.has(packageName)) {
                timeLimits.getLong(packageName)
            } else {
                0L
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting app time limit", e)
            return 0L
        }
    }
    
    // متد جدید برای تنظیم وضعیت قفل برنامه‌ها
    private fun setAppLocked(packageName: String, locked: Boolean) {
        try {
            // بررسی وضعیت فعلی
            val currentlyLocked = prefs.getBoolean("app_locked_$packageName", false)
            
            // اگر وضعیت تغییر کرده، آن را ذخیره کن
            if (currentlyLocked != locked) {
                prefs.edit().putBoolean("app_locked_$packageName", locked).apply()
                
                if (locked) {
                    Log.d(TAG, "App $packageName is now locked")
                    
                    // فرستادن یک رویداد برودکست برای اطلاع‌رسانی به کامپوننت‌های دیگر
                    val intent = Intent("com.example.flutter_application_512.APP_LOCKED")
                    intent.putExtra("packageName", packageName)
                    sendBroadcast(intent)
                } else {
                    Log.d(TAG, "App $packageName is now unlocked")
                    
                    // فرستادن یک رویداد برودکست برای اطلاع‌رسانی به کامپوننت‌های دیگر
                    val intent = Intent("com.example.flutter_application_512.APP_UNLOCKED")
                    intent.putExtra("packageName", packageName)
                    sendBroadcast(intent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting app lock status", e)
        }
    }
    
    private fun returnToHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN)
        homeIntent.addCategory(Intent.CATEGORY_HOME)
        homeIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(homeIntent)
    }

    // بهبود تشخیص قفل بودن اپلیکیشن
    private fun isAppLocked(packageName: String): Boolean {
        try {
            // ابتدا بررسی می‌کنیم آیا این اپلیکیشن در لیست قفل شده‌ها قرار دارد
            val isLocked = prefs.getBoolean("app_locked_$packageName", false)
            
            if (isLocked) {
                // همچنین بررسی می‌کنیم که آیا محدودیت زمانی هنوز وجود دارد
                val timeLimitsJson = prefs.getString(TIME_LIMITS_KEY, "{}")
                val timeLimits = JSONObject(timeLimitsJson ?: "{}")
                
                if (timeLimits.has(packageName)) {
                    // محدودیت زمانی وجود دارد، بررسی می‌کنیم که آیا از محدودیت عبور کرده‌ایم
                    val limitMinutes = timeLimits.getLong(packageName)
                    
                    // اگر محدودیت صفر باشد، قفل را برمی‌داریم
                    if (limitMinutes <= 0) {
                        prefs.edit().putBoolean("app_locked_$packageName", false).apply()
                        Log.d(TAG, "App $packageName is no longer locked - time limit removed")
                        return false
                    }
                    
                    // دریافت زمان استفاده از برنامه
                    val usageDataJson = prefs.getString(APP_USAGE_DATA_KEY, "{}")
                    val usageData = JSONObject(usageDataJson ?: "{}")
                    val usageMillis = if (usageData.has(packageName)) usageData.getLong(packageName) else 0L
                    val limitMillis = limitMinutes * 60 * 1000
                    
                    // اگر زمان استفاده از محدودیت کمتر است (شاید بعد از ریست شدن)، قفل را برمی‌داریم
                    if (usageMillis < limitMillis) {
                        prefs.edit().putBoolean("app_locked_$packageName", false).apply()
                        Log.d(TAG, "App $packageName is no longer locked - usage (${usageMillis/1000}s) is less than limit (${limitMillis/1000}s)")
                        return false
                    }
                    
                    // محدودیت زمانی وجود دارد و از آن عبور کرده‌ایم
                    Log.d(TAG, "App $packageName is confirmed locked - has time limit and exceeded")
                    return true
                } else {
                    // محدودیت زمانی برداشته شده، قفل را هم برمی‌داریم
                    prefs.edit().putBoolean("app_locked_$packageName", false).apply()
                    Log.d(TAG, "App $packageName is no longer locked - time limit removed")
                    return false
                }
            } else {
                // برنامه قفل نیست، اما ممکن است نیاز به بررسی محدودیت زمانی باشد
                // دریافت محدودیت زمانی
                val timeLimitsJson = prefs.getString(TIME_LIMITS_KEY, "{}")
                val timeLimits = JSONObject(timeLimitsJson ?: "{}")
                
                if (timeLimits.has(packageName)) {
                    val limitMinutes = timeLimits.getLong(packageName)
                    
                    // اگر محدودیت صفر باشد، برنامه قفل نیست
                    if (limitMinutes <= 0) return false
                    
                    // دریافت زمان استفاده از برنامه
                    val usageDataJson = prefs.getString(APP_USAGE_DATA_KEY, "{}")
                    val usageData = JSONObject(usageDataJson ?: "{}")
                    val usageMillis = if (usageData.has(packageName)) usageData.getLong(packageName) else 0L
                    val limitMillis = limitMinutes * 60 * 1000
                    
                    // بررسی محدودیت زمانی
                    if (usageMillis >= limitMillis) {
                        // از محدودیت عبور کرده‌ایم، برنامه باید قفل شود
                        prefs.edit().putBoolean("app_locked_$packageName", true).apply()
                        Log.d(TAG, "App $packageName is now locked - usage (${usageMillis/1000}s) exceeds limit (${limitMillis/1000}s)")
                        return true
                    }
                }
            }
            
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if app is locked", e)
            return false
        }
    }

    // ثبت براودکست ریسیور برای دریافت رویدادهای مربوط به تنظیم محدودیت زمانی
    private fun registerBroadcastReceiver() {
        try {
            broadcastReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        "com.example.flutter_application_512.TIME_LIMIT_SET" -> {
                            val packageName = intent.getStringExtra("packageName")
                            val limitMinutes = intent.getLongExtra("limitMinutes", 0)
                            
                            if (packageName != null) {
                                Log.d(TAG, "Broadcast received: Time limit set for $packageName: $limitMinutes minutes")
                                
                                // اگر این برنامه در حال حاضر در فورگراند است و محدودیت آن به اتمام رسیده، آن را قفل کنیم
                                if (packageName == currentForegroundApp) {
                                    checkAppTimeLimit(packageName)
                                }
                            }
                        }
                        "com.example.flutter_application_512.APP_LOCKED",
                        "com.example.flutter_application_512.APP_UNLOCKED" -> {
                            val packageName = intent.getStringExtra("packageName")
                            if (packageName != null) {
                                Log.d(TAG, "Broadcast received: ${intent.action} for $packageName")
                                
                                // اگر این برنامه در حال حاضر در فورگراند است، وضعیت آن را بررسی کنیم
                                if (packageName == currentForegroundApp) {
                                    checkAppTimeLimit(packageName)
                                }
                            }
                        }
                    }
                }
            }
            
            val intentFilter = IntentFilter().apply {
                addAction("com.example.flutter_application_512.TIME_LIMIT_SET")
                addAction("com.example.flutter_application_512.APP_LOCKED")
                addAction("com.example.flutter_application_512.APP_UNLOCKED")
            }
            
            registerReceiver(broadcastReceiver, intentFilter)
            Log.d(TAG, "Broadcast receiver registered for time limit events")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering broadcast receiver", e)
        }
    }
} 