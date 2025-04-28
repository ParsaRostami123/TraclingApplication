package com.example.flutter_application_512

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.app.usage.UsageEvents
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.annotation.NonNull
import java.io.ByteArrayOutputStream
import java.util.*
import kotlin.collections.HashMap
import android.content.pm.PackageInfo
import android.os.PowerManager
import android.os.SystemClock
import android.os.BatteryManager
import java.util.Calendar
import android.app.usage.UsageStats
import org.json.JSONObject
import android.net.Uri
import android.os.Bundle
import android.app.AlarmManager
import android.app.PendingIntent
import org.json.JSONArray
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result

class MainActivity: FlutterActivity() {
    private val CHANNEL = "com.example.flutter_application_512/usage_stats"
    private val TAG = "AppUsageTracker"
    private lateinit var prefs: android.content.SharedPreferences
    
    companion object {
        const val ACTION_UNLOCK_APP = "com.example.flutter_application_512.UNLOCK_APP"
    }

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler {
            call, result ->
            when (call.method) {
                "openUsageAccessSettings" -> {
                    Log.d(TAG, "Opening usage access settings")
                    openUsageAccessSettings()
                    result.success(true)
                }
                "getInstalledApps" -> {
                    try {
                        Log.d(TAG, "Getting installed apps")
                        val apps = getInstalledApps()
                        Log.d(TAG, "Found ${apps.size} installed apps")
                        result.success(apps)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error getting installed apps", e)
                        result.error("ERROR", "Failed to get installed apps", e.message)
                    }
                }
                "getAppUsageStats" -> {
                    val startTime = call.argument<Long>("startTime") ?: 0
                    val endTime = call.argument<Long>("endTime") ?: System.currentTimeMillis()
                    
                    if (hasUsageStatsPermission()) {
                        val usageStats = getAppUsageStats(startTime, endTime)
                        result.success(usageStats)
                    } else {
                        result.error("PERMISSION_DENIED", "Usage stats permission not granted", null)
                    }
                }
                "hasUsageStatsPermission" -> {
                    val hasPermission = hasUsageStatsPermission()
                    Log.d(TAG, "Checking usage stats permission: $hasPermission")
                    result.success(hasPermission)
                }
                "getScreenOnTime" -> {
                    try {
                        val startTime = call.argument<Long>("startTime") ?: 0
                        val endTime = call.argument<Long>("endTime") ?: System.currentTimeMillis()
                        
                        val screenOnTime = getScreenOnTime(startTime, endTime)
                        Log.d(TAG, "Returning screen on time to Flutter: $screenOnTime ms")
                        result.success(screenOnTime)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error getting screen on time", e)
                        result.error("UNAVAILABLE", "Error getting screen on time: ${e.message}", null)
                    }
                }
                "getCurrentForegroundApp" -> {
                    if (hasUsageStatsPermission()) {
                        val currentApp = getCurrentForegroundApp()
                        result.success(currentApp)
                    } else {
                        result.error("PERMISSION_DENIED", "Usage stats permission not granted", null)
                    }
                }
                "returnToHomeScreen" -> {
                    returnToHomeScreen()
                    result.success(true)
                }
                "setAppTimeLimit" -> {
                    setAppTimeLimit(call, result)
                }
                "removeAppTimeLimit" -> {
                    val packageName = call.argument<String>("packageName")
                    
                    if (packageName != null) {
                        removeAppTimeLimit(packageName)
                        result.success(true)
                    } else {
                        result.error("INVALID_ARGUMENTS", "Package name is required", null)
                    }
                }
                "startMonitoringService" -> {
                    startMonitoringService()
                    result.success(true)
                }
                "stopMonitoringService" -> {
                    stopMonitoringService()
                    result.success(true)
                }
                "checkAccessibilityServiceEnabled" -> {
                    result.success(isAccessibilityServiceEnabled())
                }
                "openAccessibilitySettings" -> {
                    openAccessibilitySettings()
                    result.success(true)
                }
                "checkOverlayPermission" -> {
                    result.success(hasOverlayPermission())
                }
                "openOverlaySettings" -> {
                    openOverlaySettings()
                    result.success(true)
                }
                "requestBatteryOptimizationPermission" -> {
                    requestBatteryOptimizationPermission()
                    result.success(true)
                }
                "checkBatteryOptimizationPermission" -> {
                    val isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations()
                    result.success(isIgnoringBatteryOptimizations)
                }
                "resetAppUsageData" -> {
                    try {
                        resetAppUsageData()
                        Log.d(TAG, "All app usage data reset successfully")
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error resetting app usage data", e)
                        result.error("ERROR", "Failed to reset app usage data", e.message)
                    }
                }
                "checkAccessibilityServiceStatus" -> {
                    val status = checkAndVerifyAccessibilityService()
                    result.success(status)
                }
                "ensureAccessibilityServiceRunning" -> {
                    val success = ensureAccessibilityServiceRunning()
                    result.success(success)
                }
                "ensureServiceRunning" -> {
                    try {
                        val serviceActive = isServiceRunning()
                        Log.d(TAG, "Checking if service is running: $serviceActive")
                        
                        if (!serviceActive) {
                            Log.d(TAG, "Service not running, restarting...")
                            startMonitoringService()
                        }
                        
                        // همچنین سرویس Accessibility را بررسی کن
                        val accessibilityActive = isAccessibilityServiceEnabled()
                        if (accessibilityActive) {
                            ensureAccessibilityServiceRunning()
                        }
                        
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error ensuring service is running", e)
                        result.error("ERROR", "Failed to ensure service is running", e.message)
                    }
                }
                "setAppLockStatus" -> {
                    val packageName = call.argument<String>("packageName")
                    val isLocked = call.argument<Boolean>("isLocked") ?: false
                    if (packageName != null) {
                        setAppLockStatus(packageName, isLocked, result)
                    } else {
                        result.error("INVALID_ARGUMENTS", "Package name is required", null)
                    }
                }
                "testAppLock" -> {
                    val packageName = call.argument<String>("packageName")
                    val testType = call.argument<String>("testType") ?: "full"
                    
                    if (packageName != null) {
                        testAppLock(packageName, testType)
                        result.success(true)
                    } else {
                        result.error("INVALID_ARGUMENTS", "Package name is required", null)
                    }
                }
                "unlockApp" -> {
                    val packageName = call.argument<String>("packageName")
                    if (packageName != null) {
                        unlockApp(packageName, result)
                    } else {
                        result.error("INVALID_ARGUMENTS", "Package name is required", null)
                    }
                }
                "enforceAppLocks" -> {
                    try {
                        enforceAllAppLocks()
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error enforcing app locks", e)
                        result.error("ERROR", "Failed to enforce app locks", e.message)
                    }
                }
                "forceAppLock" -> {
                    val packageName = call.argument<String>("packageName")
                    if (packageName != null) {
                        forceAppLockDirect(packageName)
                        result.success(true)
                    } else {
                        result.error("INVALID_ARGUMENTS", "Package name is required", null)
                    }
                }
                else -> {
                    result.notImplemented()
                }
            }
        }
        
        // Verify accessibility service status on app start
        checkAndVerifyAccessibilityService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        prefs = applicationContext.getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
        
        // بررسی درخواست‌های باز کردن قفل
        handleIntent(intent)
        
        // تلاش برای شروع سرویس foreground برای حفظ پایداری
        try {
            val serviceIntent = Intent(this, AppLockForegroundService::class.java)
            serviceIntent.action = AppLockForegroundService.ACTION_START_SERVICE
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.d(TAG, "Started foreground service from MainActivity")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service", e)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }
    
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_UNLOCK_APP) {
            val packageName = intent.getStringExtra("packageName")
            if (packageName != null) {
                Log.d(TAG, "Received unlock request for: $packageName")
                unlockApp(packageName, null)
            }
        }
    }

    private fun openUsageAccessSettings() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        startActivity(intent)
    }
    
    private fun hasUsageStatsPermission(): Boolean {
        try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val time = System.currentTimeMillis()
            // Query for a small time range to check if we have permission
            val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000*60*60, time)
            return stats.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking usage stats permission", e)
            return false
        }
    }

    private fun getInstalledApps(): List<Map<String, Any>> {
        val pm = context.packageManager
        val apps = ArrayList<Map<String, Any>>()
        var count = 0
        
        try {
            // Use a simpler approach - get all apps with LAUNCHER category
            val intent = Intent(Intent.ACTION_MAIN, null)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            
            val resolveInfoList = pm.queryIntentActivities(intent, 0)
            Log.d(TAG, "Total launcher apps found: ${resolveInfoList.size}")
            
            for (resolveInfo in resolveInfoList) {
                try {
                    val packageName = resolveInfo.activityInfo.packageName
                    val appInfo = pm.getApplicationInfo(packageName, 0)
                    val appName = pm.getApplicationLabel(appInfo).toString()
                    val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    
                    // Skip system apps if needed
                    // if (isSystemApp) continue
                    
                    // Get app icon as Base64 string (with error handling)
                    val icon = appInfo.loadIcon(pm)
                    val iconBase64 = try {
                        drawableToBase64(icon)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to convert icon to base64 for $packageName", e)
                        "" // Empty string if conversion fails
                    }
                    
                    val appMap = HashMap<String, Any>()
                    appMap["appName"] = appName
                    appMap["packageName"] = packageName
                    appMap["isSystemApp"] = isSystemApp
                    appMap["icon"] = iconBase64
                    
                    apps.add(appMap)
                    count++
                    
                    if (count % 10 == 0) {
                        Log.d(TAG, "Processed $count apps so far")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing app: ${resolveInfo.activityInfo?.packageName}", e)
                    // Continue with next app
                }
            }
            
            Log.d(TAG, "Successfully processed $count apps total")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting installed apps", e)
        }
        
        return apps
    }

    private fun drawableToBase64(drawable: Drawable): String {
        try {
            // Use fixed size for icons to avoid massive data transfer
            val width = 96
            val height = 96
            
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
            
            val byteArrayOutputStream = ByteArrayOutputStream()
            // Use lower quality to reduce data size
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, byteArrayOutputStream)
            val byteArray = byteArrayOutputStream.toByteArray()
            
            return Base64.encodeToString(byteArray, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Error converting drawable to base64", e)
            return ""
        }
    }

    private fun getAppUsageStats(startTimeMillis: Long, endTimeMillis: Long): Map<String, Long> {
        val appUsageMap = HashMap<String, Long>()
        
        try {
            Log.d(TAG, "Getting app usage stats from ${formatTime(startTimeMillis)} to ${formatTime(endTimeMillis)}")
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            
            // روش پیشرفته برای ردیابی دقیق‌تر زمان استفاده
            val usageEvents = usageStatsManager.queryEvents(startTimeMillis, endTimeMillis)
            val event = UsageEvents.Event()
            
            // نقشه برای ذخیره زمان‌های فورگراند و آخرین فعالیت هر برنامه
            val foregroundTimestamps = HashMap<String, Long>()
            val lastActiveTimestamps = HashMap<String, Long>()
            val usageTimes = HashMap<String, Long>()
            val usageSegments = HashMap<String, MutableList<Pair<Long, Long>>>() // برای ذخیره بازه‌های زمانی استفاده
            
            // ردیابی زمان غیرفعال شدن صفحه‌نمایش
            var lastScreenOffTime = 0L
            
            // پردازش تمام رویدادها
            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                val packageName = event.packageName
                val timestamp = event.timeStamp
                
                // رویدادهای مهم صفحه‌نمایش
                if (event.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE) {
                    lastScreenOffTime = timestamp
                    Log.d(TAG, "Screen turned OFF at ${formatTime(timestamp)}")
                    
                    // در زمان خاموش شدن صفحه، هر برنامه‌ای که در فورگراند است باید پایان یابد
                    foregroundTimestamps.forEach { (pkg, startTime) ->
                        val usageTime = timestamp - startTime
                        addUsageTime(usageTimes, pkg, usageTime)
                        
                        // ثبت بازه زمانی
                        usageSegments.getOrPut(pkg) { mutableListOf() }.add(Pair(startTime, timestamp))
                        Log.d(TAG, "App $pkg usage ended at screen off: ${usageTime/1000}s")
                    }
                    foregroundTimestamps.clear()
                    
                } else if (event.eventType == UsageEvents.Event.SCREEN_INTERACTIVE) {
                    Log.d(TAG, "Screen turned ON at ${formatTime(timestamp)}")
                    lastScreenOffTime = 0L
                    
                } else if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    if (lastScreenOffTime == 0L) { // اگر صفحه روشن است
                        // ثبت زمان شروع استفاده
                        foregroundTimestamps[packageName] = timestamp
                        lastActiveTimestamps[packageName] = timestamp
                        Log.d(TAG, "App $packageName moved to foreground at ${formatTime(timestamp)}")
                    }
                    
                } else if (event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                    // محاسبه زمان استفاده اگر زمان شروع داریم
                    val foregroundTime = foregroundTimestamps.remove(packageName)
                    if (foregroundTime != null) {
                        val usageTime = timestamp - foregroundTime
                        addUsageTime(usageTimes, packageName, usageTime)
                        
                        // ثبت بازه زمانی
                        usageSegments.getOrPut(packageName) { mutableListOf() }.add(Pair(foregroundTime, timestamp))
                        
                        Log.d(TAG, "App $packageName usage segment: ${usageTime/1000}s (${formatTime(foregroundTime)} to ${formatTime(timestamp)})")
                    }
                }
                
                // به‌روزرسانی آخرین زمان فعالیت (برای رویدادهای تعاملی)
                if (event.eventType == UsageEvents.Event.USER_INTERACTION) {
                    lastActiveTimestamps[packageName] = timestamp
                }
            }
            
            // رسیدگی به برنامه‌هایی که هنوز در فورگراند هستند
            val currentTime = System.currentTimeMillis()
            for ((packageName, foregroundTime) in foregroundTimestamps) {
                if (lastScreenOffTime > 0L && lastScreenOffTime > foregroundTime) {
                    // اگر صفحه خاموش شده، تا زمان خاموش شدن حساب کن
                    val usageTime = lastScreenOffTime - foregroundTime
                    addUsageTime(usageTimes, packageName, usageTime)
                    usageSegments.getOrPut(packageName) { mutableListOf() }.add(Pair(foregroundTime, lastScreenOffTime))
                    Log.d(TAG, "App $packageName usage until screen off: ${usageTime/1000}s")
                } else {
                    // اگر صفحه هنوز روشن است، تا الان حساب کن
                    val usageTime = currentTime - foregroundTime
                    addUsageTime(usageTimes, packageName, usageTime)
                    usageSegments.getOrPut(packageName) { mutableListOf() }.add(Pair(foregroundTime, currentTime))
                    Log.d(TAG, "App $packageName still in foreground: ${usageTime/1000}s")
                }
            }
            
            // انتقال به نقشه نهایی با زمان‌های استفاده مثبت
            for ((packageName, usageTime) in usageTimes) {
                if (usageTime > 0) {
                    // بررسی فعال بودن واقعی (با فیلتر کردن برنامه‌های پس‌زمینه)
                    if (isRealForegroundApp(packageName) && !shouldIgnoreApp(packageName)) {
                        appUsageMap[packageName] = usageTime
                        
                        // نمایش جزئیات بازه‌های زمانی استفاده
                        val segments = usageSegments[packageName] ?: emptyList()
                        val segmentInfo = segments.joinToString("\n  ") { 
                            "(${formatTime(it.first)} to ${formatTime(it.second)}, ${(it.second - it.first)/1000}s)" 
                        }
                        
                        Log.d(TAG, "Final usage for $packageName: ${usageTime/1000}s, segments:\n  $segmentInfo")
                    } else {
                        Log.d(TAG, "Filtered background app: $packageName with time ${usageTime/1000}s")
                    }
                }
            }
            
            // اگر نتیجه خالی بود، از روش سنتی استفاده کن
            if (appUsageMap.isEmpty()) {
                Log.d(TAG, "No event data found, using queryUsageStats as fallback")
                val stats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    startTimeMillis,
                    endTimeMillis
                )
                
                for (stat in stats) {
                    val packageName = stat.packageName
                    if (stat.totalTimeInForeground > 0 && isRealForegroundApp(packageName) && !shouldIgnoreApp(packageName)) {
                        appUsageMap[packageName] = stat.totalTimeInForeground
                        Log.d(TAG, "Fallback usage for $packageName: ${stat.totalTimeInForeground/1000}s")
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error getting app usage stats", e)
        }
        
        return appUsageMap
    }
    
    // ثبت زمان استفاده در نقشه
    private fun addUsageTime(usageTimes: HashMap<String, Long>, packageName: String, usageTime: Long) {
        if (usageTime <= 0) return
        val currentUsage = usageTimes[packageName] ?: 0L
        usageTimes[packageName] = currentUsage + usageTime
    }
    
    // بررسی اینکه آیا این برنامه واقعا یک برنامه پیش‌زمینه است یا سرویس سیستمی
    private fun isRealForegroundApp(packageName: String): Boolean {
        // فیلتر کردن سرویس‌های سیستمی و برنامه‌های پس‌زمینه
        return !packageName.startsWith("android.") && 
               !packageName.startsWith("com.android.") &&
               !packageName.startsWith("com.google.android.") &&
               packageName != "com.google.android.gms" &&
               packageName != "com.android.systemui" &&
               packageName != "com.example.flutter_application_512"
    }
    
    // برنامه‌هایی که باید نادیده گرفته شوند
    private fun shouldIgnoreApp(packageName: String): Boolean {
        val ignoredPackages = listOf(
            "com.android.settings",
            "com.android.systemui",
            "com.android.launcher3",
            "com.google.android.googlequicksearchbox",
            "com.android.vending"
        )
        return ignoredPackages.contains(packageName)
    }

    private fun getScreenOnTime(startTime: Long, endTime: Long): Long {
        try {
            Log.d(TAG, "Getting screen on time from $startTime to $endTime")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                
                // Get screen time statistics
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // Método mejorado para Android 9+ (API 28+)
                    val eventsQuery = usageStatsManager.queryEvents(startTime, endTime)
                    
                    var totalScreenOnTime = 0L
                    var screenOnTimestamp = 0L
                    
                    // Procesar los eventos en una sola pasada para mayor precisión
                    val event = UsageEvents.Event()
                    while (eventsQuery.hasNextEvent()) {
                        eventsQuery.getNextEvent(event)
                        
                        when (event.eventType) {
                            UsageEvents.Event.SCREEN_INTERACTIVE -> {
                                // Pantalla encendida - registrar timestamp
                                screenOnTimestamp = event.timeStamp
                                Log.d(TAG, "Screen turned ON at ${formatTime(screenOnTimestamp)}")
                            }
                            UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                                // Pantalla apagada - calcular duración si tenemos timestamp de encendido
                                if (screenOnTimestamp > 0L) {
                                    val duration = event.timeStamp - screenOnTimestamp
                                    totalScreenOnTime += duration
                                    Log.d(TAG, "Screen turned OFF at ${formatTime(event.timeStamp)}, duration: ${duration/1000} seconds")
                                    screenOnTimestamp = 0L
                                }
                            }
                        }
                    }
                    
                    // Si la pantalla sigue encendida al final del periodo
                    if (screenOnTimestamp > 0L) {
                        val duration = endTime - screenOnTimestamp
                        totalScreenOnTime += duration
                        Log.d(TAG, "Screen still ON at end of period, adding duration: ${duration/1000} seconds")
                    }
                    
                    Log.d(TAG, "Total screen on time: ${totalScreenOnTime/1000/60} minutes")
                    return totalScreenOnTime
                    
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    // Para Android 5.1+ a 8.1, usar estadísticas de configuración
                    val stats = usageStatsManager.queryUsageStats(
                        UsageStatsManager.INTERVAL_DAILY,
                        startTime,
                        endTime
                    )
                    
                    var totalScreenTime = 0L
                    
                    // Calcular tiempo total basado en estadísticas por app
                    for (stat in stats) {
                        // Sumar todos los tiempos de aplicaciones en primer plano
                        // Este es un enfoque aproximado pero más preciso que la estimación
                        totalScreenTime += stat.totalTimeInForeground
                    }
                    
                    // Ajustar por superposiciones (varias apps pueden estar "en primer plano" simultáneamente)
                    // Usar un factor de corrección basado en pruebas empíricas
                    val correctionFactor = 0.75 // Reducir 25% para compensar superposiciones
                    val adjustedTime = (totalScreenTime * correctionFactor).toLong()
                    
                    Log.d(TAG, "Adjusted screen time based on app usage: ${adjustedTime/1000/60} minutes")
                    return adjustedTime
                    
                } else {
                    // Para Android 5.0, usar una combinación de métodos
                    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                    
                    val isScreenOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                        powerManager.isInteractive
                    } else {
                        @Suppress("DEPRECATION")
                        powerManager.isScreenOn
                    }
                    
                    // Obtener nivel de batería y estadísticas
                    val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    val batteryDischargeAmount = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                    
                    Log.d(TAG, "Current battery level: $batteryLevel%, discharge amount: $batteryDischargeAmount")
                    
                    // Calcular una estimación basada en estadísticas de batería y hora del día
                    val timeRange = endTime - startTime
                    
                    // Calcular ratio base usando el nivel de batería
                    val batteryBasedRatio = when {
                        batteryLevel < 20 -> 0.85  // Batería muy baja, uso intenso probable
                        batteryLevel < 40 -> 0.75  // Uso alto
                        batteryLevel < 60 -> 0.65  // Uso medio
                        batteryLevel < 80 -> 0.55  // Uso bajo
                        else -> 0.45               // Uso muy bajo
                    }
                    
                    // Ajustar según hora del día para mayor precisión
                    val hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                    val timeBasedFactor = when {
                        hourOfDay in 0..5 -> 0.2    // Madrugada (poco uso)
                        hourOfDay in 6..8 -> 0.6    // Mañana temprano (despertar)
                        hourOfDay in 9..12 -> 0.7   // Mañana (trabajo/escuela)
                        hourOfDay in 13..15 -> 0.65 // Primeras horas de tarde
                        hourOfDay in 16..19 -> 0.75 // Tarde (mayor uso)
                        hourOfDay in 20..22 -> 0.85 // Noche (uso intenso)
                        else -> 0.5                 // Noche tardía
                    }
                    
                    // Combinar factores
                    val combinedRatio = (batteryBasedRatio * 0.7) + (timeBasedFactor * 0.3)
                    
                    // Ajustar si la pantalla está encendida actualmente
                    val finalRatio = if (isScreenOn) (combinedRatio + 0.1).coerceAtMost(0.95) else combinedRatio
                    
                    val estimatedScreenTime = (timeRange * finalRatio).toLong()
                    
                    Log.d(TAG, "Enhanced estimated screen time: ${estimatedScreenTime/1000/60} minutes (ratio: $finalRatio)")
                    return estimatedScreenTime
                }
            } else {
                Log.w(TAG, "Screen time tracking requires Android 5.0 (Lollipop) or higher")
                return 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting screen on time", e)
            return 0
        }
    }

    // Helper para formatear timestamps para depuración
    private fun formatTime(timestamp: Long): String {
        val date = Date(timestamp)
        val format = java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        return format.format(date)
    }

    private fun getCurrentForegroundApp(): String? {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                return null
            }
            
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            
            val time = System.currentTimeMillis()
            val events = usageStatsManager.queryEvents(time - 5000, time)
            val event = UsageEvents.Event()
            
            var lastEventPackage: String? = null
            var lastEventTime = 0L
            
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && 
                    event.timeStamp > lastEventTime) {
                    lastEventPackage = event.packageName
                    lastEventTime = event.timeStamp
                }
            }
            
            return lastEventPackage
        } catch (e: Exception) {
            Log.e(TAG, "خطا در دریافت برنامه فعال: ${e.message}")
            return null
        }
    }

    private fun returnToHomeScreen() {
        try {
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_HOME)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error returning to home screen", e)
        }
    }

    private fun setAppTimeLimit(call: MethodCall, result: Result) {
        try {
            val packageName = call.argument<String>("packageName")
            val limitMinutes = call.argument<Int>("limitMinutes")
            
            if (packageName == null || limitMinutes == null) {
                result.error("INVALID_ARGUMENTS", "Package name and limit minutes are required", null)
                return
            }
            
            // ذخیره محدودیت زمانی در تنظیمات مشترک
            val timeLimitsJson = prefs.getString(AppLockAccessibilityService.TIME_LIMITS_KEY, "{}")
            val timeLimits = JSONObject(timeLimitsJson ?: "{}")
            
            // اضافه کردن/به‌روزرسانی محدودیت برای این اپلیکیشن
            timeLimits.put(packageName, limitMinutes.toLong())
            
            prefs.edit().putString(AppLockAccessibilityService.TIME_LIMITS_KEY, timeLimits.toString()).apply()
            
            // اضافه کردن به لیست اپلیکیشن‌های تحت نظارت (اگر هنوز نیست)
            addAppToTracking(packageName)
            
            // اطمینان از فعال بودن سرویس دسترسی‌پذیری
            ensureAccessibilityServiceRunning()
            
            // اطمینان از فعال بودن سرویس پیش‌زمینه
            startAppLockForegroundService()
            
            // بررسی فوری برای کنترل محدودیت زمانی
            checkTimeLimitExceeded(packageName, limitMinutes)
            
            Log.d(TAG, "محدودیت زمانی برای $packageName تنظیم شد: $limitMinutes دقیقه")
            result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "خطا در تنظیم محدودیت زمانی: ${e.message}")
            result.error("TIME_LIMIT_ERROR", "خطا در تنظیم محدودیت زمانی", e.message)
        }
    }
    
    // بررسی فوری محدودیت زمانی برای اعمال بلافاصله در صورت نیاز
    private fun checkTimeLimitExceeded(packageName: String, limitMinutes: Int) {
        try {
            // بررسی زمان استفاده فعلی
            val currentUsageData = getCurrentAppUsageTime(packageName)
            val timeUsedMillis = currentUsageData ?: 0L
            val limitMillis = limitMinutes * 60 * 1000L
            
            Log.d(TAG, "بررسی محدودیت زمانی برای $packageName: استفاده ${timeUsedMillis/1000}s از ${limitMillis/1000}s")
            
            // تنها نمایش وضعیت فعلی استفاده و محدودیت، بدون قفل کردن بلافاصله
            // این به کاربر امکان می‌دهد تا پس از اتمام زمان مجاز، اپلیکیشن قفل شود
            Log.d(TAG, "محدودیت زمانی تنظیم شد. اپلیکیشن پس از رسیدن به محدودیت قفل خواهد شد")
            
            // عدم قفل کردن اپلیکیشن در زمان تنظیم محدودیت، حتی اگر زمان از محدودیت گذشته باشد
            // اپلیکیشن فقط پس از رسیدن به محدودیت در استفاده‌های بعدی قفل خواهد شد
        } catch (e: Exception) {
            Log.e(TAG, "خطا در بررسی محدودیت زمانی: ${e.message}")
        }
    }
    
    // دریافت زمان استفاده فعلی از یک اپلیکیشن
    private fun getCurrentAppUsageTime(packageName: String): Long? {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                return null
            }
            
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            
            val endTime = System.currentTimeMillis()
            val startTime = endTime - 24 * 60 * 60 * 1000 // 24 ساعت قبل
            
            val usageStats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            )
            
            for (stat in usageStats) {
                if (stat.packageName == packageName) {
                    return stat.totalTimeInForeground
                }
            }
            
            return 0
        } catch (e: Exception) {
            Log.e(TAG, "خطا در دریافت زمان استفاده: ${e.message}")
            return null
        }
    }

    private fun removeAppTimeLimit(packageName: String) {
        try {
            val prefs = context.getSharedPreferences(AppLockAccessibilityService.PREFS_NAME, Context.MODE_PRIVATE)
            val timeLimitsJson = prefs.getString(AppLockAccessibilityService.TIME_LIMITS_KEY, "{}")
            val timeLimits = JSONObject(timeLimitsJson ?: "{}")
            
            // Remove time limit for this app
            if (timeLimits.has(packageName)) {
                timeLimits.remove(packageName)
            }
            
            // همچنین وضعیت قفل اپلیکیشن را نیز پاک می‌کنیم
            prefs.edit().putBoolean("app_locked_$packageName", false).apply()
            
            // Save updated time limits
            prefs.edit().putString(AppLockAccessibilityService.TIME_LIMITS_KEY, timeLimits.toString()).apply()
            
            Log.d(TAG, "Removed time limit for $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Error removing app time limit", e)
        }
    }

    private fun resetAppUsageData() {
        try {
            val prefs = applicationContext.getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
            prefs.edit().putString("app_usage_data", "{}").apply()
            
            // همچنین وضعیت قفل همه برنامه‌ها را پاک می‌کنیم
            val allPrefs = prefs.all
            val editor = prefs.edit()
            
            for (key in allPrefs.keys) {
                if (key.startsWith("app_locked_")) {
                    editor.putBoolean(key, false)
                }
            }
            editor.apply()
            
            Log.d(TAG, "Reset all app usage data")
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting app usage data", e)
            throw e
        }
    }

    private fun startMonitoringService() {
        try {
            val serviceIntent = Intent(this, AppLockForegroundService::class.java)
            serviceIntent.action = AppLockForegroundService.ACTION_START_SERVICE
            
            // ذخیره زمان شروع سرویس
            prefs.edit().putLong("monitoring_service_started_at", System.currentTimeMillis()).apply()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            
            // اطمینان از فعال بودن سرویس دسترسی‌پذیری
            if (isAccessibilityServiceEnabled() && !AppLockAccessibilityService.isServiceRunning) {
                ensureAccessibilityServiceRunning()
            }
            
            Log.d(TAG, "Started AppLockForegroundService")
            
            // برنامه‌ریزی راه‌اندازی مجدد خودکار سرویس
            setupAutoRestartAlarm()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service", e)
        }
    }
    
    // تنظیم آلارم برای راه‌اندازی مجدد خودکار سرویس
    private fun setupAutoRestartAlarm() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            
            val restartIntent = Intent(this, ServiceRestartReceiver::class.java).apply {
                action = "com.example.flutter_application_512.RESTART_SERVICE"
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                123, // unique request code
                restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // تنظیم آلارم برای هر 15 دقیقه
            val intervalMillis = 15 * 60 * 1000L // 15 minutes
            val triggerAtMillis = System.currentTimeMillis() + intervalMillis
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setRepeating(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    intervalMillis,
                    pendingIntent
                )
            }
            
            Log.d(TAG, "Scheduled automatic service restart alarm")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up auto restart alarm", e)
        }
    }

    private fun stopMonitoringService() {
        try {
            // Stop foreground service
            val serviceIntent = Intent(this, AppLockForegroundService::class.java).apply {
                action = AppLockForegroundService.ACTION_STOP_SERVICE
            }
            context.startService(serviceIntent)
            
            Log.d(TAG, "Stopped app lock foreground service")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping monitoring service", e)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        try {
            val accessibilityServiceName = context.packageName + "/.AppLockAccessibilityService"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            
            return enabledServices.contains(accessibilityServiceName)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if accessibility service is enabled", e)
            return false
        }
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening accessibility settings", e)
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true // برای نسخه‌های قدیمی همیشه دسترسی داریم
        }
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                intent.data = Uri.parse("package:${context.packageName}")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error opening overlay settings", e)
                
                // اگر مشکلی پیش آمد، به تنظیمات اصلی برو
                val intent = Intent(Settings.ACTION_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }

    private fun checkAndVerifyAccessibilityService(): Map<String, Any> {
        val statusMap = HashMap<String, Any>()
        
        try {
            // Check if service is enabled in system settings
            val isEnabled = isAccessibilityServiceEnabled()
            statusMap["isEnabled"] = isEnabled
            
            if (isEnabled) {
                // Check if service is actually running by examining last heartbeat
                val prefs = getSharedPreferences(AppLockAccessibilityService.PREFS_NAME, Context.MODE_PRIVATE)
                val lastHeartbeat = prefs.getLong("accessibility_service_last_heartbeat", 0)
                val lastStart = prefs.getLong("accessibility_service_created_at", 0)
                val isActive = prefs.getBoolean("accessibility_service_active", false)
                
                val currentTime = System.currentTimeMillis()
                val heartbeatAge = currentTime - lastHeartbeat
                
                // If heartbeat is older than 2 minutes or no heartbeat, service might be dead
                val isRunning = isActive && lastHeartbeat > 0 && heartbeatAge < 2 * 60 * 1000
                statusMap["isRunning"] = isRunning
                statusMap["lastHeartbeatAge"] = heartbeatAge
                
                // If not running but enabled, try to restart
                if (!isRunning && isEnabled) {
                    Log.d(TAG, "Accessibility service is enabled but not running. Attempting restart.")
                    ensureAccessibilityServiceRunning()
                    statusMap["restartAttempted"] = true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking accessibility service status", e)
            statusMap["error"] = e.message ?: "Unknown error"
        }
        
        return statusMap
    }
    
    private fun ensureAccessibilityServiceRunning(): Boolean {
        try {
            if (!isAccessibilityServiceEnabled()) {
                // Can't restart if not enabled
                Log.d(TAG, "Can't restart accessibility service - not enabled in settings")
                return false
            }
            
            // Send broadcast to restart service
            val restartIntent = Intent(ServiceRestartReceiver.ACTION_RESTART_ACCESSIBILITY)
            sendBroadcast(restartIntent)
            
            // Start foreground service to keep things running
            Log.d(TAG, "Starting app lock foreground service")
            
            val serviceIntent = Intent(this, AppLockForegroundService::class.java).apply {
                action = AppLockForegroundService.ACTION_START_SERVICE
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            
            Log.d(TAG, "Restart attempt completed")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error ensuring service is running", e)
            return false
        }
    }

    // مدیریت وضعیت قفل برنامه
    private fun setAppLockStatus(packageName: String, isLocked: Boolean, result: Result? = null) {
        try {
            val prefs = applicationContext.getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("app_locked_$packageName", isLocked).apply()
            
            // ارسال برودکست برای اطلاع‌رسانی تغییر وضعیت قفل
            val action = if (isLocked) "com.example.flutter_application_512.APP_LOCKED" 
                          else "com.example.flutter_application_512.APP_UNLOCKED"
            
            val intent = Intent(action).apply {
                putExtra("packageName", packageName)
            }
            sendBroadcast(intent)
            
            Log.d(TAG, "Set app lock status for $packageName to $isLocked")
            result?.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting app lock status", e)
            result?.error("ERROR", "Failed to set app lock status", e.message)
        }
    }

    private fun unlockApp(packageName: String, result: Result? = null) {
        try {
            setAppLockStatus(packageName, false)
            
            val prefs = applicationContext.getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
            val usageDataJson = prefs.getString("app_usage_data", "{}")
            val usageData = JSONObject(usageDataJson ?: "{}")
            
            // پاک کردن داده‌های استفاده برای این برنامه
            if (usageData.has(packageName)) {
                usageData.put(packageName, 0)
            }
            
            prefs.edit().putString("app_usage_data", usageData.toString()).apply()
            
            Log.d(TAG, "Unlocked app: $packageName")
            result?.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error unlocking app", e)
            result?.error("ERROR", "Failed to unlock app", e.message)
        }
    }

    private fun isServiceRunning(): Boolean {
        val manager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (AppLockForegroundService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun requestBatteryOptimizationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val packageName = applicationContext.packageName
            val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent().apply {
                    action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }
    
    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val packageName = applicationContext.packageName
            val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            return pm.isIgnoringBatteryOptimizations(packageName)
        }
        return false
    }

    // اعمال همه قفل‌های برنامه و مطمئن شدن از اینکه برنامه‌های قفل شده باز نیستند
    private fun enforceAllAppLocks() {
        try {
            // دریافت لیست برنامه‌های قفل شده
            val prefs = applicationContext.getSharedPreferences(AppLockAccessibilityService.PREFS_NAME, Context.MODE_PRIVATE)
            val lockedAppsJson = prefs.getString(AppLockAccessibilityService.LOCKED_APPS_KEY, "[]")
            val lockedApps = JSONArray(lockedAppsJson ?: "[]")

            Log.d(TAG, "Enforcing locks for ${lockedApps.length()} apps")
            
            if (lockedApps.length() > 0) {
                // ارسال درخواست به سرویس برای بررسی و اعمال قفل‌ها
                val broadcastIntent = Intent(AppLockAccessibilityService.SERVICE_RESTART_ACTION)
                broadcastIntent.putExtra("enforceLockedApps", true)
                sendBroadcast(broadcastIntent)
                
                // همچنین برای اطمینان، سرویس را دوباره راه‌اندازی کنیم
                ensureAccessibilityServiceRunning()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enforcing app locks", e)
            throw e
        }
    }

    // اضافه کردن اپلیکیشن به لیست برنامه‌های تحت نظارت
    private fun addAppToTracking(packageName: String) {
        try {
            val trackingAppsJson = prefs.getString(AppLockAccessibilityService.TRACKING_APPS_KEY, "[]")
            val trackingApps = JSONArray(trackingAppsJson ?: "[]")
            
            // بررسی تکراری نبودن
            var alreadyTracked = false
            for (i in 0 until trackingApps.length()) {
                if (trackingApps.getString(i) == packageName) {
                    alreadyTracked = true
                    break
                }
            }
            
            // اضافه کردن به لیست اگر قبلاً نبوده
            if (!alreadyTracked) {
                trackingApps.put(packageName)
                prefs.edit().putString(AppLockAccessibilityService.TRACKING_APPS_KEY, trackingApps.toString()).apply()
                Log.d(TAG, "برنامه $packageName به لیست نظارت اضافه شد")
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطا در اضافه کردن برنامه به لیست نظارت: ${e.message}")
        }
    }

    private fun startAppLockForegroundService() {
        try {
            val serviceIntent = Intent(this, AppLockForegroundService::class.java)
            serviceIntent.action = AppLockForegroundService.ACTION_START_SERVICE
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            
            Log.d(TAG, "Started app lock foreground service")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting app lock foreground service", e)
        }
    }

    private fun testAppLock(packageName: String, testType: String) {
        Log.d(TAG, "Testing app lock functionality for $packageName with test type: $testType")
        
        val tester = AppLockTester(this)
        
        when (testType) {
            "kill" -> {
                Log.d(TAG, "Running kill app test")
                tester.testKillApp(packageName)
            }
            "lock" -> {
                Log.d(TAG, "Running lock app test")
                tester.testLockApp(packageName)
            }
            "screen" -> {
                Log.d(TAG, "Running lock screen test")
                tester.testShowLockScreen(packageName)
            }
            else -> {
                Log.d(TAG, "Running full lock sequence test")
                tester.testFullLockSequence(packageName)
            }
        }
    }

    private fun forceAppLockDirect(packageName: String) {
        try {
            Log.d(TAG, "Requesting force app lock for $packageName")
            
            // 1. اضافه کردن به لیست برنامه‌های قفل شده
            val prefs = applicationContext.getSharedPreferences("AppLockPrefs", Context.MODE_PRIVATE)
            val lockedAppsJson = prefs.getString("locked_apps", "[]")
            
            // تبدیل به آرایه و اضافه کردن برنامه جدید
            val lockedApps = JSONArray(lockedAppsJson ?: "[]")
            
            // بررسی تکراری نبودن
            var alreadyLocked = false
            for (i in 0 until lockedApps.length()) {
                if (lockedApps.getString(i) == packageName) {
                    alreadyLocked = true
                    break
                }
            }
            
            if (!alreadyLocked) {
                lockedApps.put(packageName)
                prefs.edit().putString("locked_apps", lockedApps.toString()).apply()
                Log.d(TAG, "Added $packageName to locked apps list")
            }
            
            // 2. ارسال رویداد به سرویس
            val intent = Intent("com.example.flutter_application_512.APP_LOCKED").apply {
                putExtra("packageName", packageName)
                putExtra("targetPackage", packageName)
                putExtra("fromMainActivity", true)
            }
            sendBroadcast(intent)
            
            // 3. ارسال دستور بستن برنامه
            val closeIntent = Intent("com.example.flutter_application_512.FORCE_CLOSE_APP").apply {
                putExtra("packageName", packageName)
            }
            sendBroadcast(closeIntent)
            
            Log.d(TAG, "Force app lock completed for $packageName")
            
            // 4. بازگشت به صفحه اصلی
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error in forceAppLockDirect", e)
        }
    }
}
