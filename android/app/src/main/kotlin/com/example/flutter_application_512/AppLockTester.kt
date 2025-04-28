package com.example.flutter_application_512

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

/**
 * نمونه کلاس برای تست قابلیت‌های قفل برنامه
 */
class AppLockTester(private val context: Context) {
    private val TAG = "AppLockTester"
    private val handler = Handler(Looper.getMainLooper())
    
    /**
     * تست قفل کردن یک برنامه با استفاده از قابلیت جدید
     */
    fun testLockApp(packageName: String) {
        Log.d(TAG, "آغاز تست قفل برنامه: $packageName")
        
        Toast.makeText(context, "در حال قفل برنامه $packageName...", Toast.LENGTH_SHORT).show()
        
        // ارسال رویداد قفل به سرویس دسترسی‌پذیری
        val lockIntent = Intent("com.example.flutter_application_512.APP_LOCKED").apply {
            putExtra("packageName", packageName)
            putExtra("targetPackage", packageName)
        }
        context.sendBroadcast(lockIntent)
        
        // ثبت گزارش نتیجه
        handler.postDelayed({
            Log.d(TAG, "تست قفل برنامه انجام شد. بررسی کنید که آیا برنامه قفل شده است.")
            Toast.makeText(context, "تست قفل برنامه انجام شد", Toast.LENGTH_LONG).show()
        }, 2000)
    }
    
    /**
     * تست کشتن اجباری یک برنامه
     */
    fun testKillApp(packageName: String) {
        Log.d(TAG, "آغاز تست کشتن برنامه: $packageName")
        
        Toast.makeText(context, "در حال کشتن برنامه $packageName...", Toast.LENGTH_SHORT).show()
        
        // روش 1: استفاده از ActivityManager
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.killBackgroundProcesses(packageName)
        
        // روش 2: استفاده از رویداد FORCE_CLOSE_APP
        val killIntent = Intent("com.example.flutter_application_512.FORCE_CLOSE_APP").apply {
            putExtra("packageName", packageName)
        }
        context.sendBroadcast(killIntent)
        
        // روش 3: بازگشت به صفحه اصلی
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(homeIntent)
        
        // ثبت گزارش نتیجه
        handler.postDelayed({
            Log.d(TAG, "تست کشتن برنامه انجام شد. بررسی کنید که آیا برنامه بسته شده است.")
            Toast.makeText(context, "تست کشتن برنامه انجام شد", Toast.LENGTH_LONG).show()
        }, 2000)
    }
    
    /**
     * تست نمایش صفحه قفل
     */
    fun testShowLockScreen(packageName: String) {
        Log.d(TAG, "آغاز تست نمایش صفحه قفل: $packageName")
        
        Toast.makeText(context, "در حال نمایش صفحه قفل برای $packageName...", Toast.LENGTH_SHORT).show()
        
        try {
            // بازگشت به صفحه اصلی
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(homeIntent)
            
            // نمایش صفحه قفل با تأخیر کوتاه
            handler.postDelayed({
                try {
                    // نمایش صفحه قفل مستقیم
                    val lockIntent = Intent(context, LockScreenActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        putExtra("package_name", packageName)
                    }
                    context.startActivity(lockIntent)
                    
                    Log.d(TAG, "صفحه قفل نمایش داده شد")
                } catch (e: Exception) {
                    Log.e(TAG, "خطا در نمایش صفحه قفل: ${e.message}")
                    Toast.makeText(context, "خطا در نمایش صفحه قفل: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }, 300)
        } catch (e: Exception) {
            Log.e(TAG, "خطا در نمایش صفحه قفل: ${e.message}")
            Toast.makeText(context, "خطا در نمایش صفحه قفل: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * تست کامل توالی قفل برنامه شامل کشتن، قفل و نمایش
     */
    fun testFullLockSequence(packageName: String) {
        Log.d(TAG, "آغاز تست کامل توالی قفل برنامه: $packageName")
        
        Toast.makeText(context, "تست کامل قفل برنامه $packageName...", Toast.LENGTH_SHORT).show()
        
        // ارسال درخواست مستقیم به MainActivity برای قفل برنامه
        val intent = Intent("com.example.flutter_application_512.APP_LOCKED").apply {
            putExtra("packageName", packageName)
            putExtra("targetPackage", packageName)
            putExtra("fromTester", true)
        }
        context.sendBroadcast(intent)
        
        // برگشت به صفحه اصلی
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(homeIntent)
        
        // نمایش اعلان نتیجه
        handler.postDelayed({
            Toast.makeText(context, "تست قفل برنامه انجام شد", Toast.LENGTH_LONG).show()
        }, 2000)
    }
} 