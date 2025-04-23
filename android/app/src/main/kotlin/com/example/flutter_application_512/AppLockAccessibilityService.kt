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
                
                // If time used exceeds limit, lock the app
                if (totalUsageTime >= limitMs) {
                    // Lock only if not already locked
                    if (!isAppLocked(packageName)) {
                        Log.d(TAG, "🔒 Time limit exceeded for $packageName: ${totalUsageTime/1000}s >= ${limitMs/1000}s")
                        
                        // Update usage in shared prefs before locking
                        currentDayUsage[packageName] = totalUsageTime
                        saveUsageData()
                        
                        // Lock the app - با تاکید بیشتر
                        lockApp(packageName)
                        
                        // If currently in foreground, go home immediately and forcefully
                        if (packageName == lastForegroundPackage) {
                            // Double-check that we're going home successfully
                            performGoHomeAction()
                            
                            // Try multiple approaches to ensure app closes
                            forceCloseApp(packageName)
                            
                            // Also show the lock screen after a short delay
                            handler.postDelayed({
                                showLockScreen(packageName)
                            }, 200)
                        }
                    } else {
                        // App is already locked, but make sure it's not in foreground
                        if (packageName == lastForegroundPackage) {
                            Log.d(TAG, "App already locked but still in foreground: $packageName")
                            performGoHomeAction()
                            forceCloseApp(packageName)
                        }
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
        // Method 1: Use our tracked foreground package
        if (lastForegroundPackage.isNotEmpty() && 
            lastForegroundPackage != "android" && 
            !lastForegroundPackage.startsWith("com.android")) {
            return lastForegroundPackage
        }
        
        // Method 2: Try to get from UsageStats
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                val usm = usageManager ?: return null
                val time = System.currentTimeMillis()
                val events = usm.queryEvents(time - 5000, time)
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
                    lastEventPackageName != "android" && 
                    !lastEventPackageName.startsWith("com.android")) {
                    return lastEventPackageName
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting foreground app from UsageStats", e)
            }
        }
        
        // Method 3: Try using ActivityManager (less reliable on newer Android versions)
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val tasks = am.getRunningTasks(1)
            if (tasks.isNotEmpty() && tasks[0].topActivity != null) {
                val packageName = tasks[0].topActivity!!.packageName
                if (packageName != "android" && !packageName.startsWith("com.android")) {
                    return packageName
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting foreground app from ActivityManager", e)
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
                        if (getCurrentForegroundPackage() == packageName) {
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
                
                // Show lock screen
                handler.postDelayed({
                    showLockScreen(packageName)
                }, 200)
                
                // Perform a second check after a delay to make sure app is really closed
                handler.postDelayed({
                    if (getCurrentForegroundPackage() == packageName) {
                        Log.d(TAG, "App still in foreground after locking, forcing closure again")
                        performGoHomeAction()
                        forceCloseApp(packageName)
                        showLockScreen(packageName)
                    }
                }, 800)
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
            
            // Ensure we're not in our own app before showing lock screen
            val currentApp = getCurrentForegroundPackage()
            if (currentApp == packageName) {
                // First send the user home
                performGoHomeAction()
                
                // Wait a moment before showing overlay
                handler.postDelayed({
                    // Launch overlay activity
                    val lockIntent = Intent(this, AppLockOverlayActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION
                        putExtra("packageName", packageName)
                        putExtra("appName", appName)
                        putExtra("timeUsed", timeUsedMinutes)
                        putExtra("timeLimit", timeLimitMinutes)
                    }
                    startActivity(lockIntent)
                    
                    // Extra safety check - make sure app is really closed
                    handler.postDelayed({
                        val appStillForeground = getCurrentForegroundPackage() == packageName
                        if (appStillForeground) {
                            Log.d(TAG, "App still detected after showing lock screen, forcing home again")
                            performGoHomeAction()
                            forceCloseApp(packageName)
                            
                            // Try showing lock screen again
                            handler.postDelayed({
                                startActivity(lockIntent)
                            }, 200)
                        }
                    }, 500)
                }, 200)
            } else {
                // App is not in foreground, still show lock screen for awareness
                val lockIntent = Intent(this, AppLockOverlayActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra("packageName", packageName)
                    putExtra("appName", appName)
                    putExtra("timeUsed", timeUsedMinutes)
                    putExtra("timeLimit", timeLimitMinutes)
                }
                startActivity(lockIntent)
            }
            
            Log.d(TAG, "Lock screen shown for $packageName (${appName})")
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
                    
                    // Show lock screen with delay to ensure we're back at home
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