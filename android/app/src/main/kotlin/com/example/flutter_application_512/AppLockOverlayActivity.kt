package com.example.flutter_application_512

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AnimationUtils
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.widget.ProgressBar
import android.os.Vibrator
import android.media.MediaPlayer
import android.os.VibrationEffect
import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.app.usage.UsageEvents

class AppLockOverlayActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private val TAG = "AppLockOverlayActivity"
    private var packageName: String? = null
    private var timeUsedMinutes: Long = 0
    private var timeLimitMinutes: Long = 0
    private var backPressCount = 0
    private val handler = Handler(Looper.getMainLooper())
    private var checkRunnable: Runnable? = null
    
    // UI elements
    private lateinit var txtTitle: TextView
    private lateinit var txtMessage: TextView
    private lateinit var txtTimeLimit: TextView
    private lateinit var txtAppName: TextView
    private lateinit var imgAppIcon: ImageView
    private lateinit var btnReturnHome: Button
    private lateinit var rootView: View
    
    private val LOCK_CHECK_INTERVAL = 500L // چک کردن هر 500 میلی‌ثانیه
    private var isForcedLockMode = false
    private var lastLockTime = 0L
    private var consecutiveBlockCount = 0
    private var vibrator: Vibrator? = null
    private var mediaPlayer: MediaPlayer? = null
    
    companion object {
        var isLockScreenShowing = false
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_APP_NAME = "app_name"
        const val EXTRA_TIME_USED = "time_used"
        const val EXTRA_TIME_LIMIT = "time_limit"
        const val PREFS_NAME = "AppLockPrefs"
        
        /**
         * بازنشانی وضعیت نمایش صفحه قفل
         */
        fun resetLockScreenState() {
            isLockScreenShowing = false
            Log.d("AppLockOverlayActivity", "Lock screen state reset")
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // تنظیم نوع پنجره برای نمایش روی تمام برنامه‌ها
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        } else {
            window.setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)
        }
        
        // تنظیمات پنجره برای نمایش روی برنامه‌های دیگر
        val params = window.attributes.apply {
            flags = flags or 
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            dimAmount = 0.7f
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = 0
            y = 0
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
        }
        window.attributes = params
        
        // تنظیم محتوا
        setContentView(R.layout.activity_app_lock_overlay)
        
        // مقداردهی اولیه
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        timeUsedMinutes = intent.getLongExtra(EXTRA_TIME_USED, 0)
        timeLimitMinutes = intent.getLongExtra("timeLimit", intent.getLongExtra(EXTRA_TIME_LIMIT, 0))
        val forceLock = intent.getBooleanExtra("forceLock", false)
        val showInApp = intent.getBooleanExtra("showInApp", false)
        isForcedLockMode = forceLock
        
        // ثبت کنیم که صفحه قفل در حال نمایش است
        isLockScreenShowing = true
        
        // مقداردهی UI
        initializeUI()
        
        // شروع چک کردن قفل
        startLockCheck()
        
        // تنظیم عنوان و پیام
        updateUI()
        
        // غیرفعال کردن دکمه‌های سخت‌افزاری
        disableHardwareButtons()
        
        // اگر در حالت نمایش داخل برنامه هستیم، برنامه هدف را ببند
        if (showInApp && packageName != null) {
            forceCloseLockedApp(packageName!!)
        }
        
        // اگر حالت قفل اجباری است، تنظیم بیشتر روی این صفحه
        if (isForcedLockMode) {
            setupForceLockMode()
        }
        
        // برای اطمینان، تنظیم یک تایمر برای بررسی‌های مکرر
        setupPeriodicChecks()
        
        // راه‌اندازی ویبراتور برای حالت قفل اجباری
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
        
        // اگر showFirst فعال است، ویبراتور را فعال کنیم برای جلب توجه بیشتر
        if (showInApp) {
            vibrateForAttention()
        }
        
        // لاگ اطلاعات قفل
        Log.d("AppLockOverlay", "🔒 نمایش صفحه قفل برای $packageName")
        Log.d("AppLockOverlay", "حالت قفل اجباری: $isForcedLockMode, نمایش ابتدایی: $showInApp")
    }
    
    private fun initializeUI() {
        try {
            // یافتن عناصر UI
            txtTitle = findViewById(R.id.txtTitle)
            txtMessage = findViewById(R.id.txtMessage)
            txtTimeLimit = findViewById(R.id.txtTimeLimit)
            txtAppName = findViewById(R.id.txtAppName)
            imgAppIcon = findViewById(R.id.imgAppIcon)
            btnReturnHome = findViewById(R.id.btnReturnHome)
            rootView = findViewById(R.id.lockScreenRoot)
            
            // تنظیم رویداد کلیک برای دکمه بازگشت به خانه
            btnReturnHome.setOnClickListener {
                goHome()
            }
            
            // تنظیم انیمیشن برای ورود صفحه قفل
            val animation = AnimationUtils.loadAnimation(this, android.R.anim.slide_in_left)
            rootView.startAnimation(animation)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing UI", e)
        }
    }
    
    private fun setupUI(showFirst: Boolean = false) {
        try {
            // آماده‌سازی عناصر UI
            txtTitle = findViewById(R.id.txtTitle)
            txtMessage = findViewById(R.id.txtMessage)
            txtTimeLimit = findViewById(R.id.txtTimeLimit)
            txtAppName = findViewById(R.id.txtAppName)
            imgAppIcon = findViewById(R.id.imgAppIcon)
            btnReturnHome = findViewById(R.id.btnReturnHome)
            rootView = findViewById(R.id.lockScreenRoot)
            
            val showInApp = intent.getBooleanExtra("showInApp", false)
            
            // لود آیکون برنامه و تنظیم نام آن
            val appName = intent.getStringExtra(EXTRA_APP_NAME) ?: "این برنامه"
            txtAppName.text = appName
            
            // تنظیم عنوان
            if (showInApp) {
                txtTitle.text = "زمان استفاده به پایان رسیده"
                txtTitle.setTextColor(resources.getColor(android.R.color.holo_red_dark))
            } else {
                txtTitle.text = "برنامه قفل شده است"
            }
            
            // لود آیکون برنامه
            if (packageName != null) {
                try {
                    val packageManager = packageManager
                    val appInfo = packageManager.getApplicationInfo(packageName!!, 0)
                    val appIcon = packageManager.getApplicationIcon(appInfo)
                    imgAppIcon.setImageDrawable(appIcon)
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.e(TAG, "Error getting app info", e)
                    imgAppIcon.visibility = View.GONE
                    txtAppName.text = packageName
                    txtTitle.text = "محدودیت زمانی برنامه به پایان رسیده"
                }
            }
            
            // نمایش زمان محدودیت
            val hours = timeLimitMinutes / 60
            val minutes = timeLimitMinutes % 60
            val timeText = if (hours > 0) {
                "زمان مجاز: $hours ساعت و $minutes دقیقه"
            } else {
                "زمان مجاز: $minutes دقیقه"
            }
            txtTimeLimit.text = timeText
            
            // اضافه کردن پیام مناسب‌تر برای حالت نمایش ابتدایی
            if (showFirst) {
                txtMessage.text = "شما به محدودیت زمان استفاده از این برنامه رسیده‌اید. لطفاً کمی استراحت کنید یا به فعالیت دیگری بپردازید."
                txtMessage.setTextColor(resources.getColor(android.R.color.holo_red_dark))
            } else {
                txtMessage.text = "شما به محدودیت زمان استفاده از این برنامه رسیده‌اید."
            }
            
            // تنظیم دکمه بازگشت به صفحه اصلی
            btnReturnHome.setOnClickListener {
                goHome()
            }
            
            // تنظیم انیمیشن برای ورود صفحه قفل
            val animation = AnimationUtils.loadAnimation(this, android.R.anim.slide_in_left)
            rootView.startAnimation(animation)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up UI", e)
        }
    }
    
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "Lock screen resumed, checking locked app status")
        // اطمینان از اینکه برنامه قفل شده نمی‌تواند به فوکوس برگردد
        checkAndBlockApp()
        
        // اطمینان از فعال بودن چک‌کننده قفل
        startLockCheck()
        
        // در صورت قفل اجباری، بستن اپ قفل شده را انجام بده
        if (isForcedLockMode && packageName != null && packageName!!.isNotEmpty()) {
            Log.d("AppLockOverlay", "🔴 حالت قفل اجباری فعال. تلاش برای بستن برنامه $packageName")
            forceCloseLockedApp(packageName!!)
        }
        
        // لرزش دستگاه در حالت قفل اجباری
        if (isForcedLockMode && vibrator != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(500)
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isLockScreenShowing = false
        stopLockCheck()
        
        // آزاد کردن منابع مدیا پلیر
        if (mediaPlayer != null) {
            try {
                mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null
            } catch (e: Exception) {
                Log.e("AppLockOverlay", "خطا در آزادسازی منابع صوتی: ${e.message}")
            }
        }
    }
    
    override fun onBackPressed() {
        // جلوگیری از خروج با دکمه بازگشت
        // هدایت به صفحه اصلی
        val homeIntent = Intent(Intent.ACTION_MAIN)
        homeIntent.addCategory(Intent.CATEGORY_HOME)
        homeIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(homeIntent)
    }

    // متد جدید برای بررسی و مسدود کردن اپلیکیشن قفل شده
    private fun checkAndBlockApp() {
        if (packageName == null) return
        
        try {
            // اگر زمان کوتاهی از آخرین قفل گذشته، تعداد دفعات متوالی را افزایش بده
            val now = System.currentTimeMillis()
            if (now - lastLockTime < 2000) {
                consecutiveBlockCount++
                
                // اگر تعداد دفعات بالا رفت، به حالت قفل اجباری برو
                if (consecutiveBlockCount > 5 && !isForcedLockMode) {
                    Log.d("AppLockOverlay", "⚠️⚠️⚠️ تشخیص تلاش مکرر برای دور زدن قفل! فعال‌سازی حالت قفل اجباری")
                    isForcedLockMode = true
                    
                    // پخش صدای هشدار
                    setupAlertSound()
                    
                    // نمایش پیام به کاربر
                    runOnUiThread {
                        Toast.makeText(this, "تلاش مکرر برای دور زدن قفل شناسایی شد!", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                // ریست شمارنده در صورت گذشت زمان
                consecutiveBlockCount = 0
            }
            lastLockTime = now
            
            // بررسی اینکه آیا اپ قفل شده در حال اجراست
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val isAppRunning = false
            
            // روش 1: بررسی تسک‌های در حال اجرا (در نسخه‌های پایین کار می‌کند)
            try {
                val tasks = am.getRunningTasks(10)
                for (task in tasks) {
                    if (task.topActivity?.packageName == packageName) {
                        Log.d("AppLockOverlay", "🚨 شناسایی اجرای برنامه قفل شده در RunningTasks")
                        forceCloseLockedApp(packageName!!)
                        goToHomeScreen()
                        return
                    }
                }
            } catch (e: Exception) {
                Log.e("AppLockOverlay", "خطا در بررسی RunningTasks: ${e.message}")
            }
            
            // روش 2: بررسی پروسس‌های در حال اجرا
            try {
                val runningProcesses = am.runningAppProcesses
                for (processInfo in runningProcesses) {
                    if (processInfo.processName == packageName || processInfo.pkgList.contains(packageName)) {
                        if (processInfo.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
                            Log.d("AppLockOverlay", "🚨 شناسایی اجرای برنامه قفل شده در RunningProcesses")
                            forceCloseLockedApp(packageName!!)
                            goToHomeScreen()
                            return
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("AppLockOverlay", "خطا در بررسی RunningProcesses: ${e.message}")
            }
            
            // روش 3: بررسی آخرین برنامه استفاده شده با UsageStats
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                try {
                    val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                    val time = System.currentTimeMillis()
                    
                    // بررسی آمار استفاده در 5 ثانیه اخیر
                    val usageStats = usageStatsManager.queryUsageStats(
                        UsageStatsManager.INTERVAL_DAILY,
                        time - 5 * 1000,
                        time
                    )
                    
                    for (stat in usageStats) {
                        if (stat.packageName == packageName && stat.lastTimeUsed > time - 5000) {
                            Log.d("AppLockOverlay", "🚨 شناسایی اجرای برنامه قفل شده در UsageStats اخیر")
                            forceCloseLockedApp(packageName!!)
                            goToHomeScreen()
                            return
                        }
                    }
                    
                    // بررسی رویدادهای اخیر
                    val events = usageStatsManager.queryEvents(time - 5000, time)
                    val event = UsageEvents.Event()
                    
                    while (events.hasNextEvent()) {
                        events.getNextEvent(event)
                        if (event.packageName == packageName &&
                            event.timeStamp > time - 5000 && 
                            event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                            
                            Log.d("AppLockOverlay", "🚨 شناسایی اجرای برنامه قفل شده در UsageEvents اخیر")
                            forceCloseLockedApp(packageName!!)
                            goToHomeScreen()
                            return
                        }
                    }
                } catch (e: Exception) {
                    Log.e("AppLockOverlay", "خطا در بررسی UsageStats: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("AppLockOverlay", "خطا در checkAndBlockApp: ${e.message}")
        }
    }
    
    // شروع بررسی دوره‌ای با تناوب بیشتر
    private fun startLockCheck() {
        if (packageName == null) return
        
        stopLockCheck() // ابتدا بررسی قبلی را متوقف کن
        
        checkRunnable = Runnable {
            checkAndBlockApp()
            
            // ادامه بررسی دوره‌ای با تناوب کوتاه‌تر برای پاسخگویی سریع‌تر
            handler.postDelayed(checkRunnable!!, 500) // هر نیم ثانیه
        }
        
        // شروع بررسی دوره‌ای
        handler.postDelayed(checkRunnable!!, 500)
        Log.d(TAG, "Started periodic lock checker for $packageName")
        
        // شروع بررسی از همان ابتدا
        checkAndBlockApp()
    }
    
    // توقف بررسی دوره‌ای
    private fun stopLockCheck() {
        if (checkRunnable != null) {
            handler.removeCallbacks(checkRunnable!!)
            checkRunnable = null
            Log.d(TAG, "Stopped periodic lock checker")
        }
    }
    
    private fun unlockApp() {
        try {
            Log.d(TAG, "Sending request to unlock app: $packageName")
            
            // ارسال درخواست باز کردن قفل به MainActivity
            val intent = Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_UNLOCK_APP
                putExtra("packageName", packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            
            // بستن فعالیت فعلی
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Error unlocking app", e)
            Toast.makeText(this, "خطا در باز کردن قفل", Toast.LENGTH_SHORT).show()
        }
    }
    
    // متد جدید برای رفتن به صفحه خانه
    private fun goToHomeScreen() {
        try {
            // انجام چندین اقدام برای اطمینان از بازگشت به خانه
            
            // 1. ارسال Intent برای بازگشت به خانه
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
            
            // 2. برای تأخیر در بسته شدن صفحه قفل
            handler.postDelayed({
                // بسته شدن صفحه قفل بعد از کمی تأخیر
                finish()
            }, 300)
            
            Log.d(TAG, "Returning to home screen")
        } catch (e: Exception) {
            Log.e(TAG, "Error returning to home screen", e)
            // در صورت خطا، تلاش برای بستن صفحه قفل
            finish()
        }
    }
    
    // جلوگیری از تمام کلیدهای سیستمی
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // مسدود کردن همه کلیدهای سیستمی به جز home که توسط سیستم عامل مدیریت می‌شود
        if (event.keyCode == KeyEvent.KEYCODE_BACK || 
            event.keyCode == KeyEvent.KEYCODE_APP_SWITCH ||
            event.keyCode == KeyEvent.KEYCODE_MENU ||
            event.keyCode == KeyEvent.KEYCODE_SEARCH) {
            goToHomeScreen()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    
    // ایجاد حالت قفل اجباری با مقاومت بیشتر در برابر بستن
    private fun setupForceLockMode() {
        // تنظیمات اضافی برای حالت قفل اجباری
        val showInApp = intent.getBooleanExtra("showInApp", false)
        
        if (showInApp) {
            // برای نمایش داخل برنامه، متن پیام را تغییر می‌دهیم
            txtMessage.text = "محدودیت زمانی این برنامه به پایان رسیده است. بعد از چند ثانیه برنامه بسته خواهد شد."
            txtMessage.setTextColor(resources.getColor(android.R.color.holo_red_dark))
            txtMessage.textSize = 18f
            
            // تایمر برای نمایش زمان باقی‌مانده تا بسته شدن برنامه
            val countdownTime = 2500L // میلی‌ثانیه - تبدیل به Long
            val countdownInterval = 500L // بررسی هر 500 میلی‌ثانیه - تبدیل به Long
            var remainingTime = countdownTime
            
            val countdownHandler = Handler(Looper.getMainLooper())
            val countdownRunnable = object : Runnable {
                override fun run() {
                    remainingTime -= countdownInterval
                    val secondsLeft = remainingTime / 1000 + 1
                    
                    if (secondsLeft > 0) {
                        txtTimeLimit.text = "برنامه تا ${secondsLeft} ثانیه دیگر بسته می‌شود"
                        countdownHandler.postDelayed(this, countdownInterval)
                    }
                }
            }
            
            countdownHandler.post(countdownRunnable)
        } else {
            // برای حالت عادی کد قبلی
            txtTitle.setTextColor(resources.getColor(android.R.color.holo_red_dark))
            txtMessage.setTextColor(resources.getColor(android.R.color.holo_red_dark))
            
            // نمایش بولد‌تر پیام
            txtTitle.textSize = 24f
            txtMessage.textSize = 18f
        }
    }
    
    // راه‌اندازی بررسی‌های دوره‌ای برای اطمینان از بسته ماندن برنامه
    private fun setupPeriodicChecks() {
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (packageName != null) {
                    forceCloseLockedApp(packageName!!)
                }
                handler.postDelayed(this, 500) // هر نیم ثانیه چک کن برای واکنش سریع‌تر
            }
        }, 500)
        
        // فعال کردن حالت قفل اجباری برای اطمینان بیشتر
        setupForceLockMode()
    }
    
    // متد جدید برای بستن اجباری برنامه قفل شده
    private fun forceCloseLockedApp(packageToClose: String) {
        try {
            // بررسی مجوز
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Log.d("AppLockOverlay", "⚠️ نیاز به استفاده از راه‌های جایگزین برای بستن برنامه در اندروید 9+")
                
                // ارسال برودکست به سرویس اصلی برای بستن برنامه
                val intent = Intent("com.example.flutter_application_512.FORCE_CLOSE_APP")
                intent.putExtra("package_name", packageToClose)
                sendBroadcast(intent)
                
                // تلاش برای رفتن به صفحه خانه
                goToHomeScreen()
                return
            }
            
            // در نسخه‌های قدیمی‌تر از روش سیستمی استفاده کن
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(packageToClose)
            
            // لاگ اطلاعات
            Log.d("AppLockOverlay", "✅ درخواست بستن برنامه $packageToClose ارسال شد")
            
        } catch (e: Exception) {
            Log.e("AppLockOverlay", "خطا در بستن برنامه: ${e.message}")
        }
    }
    
    // جلوگیری از پردازش کلیدها
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // همه کلیدها غیر از HOME و POWER را بلاک کن
        return if (keyCode != KeyEvent.KEYCODE_HOME && keyCode != KeyEvent.KEYCODE_POWER) {
            true
        } else {
            super.onKeyDown(keyCode, event)
        }
    }
    
    private fun setupAlertSound() {
        try {
            mediaPlayer = MediaPlayer.create(this, R.raw.lock_alert)
            mediaPlayer?.isLooping = true
            mediaPlayer?.start()
        } catch (e: Exception) {
            Log.e("AppLockOverlay", "خطا در پخش صدای هشدار: ${e.message}")
        }
    }
    
    // پخش صدای هشدار برای جلب توجه کاربر
    private fun playLockSound() {
        try {
            if (mediaPlayer == null) {
                // استفاده از صدای پیش‌فرض سیستم به جای منبع نامعتبر
                val notification = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                mediaPlayer = MediaPlayer.create(this, notification)
                mediaPlayer?.setOnCompletionListener {
                    it.release()
                    mediaPlayer = null
                }
                mediaPlayer?.start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing lock sound", e)
        }
    }
    
    // ویبره برای جلب توجه بیشتر
    private fun vibrateForAttention() {
        try {
            vibrator?.let { v ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(500)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error vibrating", e)
        }
    }

    // متد goHome که به جای goToHomeScreen استفاده می‌شود
    private fun goHome() {
        try {
            // ارسال کاربر به صفحه اصلی
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(homeIntent)
            
            // بستن برنامه هدف به صورت اجباری اگر هنوز باز است
            if (packageName != null) {
                forceCloseLockedApp(packageName!!)
            }
            
            // بستن صفحه قفل بعد از هدایت به صفحه اصلی
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Error going to home screen", e)
            
            // تلاش مجدد با روش قدیمی‌تر در صورت خطا
            try {
                val homeIntent = Intent(Intent.ACTION_MAIN)
                homeIntent.addCategory(Intent.CATEGORY_HOME)
                homeIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(homeIntent)
                finish()
            } catch (e2: Exception) {
                Log.e(TAG, "Error in fallback home method", e2)
            }
        }
    }

    private fun updateUI() {
        try {
            // تنظیم عنوان و پیام
            txtTitle.text = "محدودیت زمانی"
            txtMessage.text = "زمان استفاده شما به پایان رسیده است. لطفاً فردا دوباره تلاش کنید."
            
            // نمایش زمان باقیمانده
            val hours = timeLimitMinutes / 60
            val minutes = timeLimitMinutes % 60
            val timeText = if (hours > 0) {
                "$hours ساعت و $minutes دقیقه"
            } else {
                "$minutes دقیقه"
            }
            txtTimeLimit.text = "محدودیت: $timeText"
            
            // نمایش نام برنامه
            if (packageName != null) {
                val appName = getAppName(packageName!!)
                txtAppName.text = appName
                
                // نمایش آیکون برنامه
                try {
                    val pm = packageManager
                    val appInfo = pm.getApplicationInfo(packageName!!, 0)
                    imgAppIcon.setImageDrawable(appInfo.loadIcon(pm))
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading app icon", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating UI", e)
        }
    }

    private fun getAppName(packageName: String): String {
        try {
            val packageManager = packageManager
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            return packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting app name", e)
            return packageName
        }
    }

    private fun disableHardwareButtons() {
        // جلوگیری از پردازش کلیدهای سخت‌افزاری
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
    }
} 