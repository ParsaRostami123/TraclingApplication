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

class AppLockAccessibilityService : AccessibilityService() {
    
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null
    private var lastForegroundPackage: String = ""
    private var lastAppSwitchTime: Long = 0
    private var lastEventTime: Long = 0
    private val timeBeforeConsideringNewApp = 1500L // 1.5 seconds threshold for genuine app switch
    private var isCheckingInProgress = false
    private var currentForegroundTime: Long = 0
    private var usageManager: UsageStatsManager? = null
    private val currentDayUsage = ConcurrentHashMap<String, Long>() // Thread-safe map
    private var appLockReceiver: BroadcastReceiver? = null
    private val TAG = "AppLockService"
    
    // لیست برنامه‌های اجتماعی محبوب که نیازمند نظارت ویژه هستند
    private val prioritySocialApps = arrayOf(
        "com.instagram.android", // Instagram
        "org.telegram.messenger", // Telegram
        "com.whatsapp", // WhatsApp
        "com.facebook.katana", // Facebook
        "com.zhiliaoapp.musically" // TikTok
    )
    
    companion object {
        const val PREFS_NAME = "AppLockPrefs"
        const val TRACKING_APPS_KEY = "tracking_apps"
        const val TIME_LIMITS_KEY = "time_limits"
        const val APP_USAGE_DATA_KEY = "app_usage_data"
        const val LOCKED_APPS_KEY = "locked_apps"
        const val SERVICE_RESTART_ACTION = "com.example.flutter_application_512.RESTART_SERVICE"
        
        // Reduced interval for more responsive locking - even faster now
        private const val CHECK_INTERVAL = 300L // Check every 300ms
        
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
        // Update last event time to help with detection
        lastEventTime = System.currentTimeMillis()
        
        // Only process events that indicate a window state change
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            
            val packageName = event.packageName?.toString() ?: ""
            if (packageName.isNotEmpty() && packageName != "android" && !packageName.startsWith("com.android")) {
                // Check if this app is locked regardless of switch
                if (isAppLocked(packageName)) {
                    Log.d(TAG, "⚠️ LOCKED APP DETECTED in accessibility event: $packageName")
                    
                    // Give detailed event info for debugging
                    val eventText = event.text?.joinToString(", ") ?: "no text"
                    val eventSource = event.source?.className ?: "unknown source"
                    Log.d(TAG, "Event details - Type: ${event.eventType}, Text: $eventText, Source: $eventSource")
                    
                    performGoHomeAction()
                    forceCloseApp(packageName)
                    showLockScreen(packageName)
                    return
                }
                
                // Normal app switch handling
                if (lastForegroundPackage != packageName) {
                    Log.d(TAG, "📱 App switch detected: $lastForegroundPackage -> $packageName")
                    handleAppSwitch(packageName)
                }
            }
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
        checkAndLockApp(newPackage)
        
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

                    // Log usage time more frequently for apps close to their limits (Telegram fix)
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
                // This helps fix issues with apps like Telegram that might be missed
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
                    
                    // نمایش توست برای آگاهی بیشتر کاربر
                    try {
                        handler.post {
                            Toast.makeText(
                                applicationContext,
                                getAppName(packageName) + " قفل شد - زمان مجاز به پایان رسیده است",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } catch (e: Exception) {
                        // Ignore toast errors
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
    
    // روش جدید با اقدامات چندگانه برای اطمینان از قفل شدن قطعی برنامه
    private fun forceAppLock(packageName: String) {
        try {
            Log.d(TAG, "🔥🔥🔥 اجرای قفل اجباری برای $packageName")
            
            // 1. ابتدا برنامه را به لیست قفل‌ها اضافه کن
            val lockedAppsJson = prefs.getString(LOCKED_APPS_KEY, "[]")
            val lockedApps = JSONArray(lockedAppsJson ?: "[]")
            
            // بررسی برای جلوگیری از تکرار
            var isAlreadyLocked = false
            for (i in 0 until lockedApps.length()) {
                if (lockedApps.getString(i) == packageName) {
                    isAlreadyLocked = true
                    break
                }
            }
            
            if (!isAlreadyLocked) {
                lockedApps.put(packageName)
                // ذخیره فوری با commit به جای apply
                prefs.edit().putString(LOCKED_APPS_KEY, lockedApps.toString()).commit()
                Log.d(TAG, "🔒 برنامه به لیست قفل‌ها اضافه شد: $packageName")
            }
            
            // ارسال برودکست به صورت مستقیم برای قفل کردن برنامه هدف
            val lockIntent = Intent("com.example.flutter_application_512.APP_LOCKED").apply {
                putExtra("packageName", packageName)
                putExtra("emergency", true)
                putExtra("timeLimit", true)
                putExtra("targetPackage", packageName) // اضافه کردن پکیج نام هدف به طور مشخص
            }
            sendBroadcast(lockIntent)
            
            // 2. بررسی اگر برنامه در حال اجراست، آن را ببند
            val currentForeground = getCurrentForegroundPackage()
            if (currentForeground == packageName || lastForegroundPackage == packageName) {
                Log.d(TAG, "🚨 برنامه قفل شده در حال اجراست - در حال بستن اجباری")
                
                // 2.1. اقدامات متعدد برای بستن برنامه هدف (نه برنامه اصلی خودمان)
                killApp(packageName)
                performGoHomeAction()
                
                // 2.2. استفاده از روش killBackgroundProcesses
                try {
                    val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    am.killBackgroundProcesses(packageName)
                } catch (e: Exception) {
                    Log.e(TAG, "خطا در بستن پروسس: ${e.message}")
                }
                
                // 2.3. استفاده مکرر از دکمه بازگشت
                for (i in 0 until 5) {
                    handler.postDelayed({
                        try {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                        } catch (e: Exception) {}
                    }, i * 100L)
                }
                
                // 2.4. نمایش صفحه قفل با تاخیر برای اطمینان از بسته شدن برنامه
                handler.postDelayed({
                    showLockScreen(packageName)
                    
                    // نمایش مجدد صفحه قفل
                    handler.postDelayed({
                        showLockScreen(packageName)
                    }, 500)
                }, 200)
                
                // 2.6. تنظیم بررسی‌های مکرر برای اطمینان از قفل ماندن
                scheduleLockedAppChecks(packageName)
            } else {
                // حتی اگر برنامه در حال اجرا نیست، صفحه قفل را به صورت پیشگیرانه نشان بده
                showLockScreen(packageName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطا در اجرای قفل اجباری: ${e.message}")
            
            // تلاش نهایی برای قفل کردن حتی در صورت خطا
            try {
                lockApp(packageName)
                performGoHomeAction()
            } catch (e2: Exception) {
                Log.e(TAG, "خطا در تلاش نهایی قفل: ${e2.message}")
            }
        }
    }
    
    // متد جدید برای کشتن برنامه هدف به طور خاص
    private fun killApp(packageName: String) {
        try {
            // روش 1: کشتن فرآیند پس‌زمینه
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(packageName)
            
            // روش 2: استفاده از دکمه‌های سیستمی
            performGlobalAction(GLOBAL_ACTION_RECENTS)
            handler.postDelayed({
                performGlobalAction(GLOBAL_ACTION_HOME)
            }, 300)
            
            Log.d(TAG, "تلاش برای کشتن برنامه: $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "خطا در کشتن برنامه: ${e.message}")
        }
    }
    
    // برنامه‌ریزی بررسی‌های مکرر برای اطمینان از قفل ماندن برنامه
    private fun scheduleLockedAppChecks(packageName: String) {
        // بررسی اول پس از 2 ثانیه
        handler.postDelayed({
            checkLockedAppStatus(packageName)
        }, 2000)
        
        // بررسی دوم پس از 5 ثانیه
        handler.postDelayed({
            checkLockedAppStatus(packageName)
        }, 5000)
        
        // بررسی سوم پس از 10 ثانیه
        handler.postDelayed({
            checkLockedAppStatus(packageName)
        }, 10000)
    }
    
    // بررسی وضعیت برنامه قفل شده
    private fun checkLockedAppStatus(packageName: String) {
        val currentApp = getCurrentForegroundPackage()
        if (currentApp == packageName) {
            Log.d(TAG, "⚠️🔒⚠️ برنامه قفل شده همچنان در حال اجراست! اقدام مجدد...")
            
            // تلاش مجدد برای بستن برنامه
            performGoHomeAction()
            
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.killBackgroundProcesses(packageName)
            } catch (e: Exception) {}
            
            // نمایش مجدد صفحه قفل
            handler.postDelayed({
                showLockScreen(packageName)
            }, 300)
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
                                now - 30 * 1000, // 30 seconds ago
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
    
    private fun checkAndLockApp(packageName: String) {
        // Skip system packages and non-tracked apps
        if (packageName.isEmpty() || packageName == "android" || 
            packageName.startsWith("com.android") || !isAppTracked(packageName)) {
            return
        }
        
        try {
            // First check if app is already locked - اگر برنامه قفل باشد باید بلافاصله بسته شود
            if (isAppLocked(packageName)) {
                Log.d(TAG, "🔥 $packageName is locked, enforcing closure and returning to home")
                
                // Multiple attempts to enforce the closure
                performGoHomeAction()
                forceCloseApp(packageName)
                
                // Show lock screen with slight delay to ensure home action completes first
                handler.postDelayed({
                    showLockScreen(packageName)
                    
                    // Double-check after a short delay that we're not still in the app
                    handler.postDelayed({
                        val current = getCurrentForegroundPackage()
                        if (current == packageName) {
                            Log.d(TAG, "⚠️ App still in foreground after lock attempt, trying again: $packageName")
                            performGoHomeAction()
                            forceCloseApp(packageName)
                        }
                    }, 500)
                }, 200)
                
                return
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
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in checkAndLockApp", e)
        }
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
            val lockedApps = JSONArray(lockedAppsJson ?: "[]")
            
            // Check if already locked to avoid duplicates
            for (i in 0 until lockedApps.length()) {
                if (lockedApps.getString(i) == packageName) {
                    // Already locked - make sure it's closed if in foreground
                    Log.d(TAG, "App already locked: $packageName - enforcing closure")
                    if (packageName == lastForegroundPackage || getCurrentForegroundPackage() == packageName) {
                        performGoHomeAction()
                        forceCloseApp(packageName)
                        showLockScreen(packageName)
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
            
            // If app is in foreground, force it to close
            if (packageName == lastForegroundPackage || getCurrentForegroundPackage() == packageName) {
                Log.d(TAG, "Locked app is in foreground, forcing closure")
                performGoHomeAction()
                forceCloseApp(packageName)
            
                // نمایش مجدد صفحه قفل
                handler.postDelayed({
                    showLockScreen(packageName)
                }, 500)
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
            
            // قبل از نمایش صفحه قفل، مطمئن شو که برنامه هدف بسته شده است
            handler.post {
                // بستن برنامه هدف با چندین روش
                killApp(packageName)
                
                // انجام اقدامات متعدد برای بستن برنامه
                performGoHomeAction()
                
                // کمی صبر کن و دوباره بررسی کن
                handler.postDelayed({
                    // بررسی مجدد اینکه آیا برنامه واقعاً در حال اجراست
                    val currentApp = getCurrentForegroundPackage()
                    
                    // اگر هنوز در حال اجراست، دوباره تلاش کن
                    if (currentApp == packageName) {
                        killApp(packageName)
                        performGoHomeAction()
                    }
                    
                    // ابتدا به هوم بازگرد، بدون توجه به اینکه برنامه در حال اجراست یا خیر
                    performGoHomeAction()
                    
                    // کمی صبر کن تا هوم نمایش داده شود
                    handler.postDelayed({
                        // نمایش اکتیویتی قفل
                        try {
                            val lockIntent = Intent(this, AppLockOverlayActivity::class.java).apply {
                                // استفاده از فلگ‌های مناسب برای نمایش روی همه صفحات
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                                
                                // ارسال اطلاعات به اکتیویتی
                                putExtra("packageName", packageName)
                                putExtra("appName", appName)
                                putExtra("timeUsed", timeUsedMinutes)
                                putExtra("timeLimit", timeLimitMinutes)
                                putExtra("forceLock", true)  // فلگ جدید برای اعمال قفل اجباری
                            }
                            
                            // چک کن که اگر برنامه خودمان است، از تداخل جلوگیری کن
                            if (currentApp != this.packageName) {
                                startActivity(lockIntent)
                                
                                // نمایش توست برای اطلاع‌رسانی
                                handler.post {
                                    try {
                                        Toast.makeText(
                                            applicationContext,
                                            "$appName قفل شده است - دسترسی وجود ندارد",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } catch (e: Exception) {
                                        // خطای توست را نادیده بگیر
                                    }
                                }
                                
                                // یک بررسی نهایی برای اطمینان از بسته شدن برنامه
                                handler.postDelayed({
                                    if (getCurrentForegroundPackage() == packageName) {
                                        Log.d(TAG, "⚠️⚠️⚠️ برنامه همچنان باز است، نیاز به اقدام جدی‌تر!")
                                        
                                        // تلاش مجدد با اینتنت HOME قوی‌تر
                                        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                            addCategory(Intent.CATEGORY_HOME)
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                        }
                                        startActivity(homeIntent)
                                        
                                        // بسته شدن اجباری
                                        try {
                                            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                                            am.killBackgroundProcesses(packageName)
                                        } catch (e: Exception) {}
                                        
                                        // تلاش برای بستن برنامه از طریق دکمه‌های اخیر
                                        try {
                                            performGlobalAction(GLOBAL_ACTION_RECENTS)
                                            handler.postDelayed({
                                                performGlobalAction(GLOBAL_ACTION_HOME)
                                            }, 300)
                                        } catch (e: Exception) {}
                                        
                                        // نمایش مجدد صفحه قفل
                                        handler.postDelayed({
                                            startActivity(lockIntent)
                                        }, 500)
                                    }
                                }, 1000)
                            }
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
                        }
                    }, 300)
                }, 300)
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطای کلی در نمایش صفحه قفل", e)
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
            // Method 1: Most direct way, but requires permissions
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(packageName)
            
            // Method 2: Force the user back to home screen
            performGoHomeAction()
            
            // Method 3: Use accessibility action to go back multiple times rapidly
            // This can help exit from deep navigation within apps
            for (i in 0 until 3) {
                handler.postDelayed({
                    try {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    } catch (e: Exception) {
                        // Ignore
                    }
                }, i * 100L)
            }
            
            // Method 4: Try to use recents menu and then home as a final measure
            handler.postDelayed({
                try {
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                    handler.postDelayed({
                        performGlobalAction(GLOBAL_ACTION_HOME)
                    }, 200)
                } catch (e: Exception) {
                    // Ignore
                }
            }, 300)
            
            Log.d(TAG, "Multiple methods used to force close: $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Error force-closing app", e)
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
    
    // متد مخصوص برای بررسی اینکه آیا برنامه خاصی در حال اجراست
    private fun checkIfAppIsRunning(packageName: String): Boolean {
        try {
            // روش 1: بررسی لیست پروسس‌های در حال اجرا
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val runningProcesses = am.runningAppProcesses
            
            for (processInfo in runningProcesses) {
                if (processInfo.processName == packageName || processInfo.pkgList.contains(packageName)) {
                    Log.d(TAG, "✓ برنامه $packageName در لیست پروسس‌های در حال اجرا یافت شد")
                    return true
                }
            }
            
            // روش 2: بررسی تسک‌های در حال اجرا
            val tasks = am.getRunningTasks(10)
            for (task in tasks) {
                if (task.topActivity?.packageName == packageName) {
                    Log.d(TAG, "✓ برنامه $packageName در تسک‌های در حال اجرا یافت شد")
                    return true
                }
            }
            
            // روش 3: بررسی آمار استفاده اخیر (در 15 ثانیه اخیر)
            if (usageManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val now = System.currentTimeMillis()
                
                // بررسی آمار استفاده
                val stats = usageManager!!.queryUsageStats(
                    android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                    now - 15 * 1000, // 15 seconds ago
                    now
                )
                
                for (stat in stats) {
                    if (stat.packageName == packageName && stat.lastTimeUsed > now - 15000) {
                        Log.d(TAG, "✓ برنامه $packageName در 15 ثانیه اخیر استفاده شده")
                        return true
                    }
                }
                
                // بررسی رویدادهای اخیر
                val events = usageManager!!.queryEvents(now - 10000, now)
                val event = android.app.usage.UsageEvents.Event()
                
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.packageName == packageName && 
                        event.timeStamp > now - 10000 && 
                        event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                        
                        Log.d(TAG, "✓ برنامه $packageName در 10 ثانیه اخیر به فورگراند رفته")
                        return true
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "خطا در بررسی وضعیت اجرای برنامه: ${e.message}")
        }
        
        return false
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
} 