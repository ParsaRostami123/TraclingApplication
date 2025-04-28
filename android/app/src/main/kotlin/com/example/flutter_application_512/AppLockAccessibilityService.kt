package com.example.flutter_application_512

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.Calendar
import com.google.gson.Gson

class AppLockAccessibilityService : AccessibilityService() {
    
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null
    private var lastForegroundPackage: String = ""
    private var lastAppSwitchTime: Long = 0
    private var lastEventTime: Long = 0
    private var lastForegroundTime: Long = 0
    private val timeBeforeConsideringNewApp = 1500L // 1.5 seconds threshold for genuine app switch
    private var isCheckingInProgress = false
    private var currentForegroundTime: Long = 0
    private var usageManager: UsageStatsManager? = null
    private val currentDayUsage = ConcurrentHashMap<String, Long>() // Thread-safe map
    private var appLockReceiver: BroadcastReceiver? = null
    private val TAG = "AppLockService"
    
    // لیست اپ‌های اجتماعی با اولویت بالا - باید به طور ویژه کنترل شوند
    private val prioritySocialApps = setOf(
        "com.instagram.android",
        "com.facebook.katana",
        "com.twitter.android",
        "com.snapchat.android",
        "com.zhiliaoapp.musically",
        "com.whatsapp"
    )
    
    companion object {
        const val PREFS_NAME = "AppLockPrefs"
        const val TRACKING_APPS_KEY = "tracking_apps"
        const val TIME_LIMITS_KEY = "time_limits"
        const val APP_USAGE_DATA_KEY = "app_usage_data"
        const val LOCKED_APPS_KEY = "locked_apps"
        const val SERVICE_RESTART_ACTION = "com.example.flutter_application_512.RESTART_SERVICE"
        const val LAST_DAILY_RESET_KEY = "last_daily_reset"
        
        // کاهش فاصله زمانی بررسی برای واکنش سریع‌تر
        private const val CHECK_INTERVAL = 100L // بررسی هر 100 میلی‌ثانیه برای واکنش سریع‌تر
        
        // Static flag to track service running state
        var isServiceRunning = false
        
        // DEBUG flag - enabled for troubleshooting
        private const val DEBUG = true
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "🚀 Service connected - Enhanced Lock Mode Activated")
        isServiceRunning = true
        
        // Initialize preferences
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Record service start time
        prefs.edit().putLong("service_start_time", System.currentTimeMillis()).apply()
        
        // Initialize usage stats manager
        usageManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        } else {
            null
        }
        
        // Load current usage data
        loadUsageData()
        
        // Configure service
        configureService()
        
        // Register broadcast receiver for app lock/unlock events
        registerAppLockReceiver()
        
        // Start periodic checks
        startPeriodicChecks()
        
        // Report a heartbeat immediately to confirm service is running
        updateHeartbeat()
        
        // Create a list of all tracked apps with time limits for debugging
        logTrackedAppsWithLimits()
        
        // Show toast only in debug mode
        if (DEBUG) {
            Toast.makeText(this, "سرویس قفل برنامه با حالت پیشرفته فعال شد", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun configureService() {
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or 
                        AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100 // Make notifications faster
        }
        serviceInfo = info
    }
    
    private fun registerAppLockReceiver() {
        if (appLockReceiver != null) {
            try {
                unregisterReceiver(appLockReceiver)
            } catch (e: Exception) {
                // Already unregistered
            }
        }
        
        appLockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    "com.example.flutter_application_512.APP_LOCKED" -> {
                        val packageName = intent.getStringExtra("packageName")
                        Log.d(TAG, "Received lock notification for: $packageName")
                        
                        // گرفتن بسته‌ی هدف مشخص (اگر وجود داشته باشد)
                        val targetPackage = intent.getStringExtra("targetPackage") ?: packageName
                        
                        // اگر بسته هدف داده شد، آن را قفل کن (مانند اینستاگرام)
                        if (targetPackage != null && targetPackage != this@AppLockAccessibilityService.packageName) {
                            Log.d(TAG, "قفل کردن برنامه هدف: $targetPackage")
                            
                            // اضافه کردن به لیست برنامه‌های قفل شده
                            val lockedAppsJson = prefs.getString(LOCKED_APPS_KEY, "[]")
                            val lockedApps = JSONArray(lockedAppsJson ?: "[]")
                            
                            // بررسی تکرار
                            var alreadyLocked = false
                            for (i in 0 until lockedApps.length()) {
                                if (lockedApps.getString(i) == targetPackage) {
                                    alreadyLocked = true
                                    break
                                }
                            }
                            
                            if (!alreadyLocked) {
                                lockedApps.put(targetPackage)
                                prefs.edit().putString(LOCKED_APPS_KEY, lockedApps.toString()).commit()
                                Log.d(TAG, "🔒 برنامه هدف به لیست قفل‌ها اضافه شد: $targetPackage")
                            }
                            
                            // اجبار به بستن برنامه و بازگشت به صفحه اصلی
                            if (lastForegroundPackage == targetPackage || getCurrentForegroundPackage() == targetPackage) {
                                Log.d(TAG, "برنامه هدف در حال اجراست، در حال بستن اجباری: $targetPackage")
                                performGoHomeAction()
                                forceCloseApp(targetPackage)
                                
                                // نمایش صفحه قفل
                                handler.postDelayed({
                                    showLockScreen(targetPackage)
                                }, 300)
                            } else {
                                // حتی اگر در حال اجرا نیست، نمایش صفحه قفل برای اطمینان
                                showLockScreen(targetPackage)
                            }
                        }
                        
                        // If this app is currently in foreground, go home
                        if (packageName == lastForegroundPackage) {
                            performGoHomeAction()
                        }
                    }
                    "com.example.flutter_application_512.APP_UNLOCKED" -> {
                        val packageName = intent.getStringExtra("packageName")
                        Log.d(TAG, "Received unlock notification for: $packageName")
                    }
                    "com.example.flutter_application_512.FORCE_CLOSE_APP" -> {
                        val packageToClose = intent.getStringExtra("packageName")
                        if (packageToClose != null) {
                            Log.d(TAG, "Received request to force close app: $packageToClose")
                            
                            // اقدامات متعدد برای بستن برنامه هدف
                            performGoHomeAction()
                            forceCloseApp(packageToClose)
                        }
                    }
                    "com.example.flutter_application_512.CHECK_LOCKED_APP" -> {
                        val packageToCheck = intent.getStringExtra("packageName")
                        if (packageToCheck != null && isAppLocked(packageToCheck)) {
                            Log.d(TAG, "Checking locked app: $packageToCheck")
                            
                            // بررسی اینکه آیا اپ در حال اجراست
                            val currentApp = getCurrentForegroundPackage()
                            if (currentApp == packageToCheck) {
                                Log.d(TAG, "🚨 Locked app detected in foreground, enforcing closure: $packageToCheck")
                                performGoHomeAction()
                                forceCloseApp(packageToCheck)
                            }
                        }
                    }
                    SERVICE_RESTART_ACTION -> {
                        Log.d(TAG, "Received service restart request")
                        
                        // Check if we need to enforce locked apps
                        if (intent.getBooleanExtra("enforceLockedApps", false)) {
                            Log.d(TAG, "Received request to enforce all locked apps")
                            enforceAllLockedApps()
                        }
                        
                        // No need to restart here since we're already running
                        // Just update the heartbeat to confirm we're active
                        updateHeartbeat()
                    }
                }
            }
        }
        
        val filter = IntentFilter().apply {
            addAction("com.example.flutter_application_512.APP_LOCKED")
            addAction("com.example.flutter_application_512.APP_UNLOCKED")
            addAction("com.example.flutter_application_512.FORCE_CLOSE_APP")
            addAction("com.example.flutter_application_512.CHECK_LOCKED_APP")
            addAction(SERVICE_RESTART_ACTION)
        }
        registerReceiver(appLockReceiver, filter)
    }
    
    private fun loadUsageData() {
        try {
            val usageDataJson = prefs.getString(APP_USAGE_DATA_KEY, "{}")
            val usageData = JSONObject(usageDataJson ?: "{}")
            
            // Clear our current tracking map and repopulate it
            currentDayUsage.clear()
            
            // If we have existing usage data, load it
            usageData.keys().forEach { key ->
                if (key != "JSONObject" && !key.startsWith("org.json")) { // Avoid strange JSON parsing quirks
                    try {
                        val timeUsed = usageData.getLong(key)
                        currentDayUsage[key] = timeUsed
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing usage time for $key", e)
                    }
                }
            }
            
            Log.d(TAG, "Loaded usage data for ${currentDayUsage.size} apps")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading usage data", e)
        }
    }
    
    private fun saveUsageData() {
        try {
            val usageData = JSONObject()
            currentDayUsage.forEach { (packageName, timeUsed) ->
                usageData.put(packageName, timeUsed)
            }
            
            prefs.edit().putString(APP_USAGE_DATA_KEY, usageData.toString()).apply()
            Log.d(TAG, "Saved usage data")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving usage data", e)
        }
    }
    
    private fun startPeriodicChecks() {
        if (runnable == null) {
            runnable = Runnable {
                checkForegroundApp()
                
                // بررسی مکرر برنامه‌های اجتماعی با اهمیت ویژه در هر بار اجرای چک
                checkSocialAppsStrictly()
                
                // Additionally, verify against locked apps list more frequently
                verifyNoLockedAppsRunning()
                
                handler.postDelayed(runnable!!, CHECK_INTERVAL)
            }
            handler.post(runnable!!)
            Log.d(TAG, "Started periodic checks with interval: $CHECK_INTERVAL ms")
        }
    }
    
    private fun stopPeriodicChecks() {
        runnable?.let { handler.removeCallbacks(it) }
        runnable = null
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            try {
                val packageName = event.packageName?.toString() ?: return
                if (packageName == "com.example.flutter_application_512") return  // اگر برنامه خودمان باشد، چیزی انجام نده
                
                val currentTime = System.currentTimeMillis()
                Log.d(TAG, "🔍 برنامه فعال: $packageName")
                
                // بررسی و اعمال قفل‌ها
                val shouldForceLock = checkAndLockApp(packageName, currentTime)
                
                // ثبت زمان استفاده فعلی
                updateCurrentUsageTime(packageName, currentTime)
                
                // بررسی برنامه‌های اجتماعی با اولویت بالا برای اعمال محدودیت زمانی
                if (!shouldForceLock && prioritySocialApps.contains(packageName)) {
                    checkSocialAppTimeLimit(packageName, currentTime)
                }
            } catch (e: Exception) {
                Log.e(TAG, "خطا در onAccessibilityEvent: ${e.message}")
            }
        }
    }
    
    // بررسی محدودیت زمانی برنامه‌های اجتماعی
    private fun checkSocialAppTimeLimit(packageName: String, currentTime: Long): Boolean {
        try {
            val appTimeLimitsJson = prefs.getString(TIME_LIMITS_KEY, "{}")
            val appTimeLimits = JSONObject(appTimeLimitsJson)
            
            if (appTimeLimits.has(packageName)) {
                val limitMinutes = appTimeLimits.getInt(packageName)
                
                // بررسی زمان استفاده امروز
                val appUsageToday = getDailyAppUsage(packageName)
                Log.d(TAG, "⏱️ زمان استفاده $packageName امروز: ${appUsageToday/60000} دقیقه، محدودیت: $limitMinutes دقیقه")
                
                // اگر از محدودیت تجاوز کرده باشد، قفل را اعمال کن
                if (appUsageToday >= limitMinutes * 60 * 1000) {
                    Log.d(TAG, "⛔ زمان استفاده به پایان رسیده: $packageName")
                    forceAppLock(packageName)
                    return true
                }
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "خطا در بررسی محدودیت زمانی: ${e.message}")
            return false
        }
    }
    
    private fun handleAppSwitch(newPackage: String) {
        val now = System.currentTimeMillis()
        
        // Don't process app switches that happen too quickly (likely false positives)
        if (now - lastAppSwitchTime < timeBeforeConsideringNewApp) {
            Log.d(TAG, "Ignoring rapid app switch to $newPackage")
            return
        }
        
        // Record app switch time
        lastAppSwitchTime = now
        
        // Check if the former app should be tracked
        if (isAppTracked(lastForegroundPackage) && lastForegroundPackage.isNotEmpty()) {
            val usedTime = currentDayUsage.getOrDefault(lastForegroundPackage, 0L) + currentForegroundTime
            currentDayUsage[lastForegroundPackage] = usedTime
            Log.d(TAG, "Updated usage for $lastForegroundPackage: $usedTime ms")
            
            // Save after each significant app switch
            saveUsageData()
        }
        
        // Update foreground app
        lastForegroundPackage = newPackage
        currentForegroundTime = 0
        
        // Immediately check if the new app should be locked
        checkAndLockApp(newPackage, System.currentTimeMillis())
        
        // If app is locked, show lock screen
        if (isAppLocked(newPackage)) {
            Log.d(TAG, "User attempted to open locked app: $newPackage")
            showLockScreen(newPackage)
        }
        
        // Update heartbeat to show service is active
        updateHeartbeat()
    }
    
    private fun checkForegroundApp() {
        // Prevent concurrent execution
        if (isCheckingInProgress) return
        isCheckingInProgress = true
        
        try {
            // محدودیت زمانی اپ‌های اجتماعی را مرتباً بررسی کن
            checkSocialAppsStrictly()
            
            // First verify no locked apps are running
            val currentApp = getCurrentForegroundPackage()
            if (currentApp != null && isAppLocked(currentApp)) {
                Log.d(TAG, "🔒 Locked app detected during regular check: $currentApp")
                performGoHomeAction()
                forceCloseApp(currentApp)
                showLockScreen(currentApp)
                isCheckingInProgress = false
                return
            }
            
            // Verify we have a recent event (within last 500ms) to ensure service is working
            val now = System.currentTimeMillis()
            val timeSinceLastEvent = now - lastEventTime
            
            // If app is actively being used, update time
            if (lastForegroundPackage.isNotEmpty() && isAppTracked(lastForegroundPackage)) {
                // Only count time if the service is actively detecting events and screen is on
                if (timeSinceLastEvent < 1000 && isScreenOn()) {
                    currentForegroundTime += CHECK_INTERVAL
                    
                    // Get current usage including current session
                    val totalUsageTime = currentDayUsage.getOrDefault(lastForegroundPackage, 0L) + currentForegroundTime

                    // Log usage time more frequently for apps close to their limits
                    if (totalUsageTime % 3000 < CHECK_INTERVAL) {
                        Log.d(TAG, "⏱️ App usage: $lastForegroundPackage - ${totalUsageTime/1000}s (session: ${currentForegroundTime/1000}s)")
                    }
                    
                    // Check for time limit exceeded
                    checkTimeLimitExceeded(lastForegroundPackage, totalUsageTime)
                } else {
                    // If we haven't detected events in a while, try to update current foreground app
                    Log.d(TAG, "No recent events for ${timeSinceLastEvent}ms, trying to update foreground app")
                    verifyForegroundAppWithUsageStats()
                }
            } else {
                // Check if we're actually in another app that's not being detected through normal events
                verifyForegroundAppWithUsageStats()
            }
            
            // Periodically update heartbeat (every 15 seconds)
            if (now % 15000 < CHECK_INTERVAL) {
                updateHeartbeat()
            }
            
            // Save usage data more frequently (every 15 seconds)
            if (now % 15000 < CHECK_INTERVAL) {
                saveUsageData()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in checkForegroundApp", e)
        } finally {
            isCheckingInProgress = false
        }
    }
    
    // بررسی سخت‌گیرانه‌تر اپ‌های اجتماعی با اولویت بالا
    private fun checkSocialAppsStrictly() {
        try {
            val timeLimitsJson = prefs.getString(TIME_LIMITS_KEY, "{}")
            val timeLimits = JSONObject(timeLimitsJson ?: "{}")
            
            // بررسی ویژه برنامه‌های اجتماعی با اولویت بالا
            for (packageName in prioritySocialApps) {
                // فقط برنامه‌هایی که محدودیت دارند و توسط فرایندها شناسایی می‌شوند
                if (timeLimits.has(packageName)) {
                    val limitMinutes = timeLimits.getLong(packageName)
                    val limitMs = limitMinutes * 60 * 1000
                    
                    // محاسبه زمان استفاده برای این برنامه
                    val currentSessionTime = if (packageName == lastForegroundPackage) currentForegroundTime else 0
                    val savedUsageTime = currentDayUsage.getOrDefault(packageName, 0L)
                    val totalUsageTime = savedUsageTime + currentSessionTime
                    
                    // لاگ برای برنامه‌های اجتماعی مهم مانند اینستاگرام
                    if (packageName == "com.instagram.android") {
                        Log.d(TAG, "🔍 وضعیت اینستاگرام: زمان استفاده ${totalUsageTime/1000}s از محدودیت ${limitMs/1000}s")
                    }
                    
                    // بررسی محدودیت زمانی
                    if (totalUsageTime >= limitMs) {
                        Log.d(TAG, "⏱️ پایان زمان $packageName: ${totalUsageTime/1000}s >= ${limitMs/1000}s")
                        
                        // بروزرسانی داده‌ها
                        currentDayUsage[packageName] = totalUsageTime
                        saveUsageData()
                        
                        // اعمال قفل حتی اگر برنامه در حال اجرا نباشد
                        forceAppLock(packageName)
                        continue
                    }
                    
                    // تشخیص اگر اپ در حال اجراست با روش‌های متعدد
                    var isRunning = isAppRunningAdvanced(packageName)
                    
                    // اگر برنامه در حال اجراست و از محدودیت عبور کرده، قفل کن
                    if (isRunning && totalUsageTime >= limitMs) {
                        Log.d(TAG, "🔒 محدودیت زمانی برای $packageName به پایان رسیده است: ${totalUsageTime/1000}s >= ${limitMs/1000}s")
                        
                        // بروزرسانی داده‌ها قبل از قفل
                        currentDayUsage[packageName] = totalUsageTime
                        saveUsageData()
                        
                        // اعمال قفل
                        forceAppLock(packageName)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطا در بررسی سخت‌گیرانه اپ‌های اجتماعی: ${e.message}")
        }
    }
    
    /**
     * تشخیص دقیق‌تر اجرا شدن برنامه با بررسی چند روش
     */
    private fun isAppRunningAdvanced(packageName: String): Boolean {
        try {
            // روش 1: بررسی با ActivityManager
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val runningApps = am.runningAppProcesses
            for (app in runningApps) {
                if (app.processName == packageName) {
                    Log.d(TAG, "برنامه $packageName فعال است (روش ActivityManager)")
                    return true
                }
            }
            
            // روش 2: بررسی با UsageStatsManager
            val currentApp = getCurrentForegroundPackage()
            if (currentApp == packageName) {
                Log.d(TAG, "برنامه $packageName در حال اجراست (روش UsageStats)")
                return true
            }
            
            // روش 3: بررسی با آخرین گزارش Accessibility
            if (lastForegroundPackage == packageName) {
                // بررسی زمان برای اطمینان از به‌روز بودن
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastEventTime < 3000) { // اگر گزارش در 3 ثانیه اخیر باشد
                    Log.d(TAG, "برنامه $packageName در فورگراند گزارش شده (روش Accessibility)")
                    return true
                }
            }
            
            Log.d(TAG, "برنامه $packageName در حال اجرا نیست (بررسی کامل)")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "خطا در بررسی اجرای برنامه: ${e.message}")
            // در صورت خطا، فرض می‌کنیم برنامه در حال اجرا نیست
            return false
        }
    }
    
    private fun verifyForegroundAppWithUsageStats() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                val usm = usageManager ?: return
                val time = System.currentTimeMillis()
                val events = usm.queryEvents(time - 3000, time)
                val event = android.app.usage.UsageEvents.Event()
                
                var lastEventPackageName: String? = null
                var lastEventTime = 0L
                
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND && 
                        event.timeStamp > lastEventTime) {
                        lastEventPackageName = event.packageName
                        lastEventTime = event.timeStamp
                    }
                }
                
                if (lastEventPackageName != null && 
                    lastEventPackageName != lastForegroundPackage &&
                    lastEventPackageName != "android" &&
                    !lastEventPackageName.startsWith("com.android")) {
                    
                    Log.d(TAG, "⚠️ Detected foreground app change through UsageStats: $lastEventPackageName")
                    
                    // Handle this as an app switch
                    handleAppSwitch(lastEventPackageName)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error verifying foreground app with usage stats", e)
            }
        }
    }
    
    private fun checkTimeLimitExceeded(packageName: String, totalUsageTime: Long) {
        try {
            // Read time limits
            val timeLimitsJson = prefs.getString(TIME_LIMITS_KEY, "{}")
            val timeLimits = JSONObject(timeLimitsJson ?: "{}")
            
            // Check if this app has a time limit
            if (timeLimits.has(packageName)) {
                val limitMinutes = timeLimits.getLong(packageName)
                val limitMs = limitMinutes * 60 * 1000
                
                // ارسال لاگ برای بررسی دقیق
                Log.d(TAG, "⏱️⏱️⏱️ بررسی محدودیت زمانی برای $packageName: ${totalUsageTime/1000}s از ${limitMs/1000}s")
                
                // If time used exceeds limit, lock the app with more aggressive approach
                if (totalUsageTime >= limitMs) {
                    Log.d(TAG, "🔒🔒🔒 محدودیت زمانی برای $packageName به پایان رسیده است: ${totalUsageTime/1000}s >= ${limitMs/1000}s")
                        
                    // Update usage in shared prefs before locking
                    currentDayUsage[packageName] = totalUsageTime
                    saveUsageData()
                    
                    // کاملا مطمئن شو که برنامه قفل می‌شود - با تاکید شدید
                    forceAppLock(packageName)
                    
                    // نمایش پیام به کاربر و هدایت به صفحه اصلی
                    try {
                        // نمایش پیام در برنامه هدف
                        val intent = Intent(this, AppLockOverlayActivity::class.java)
                        intent.putExtra("package_name", packageName)
                        intent.putExtra("time_limit", limitMinutes)
                        intent.putExtra("showInApp", true)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                        
                        // هدایت به صفحه اصلی
                        val homeIntent = Intent(Intent.ACTION_MAIN)
                        homeIntent.addCategory(Intent.CATEGORY_HOME)
                        homeIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        startActivity(homeIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error showing time limit exceeded message", e)
                    }
                }
                // Alert when approaching limit (90%)
                else if (totalUsageTime >= limitMs * 0.9 && 
                         totalUsageTime < limitMs && 
                         totalUsageTime % 15000 < CHECK_INTERVAL) {
                    // Show a warning more frequently (every 15 seconds) when close to limit
                    val timeLeftSeconds = (limitMs - totalUsageTime) / 1000
                    Log.d(TAG, "⚠️ Approaching time limit for $packageName: ${timeLeftSeconds}s left")
                    
                    // Show toast notification
                    if (packageName == lastForegroundPackage) {
                        handler.post {
                            try {
                                val appName = getAppName(packageName)
                                Toast.makeText(
                                    this, 
                                    "$appName: تنها ${timeLeftSeconds} ثانیه از زمان مجاز باقی مانده", 
                                    Toast.LENGTH_SHORT
                                ).show()
                            } catch (e: Exception) {
                                // Ignore errors in toast display
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking time limits", e)
        }
    }
    
    /**
     * قفل اجباری برنامه - این متد هم برنامه را می‌کشد و هم آن را به لیست قفل‌شده‌ها اضافه می‌کند
     */
    private fun forceAppLock(packageName: String) {
        try {
            // بستن برنامه هدف
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(packageName)
            
            // نمایش صفحه قفل در برنامه هدف
            val intent = Intent(this, AppLockOverlayActivity::class.java).apply {
                putExtra("package_name", packageName)
                putExtra("showInApp", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage(packageName) // این خط مهم است - باعث می‌شود صفحه قفل در برنامه هدف نمایش داده شود
            }
            
            // تلاش برای نمایش صفحه قفل در برنامه هدف
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error showing lock screen in target app", e)
                // اگر نمایش در برنامه هدف موفق نبود، در برنامه خودمان نمایش بده
                intent.setPackage(null)
                startActivity(intent)
            }
            
            // هدایت به صفحه اصلی
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error forcing app lock", e)
        }
    }
    
    /**
     * نمایش اورلی داخل برنامه قبل از بستن آن
     */
    private fun showOverlayInApp(packageName: String) {
        try {
            val appName = getAppName(packageName)
            // دریافت زمان استفاده
            val timeUsedMinutes = currentDayUsage.getOrDefault(packageName, 0L) / (60 * 1000)
            // دریافت محدودیت زمانی
            val timeLimitsJson = prefs.getString(TIME_LIMITS_KEY, "{}")
            val timeLimits = JSONObject(timeLimitsJson ?: "{}")
            val timeLimitMinutes = if (timeLimits.has(packageName)) timeLimits.getLong(packageName) else 0
            
            // ذخیره اطلاعات برنامه قفل شده
            prefs.edit().apply {
                putString("last_locked_app", packageName)
                putString("last_locked_app_name", appName)
                putLong("lock_time", System.currentTimeMillis())
                apply()
            }
            
            // نمایش توست اولیه برای اطلاع‌رسانی سریع
            handler.post {
                try {
                    Toast.makeText(
                        applicationContext,
                        "$appName قفل شده است - زمان استفاده به پایان رسیده",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    // خطای توست را نادیده بگیر
                }
            }
            
            // نمایش اورلی داخل برنامه با تنظیمات مخصوص
            val lockIntent = Intent(this, AppLockOverlayActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                
                // ارسال اطلاعات به اکتیویتی
                putExtra("packageName", packageName)
                putExtra("appName", appName)
                putExtra("timeUsed", timeUsedMinutes)
                putExtra("timeLimit", timeLimitMinutes)
                putExtra("showInApp", true)  // فلگ جدید برای نمایش داخل برنامه
                putExtra("showFirst", true)  // نمایش اولیه
            }
            
            startActivity(lockIntent)
            Log.d(TAG, "✅ صفحه قفل داخل برنامه برای $packageName نمایش داده شد")
            
        } catch (e: Exception) {
            Log.e(TAG, "خطا در نمایش اورلی داخل برنامه: ${e.message}")
        }
    }
    
    /**
     * افزودن برنامه به لیست قفل‌ها
     */
    private fun addToLockedApps(packageName: String) {
        try {
            val gson = Gson()
            val lockedAppsJson = prefs.getString(LOCKED_APPS_KEY, "[]")
            
            val lockedAppsList = try {
                gson.fromJson(lockedAppsJson, Array<String>::class.java).toMutableList()
            } catch (e: Exception) {
                Log.e(TAG, "خطا در خواندن لیست قفل‌ها: ${e.message}")
                mutableListOf<String>()
            }
            
            if (!lockedAppsList.contains(packageName)) {
                lockedAppsList.add(packageName)
                val updatedJson = gson.toJson(lockedAppsList)
                prefs.edit().putString(LOCKED_APPS_KEY, updatedJson).apply()
                Log.d(TAG, "🔐 برنامه به لیست قفل‌ها اضافه شد: $packageName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطا در افزودن به لیست قفل‌ها: ${e.message}")
        }
    }
    
    private fun verifyNoLockedAppsRunning() {
        try {
            // Get current foreground app using multiple methods to be extra sure
            val currentApp = getCurrentForegroundPackage()
            
            if (currentApp != null && currentApp.isNotEmpty() && isAppLocked(currentApp)) {
                Log.d(TAG, "🚨 CRITICAL: Locked app detected in foreground: $currentApp")
                
                // Close it immediately using all available methods
                performGoHomeAction()
                forceCloseApp(currentApp)
                showLockScreen(currentApp)
                
                // Also broadcast an emergency notification
                val intent = Intent("com.example.flutter_application_512.APP_LOCKED").apply {
                    putExtra("packageName", currentApp)
                    putExtra("emergency", true)
                }
                sendBroadcast(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in verifyNoLockedAppsRunning", e)
        }
    }
    
    private fun getCurrentForegroundPackage(): String? {
        try {
            // بررسی اجباری برنامه‌های اجتماعی خاص که ممکن است در سیستم‌های مختلف به درستی شناسایی نشوند
            val socialApps = prioritySocialApps
            
            val timeLimitsJson = prefs.getString(TIME_LIMITS_KEY, "{}")
            val timeLimits = JSONObject(timeLimitsJson ?: "{}")
            
            // ابتدا برنامه‌های اجتماعی دارای محدودیت زمانی را بررسی کن
            for (appPackage in socialApps) {
                if (timeLimits.has(appPackage) && isAppLocked(appPackage)) {
                    // بررسی با استفاده از چندین روش دقیق‌تر
                    try {
                        // روش 1: بررسی فرآیندهای در حال اجرا
                        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                        val runningProcesses = am.runningAppProcesses
                        
                        for (processInfo in runningProcesses) {
                            if (processInfo.processName == appPackage || 
                                processInfo.pkgList.contains(appPackage)) {
                                
                                Log.d(TAG, "🎯 شناسایی دقیق برنامه اجتماعی در لیست پروسس‌ها: $appPackage")
                                return appPackage
                            }
                        }
                        
                        // روش 2: استفاده از UsageStats برای بررسی استفاده‌های اخیر
                        if (usageManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                            val now = System.currentTimeMillis()
                            
                            // بررسی آمار استفاده در 30 ثانیه اخیر
                            val stats = usageManager!!.queryUsageStats(
                                android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                                now - 30 * 1000,
                                now
                            )
                            
                            for (stat in stats) {
                                if (stat.packageName == appPackage && 
                                    stat.lastTimeUsed > now - 15000) { // استفاده در 15 ثانیه اخیر
                                    
                                    Log.d(TAG, "🎯 شناسایی دقیق برنامه اجتماعی با استفاده‌ی اخیر: $appPackage")
                                    return appPackage
                                }
                            }
                            
                            // روش 3: بررسی رویدادهای اخیر
                            val events = usageManager!!.queryEvents(now - 10000, now)
                            val event = android.app.usage.UsageEvents.Event()
                            
                            while (events.hasNextEvent()) {
                                events.getNextEvent(event)
                                if (event.packageName == appPackage && 
                                    event.timeStamp > now - 10000) { // رویداد در 10 ثانیه اخیر
                                    
                                    Log.d(TAG, "🎯 شناسایی دقیق برنامه اجتماعی با رویداد اخیر: $appPackage")
                                    return appPackage
                                }
                            }
                        }
                        
                        // روش 4: بررسی وظایف در حال اجرا
                        val tasks = am.getRunningTasks(10)
                        for (task in tasks) {
                            if (task.topActivity?.packageName == appPackage) {
                                Log.d(TAG, "🎯 شناسایی دقیق برنامه اجتماعی در تسک‌های در حال اجرا: $appPackage")
                                return appPackage
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "خطا در بررسی دقیق برنامه‌های اجتماعی: ${e.message}")
                    }
                }
            }
            
            // روش‌های معمول بعد از بررسی ویژه برنامه‌های اجتماعی

            // روش 1: استفاده از آخرین برنامه شناسایی شده توسط سرویس
            if (lastForegroundPackage.isNotEmpty() && 
                lastForegroundPackage != "android" && 
                !lastForegroundPackage.startsWith("com.android") &&
                lastForegroundPackage != packageName) {
                
                // چک مجدد با استفاده از ActivityManager برای اطمینان بیشتر
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val tasks = am.getRunningTasks(1)
                if (tasks.isNotEmpty() && tasks[0].topActivity != null) {
                    val amPackage = tasks[0].topActivity!!.packageName
                    // اگر ActivityManager برنامه دیگری را نشان می‌دهد، آن را بررسی کن
                    if (amPackage != lastForegroundPackage && 
                        amPackage != "android" && 
                        !amPackage.startsWith("com.android") && 
                        amPackage != packageName) {
                        
                        Log.d(TAG, "تناقض در تشخیص برنامه فعال: Last=$lastForegroundPackage, AM=$amPackage")
                        return amPackage
                    }
                }
                
                return lastForegroundPackage
            }
            
            // روش 2: استفاده از Usage Stats در اندروید 5.0+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 && usageManager != null) {
                val time = System.currentTimeMillis()
                val events = usageManager!!.queryEvents(time - 5000, time)
                val event = android.app.usage.UsageEvents.Event()
                
                var lastEventPackageName: String? = null
                var lastEventTime = 0L
                
                // جستجوی آخرین رویداد MOVE_TO_FOREGROUND
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND && 
                        event.timeStamp > lastEventTime &&
                        event.packageName != "android" &&
                        !event.packageName.startsWith("com.android") &&
                        event.packageName != packageName) {
                        
                        lastEventPackageName = event.packageName
                        lastEventTime = event.timeStamp
                    }
                }
                
                if (lastEventPackageName != null) {
                    Log.d(TAG, "برنامه فعال از طریق UsageStats: $lastEventPackageName")
                    return lastEventPackageName
                }
            }
            
            // روش 3: استفاده از ActivityManager (در نسخه‌های قدیمی‌تر اندروید)
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val tasks = am.getRunningTasks(1)
                if (tasks.isNotEmpty() && tasks[0].topActivity != null) {
                    val amPackage = tasks[0].topActivity!!.packageName
                    if (amPackage != "android" && 
                        !amPackage.startsWith("com.android") && 
                        amPackage != packageName) {
                        
                        Log.d(TAG, "برنامه فعال از طریق ActivityManager: $amPackage")
                        return amPackage
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "خطا در دریافت برنامه فعال از ActivityManager", e)
            }
            
            // روش 4: چک کردن مستقیم برنامه‌های محبوب و مشکل‌ساز مانند اینستاگرام و تلگرام - چک عمومی
            for (appPackage in socialApps) {
                if (timeLimits.has(appPackage)) {
                    try {
                        // چک کنیم که آیا پروسس برنامه در حال اجراست
                        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                        val runningProcesses = am.runningAppProcesses
                        
                        for (processInfo in runningProcesses) {
                            if (processInfo.processName == appPackage || 
                                processInfo.pkgList.contains(appPackage)) {
                                
                                Log.d(TAG, "🔍 برنامه $appPackage در لیست پروسس‌های در حال اجرا یافت شد")
                                return appPackage
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "خطا در بررسی برنامه‌های اجتماعی: ${e.message}")
                    }
                }
            }
            
            // روش 5: بررسی یک به یک برنامه‌های دارای محدودیت زمانی
            try {
                val allAppsWithLimits = JSONObject(timeLimitsJson ?: "{}")
                val keys = allAppsWithLimits.keys()
                
                while (keys.hasNext()) {
                    val appWithLimit = keys.next()
                    
                    // اگر این برنامه قفل است، بررسی کن که آیا در حال اجراست
                    if (isAppLocked(appWithLimit)) {
                        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                        val runningProcesses = am.runningAppProcesses
                        
                        for (processInfo in runningProcesses) {
                            if (processInfo.processName == appWithLimit || 
                                processInfo.pkgList.contains(appWithLimit)) {
                                
                                Log.d(TAG, "⚠️ برنامه با محدودیت زمانی $appWithLimit در حال اجراست!")
                                return appWithLimit
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "خطا در بررسی برنامه‌های دارای محدودیت: ${e.message}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "خطای کلی در تشخیص برنامه فعال", e)
        }
        
        return null
    }
    
    private fun checkAndLockApp(packageName: String, currentTime: Long): Boolean {
        // Skip system packages and non-tracked apps
        if (packageName.isEmpty() || packageName == "android" || 
            packageName.startsWith("com.android") || !isAppTracked(packageName)) {
            return false
        }
        
        try {
            // First check if app is already locked - اگر برنامه قفل باشد باید بلافاصله بسته شود
            if (isAppLocked(packageName)) {
                Log.d(TAG, "🔥 $packageName is locked, enforcing closure and returning to home")
                
                // اول پیام قفل را نمایش می‌دهیم
                showOverlayInApp(packageName)
                
                // سپس با تاخیر برنامه را می‌بندیم
                handler.postDelayed({
                    // Multiple attempts to enforce the closure
                    performGoHomeAction()
                    forceCloseApp(packageName)
                }, 2500)
                
                return true
            }
            
            // Then check time limits
            val timeLimitsJson = prefs.getString(TIME_LIMITS_KEY, "{}")
            val timeLimits = JSONObject(timeLimitsJson ?: "{}")
            
            if (timeLimits.has(packageName)) {
                val limitMinutes = timeLimits.getLong(packageName)
                val limitMs = limitMinutes * 60 * 1000
                
                // Get current usage including current session to avoid any delays
                val currentSessionTime = if (packageName == lastForegroundPackage) currentForegroundTime else 0
                val totalUsageTime = currentDayUsage.getOrDefault(packageName, 0L) + currentSessionTime
                
                // Log time limit status for debugging
                Log.d(TAG, "Checking time limit for $packageName: used ${totalUsageTime/1000}s of ${limitMs/1000}s limit")
                
                // If already over limit, lock app
                if (totalUsageTime >= limitMs) {
                    Log.d(TAG, "🔒 Time limit exceeded for $packageName: ${totalUsageTime/1000}s >= ${limitMs/1000}s")
                    
                    // Lock the app IMMEDIATELY
                    lockApp(packageName)
                    
                    // Multiple enforcement actions
                    performGoHomeAction()
                    forceCloseApp(packageName)
                    
                    // Show lock screen with slight delay
                    handler.postDelayed({
                        showLockScreen(packageName)
                        
                        // Double check the app is actually closed
                        handler.postDelayed({
                            val current = getCurrentForegroundPackage()
                            if (current == packageName) {
                                Log.d(TAG, "⚠️ App still in foreground after locking, forcing closure again: $packageName")
                                performGoHomeAction()
                                forceCloseApp(packageName)
                            }
                        }, 500)
                    }, 300)
                    
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in checkAndLockApp", e)
        }
        return false
    }
    
    private fun isAppTracked(packageName: String): Boolean {
        try {
            if (packageName.isEmpty()) return false
            
            val trackingAppsJson = prefs.getString(TRACKING_APPS_KEY, "[]")
            val trackingApps = JSONArray(trackingAppsJson ?: "[]")
            
            for (i in 0 until trackingApps.length()) {
                if (trackingApps.getString(i) == packageName) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if app is tracked", e)
        }
        return false
    }
    
    private fun isAppLocked(packageName: String): Boolean {
        try {
            val lockedAppsJson = prefs.getString(LOCKED_APPS_KEY, "[]")
            val lockedApps = JSONArray(lockedAppsJson ?: "[]")
            
            for (i in 0 until lockedApps.length()) {
                if (lockedApps.getString(i) == packageName) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if app is locked", e)
        }
        return false
    }
    
    private fun lockApp(packageName: String) {
        try {
            // Get current locked apps list
            val lockedAppsJson = prefs.getString(LOCKED_APPS_KEY, "[]")
            val lockedApps = JSONArray(lockedAppsJson)
            
            // Check if already locked to avoid duplicates
            for (i in 0 until lockedApps.length()) {
                if (lockedApps.getString(i) == packageName) {
                    // Already locked - make sure it's closed if in foreground
                    Log.d(TAG, "App already locked: $packageName - enforcing closure")
                    if (packageName == lastForegroundPackage || getCurrentForegroundPackage() == packageName) {
                        // اول پیام قفل را نمایش می‌دهیم
                        showOverlayInApp(packageName)
                        
                        // سپس با تاخیر برنامه را می‌بندیم
                        handler.postDelayed({
                            performGoHomeAction()
                            forceCloseApp(packageName)
                        }, 2500)
                    }
                    return
                }
            }
            
            // Add to locked apps
            lockedApps.put(packageName)
            prefs.edit().putString(LOCKED_APPS_KEY, lockedApps.toString()).apply()
            
            // Log the updated locked apps list
            Log.d(TAG, "🔒 UPDATED LOCKED APPS LIST: ${lockedApps.toString()}")
            
            // Broadcast that app is locked
            val intent = Intent("com.example.flutter_application_512.APP_LOCKED").apply {
                putExtra("packageName", packageName)
            }
            sendBroadcast(intent)
            
            Log.d(TAG, "🔒🔒🔒 LOCKED APP: $packageName 🔒🔒🔒")
            
            // If app is in foreground, first show overlay then force it to close
            if (packageName == lastForegroundPackage || getCurrentForegroundPackage() == packageName) {
                Log.d(TAG, "Locked app is in foreground, showing overlay then forcing closure")
                
                // اول پیام قفل را نمایش می‌دهیم
                showOverlayInApp(packageName)
                
                // سپس با تاخیر برنامه را می‌بندیم
                handler.postDelayed({
                    performGoHomeAction()
                    forceCloseApp(packageName)
                }, 2500)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error locking app", e)
        }
    }
    
    private fun showLockScreen(packageName: String) {
        try {
            // Ensure we're showing lock screen for a tracked app
            if (!isAppTracked(packageName)) {
                Log.d(TAG, "Attempted to show lock screen for non-tracked app: $packageName")
                return
            }
            
            Log.d(TAG, "🔐🔐🔐 نمایش صفحه قفل برای: $packageName")
            
            // Getting time used and time limit information
            val timeLimitsJson = prefs.getString(TIME_LIMITS_KEY, "{}")
            val timeLimits = JSONObject(timeLimitsJson ?: "{}")
            val usageDataJson = prefs.getString(APP_USAGE_DATA_KEY, "{}")
            val usageData = JSONObject(usageDataJson ?: "{}")
            
            val appName = getAppName(packageName)
            var timeUsedMinutes: Long = 0
            var timeLimitMinutes: Long = 0
            
            if (usageData.has(packageName)) {
                timeUsedMinutes = usageData.getLong(packageName) / (60 * 1000)
            }
            
            if (timeLimits.has(packageName)) {
                timeLimitMinutes = timeLimits.getLong(packageName)
            }
            
            // اطمینان از بسته بودن برنامه قبل از نمایش صفحه قفل
            killApp(packageName)
            
            // ابتدا بازگشت به صفحه اصلی
            performGoHomeAction()
            
            // تکمیل سایر اطلاعات که ممکن است نیاز باشد
            // هماهنگ کردن اطلاعات بین سرویس‌ها
            prefs.edit().apply {
                putString("last_locked_app", packageName)
                putString("last_locked_app_name", appName)
                putLong("lock_time", System.currentTimeMillis())
                apply()
            }
            
            // نمایش توست اولیه برای اطلاع‌رسانی سریع
            handler.post {
                try {
                    Toast.makeText(
                        applicationContext,
                        "$appName قفل شده است - زمان استفاده به پایان رسیده",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    // خطای توست را نادیده بگیر
                }
            }
            
            // تاخیر کوتاه برای اطمینان از اعمال تغییرات UI
            handler.postDelayed({
                try {
                    // اینستنت به کلاس LockScreenActivity با فلگ‌های مناسب
                    val lockIntent = Intent(this, LockScreenActivity::class.java).apply {
                        // استفاده از فلگ‌های مناسب برای نمایش روی همه صفحات
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                                Intent.FLAG_ACTIVITY_CLEAR_TASK
                        
                        // ارسال اطلاعات به اکتیویتی
                        putExtra("package_name", packageName)
                        putExtra("app_name", appName)
                        putExtra("time_used", timeUsedMinutes)
                        putExtra("time_limit", timeLimitMinutes)
                        putExtra("force_lock", true)  // فلگ برای اعمال قفل اجباری
                    }
                    
                    // شروع اکتیویتی با فلگ‌های مناسب
                    startActivity(lockIntent)
                    
                    Log.d(TAG, "✅ صفحه قفل برای $packageName نمایش داده شد")
                    
                    // بعد از نمایش صفحه قفل، با تاخیر کوتاه برنامه را مجدد بررسی کن
                    handler.postDelayed({
                        // بررسی مجدد و بستن برنامه اگر هنوز باز است
                        if (isAppRunningAdvanced(packageName)) {
                            Log.d(TAG, "⚠️ برنامه $packageName هنوز باز است، بستن مجدد...")
                            killApp(packageName)
                            performGoHomeAction()
                            
                            // تلاش مجدد برای نمایش صفحه قفل
                            handler.postDelayed({
                                startActivity(lockIntent)
                            }, 300)
                        }
                    }, 1000)
                } catch (e: Exception) {
                    Log.e(TAG, "خطا در نمایش صفحه قفل: ${e.message}")
                    
                    // در صورت خطا، حداقل توست نمایش داده شود
                    handler.post {
                        try {
                            Toast.makeText(
                                applicationContext,
                                "$appName قفل شده است",
                                Toast.LENGTH_LONG
                            ).show()
                        } catch (e2: Exception) {}
                    }
                    
                    // تلاش برای بستن برنامه در صورت خطا در نمایش صفحه قفل
                    performGoHomeAction()
                    killApp(packageName)
                }
            }, 200)
        } catch (e: Exception) {
            Log.e(TAG, "خطای کلی در نمایش صفحه قفل", e)
            
            // تلاش برای بستن برنامه حتی در صورت خطا
            performGoHomeAction()
            killApp(packageName)
        }
    }
    
    private fun performGoHomeAction() {
        try {
            // ترکیبی از روش‌های مختلف برای بستن قطعی برنامه
            
            // روش 1: استفاده از اینتنت HOME
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(homeIntent)
            
            // روش 2: استفاده از اکشن دسترسی‌پذیری
            performGlobalAction(GLOBAL_ACTION_HOME)
            
            // روش 3: استفاده از منوی اخیر و سپس HOME برای بستن برنامه‌هایی که مقاومت می‌کنند
            handler.postDelayed({
                try {
                    // فشار دکمه اخیر
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                    
                    // و سپس فشار HOME بعد از تاخیر کوتاه
                    handler.postDelayed({
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        
                        // یک بار دیگر HOME را فشار بده برای اطمینان
                        handler.postDelayed({
                            performGlobalAction(GLOBAL_ACTION_HOME)
                            
                            // اینتنت HOME را دوباره اجرا کن
                            try {
                                startActivity(homeIntent)
                            } catch (e: Exception) {}
                        }, 100)
                    }, 150)
                } catch (e: Exception) {
                    // خطا را نادیده بگیر و به استفاده از روش‌های دیگر ادامه بده
                }
            }, 200)
            
            Log.d(TAG, "اقدامات متعدد برای بازگشت به صفحه اصلی انجام شد")
        } catch (e: Exception) {
            Log.e(TAG, "خطا در اجرای عملیات بازگشت به صفحه اصلی", e)
            
            // تلاش با روش ساده‌تر در صورت خطا
            try {
                val simpleHomeIntent = Intent(Intent.ACTION_MAIN)
                simpleHomeIntent.addCategory(Intent.CATEGORY_HOME)
                simpleHomeIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(simpleHomeIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "خطا در روش ساده بازگشت به خانه", e2)
            }
        }
    }
    
    private fun isScreenOn(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            powerManager.isInteractive
        } else {
            @Suppress("DEPRECATION")
            powerManager.isScreenOn
        }
    }
    
    private fun getAppName(packageName: String): String {
        try {
            val packageManager = applicationContext.packageManager
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            return packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            return packageName
        }
    }
    
    private fun updateHeartbeat() {
        try {
            prefs.edit().putLong("accessibility_service_last_heartbeat", System.currentTimeMillis()).apply()
            // Also update general last heartbeat
            prefs.edit().putLong("last_heartbeat", System.currentTimeMillis()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error updating heartbeat", e)
        }
    }
    
    private fun logTrackedAppsWithLimits() {
        try {
            val trackingAppsJson = prefs.getString(TRACKING_APPS_KEY, "[]")
            val trackingApps = JSONArray(trackingAppsJson ?: "[]")
            
            val timeLimitsJson = prefs.getString(TIME_LIMITS_KEY, "{}")
            val timeLimits = JSONObject(timeLimitsJson ?: "{}")
            
            Log.d(TAG, "📊 === Tracked Apps Configuration ===")
            for (i in 0 until trackingApps.length()) {
                val packageName = trackingApps.getString(i)
                val appName = getAppName(packageName)
                val limitMinutes = if (timeLimits.has(packageName)) timeLimits.getLong(packageName) else 0
                val usage = currentDayUsage.getOrDefault(packageName, 0L) / (60 * 1000)
                
                Log.d(TAG, "📱 App: $appName ($packageName) | Limit: $limitMinutes min | Used: $usage min")
            }
            Log.d(TAG, "📊 ==============================")
        } catch (e: Exception) {
            Log.e(TAG, "Error logging tracked apps", e)
        }
    }
    
    private fun forceCloseApp(packageName: String) {
        try {
            Log.d(TAG, "⚡ تلاش برای بستن اجباری برنامه: $packageName")
            
            // روش 1: استفاده از ActivityManager برای کشتن فرآیندهای پس‌زمینه
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(packageName)
            
            // روش 2: استفاده از دکمه‌های سیستمی برای بستن برنامه‌های اخیر
            handler.postDelayed({
                performGlobalAction(GLOBAL_ACTION_RECENTS) // باز کردن برنامه‌های اخیر
                
                // کمی صبر کن و سپس به خانه برگرد
                handler.postDelayed({
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }, 200)
            }, 100)
            
            // روش 3: استفاده از چندین دکمه بازگشت متوالی
            handler.postDelayed({
                for (i in 0 until 3) {
                    handler.postDelayed({
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }, i * 100L)
                }
            }, 500)
            
        } catch (e: Exception) {
            Log.e(TAG, "خطا در بستن اجباری برنامه: ${e.message}")
        }
    }
    
    // بررسی و اعمال قفل برای تمام برنامه‌های قفل شده
    private fun enforceAllLockedApps() {
        try {
            // Get the current foreground app
            val currentApp = getCurrentForegroundPackage()
            
            // Get list of locked apps
            val lockedAppsJson = prefs.getString(LOCKED_APPS_KEY, "[]")
            val lockedApps = JSONArray(lockedAppsJson ?: "[]")
            
            Log.d(TAG, "Enforcing locks for ${lockedApps.length()} apps")
            
            // Check if current foreground app is locked
            var currentAppIsLocked = false
            
            for (i in 0 until lockedApps.length()) {
                val lockedPackage = lockedApps.getString(i)
                
                // If current app is locked, enforce it
                if (currentApp == lockedPackage) {
                    currentAppIsLocked = true
                    Log.d(TAG, "Current foreground app $lockedPackage is locked - enforcing closure")
                    
                    // Use all available methods to close it
                    performGoHomeAction()
                    forceCloseApp(lockedPackage)
                    
                    // نمایش مجدد صفحه قفل
                    handler.postDelayed({
                        showLockScreen(lockedPackage)
                    }, 500)
                }
            }
            
            // If no locked app is currently in foreground, still do a verification
            if (!currentAppIsLocked) {
                Log.d(TAG, "No locked apps are currently in foreground")
                verifyNoLockedAppsRunning()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error enforcing locked apps", e)
        }
    }
    
    // متد مخصوص برای بررسی و قفل برنامه‌های اجتماعی با اهمیت ویژه
    private fun checkAndLockSocialApps() {
        try {
            Log.d(TAG, "در حال بررسی ویژه برنامه‌های اجتماعی...")
            
            // بررسی دیکشنری محدودیت‌های زمانی
            val timeLimitsJson = prefs.getString(TIME_LIMITS_KEY, "{}")
            val timeLimits = JSONObject(timeLimitsJson ?: "{}")
            
            // بررسی تک تک برنامه‌های اجتماعی اولویت‌دار
            for (appPackage in prioritySocialApps) {
                // فقط برنامه‌های دارای محدودیت زمانی را بررسی کن
                if (timeLimits.has(appPackage)) {
                    Log.d(TAG, "بررسی برنامه اجتماعی $appPackage")
                    
                    // اگر اپ باید قفل باشد، بررسی کن که آیا در حال اجراست
                    if (isAppLocked(appPackage)) {
                        Log.d(TAG, "🔐 برنامه اجتماعی $appPackage باید قفل باشد!")
                        
                        // بررسی اینکه آیا برنامه در حال اجراست
                        val isSocialAppRunning = checkIfAppIsRunning(appPackage)
                        
                        if (isSocialAppRunning) {
                            Log.d(TAG, "⚠️⚠️⚠️ برنامه اجتماعی $appPackage در حال اجراست! اقدام به قفل...")
                            
                            // اقدامات متعدد برای بستن برنامه
                            performGoHomeAction()
                            forceCloseApp(appPackage)
                            
                            // نمایش صفحه قفل
                            handler.postDelayed({
                                showLockScreen(appPackage)
                                
                                // یک بررسی مجدد پس از نمایش صفحه قفل
                                handler.postDelayed({
                                    if (checkIfAppIsRunning(appPackage)) {
                                        Log.d(TAG, "🚨🚨🚨 برنامه اجتماعی $appPackage هنوز در حال اجراست! تلاش مجدد...")
                                        performGoHomeAction()
                                        forceCloseApp(appPackage)
                                    }
                                }, 1000)
                            }, 300)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطا در بررسی برنامه‌های اجتماعی: ${e.message}")
        }
    }
    
    // Obtener el uso diario de la aplicación
    private fun getDailyAppUsage(packageName: String): Long {
        try {
            checkAndResetDailyUsage()
            
            val appUsageJson = prefs.getString(APP_USAGE_DATA_KEY, "{}")
            val appUsage = JSONObject(appUsageJson)
            
            return if (appUsage.has(packageName)) {
                appUsage.getLong(packageName)
            } else {
                0L
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al obtener uso diario: ${e.message}")
            return 0L
        }
    }
    
    // Actualizar el tiempo de uso actual
    private fun updateCurrentUsageTime(packageName: String, currentTime: Long) {
        try {
            if (packageName.isEmpty() || packageName == "android" || 
                packageName.startsWith("com.android")) {
                return
            }
            
            // Actualizar última aplicación y tiempo
            if (lastForegroundPackage != packageName) {
                if (lastForegroundPackage.isNotEmpty() && lastForegroundTime > 0) {
                    // Calcular el tiempo usado para la aplicación anterior
                    val usedTime = currentTime - lastForegroundTime
                    if (usedTime > 500) { // Ignorar cambios muy rápidos
                        updateAppUsageTime(lastForegroundPackage, usedTime, currentTime)
                    }
                }
                
                // Actualizar la nueva aplicación activa
                lastForegroundPackage = packageName
                lastForegroundTime = currentTime
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al actualizar tiempo de uso: ${e.message}")
        }
    }
    
    // Verificar y resetear uso diario a medianoche
    private fun checkAndResetDailyUsage() {
        try {
            val currentTimeMillis = System.currentTimeMillis()
            val lastResetTime = prefs.getLong(LAST_DAILY_RESET_KEY, 0)
            
            // Obtener día actual y último día de reseteo
            val currentDate = Calendar.getInstance()
            val lastResetDate = Calendar.getInstance()
            lastResetDate.timeInMillis = lastResetTime
            
            // Si es un nuevo día, resetear contadores
            if (currentDate.get(Calendar.DAY_OF_YEAR) != lastResetDate.get(Calendar.DAY_OF_YEAR) ||
                currentDate.get(Calendar.YEAR) != lastResetDate.get(Calendar.YEAR)) {
                
                Log.d(TAG, "Reseteando contadores de uso diario")
                prefs.edit().apply {
                    putString(APP_USAGE_DATA_KEY, "{}")
                    putLong(LAST_DAILY_RESET_KEY, currentTimeMillis)
                    apply()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al verificar reseteo diario: ${e.message}")
        }
    }
    
    // Actualizar el tiempo de uso de una aplicación
    private fun updateAppUsageTime(packageName: String, usedTimeMillis: Long, currentTime: Long = System.currentTimeMillis()) {
        try {
            val appUsageJson = prefs.getString(APP_USAGE_DATA_KEY, "{}")
            val appUsage = JSONObject(appUsageJson)
            
            val currentUsage = if (appUsage.has(packageName)) {
                appUsage.getLong(packageName)
            } else {
                0L
            }
            
            // Actualizar tiempo total
            val newUsage = currentUsage + usedTimeMillis
            appUsage.put(packageName, newUsage)
            
            // Guardar actualización
            prefs.edit().putString(APP_USAGE_DATA_KEY, appUsage.toString()).apply()
            
            // Log para debugging
            if (prioritySocialApps.contains(packageName)) {
                Log.d(TAG, "⏱️ Actualizado uso de $packageName: ${newUsage/60000} minutos")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al actualizar tiempo de uso: ${e.message}")
        }
    }
    
    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }
    
    override fun onDestroy() {
        isServiceRunning = false
        Log.d(TAG, "Service destroyed")
        
        // Save final usage data
        saveUsageData()
        
        // Stop periodic checks
        stopPeriodicChecks()
        
        // Unregister receiver
        try {
            if (appLockReceiver != null) {
                unregisterReceiver(appLockReceiver)
                appLockReceiver = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
        
        // Try to automatically restart the service
        val restartIntent = Intent(SERVICE_RESTART_ACTION)
        sendBroadcast(restartIntent)
        
        super.onDestroy()
    }
    
    private fun killApp(packageName: String) {
        try {
            Log.d(TAG, "🔫 تلاش برای کشتن اجباری برنامه: $packageName")
            
            // 1. استفاده از ActivityManager برای کشتن پروسس‌ها
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(packageName)
            
            // 2. استفاده از شبیه‌سازی دکمه HOME برای بیرون آمدن از برنامه
            performGlobalAction(GLOBAL_ACTION_HOME)
            
            // 3. روش قدرتمند - استفاده از متوالی RECENTS و HOME
            handler.postDelayed({
                try {
                    // فشار دکمه RECENTS برای نمایش برنامه‌های اخیر
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                    
                    // با تأخیر کوتاه، دکمه HOME را فشار می‌دهیم
                    handler.postDelayed({
                        performGlobalAction(GLOBAL_ACTION_HOME)
                    }, 300)
                } catch (e: Exception) {
                    Log.e(TAG, "خطا در اجرای RECENTS-HOME: ${e.message}")
                }
            }, 200)
            
            // 4. استفاده از intent برای رفتن به صفحه اصلی
            handler.postDelayed({
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(homeIntent)
            }, 500)
            
            // 5. استفاده از FORCE_STOP (در دستگاه‌های روت شده کار می‌کند)
            try {
                val forceStopIntent = Intent("com.example.flutter_application_512.FORCE_STOP_APP").apply {
                    putExtra("packageName", packageName)
                }
                sendBroadcast(forceStopIntent)
            } catch (e: Exception) {
                Log.d(TAG, "روش force stop پشتیبانی نمی‌شود")
            }
            
            // 6. تلاش برای استفاده از shell command (برای دستگاه‌های روت)
            try {
                val pm = applicationContext.packageManager
                val intent = pm.getLaunchIntentForPackage("com.android.settings")
                intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
                
                handler.postDelayed({
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }, 500)
            } catch (e: Exception) {
                Log.d(TAG, "روش تنظیمات پشتیبانی نمی‌شود")
            }
            
            Log.d(TAG, "✅ تلاش‌های متعدد برای بستن اجباری $packageName انجام شد")
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطا در اجرای killApp: ${e.message}")
        }
    }
    
    /**
     * ساده‌سازی نمایش صفحه قفل با تأخیر
     * این متد یک روش اطمینان‌بخش ارائه می‌دهد
     */
    private fun showLockScreenSimple(packageName: String) {
        try {
            val appName = getAppName(packageName)
            
            // کشتن برنامه هدف
            killApp(packageName)
            performGoHomeAction()
            
            // نمایش توست اولیه
            handler.post {
                try {
                    Toast.makeText(
                        applicationContext,
                        "$appName قفل شده است",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) { }
            }
            
            // نمایش صفحه قفل با تأخیر کوتاه
            handler.postDelayed({
                try {
                    val lockIntent = Intent(this, LockScreenActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                                Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("package_name", packageName)
                    }
                    startActivity(lockIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "خطا در نمایش صفحه قفل ساده: ${e.message}")
                }
            }, 300)
        } catch (e: Exception) {
            Log.e(TAG, "خطای کلی در showLockScreenSimple: ${e.message}")
        }
    }
    
    // Check if the app is running
    private fun checkIfAppIsRunning(packageName: String): Boolean {
        return isAppRunningAdvanced(packageName)
    }
} 