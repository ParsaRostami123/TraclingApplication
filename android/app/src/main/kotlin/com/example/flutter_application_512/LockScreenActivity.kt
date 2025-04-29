package com.example.flutter_application_512

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView

class LockScreenActivity : Activity() {
    private val TAG = "LockScreenActivity"
    private val handler = Handler(Looper.getMainLooper())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // اطمینان از نمایش در بالای قفل صفحه و سایر پنجره‌ها
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                 WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                 WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                 WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                 WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        
        val packageName = intent.getStringExtra("package_name") ?: ""
        val appName = intent.getStringExtra("app_name") ?: "برنامه"
        val timeLimit = intent.getIntExtra("time_limit", 0)
        val usedTime = intent.getIntExtra("used_time", 0)
        
        Log.d(TAG, "نمایش صفحه قفل برای بسته: $packageName (نام: $appName، محدودیت: $timeLimit دقیقه، استفاده: $usedTime دقیقه)")
        
        // ایجاد لایه اصلی
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            
            // رنگ‌آمیزی گرادیانت پس‌زمینه
            val gradient = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#1a237e"), Color.parseColor("#000051"))
            )
            background = gradient
            
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(32, 32, 32, 32)
        }
        
        // آیکون قفل
        val lockIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_lock_lock)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 60)
                gravity = Gravity.CENTER
                width = 150
                height = 150
            }
            setColorFilter(Color.RED)
        }
        layout.addView(lockIcon)
        
        // عنوان
        val title = TextView(this).apply {
            text = "${appName} قفل شده است"
            setTextColor(Color.WHITE)
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 60)
            }
        }
        layout.addView(title)
        
        // توضیحات
        val description = TextView(this).apply {
            text = "زمان مجاز استفاده از این برنامه به اتمام رسیده است."
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 60)
            }
        }
        layout.addView(description)
        
        // نمایش اطلاعات محدودیت زمانی
        val limitInfo = TextView(this).apply {
            text = "محدودیت زمانی: $timeLimit دقیقه\nزمان استفاده شده: $usedTime دقیقه"
            setTextColor(Color.YELLOW)
            textSize = 18f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 100)
            }
        }
        layout.addView(limitInfo)
        
        // دکمه بازگشت
        val backButton = Button(this).apply {
            text = "بازگشت به صفحه اصلی"
            
            // استایل دکمه
            val buttonShape = GradientDrawable()
            buttonShape.shape = GradientDrawable.RECTANGLE
            buttonShape.cornerRadius = 30f
            buttonShape.setColor(Color.parseColor("#2196F3"))
            
            background = buttonShape
            setTextColor(Color.WHITE)
            textSize = 18f
            
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                width = 600
                height = 150
            }
            
            setPadding(40, 20, 40, 20)
            setOnClickListener {
                finish()
                // بازگشت به صفحه اصلی
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(homeIntent)
                
                // ارسال broadcast برای بستن برنامه
                val closeIntent = Intent("com.example.flutter_application_512.FORCE_CLOSE_APP")
                closeIntent.putExtra("packageName", packageName)
                sendBroadcast(closeIntent)
            }
        }
        layout.addView(backButton)
        
        setContentView(layout)
        
        // بستن اتوماتیک صفحه قفل بعد از چند ثانیه
        handler.postDelayed({
            if (!isFinishing) {
                finish()
                // بازگشت به صفحه اصلی
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(homeIntent)
                
                // ارسال broadcast برای بستن برنامه مجدداً
                val closeIntent = Intent("com.example.flutter_application_512.FORCE_CLOSE_APP")
                closeIntent.putExtra("packageName", packageName)
                sendBroadcast(closeIntent)
            }
        }, 10000) // 10 ثانیه
    }
    
    override fun onBackPressed() {
        // جلوگیری از بستن با دکمه بازگشت
        // به جای آن، بازگشت به صفحه اصلی
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
    }
} 