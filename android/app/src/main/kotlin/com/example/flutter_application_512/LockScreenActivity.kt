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
        Log.d(TAG, "نمایش صفحه قفل برای بسته: $packageName")
        
        // ایجاد لایه اصلی
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#AA000000")) // رنگ پس زمینه تیره‌تر با شفافیت
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(32, 32, 32, 32)
        }
        
        // عنوان
        val title = TextView(this).apply {
            text = "برنامه قفل شده است"
            setTextColor(Color.WHITE)
            textSize = 26f // اندازه بزرگتر
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
            text = "زمان استفاده از این برنامه به اتمام رسیده است."
            setTextColor(Color.WHITE)
            textSize = 20f // اندازه بزرگتر
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 120)
            }
        }
        layout.addView(description)
        
        // دکمه بازگشت
        val backButton = Button(this).apply {
            text = "بازگشت به صفحه اصلی"
            setBackgroundColor(Color.parseColor("#2196F3"))
            setTextColor(Color.WHITE)
            textSize = 16f // اندازه متن بزرگتر
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                width = 600 // عرض بیشتر
                height = 150 // ارتفاع بیشتر
            }
            setPadding(40, 20, 40, 20) // پدینگ بیشتر
            setOnClickListener {
                finish()
                // بازگشت به صفحه اصلی
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(homeIntent)
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
            }
        }, 8000) // افزایش به 8 ثانیه
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