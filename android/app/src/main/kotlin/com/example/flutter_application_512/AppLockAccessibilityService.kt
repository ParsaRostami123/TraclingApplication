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
    
    companion object {
        const val PREFS_NAME = "AppLockPrefs"
        const val TRACKING_APPS_KEY = "tracking_apps"
        const val TIME_LIMITS_KEY = "time_limits"
        const val APP_USAGE_DATA_KEY = "app_usage_data"
        const val LOCKED_APPS_KEY = "locked_apps"
        const val SERVICE_RESTART_ACTION = "com.example.flutter_application_512.RESTART_SERVICE"
        
        // Reduced interval for more responsive locking
        private const val CHECK_INTERVAL = 500L // Check every 500ms
        
        // Static flag to track service running state
        var isServiceRunning = false
        
        // DEBUG flag - should be disabled in production
        private const val DEBUG = true
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service connected")
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
        
        // Show toast only in debug mode
        if (DEBUG) {
            Toast.makeText(this, "App Lock Service Started", Toast.LENGTH_SHORT).show()
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
        appLockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    "com.example.flutter_application_512.APP_LOCKED" -> {
                        val packageName = intent.getStringExtra("packageName")
                        Log.d(TAG, "Received lock notification for: $packageName")
                        
                        // If this app is currently in foreground, go home
                        if (packageName == lastForegroundPackage) {
                            performGoHomeAction()
                        }
                    }
                    "com.example.flutter_application_512.APP_UNLOCKED" -> {
                        val packageName = intent.getStringExtra("packageName")
                        Log.d(TAG, "Received unlock notification for: $packageName")
                    }
                    SERVICE_RESTART_ACTION -> {
                        Log.d(TAG, "Received service restart request")
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
                handler.postDelayed(runnable!!, CHECK_INTERVAL)
            }
            handler.post(runnable!!)
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
                if (lastForegroundPackage != packageName) {
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
            // Verify we have a recent event (within last 500ms) to ensure service is working
            val now = System.currentTimeMillis()
            val timeSinceLastEvent = now - lastEventTime
            
            // If app is actively being used, update time
            if (lastForegroundPackage.isNotEmpty() && isAppTracked(lastForegroundPackage)) {
                // Only count time if the service is actively detecting events and screen is on
                if (timeSinceLastEvent < 500 && isScreenOn()) {
                    currentForegroundTime += CHECK_INTERVAL
                    
                    // Get current usage including current session
                    val totalUsageTime = currentDayUsage.getOrDefault(lastForegroundPackage, 0L) + currentForegroundTime

                    // Log usage time more frequently for apps close to their limits (Telegram fix)
                    if (totalUsageTime % 5000 < CHECK_INTERVAL) {
                        Log.d(TAG, "⏱️ App usage: $lastForegroundPackage - ${totalUsageTime/1000}s (session: ${currentForegroundTime/1000}s)")
                    }
                    
                    // Check for time limit exceeded
                    checkTimeLimitExceeded(lastForegroundPackage, totalUsageTime)
                }
            } else {
                // Check if we're actually in another app that's not being detected through normal events
                // This helps fix issues with apps like Telegram that might be missed
                verifyForegroundAppWithUsageStats()
            }
            
            // Periodically update heartbeat (every 30 seconds)
            if (now % 30000 < CHECK_INTERVAL) {
                updateHeartbeat()
            }
            
            // Save usage data more frequently (every 30 seconds)
            if (now % 30000 < CHECK_INTERVAL) {
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
                
                // If time used exceeds limit, lock the app
                if (totalUsageTime >= limitMs) {
                    // Lock only if not already locked
                    if (!isAppLocked(packageName)) {
                        Log.d(TAG, "🔒 Time limit exceeded for $packageName: ${totalUsageTime/1000}s >= ${limitMs/1000}s")
                        
                        // Update usage in shared prefs before locking
                        currentDayUsage[packageName] = totalUsageTime
                        saveUsageData()
                        
                        // Lock the app
                        lockApp(packageName)
                        
                        // If currently in foreground, go home
                        if (packageName == lastForegroundPackage) {
                            // Double-check that we're going home successfully
                            performGoHomeAction()
                            
                            // Also try closing recent apps as a backup
                            handler.postDelayed({
                                try {
                                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                                    handler.postDelayed({
                                        performGlobalAction(GLOBAL_ACTION_HOME)
                                    }, 300)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error performing recents action", e)
                                }
                            }, 500)
                        }
                    }
                }
                // Alert when approaching limit (90%)
                else if (totalUsageTime >= limitMs * 0.9 && 
                         totalUsageTime < limitMs && 
                         totalUsageTime % 30000 < CHECK_INTERVAL) {
                    // Show a warning every 30 seconds when close to limit
                    val timeLeftSeconds = (limitMs - totalUsageTime) / 1000
                    Log.d(TAG, "⚠️ Approaching time limit for $packageName: ${timeLeftSeconds}s left")
                    
                    // Show toast notification
                    if (packageName == lastForegroundPackage) {
                        handler.post {
                            try {
                                val appName = getAppName(packageName)
                                Toast.makeText(
                                    this, 
                                    "$appName: ${timeLeftSeconds}s از زمان مجاز باقی مانده", 
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
    
    private fun checkAndLockApp(packageName: String) {
        // Skip system packages and non-tracked apps
        if (packageName.isEmpty() || packageName == "android" || 
            packageName.startsWith("com.android") || !isAppTracked(packageName)) {
            return
        }
        
        try {
            // First check if app is already locked
            if (isAppLocked(packageName)) {
                Log.d(TAG, "$packageName is locked, returning to home")
                performGoHomeAction()
                
                // Show lock screen
                showLockScreen(packageName)
                return
            }
            
            // Then check time limits
            val timeLimitsJson = prefs.getString(TIME_LIMITS_KEY, "{}")
            val timeLimits = JSONObject(timeLimitsJson ?: "{}")
            
            if (timeLimits.has(packageName)) {
                val limitMinutes = timeLimits.getLong(packageName)
                val limitMs = limitMinutes * 60 * 1000
                
                // Get current usage from our tracking map
                val usageTime = currentDayUsage.getOrDefault(packageName, 0L)
                
                // If already over limit, lock app
                if (usageTime >= limitMs) {
                    Log.d(TAG, "Time limit already exceeded for $packageName")
                    lockApp(packageName)
                    performGoHomeAction()
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
                    // Already locked
                    return
                }
            }
            
            // Add to locked apps
            lockedApps.put(packageName)
            prefs.edit().putString(LOCKED_APPS_KEY, lockedApps.toString()).apply()
            
            // Broadcast that app is locked
            val intent = Intent("com.example.flutter_application_512.APP_LOCKED").apply {
                putExtra("packageName", packageName)
            }
            sendBroadcast(intent)
            
            Log.d(TAG, "🔒 Locked app: $packageName")
            
            // Show notification to user
            handler.post {
                try {
                    val appName = getAppName(packageName)
                    Toast.makeText(this, "زمان استفاده از $appName به پایان رسید", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    // Ignore errors in toast display
                }
            }
            
            // Special handling for problematic apps (e.g., Telegram)
            if (packageName == "org.telegram.messenger" || 
                packageName == "com.whatsapp" || 
                packageName == "com.instagram.android") {
                
                // Force-close recent apps and return to home
                handler.postDelayed({
                    try {
                        performGlobalAction(GLOBAL_ACTION_RECENTS)
                        handler.postDelayed({
                            performGlobalAction(GLOBAL_ACTION_HOME)
                        }, 300)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error forcing app closure", e)
                    }
                }, 300)
            }
            
            // نمایش صفحه قفل با استفاده از متد showLockScreen
            showLockScreen(packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Error locking app", e)
        }
    }
    
    private fun showLockScreen(packageName: String) {
        try {
            if (AppLockOverlayActivity.isLockScreenShowing) {
                Log.d(TAG, "Lock screen is already showing, not showing again for $packageName")
                return
            }
            
            // اطلاعات برنامه را دریافت می‌کنیم
            val appName = getAppName(packageName)
            
            // دریافت اطلاعات زمان استفاده و محدودیت
            val timeLimitsJson = prefs.getString(TIME_LIMITS_KEY, "{}")
            val timeLimits = JSONObject(timeLimitsJson ?: "{}")
            
            var timeLimitMinutes = 0L
            if (timeLimits.has(packageName)) {
                timeLimitMinutes = timeLimits.getLong(packageName)
            }
            
            val usageMs = currentDayUsage.getOrDefault(packageName, 0L)
            val usageMinutes = usageMs / (60 * 1000)
            
            // ایجاد Intent برای نمایش Activity قفل
            val lockIntent = Intent(this, AppLockOverlayActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_HISTORY
                putExtra(AppLockOverlayActivity.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AppLockOverlayActivity.EXTRA_APP_NAME, appName)
                putExtra(AppLockOverlayActivity.EXTRA_TIME_USED, usageMinutes)
                putExtra(AppLockOverlayActivity.EXTRA_TIME_LIMIT, timeLimitMinutes)
            }
            
            // نمایش اکتیویتی
            startActivity(lockIntent)
            
            Log.d(TAG, "Showing lock screen for $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing lock screen", e)
        }
    }
    
    private fun performGoHomeAction() {
        try {
            // More reliable home action implementation
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
            
            // As fallback also try the traditional approach
            performGlobalAction(GLOBAL_ACTION_HOME)
            
            // For problematic apps, try to use recents menu
            handler.postDelayed({
                try {
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                    handler.postDelayed({
                        performGlobalAction(GLOBAL_ACTION_HOME)
                    }, 200)
                } catch (e: Exception) {
                    // Ignore errors
                }
            }, 300)
            
            Log.d(TAG, "Performed go home action")
        } catch (e: Exception) {
            Log.e(TAG, "Error performing go home action", e)
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