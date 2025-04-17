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
        
        // تنظیم نمایش تمام صفحه و روی تمام برنامه‌ها
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or 
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_FULLSCREEN or 
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        
        // اطمینان از نمایش روی قفل صفحه و نمایش‌های دیگر
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        
        // مطمئن شویم که فوکوس دارد و خروج از آن سخت است
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        
        setContentView(R.layout.activity_app_lock_overlay)
        
        // ثبت کنیم که صفحه قفل در حال نمایش است
        isLockScreenShowing = true
        
        // دریافت اطلاعات از intent
        packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        val appName = intent.getStringExtra(EXTRA_APP_NAME) ?: "این برنامه"
        timeUsedMinutes = intent.getLongExtra(EXTRA_TIME_USED, 0)
        timeLimitMinutes = intent.getLongExtra(EXTRA_TIME_LIMIT, 0)
        
        // دریافت پیش‌فرض‌ها
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // initialize UI elements
        initializeUIElements()
        
        // configure UI
        setupUI()
        
        // شروع بررسی دوره‌ای
        startLockChecker()
        
        // برای اطمینان، یکبار دیگر برنامه قفل شده را بررسی و بلاک می‌کنیم
        checkAndBlockApp()
    }
    
    private fun initializeUIElements() {
        try {
            // یافتن عناصر UI
            txtTitle = findViewById(R.id.txtTitle)
            txtMessage = findViewById(R.id.txtMessage)
            txtTimeLimit = findViewById(R.id.txtTimeLimit)
            txtAppName = findViewById(R.id.txtAppName)
            imgAppIcon = findViewById(R.id.imgAppIcon)
            btnReturnHome = findViewById(R.id.btnReturnHome)
            rootView = findViewById(R.id.lockScreenRoot)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing UI elements", e)
        }
    }
    
    private fun setupUI() {
        try {
            // نمایش اطلاعات برنامه قفل شده
            if (packageName != null) {
                try {
                    val pm = packageManager
                    val appInfo = pm.getApplicationInfo(packageName!!, 0)
                    val actualAppName = pm.getApplicationLabel(appInfo).toString()
                    val appIcon = pm.getApplicationIcon(appInfo)
                    
                    // نمایش آیکون و نام برنامه
                    imgAppIcon.setImageDrawable(appIcon)
                    txtAppName.text = actualAppName
                    
                    // تنظیم پیام قفل
                    txtTitle.text = "محدودیت زمانی $actualAppName به پایان رسیده"
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
            
            // پیام واضح‌تر برای کاربر
            txtMessage.text = "محدودیت زمانی برنامه به پایان رسیده است.\nبرای استفاده مجدد، ابتدا باید محدودیت زمانی را از اپلیکیشن کنترل زمان حذف کنید."
            
            // دکمه‌ی بازگشت به صفحه اصلی
            btnReturnHome.setOnClickListener {
                goToHomeScreen()
            }
            
            // نمایش پیام به کاربر
            Toast.makeText(this, "محدودیت زمانی برنامه به پایان رسیده است", Toast.LENGTH_LONG).show()
            
            // اعمال انیمیشن ورود
            val animation = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
            rootView.startAnimation(animation)
            
            // جلوگیری از دور زدن قفل
            setFinishOnTouchOutside(false)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up UI", e)
        }
    }
    
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "Lock screen resumed, checking locked app status")
        // اطمینان از اینکه برنامه قفل شده نمی‌تواند به فوکوس برگردد
        checkAndBlockApp()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isLockScreenShowing = false
        stopLockChecker()
    }
    
    override fun onBackPressed() {
        // غیرفعال کردن دکمه‌ی بازگشت و جایگزینی با رفتن به صفحه اصلی
        goToHomeScreen()
    }

    // متد جدید برای بررسی و مسدود کردن اپلیکیشن قفل شده
    private fun checkAndBlockApp() {
        if (packageName == null) return
        
        try {
            Log.d(TAG, "Checking if $packageName is running and needs to be blocked")
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val runningTasks = am.getRunningTasks(5)
            
            for (task in runningTasks) {
                if (task.topActivity?.packageName == packageName) {
                    Log.d(TAG, "Detected locked app in running tasks, forcing home screen")
                    goToHomeScreen()
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking running apps", e)
        }
    }
    
    // شروع بررسی دوره‌ای
    private fun startLockChecker() {
        if (packageName == null) return
        
        stopLockChecker() // ابتدا بررسی قبلی را متوقف کن
        
        checkRunnable = Runnable {
            checkAndBlockApp()
            
            // ادامه بررسی دوره‌ای
            handler.postDelayed(checkRunnable!!, 1000) // هر یک ثانیه
        }
        
        // شروع بررسی دوره‌ای
        handler.postDelayed(checkRunnable!!, 1000)
        Log.d(TAG, "Started periodic lock checker for $packageName")
    }
    
    // توقف بررسی دوره‌ای
    private fun stopLockChecker() {
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
    
    private fun goToHomeScreen() {
        try {
            // بازگشت به صفحه اصلی دستگاه
            val homeIntent = Intent(Intent.ACTION_MAIN)
            homeIntent.addCategory(Intent.CATEGORY_HOME)
            homeIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(homeIntent)
            
            // بستن فعالیت فعلی
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Error going to home screen", e)
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
} 