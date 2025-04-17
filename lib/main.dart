import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart';
import 'dart:async';
import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';
import 'dart:math' as Math;

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'App Usage Tracker',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
        useMaterial3: true,
      ),
      home: const AppListScreen(),
    );
  }
}

// Model class to combine app info with usage stats
class AppInfo {
  final String appName;
  final String packageName;
  final bool isSystemApp;
  final Uint8List icon;
  final Duration usageDuration;
  Duration timeLimit; // محدودیت زمانی برای اپلیکیشن

  AppInfo({
    required this.appName,
    required this.packageName,
    required this.isSystemApp,
    required this.icon,
    required this.usageDuration,
    this.timeLimit = Duration.zero, // مقدار پیش‌فرض: بدون محدودیت
  });
}

class AppListScreen extends StatefulWidget {
  const AppListScreen({super.key});

  @override
  State<AppListScreen> createState() => _AppListScreenState();
}

class _AppListScreenState extends State<AppListScreen> {
  List<AppInfo> _apps = [];
  bool _hasUsagePermission = false;
  bool _hasAccessibilityPermission = false;
  bool _isServiceInstalled = false;
  bool _isLoading = true;
  bool _isScreenTimerRunning = false;

  int _screenTimeMillis = 0;
  DateTime _lastUpdated = DateTime.now();
  DateTime? _lastResetTime;
  DateTime? _lastUpdateTime;
  Duration _totalScreenTime = Duration.zero;
  double totalUsageTime = 0;
  double screenOnTime = 0;
  Map<String, Duration> _timeLimits = {};

  // تایمر برای به‌روزرسانی خودکار و نمایش زمان دقیق
  Timer? _liveUpdateTimer;
  int _secondsPassedSinceLastUpdate = 0;

  static const platform =
      MethodChannel('com.example.flutter_application_512/app_lock');
  static const _methodChannel =
      MethodChannel('com.example.flutter_application_512/usage_stats');
  bool _hasOverlayPermission = false;
  bool _showingPermissionDialog = false;
  bool _isMonitoringServiceRunning = false;
  Timer? _monitorTimer; // تایمر برای بررسی دوره‌ای برنامه‌های در حال اجرا
  late Future<SharedPreferences> _prefs; // برای ذخیره تنظیمات

  // بررسی و به‌روزرسانی برنامه فعال فعلی
  String? _currentForegroundApp;

  @override
  void initState() {
    super.initState();
    _prefs = SharedPreferences.getInstance();
    _loadLastResetTime();
    _initApp();
    // بررسی وضعیت سرویس‌ها
    _checkServiceStatus();
    // شروع تایمر زنده برای نمایش استفاده در لحظه
    _startLiveTimer();
  }

  @override
  void dispose() {
    _monitorTimer?.cancel();
    _liveUpdateTimer?.cancel();
    super.dispose();
  }

  // بارگذاری زمان آخرین ریست از حافظه
  Future<void> _loadLastResetTime() async {
    try {
      final prefs = await _prefs;
      final lastResetMillis = prefs.getInt('last_reset_time');
      if (lastResetMillis != null) {
        setState(() {
          _lastResetTime = DateTime.fromMillisecondsSinceEpoch(lastResetMillis);
        });
        print("Last reset time loaded: $_lastResetTime");
      } else {
        // اگر تاری نداریم، الان را ثبت کنیم
        final now = DateTime.now();
        await _saveLastResetTime(now);
        setState(() {
          _lastResetTime = now;
        });
        print("Initialized reset time to: $now");
      }
    } catch (e) {
      print("Error loading last reset time: $e");
    }
  }

  // ذخیره زمان آخرین ریست در حافظه
  Future<void> _saveLastResetTime(DateTime time) async {
    try {
      final prefs = await _prefs;
      await prefs.setInt('last_reset_time', time.millisecondsSinceEpoch);
      setState(() {
        _lastResetTime = time;
      });
      print("Saved reset time: $time");
    } catch (e) {
      print("Error saving reset time: $e");
    }
  }

  // بررسی و ریست کردن آمار در صورت نیاز
  Future<void> _checkAndResetStats() async {
    if (_lastResetTime == null) {
      await _loadLastResetTime();
    }

    final now = DateTime.now();
    if (_lastResetTime != null) {
      final difference = now.difference(_lastResetTime!);

      // اگر 24 ساعت گذشته، آمار را ریست کن
      if (difference.inHours >= 24) {
        print(
            "24 hours have passed since last reset. Resetting usage stats...");

        try {
          // ریست کردن آمار در سمت اندروید
          await _methodChannel.invokeMethod('resetAppUsageData');

          // به‌روزرسانی زمان آخرین ریست
          await _saveLastResetTime(now);

          // به‌روزرسانی رابط کاربری
          await _updateAppsWithUsageData();

          print("Usage stats reset successfully");
        } catch (e) {
          print("Error resetting usage stats: $e");
        }
      } else {
        final hoursLeft = 24 - difference.inHours;
        print("Next reset in $hoursLeft hours");
      }
    }
  }

  // شروع مانیتورینگ برنامه‌های در حال اجرا
  void _startAppMonitoring() {
    // بررسی هر 10 ثانیه
    _monitorTimer = Timer.periodic(const Duration(seconds: 10), (timer) {
      if (_hasUsagePermission) {
        _checkRunningApps();
      }
    });

    // همچنین هر یک ساعت بررسی کن که آیا زمان ریست فرا رسیده
    Timer.periodic(const Duration(hours: 1), (timer) {
      _checkAndResetStats();
    });
  }

  // بررسی برنامه‌های در حال اجرا و محدودیت زمانی
  Future<void> _checkRunningApps() async {
    try {
      // دریافت برنامه در حال اجرا در فورگراند
      final currentApp =
          await _methodChannel.invokeMethod('getCurrentForegroundApp');

      if (currentApp != null && _timeLimits.containsKey(currentApp)) {
        // یافتن اپ در لیست
        final appIndex =
            _apps.indexWhere((app) => app.packageName == currentApp);
        if (appIndex >= 0) {
          final app = _apps[appIndex];
          // اگر زمان استفاده از محدودیت بیشتر شده
          if (app.timeLimit.inSeconds > 0 &&
              app.usageDuration >= app.timeLimit) {
            // نمایش پنجره قفل
            _showLockScreen(app);
          }
        }
      }
    } catch (e) {
      print("Error checking running apps: $e");
    }
  }

  // نمایش صفحه قفل
  void _showLockScreen(AppInfo app) {
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (context) => WillPopScope(
        onWillPop: () async => false, // غیرفعال کردن دکمه بازگشت
        child: AlertDialog(
          title: Text('${app.appName} قفل شده است'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.lock, size: 48, color: Colors.red),
              const SizedBox(height: 16),
              Text('زمان مجاز استفاده از ${app.appName} به پایان رسیده است.'),
              const SizedBox(height: 8),
              Text('محدودیت: ${_formatDuration(app.timeLimit)}'),
              Text('زمان استفاده شده: ${_formatDuration(app.usageDuration)}'),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () {
                Navigator.of(context).pop();
                // بازگشت به برنامه اصلی
                _methodChannel.invokeMethod('returnToHomeScreen');
              },
              child: const Text('بازگشت به صفحه اصلی'),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _initApp() async {
    setState(() {
      _isLoading = true;
    });

    // Always load apps first, even without usage permission
    await _loadAppsWithoutUsage();

    // Then check if we have usage permission
    _hasUsagePermission = await _checkUsageStatsPermission();

    if (_hasUsagePermission) {
      print("Has usage permission, loading app usage data...");
      // If we have permission, update with usage data
      await _updateAppsWithUsageData();

      // Make sure _totalScreenTime is properly set from _screenTimeMillis
      setState(() {
        if (_totalScreenTime.inMilliseconds == 0 && _screenTimeMillis > 0) {
          _totalScreenTime = Duration(milliseconds: _screenTimeMillis);
          print("Set total screen time to $_totalScreenTime");
        }
        _lastUpdateTime = DateTime.now();
      });
    } else if (!_showingPermissionDialog) {
      // Only show permission dialog if we haven't shown it yet
      _showingPermissionDialog = true;
      // Schedule permission request for after build completes
      WidgetsBinding.instance.addPostFrameCallback((_) {
        _requestUsagePermission();
      });
    }

    setState(() {
      _isLoading = false;
    });
  }

  Future<bool> _checkUsageStatsPermission() async {
    try {
      final hasPermission =
          await _methodChannel.invokeMethod('hasUsageStatsPermission');
      return hasPermission == true;
    } catch (e) {
      print("Error checking usage permission: $e");
      return false;
    }
  }

  Future<void> _requestUsagePermission() async {
    bool shouldReload = await showDialog<bool>(
          context: context,
          barrierDismissible: false,
          builder: (context) => AlertDialog(
            title: const Text('Permission Required'),
            content: const Text(
              'This app needs usage stats permission to show how much time you spend on each app.\n\n'
              'Please enable "Usage Access" for this app in the next screen.',
            ),
            actions: [
              TextButton(
                onPressed: () {
                  Navigator.of(context).pop(false); // Don't reload
                },
                child: const Text('Not Now'),
              ),
              ElevatedButton(
                onPressed: () async {
                  Navigator.of(context).pop(true); // Reload after
                  try {
                    await _methodChannel
                        .invokeMethod('openUsageAccessSettings');
                  } catch (e) {
                    print("Error opening settings: $e");
                    await openAppSettings();
                  }
                },
                child: const Text('Grant Permission'),
              ),
            ],
          ),
        ) ??
        false;

    // Reset flag
    _showingPermissionDialog = false;

    if (shouldReload) {
      // Wait a bit for user to grant permission
      await Future.delayed(const Duration(seconds: 2));
      // Check permission and reload if needed
      final hasPermission = await _checkUsageStatsPermission();
      if (hasPermission) {
        setState(() {
          _hasUsagePermission = true;
        });
        await _updateAppsWithUsageData();
      }
    }
  }

  Future<void> _loadAppsWithoutUsage() async {
    try {
      print("Loading installed apps...");

      // Get installed apps
      final result = await _methodChannel.invokeMethod('getInstalledApps');

      if (result == null) {
        print("No installed apps data returned");
        return;
      }

      List<dynamic> installedApps = List<dynamic>.from(result);
      print("Got ${installedApps.length} installed apps");

      if (installedApps.isEmpty) {
        print("Installed apps list is empty!");
        return;
      }

      // Convert to AppInfo objects with zero usage time
      List<AppInfo> apps = [];
      for (var appData in installedApps) {
        try {
          final Map<String, dynamic> app = Map<String, dynamic>.from(appData);
          final String packageName = app['packageName'] as String;
          final String iconBase64 = app['icon'] as String;

          // Skip apps with no icon data
          if (iconBase64.isEmpty) {
            continue;
          }

          Uint8List iconBytes = base64Decode(iconBase64);

          apps.add(
            AppInfo(
              appName: app['appName'] as String,
              packageName: packageName,
              isSystemApp: app['isSystemApp'] as bool,
              icon: iconBytes,
              usageDuration: Duration.zero,
            ),
          );
        } catch (e) {
          print("Error processing app data: $e");
        }
      }

      // Sort alphabetically initially
      apps.sort((a, b) => a.appName.compareTo(b.appName));

      setState(() {
        _apps = apps;
      });
    } catch (e) {
      print("Error loading apps without usage: $e");
    }
  }

  Future<void> _updateAppsWithUsageData() async {
    try {
      print("Updating apps with usage data...");

      // Debug: Check if permission is granted
      print("Has usage permission: $_hasUsagePermission");

      final endTime = DateTime.now().millisecondsSinceEpoch;
      // Get timestamp for 24 hours ago
      final startTime = endTime - 24 * 60 * 60 * 1000;

      print("Fetching usage stats from $startTime to $endTime");

      // Get app usage stats
      final result = await _methodChannel.invokeMethod('getAppUsageStats', {
        'startTime': startTime,
        'endTime': endTime,
      });

      // Debug: Print result
      print("App usage stats result: $result");

      // Get screen on time
      final screenTimeResult =
          await _methodChannel.invokeMethod('getScreenOnTime', {
        'startTime': startTime,
        'endTime': endTime,
      });

      print("Raw screen time from native: $screenTimeResult milliseconds");

      double totalTime = 0;
      final List<AppInfo> updatedAppList = [];

      // Debug: Print apps count
      print("Updating ${_apps.length} apps with usage data");

      // Create new AppInfo objects with usage stats
      for (AppInfo app in _apps) {
        // Get usage duration for this app, default to zero if not available
        final Duration usageDuration =
            result != null && result.containsKey(app.packageName)
                ? Duration(milliseconds: result[app.packageName] as int)
                : Duration.zero;

        // Debug: Print app usage
        if (usageDuration.inMinutes > 0) {
          print("${app.packageName}: ${usageDuration.inMinutes} minutes");
        }

        // Add to total time
        totalTime += usageDuration.inMinutes;

        // Create a new AppInfo object with updated usageDuration
        updatedAppList.add(
          AppInfo(
            appName: app.appName,
            packageName: app.packageName,
            isSystemApp: app.isSystemApp,
            icon: app.icon,
            usageDuration: usageDuration,
            timeLimit: app.timeLimit, // حفظ محدودیت زمانی قبلی
          ),
        );
      }

      // Sort apps by usage time (descending)
      updatedAppList.sort((a, b) => b.usageDuration.compareTo(a.usageDuration));

      final now = DateTime.now();
      setState(() {
        _apps = updatedAppList;
        totalUsageTime = totalTime;
        _screenTimeMillis = screenTimeResult as int;
        screenOnTime = _screenTimeMillis /
            (60 * 1000); // Convert to minutes for backwards compatibility
        _totalScreenTime = Duration(milliseconds: _screenTimeMillis);
        _lastUpdated = now;
        _lastUpdateTime = now;
        _isLoading = false;

        // Debug: Print updated values
        print("Total usage time: $totalTime minutes");
        print("Screen time: $_totalScreenTime");
        print("Last updated: $_lastUpdateTime");

        // محاسبه میانگین دقیق زمان استفاده برای ساعات مختلف روز
        _calculateHourlyAverages(result, startTime, endTime);
      });
    } catch (e) {
      print("Error updating app usage data: $e");
      setState(() {
        _isLoading = false;
      });
    }
  }

  // جدید: محاسبه میانگین زمان استفاده در ساعات مختلف روز
  void _calculateHourlyAverages(
      Map<dynamic, dynamic>? result, int startTime, int endTime) {
    if (result == null) return;

    // برای سادگی فقط لاگ می‌کنیم، اما می‌توان برای نمایش در رابط کاربری استفاده کرد
    print("=== تحلیل دقیق زمان استفاده در 24 ساعت گذشته ===");
    print("زمان کل روشن بودن صفحه: $_totalScreenTime");
    print(
        "زمان کل استفاده از برنامه‌ها: ${Duration(minutes: totalUsageTime.toInt())}");

    // محاسبه نسبت زمان استفاده از برنامه‌ها به زمان روشن بودن صفحه
    if (_totalScreenTime.inSeconds > 0) {
      final ratio = totalUsageTime * 60 / _totalScreenTime.inSeconds;
      print(
          "نسبت زمان استفاده از برنامه‌ها به زمان روشن بودن صفحه: ${ratio.toStringAsFixed(2)}");
    }
  }

  String _formatDuration(Duration duration) {
    if (duration.inSeconds < 60) {
      return "${duration.inSeconds} ثانیه";
    } else if (duration.inMinutes < 60) {
      // افزودن ثانیه‌ها برای دقت بیشتر
      final int minutes = duration.inMinutes;
      final int seconds = duration.inSeconds % 60;
      return "$minutes دقیقه${seconds > 0 ? ' و $seconds ثانیه' : ''}";
    } else {
      int hours = duration.inHours;
      int minutes = duration.inMinutes % 60;
      int seconds = duration.inSeconds % 60;
      String result = "$hours ساعت";
      if (minutes > 0) {
        result += " و $minutes دقیقه";
      }
      if (seconds > 0) {
        result += " و $seconds ثانیه";
      }
      return result;
    }
  }

  Future<void> updateAppUsage() async {
    if (!_hasUsagePermission) {
      return;
    }

    try {
      setState(() {
        _isLoading = true;
      });

      await _updateAppsWithUsageData();
    } catch (e) {
      print("Error in updateAppUsage: $e");
      setState(() {
        _isLoading = false;
      });
    }
  }

  Future<void> _updateScreenTimeOnly() async {
    if (!_hasUsagePermission) {
      return;
    }

    try {
      setState(() {
        _isLoading = true;
      });

      final endTime = DateTime.now().millisecondsSinceEpoch;
      // Get timestamp for 24 hours ago
      final startTime = endTime - 24 * 60 * 60 * 1000;

      // Get screen on time only
      final screenTimeResult =
          await _methodChannel.invokeMethod('getScreenOnTime', {
        'startTime': startTime,
        'endTime': endTime,
      });

      print(
          "Raw screen time from native (update only): $screenTimeResult milliseconds");

      setState(() {
        _screenTimeMillis = screenTimeResult as int;
        screenOnTime = _screenTimeMillis /
            (60 * 1000); // Convert to minutes for backwards compatibility
        _lastUpdated = DateTime.now();
        _isLoading = false;
      });
    } catch (e) {
      print("Error updating screen time: $e");
      setState(() {
        _isLoading = false;
      });
    }
  }

  Future<void> _checkServiceStatus() async {
    try {
      // بررسی وضعیت سرویس‌ها
      final hasOverlayPermission =
          await _methodChannel.invokeMethod('checkOverlayPermission');
      final hasAccessibility =
          await _methodChannel.invokeMethod('checkAccessibilityServiceEnabled');

      // بررسی دقیق‌تر وضعیت سرویس دسترسی‌پذیری
      final serviceStatus =
          await _methodChannel.invokeMethod('checkAccessibilityServiceStatus');

      setState(() {
        _hasOverlayPermission = hasOverlayPermission == true;
        _hasAccessibilityPermission = hasAccessibility == true;

        // اگر سرویس فعال شده اما در حال اجرا نیست، سعی کنیم دوباره آن را اجرا کنیم
        if (_hasAccessibilityPermission &&
            serviceStatus is Map &&
            serviceStatus['isEnabled'] == true &&
            serviceStatus['isRunning'] == false) {
          print(
              "Accessibility service is enabled but not running. Attempting to restart...");
          _ensureAccessibilityServiceRunning();
        }
      });

      // اگر سرویس‌ها فعال هستند، مانیتورینگ را شروع کن
      if (_hasAccessibilityPermission &&
          _hasOverlayPermission &&
          _hasUsagePermission) {
        await _methodChannel.invokeMethod('startMonitoringService');
        _isMonitoringServiceRunning = true;

        // شروع بررسی‌های دوره‌ای
        _startAppMonitoring();
      }
    } catch (e) {
      print("Error checking service status: $e");
    }
  }

  Future<void> _ensureAccessibilityServiceRunning() async {
    try {
      final result = await _methodChannel
          .invokeMethod('ensureAccessibilityServiceRunning');
      print("Service restart attempt result: $result");

      // بررسی مجدد وضعیت پس از تلاش برای راه‌اندازی مجدد
      Future.delayed(const Duration(seconds: 2), () {
        _checkServiceStatus();
      });
    } catch (e) {
      print("Error ensuring accessibility service is running: $e");
    }
  }

  Future<void> _requestAccessibilityPermission() async {
    if (!_showingPermissionDialog) {
      _showingPermissionDialog = true;

      bool shouldReload = await showDialog<bool>(
            context: context,
            barrierDismissible: false,
            builder: (context) => AlertDialog(
              title: const Text('اجازه دسترسی'),
              content: const Text(
                'برای قفل خودکار برنامه‌ها، این برنامه نیاز به دسترسی خدمات دسترسی‌پذیری دارد.\n\n'
                'لطفاً در صفحه بعدی، سرویس "App Lock" را فعال کنید.',
              ),
              actions: [
                TextButton(
                  onPressed: () {
                    Navigator.of(context).pop(false); // Reload after
                  },
                  child: const Text('فعلاً نه'),
                ),
                ElevatedButton(
                  onPressed: () {
                    Navigator.of(context).pop(true); // Reload after
                  },
                  child: const Text('فعال کردن'),
                ),
              ],
            ),
          ) ??
          false;

      _showingPermissionDialog = false;

      if (shouldReload) {
        try {
          await _methodChannel.invokeMethod('openAccessibilitySettings');

          // زمان دادن به کاربر برای فعال کردن سرویس
          await Future.delayed(const Duration(seconds: 3));

          // بررسی وضعیت سرویس‌
          await _checkServiceStatus();

          // اگر فعال شده، مطمئن شو که در حال اجراست
          if (_hasAccessibilityPermission) {
            await _ensureAccessibilityServiceRunning();
          }
        } catch (e) {
          print("Error opening accessibility settings: $e");
        }
      }
    }
  }

  // درخواست دسترسی نمایش روی سایر برنامه‌ها (Overlay)
  Future<void> _requestOverlayPermission() async {
    if (!_showingPermissionDialog) {
      _showingPermissionDialog = true;

      bool shouldOpen = await showDialog<bool>(
            context: context,
            barrierDismissible: false,
            builder: (context) => AlertDialog(
              title: const Text('دسترسی نمایش روی برنامه‌ها'),
              content: const Text(
                'برای نمایش صفحه قفل، این برنامه نیاز به دسترسی نمایش روی سایر برنامه‌ها دارد.\n\n'
                'لطفاً در صفحه بعدی، این دسترسی را فعال کنید.',
              ),
              actions: [
                TextButton(
                  onPressed: () {
                    Navigator.of(context).pop(false);
                  },
                  child: const Text('فعلاً نه'),
                ),
                ElevatedButton(
                  onPressed: () {
                    Navigator.of(context).pop(true);
                  },
                  child: const Text('فعال کردن'),
                ),
              ],
            ),
          ) ??
          false;

      _showingPermissionDialog = false;

      if (shouldOpen) {
        try {
          await _methodChannel.invokeMethod('openOverlaySettings');

          // Wait a bit for user to grant permission
          await Future.delayed(const Duration(seconds: 3));

          // Check if permission was granted
          final hasPermission =
              await _methodChannel.invokeMethod('checkOverlayPermission');
          setState(() {
            _hasOverlayPermission = hasPermission == true;
          });
        } catch (e) {
          print("Error opening overlay settings: $e");
        }
      }
    }
  }

  // متد جدید برای درخواست دسترسی‌های مورد نیاز
  Future<void> _requestRequiredPermissions() async {
    // درخواست دسترسی usage stats
    if (!_hasUsagePermission) {
      await _requestUsagePermission();
    }

    // درخواست دسترسی accessibility
    if (!_hasAccessibilityPermission) {
      await _requestAccessibilityPermission();
    }

    // درخواست دسترسی overlay اگر نیاز باشد
    if (!_hasOverlayPermission) {
      await _requestOverlayPermission();
    }

    // بررسی مجدد وضعیت سرویس‌ها
    await _checkServiceStatus();
  }

  // شروع سرویس مانیتورینگ در سمت اندروید
  Future<void> _startMonitoringService() async {
    try {
      // فقط اگر سرویس در حال اجرا نیست، شروع کن
      if (!_isMonitoringServiceRunning) {
        await _methodChannel.invokeMethod('startMonitoringService');
        setState(() {
          _isMonitoringServiceRunning = true;
        });
        print("Monitoring service started");
      } else {
        print("Monitoring service is already running");
      }
    } catch (e) {
      print("Error starting monitoring service: $e");
    }
  }

  // توقف سرویس نظارت
  Future<void> _stopMonitoringService() async {
    try {
      await _methodChannel.invokeMethod('stopMonitoringService');
      setState(() {
        _isMonitoringServiceRunning = false;
      });
    } catch (e) {
      print("Error stopping monitoring service: $e");
    }
  }

  // تنظیم محدودیت زمانی برای یک اپلیکیشن
  Future<void> _setAppTimeLimit(AppInfo app, Duration limit) async {
    try {
      // تبدیل محدودیت زمانی به دقیقه
      final limitInMinutes = limit.inMinutes;

      // ذخیره در نیتیو
      await _methodChannel.invokeMethod('setAppTimeLimit', {
        'packageName': app.packageName,
        'limitMinutes': limitInMinutes,
      });

      setState(() {
        final index = _apps.indexWhere((a) => a.packageName == app.packageName);
        if (index >= 0) {
          // ایجاد نسخه جدید از AppInfo با محدودیت زمانی جدید
          _apps[index] = AppInfo(
            appName: app.appName,
            packageName: app.packageName,
            isSystemApp: app.isSystemApp,
            icon: app.icon,
            usageDuration: app.usageDuration,
            timeLimit: limit,
          );

          // ذخیره محدودیت در مپ
          if (limit.inSeconds > 0) {
            _timeLimits[app.packageName] = limit;
          } else {
            _timeLimits.remove(app.packageName);
          }
        }
      });

      // اگر سرویس در حال اجرا نیست، شروع کن
      if (!_isMonitoringServiceRunning) {
        await _startMonitoringService();
      }
    } catch (e) {
      print("Error setting app time limit: $e");
    }
  }

  // حذف محدودیت زمانی برای یک اپلیکیشن
  Future<void> _removeAppTimeLimit(AppInfo app) async {
    try {
      await _methodChannel.invokeMethod('removeAppTimeLimit', {
        'packageName': app.packageName,
      });

      setState(() {
        final index = _apps.indexWhere((a) => a.packageName == app.packageName);
        if (index >= 0) {
          // ایجاد نسخه جدید از AppInfo با محدودیت زمانی صفر
          _apps[index] = AppInfo(
            appName: app.appName,
            packageName: app.packageName,
            isSystemApp: app.isSystemApp,
            icon: app.icon,
            usageDuration: app.usageDuration,
            timeLimit: Duration.zero,
          );

          // حذف محدودیت از مپ
          _timeLimits.remove(app.packageName);
        }
      });
    } catch (e) {
      print("Error removing app time limit: $e");
    }
  }

  // پاک کردن تمام داده‌های استفاده
  Future<void> _resetAllAppUsage() async {
    try {
      setState(() {
        _isLoading = true;
      });

      print("Resetting all app usage data...");

      // Call the native method to reset usage data
      final result = await _methodChannel.invokeMethod('resetAppUsageData');
      print("Reset result: $result");

      // Update the last reset time
      final now = DateTime.now();
      await _saveLastResetTime(now);

      // Update the UI with fresh data
      await _updateAppsWithUsageData();

      print("App usage data reset successfully");
    } catch (e) {
      print("Error resetting app usage data: $e");
      setState(() {
        _isLoading = false;
      });
    }
  }

  // متد برای نمایش متن زمان بعدی ریست شدن آمار
  String _getNextResetText() {
    if (_lastResetTime == null) return '';

    // Calculate when the next reset would be (24 hours after last reset)
    final nextReset = _lastResetTime!.add(const Duration(hours: 24));
    final now = DateTime.now();
    final difference = nextReset.difference(now);

    if (difference.isNegative) {
      return 'آمار استفاده از برنامه‌ها به زودی ریست خواهد شد.';
    }

    // Format the remaining time until reset
    if (difference.inHours > 0) {
      return 'آمار استفاده از برنامه‌ها ${difference.inHours} ساعت و ${difference.inMinutes.remainder(60)} دقیقه دیگر ریست می‌شود.';
    } else if (difference.inMinutes > 0) {
      return 'آمار استفاده از برنامه‌ها ${difference.inMinutes} دقیقه دیگر ریست می‌شود.';
    } else {
      return 'آمار استفاده از برنامه‌ها به زودی ریست خواهد شد.';
    }
  }

  // آزاد کردن برنامه قفل شده
  Future<void> _unlockApp(String packageName) async {
    try {
      final bool result = await platform.invokeMethod('unlockApp', {
        'packageName': packageName,
      });

      if (result) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('محدودیت برنامه $packageName برداشته شد'),
            duration: const Duration(seconds: 2),
          ),
        );
        // بروزرسانی لیست برنامه‌ها
        await _initApp();
      }
    } catch (e) {
      print("Error unlocking app: $e");
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('خطا در برداشتن محدودیت: $e'),
          backgroundColor: Colors.red,
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
        title: const Text('آمار استفاده از برنامه‌ها'),
        actions: [
          if (!_hasUsagePermission || !_hasAccessibilityPermission)
            IconButton(
              icon: const Icon(Icons.settings),
              tooltip: 'دسترسی‌های مورد نیاز',
              onPressed: _requestRequiredPermissions,
            ),
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: 'به‌روزرسانی',
            onPressed: _initApp,
          ),
        ],
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : _apps.isEmpty
              ? _buildEmptyState()
              : _buildAppsList(),
    );
  }

  Widget _buildEmptyState() {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const Icon(Icons.apps, size: 64, color: Colors.grey),
          const SizedBox(height: 16),
          const Text(
            'هیچ برنامه‌ای یافت نشد',
            style: TextStyle(fontSize: 18),
          ),
          const SizedBox(height: 24),
          ElevatedButton.icon(
            icon: const Icon(Icons.refresh),
            label: const Text('تلاش دوباره'),
            onPressed: _initApp,
          ),
        ],
      ),
    );
  }

  Widget _buildAppsList() {
    return SingleChildScrollView(
      child: Column(
        children: [
          // آمار دقیق زمان روشن بودن صفحه
          _buildScreenTimeStats(),

          // لیست برنامه‌ها
          Container(
            height: MediaQuery.of(context).size.height -
                240, // Fixed height for list
            child: ListView.builder(
              physics: const AlwaysScrollableScrollPhysics(),
              shrinkWrap: true,
              itemCount: _apps.length,
              itemBuilder: (context, index) {
                final app = _apps[index];
                final currentUsage =
                    getCurrentAppUsage(app, _currentForegroundApp);
                final usageMinutes = currentUsage.inMinutes;
                final isCurrentlyActive =
                    app.packageName == _currentForegroundApp;

                // Define color based on usage time
                Color usageColor;
                if (usageMinutes > 180) {
                  // More than 3 hours
                  usageColor = Colors.red;
                } else if (usageMinutes > 60) {
                  // 1-3 hours
                  usageColor = Colors.orange;
                } else if (usageMinutes > 15) {
                  // 15-60 minutes
                  usageColor = Colors.amber;
                } else if (usageMinutes > 0) {
                  // 1-15 minutes
                  usageColor = Colors.green;
                } else {
                  // No usage
                  usageColor = Colors.grey;
                }

                return Card(
                  margin:
                      const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                  color: isCurrentlyActive ? Colors.blue.shade50 : null,
                  child: Column(
                    children: [
                      ListTile(
                        contentPadding: const EdgeInsets.all(8),
                        leading: Stack(
                          children: [
                            ClipRRect(
                              borderRadius: BorderRadius.circular(8),
                              child: Image.memory(
                                app.icon,
                                width: 48,
                                height: 48,
                                fit: BoxFit.contain,
                                errorBuilder: (context, error, stackTrace) {
                                  return Container(
                                    width: 48,
                                    height: 48,
                                    color: Colors.grey[300],
                                    child: const Icon(Icons.broken_image,
                                        color: Colors.white),
                                  );
                                },
                              ),
                            ),
                            if (isCurrentlyActive)
                              Positioned(
                                right: 0,
                                bottom: 0,
                                child: Container(
                                  padding: const EdgeInsets.all(2),
                                  decoration: BoxDecoration(
                                    color: Colors.green,
                                    shape: BoxShape.circle,
                                  ),
                                  child: const Icon(
                                    Icons.play_arrow,
                                    color: Colors.white,
                                    size: 12,
                                  ),
                                ),
                              ),
                          ],
                        ),
                        title: Row(
                          children: [
                            Expanded(
                              child: Text(
                                app.appName,
                                style: const TextStyle(
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                            ),
                            if (isCurrentlyActive)
                              Container(
                                padding: const EdgeInsets.symmetric(
                                    horizontal: 6, vertical: 2),
                                decoration: BoxDecoration(
                                  color: Colors.green,
                                  borderRadius: BorderRadius.circular(10),
                                ),
                                child: const Text(
                                  'در حال استفاده',
                                  style: TextStyle(
                                      color: Colors.white, fontSize: 10),
                                ),
                              ),
                          ],
                        ),
                        subtitle: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            const SizedBox(height: 4),
                            Text(
                              app.packageName,
                              style: TextStyle(
                                  fontSize: 12, color: Colors.grey[600]),
                            ),
                          ],
                        ),
                        trailing: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          crossAxisAlignment: CrossAxisAlignment.end,
                          children: [
                            Text(
                              _hasUsagePermission
                                  ? _formatDuration(currentUsage)
                                  : '-',
                              style: TextStyle(
                                fontWeight: FontWeight.bold,
                                color: usageColor,
                              ),
                            ),
                            if (_hasUsagePermission) ...[
                              const SizedBox(height: 4),
                              Container(
                                width: 100,
                                height: 6,
                                decoration: BoxDecoration(
                                  color: Colors.grey[200],
                                  borderRadius: BorderRadius.circular(3),
                                ),
                                child: FractionallySizedBox(
                                  alignment: Alignment.centerLeft,
                                  widthFactor: usageMinutes > 0
                                      ? (usageMinutes > 360
                                          ? 1.0
                                          : usageMinutes / 360)
                                      : 0,
                                  child: Container(
                                    decoration: BoxDecoration(
                                      color: usageColor,
                                      borderRadius: BorderRadius.circular(3),
                                    ),
                                  ),
                                ),
                              ),
                            ],
                          ],
                        ),
                      ),
                      // افزودن دکمه محدودیت زمانی
                      if (_hasUsagePermission)
                        Padding(
                          padding: const EdgeInsets.symmetric(
                              horizontal: 16, vertical: 8),
                          child: Row(
                            children: [
                              const Text('محدودیت زمانی:',
                                  style: TextStyle(fontSize: 13)),
                              const SizedBox(width: 8),
                              Expanded(
                                child: app.timeLimit.inMinutes > 0
                                    ? Text(
                                        _formatDuration(app.timeLimit),
                                        style: const TextStyle(
                                          color: Colors.red,
                                          fontWeight: FontWeight.bold,
                                        ),
                                      )
                                    : const Text('بدون محدودیت',
                                        style: TextStyle(color: Colors.grey)),
                              ),
                              IconButton(
                                icon: const Icon(Icons.timer, size: 20),
                                color: Colors.blue,
                                onPressed: () => _setTimeLimit(app),
                                tooltip: 'تنظیم محدودیت زمانی',
                              ),
                              // دکمه آزادسازی قفل برنامه (فقط برای برنامه‌های قفل شده نمایش داده می‌شود)
                              if (app.timeLimit.inMinutes > 0)
                                IconButton(
                                  icon: const Icon(Icons.lock_open, size: 20),
                                  color: Colors.orange,
                                  onPressed: () => _unlockApp(app.packageName),
                                  tooltip: 'آزادسازی قفل برنامه',
                                ),
                            ],
                          ),
                        ),
                    ],
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  // دیالوگ تنظیم محدودیت زمانی
  void _setTimeLimit(AppInfo app) {
    int hours = 0;
    int minutes = 0;

    // اگر قبلا محدودیتی تنظیم شده، آن را بارگذاری کنیم
    if (app.timeLimit.inSeconds > 0) {
      hours = app.timeLimit.inHours;
      minutes = app.timeLimit.inMinutes % 60;
    }

    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('${app.appName} محدودیت زمانی'),
        content: StatefulBuilder(
          builder: (context, setState) {
            return Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Text('مدت زمان مجاز استفاده را تعیین کنید:'),
                const SizedBox(height: 16),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                  children: [
                    Column(
                      children: [
                        const Text('ساعت'),
                        const SizedBox(height: 8),
                        SizedBox(
                          width: 80,
                          child: DropdownButton<int>(
                            value: hours,
                            isExpanded: true,
                            items: List.generate(24, (i) => i)
                                .map((h) => DropdownMenuItem(
                                      value: h,
                                      child: Text('$h'),
                                    ))
                                .toList(),
                            onChanged: (value) {
                              setState(() {
                                hours = value!;
                              });
                            },
                          ),
                        ),
                      ],
                    ),
                    Column(
                      children: [
                        const Text('دقیقه'),
                        const SizedBox(height: 8),
                        SizedBox(
                          width: 80,
                          child: DropdownButton<int>(
                            value: minutes,
                            isExpanded: true,
                            items: List.generate(60, (i) => i)
                                .map((m) => DropdownMenuItem(
                                      value: m,
                                      child: Text('$m'),
                                    ))
                                .toList(),
                            onChanged: (value) {
                              setState(() {
                                minutes = value!;
                              });
                            },
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
                if (!_hasAccessibilityPermission) ...[
                  const SizedBox(height: 16),
                  Container(
                    padding: const EdgeInsets.all(8),
                    color: Colors.red[50],
                    child: Row(
                      children: [
                        const Icon(Icons.warning, color: Colors.red, size: 16),
                        const SizedBox(width: 8),
                        const Expanded(
                          child: Text(
                            'برای فعال شدن قفل خودکار، دسترسی Accessibility مورد نیاز است',
                            style: TextStyle(fontSize: 12),
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ],
            );
          },
        ),
        actions: [
          TextButton(
            onPressed: () {
              Navigator.of(context).pop();
            },
            child: const Text('لغو'),
          ),
          TextButton(
            onPressed: () {
              _removeAppTimeLimit(app);
              Navigator.of(context).pop();
            },
            child: const Text('حذف محدودیت'),
          ),
          ElevatedButton(
            onPressed: () {
              final duration = Duration(hours: hours, minutes: minutes);
              if (duration.inSeconds > 0) {
                _setAppTimeLimit(app, duration);
              }
              Navigator.of(context).pop();

              // اگر دسترسی accessibility فعال نیست، درخواست کن
              if (!_hasAccessibilityPermission) {
                _requestAccessibilityPermission();
              }
            },
            child: const Text('ذخیره'),
          ),
        ],
      ),
    );
  }

  // نمایش آمار دقیق استفاده از گوشی
  Widget _buildScreenTimeStats() {
    // Debug print
    print(
        "Building screen time stats: Total screen time: $_totalScreenTime, Last update: $_lastUpdateTime");

    // محاسبه دقیق درصدها
    final totalDayMinutes = 24 * 60;
    final screenTimeMinutes = currentScreenTime.inMinutes.toDouble();
    final screenTimePercent =
        (screenTimeMinutes / totalDayMinutes * 100).toStringAsFixed(1);

    // محاسبه درصد استفاده از برنامه‌ها نسبت به زمان روشن بودن صفحه
    final appUsagePercent = screenTimeMinutes > 0
        ? (totalUsageTime / screenTimeMinutes * 100).toStringAsFixed(1)
        : "0.0";

    return Container(
      margin: const EdgeInsets.all(16.0),
      padding: const EdgeInsets.all(16.0),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(12.0),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withOpacity(0.1),
            blurRadius: 8.0,
            offset: const Offset(0, 2),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // زمان روشن بودن صفحه
          Row(
            children: [
              Icon(Icons.smartphone, color: Colors.blue[700], size: 28),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      'زمان روشن بودن صفحه',
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      '${_formatDuration(currentScreenTime)} (${screenTimePercent}% از کل روز)',
                      style: TextStyle(
                        fontSize: 15,
                        color: Colors.blue[700],
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    Row(
                      children: [
                        Text(
                          'دقیق: ${(_screenTimeMillis / 1000).toStringAsFixed(1)} ثانیه',
                          style: TextStyle(
                            fontSize: 12,
                            color: Colors.blue[400],
                          ),
                        ),
                        if (_secondsPassedSinceLastUpdate > 0)
                          Text(
                            ' (+${_secondsPassedSinceLastUpdate}s)',
                            style: TextStyle(
                              fontSize: 12,
                              color: Colors.orange[400],
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                      ],
                    ),
                  ],
                ),
              ),
            ],
          ),

          const SizedBox(height: 6),

          // نمایش نوار پیشرفت زمان روشن بودن صفحه
          ClipRRect(
            borderRadius: BorderRadius.circular(4),
            child: LinearProgressIndicator(
              value: screenTimeMinutes / totalDayMinutes,
              backgroundColor: Colors.grey[200],
              color: Colors.blue[600],
              minHeight: 8,
            ),
          ),

          const SizedBox(height: 20),

          // زمان استفاده از اپلیکیشن‌ها
          Row(
            children: [
              Icon(Icons.apps, color: Colors.green[700], size: 28),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      'زمان استفاده از برنامه‌ها',
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      '${_formatDuration(Duration(minutes: totalUsageTime.toInt()))} (${appUsagePercent}% از زمان روشن بودن)',
                      style: TextStyle(
                        fontSize: 15,
                        color: Colors.green[700],
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),

          const SizedBox(height: 6),

          // نمایش نوار پیشرفت زمان استفاده از برنامه‌ها
          ClipRRect(
            borderRadius: BorderRadius.circular(4),
            child: LinearProgressIndicator(
              value: screenTimeMinutes > 0
                  ? totalUsageTime / screenTimeMinutes
                  : 0,
              backgroundColor: Colors.grey[200],
              color: Colors.green[600],
              minHeight: 8,
            ),
          ),

          const SizedBox(height: 16),

          // اطلاعات به‌روزرسانی
          if (_lastUpdateTime != null)
            Row(
              children: [
                Icon(Icons.update, color: Colors.grey[700], size: 18),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'آخرین به‌روزرسانی: ${_formatDateTime(_lastUpdateTime!)}',
                    style: TextStyle(
                      fontSize: 13,
                      color: Colors.grey[700],
                    ),
                  ),
                ),
              ],
            ),

          // نمایش زمان ریست بعدی
          if (_lastResetTime != null)
            Padding(
              padding: const EdgeInsets.only(top: 8.0),
              child: Row(
                children: [
                  Icon(Icons.timer_outlined,
                      color: Colors.amber[700], size: 18),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      _getNextResetText(),
                      style: TextStyle(
                        fontSize: 13,
                        color: Colors.amber[700],
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                ],
              ),
            ),

          // دکمه پاک کردن آمار
          const SizedBox(height: 16),
          Center(
            child: ElevatedButton.icon(
              onPressed: _resetAllAppUsage,
              style: ElevatedButton.styleFrom(
                backgroundColor: Colors.redAccent,
                foregroundColor: Colors.white,
                padding:
                    const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
              ),
              icon: const Icon(Icons.cleaning_services_outlined),
              label: const Text('پاک کردن آمار',
                  style: TextStyle(fontWeight: FontWeight.bold)),
            ),
          ),
        ],
      ),
    );
  }

  // تبدیل DateTime به فرمت نمایشی
  String _formatDateTime(DateTime dateTime) {
    final now = DateTime.now();
    final difference = now.difference(dateTime);

    if (difference.inMinutes < 1) {
      return 'همین الان';
    } else if (difference.inMinutes < 60) {
      return '${difference.inMinutes} دقیقه پیش';
    } else if (difference.inHours < 24) {
      return '${difference.inHours} ساعت پیش';
    } else {
      return '${difference.inDays} روز پیش';
    }
  }

  // شروع تایمر زنده برای به‌روزرسانی نمایش زمان استفاده در لحظه
  void _startLiveTimer() {
    _liveUpdateTimer?.cancel();
    _secondsPassedSinceLastUpdate = 0;

    _liveUpdateTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (_hasUsagePermission && _lastUpdateTime != null) {
        _updateCurrentApp();
        setState(() {
          _secondsPassedSinceLastUpdate++;
        });
      }
    });
  }

  // محاسبه زمان کنونی با در نظر گرفتن زمان گذشته از آخرین به‌روزرسانی
  Duration get currentScreenTime {
    if (_totalScreenTime.inSeconds == 0 || _lastUpdateTime == null) {
      return _totalScreenTime;
    }
    // تخمین زمان جاری با افزودن ثانیه‌های گذشته از آخرین به‌روزرسانی
    return _totalScreenTime + Duration(seconds: _secondsPassedSinceLastUpdate);
  }

  // محاسبه زمان استفاده فعلی از یک برنامه
  Duration getCurrentAppUsage(AppInfo app, String? currentForegroundApp) {
    if (app.packageName == currentForegroundApp && _lastUpdateTime != null) {
      // اگر برنامه در حال استفاده است، زمان گذشته از آخرین به‌روزرسانی را اضافه کن
      return app.usageDuration +
          Duration(seconds: _secondsPassedSinceLastUpdate);
    }
    return app.usageDuration;
  }

  // بررسی و به‌روزرسانی برنامه فعال فعلی
  Future<void> _updateCurrentApp() async {
    if (!_hasUsagePermission || !_hasAccessibilityPermission) return;

    try {
      final currentApp =
          await _methodChannel.invokeMethod('getCurrentForegroundApp');
      setState(() {
        _currentForegroundApp = currentApp as String?;
      });
    } catch (e) {
      print("Error getting current foreground app: $e");
    }
  }
}
