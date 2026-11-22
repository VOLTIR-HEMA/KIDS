package com.kids.monitor.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.google.android.gms.tasks.Tasks
import com.google.firebase.storage.FirebaseStorage
import java.io.File

/**
 * رفع الملفات إلى Firebase Storage داخل مسار المستخدم/الجهاز ليطابق قواعد الأمان:
 * users/{uid}/devices/{deviceId}/<path>
 * ولو فشل الرفع (قواعد/شبكة) نوفّر بديلاً Base64 صغيراً ليظهر في لوحة التحكم.
 */
object StorageUploader {

    fun fullPath(path: String): String? {
        val uid = FirestoreSync.uid() ?: return null
        return "users/$uid/devices/${deviceId()}/" + path.trimStart('/')
    }

    private var cachedDeviceId: String? = null

    private fun deviceId(): String {
        cachedDeviceId?.let { return it }
        val ctx = appContext ?: return "unknown"
        return FirestoreSync.deviceId(ctx).also { cachedDeviceId = it }
    }

    @Volatile
    var appContext: Context? = null

    /** رفع ملف إلى Storage وإرجاع رابط التنزيل (متزامن، يُستخدم من خيط خلفية). */
    fun uploadSync(file: File, path: String): String? {
        val full = fullPath(path) ?: return null
        return try {
            val ref = FirebaseStorage.getInstance().reference.child(full)
            Tasks.await(ref.putFile(Uri.fromFile(file)))
            Tasks.await(ref.downloadUrl)?.toString()
        } catch (e: Exception) {
            null
        }
    }

    /** رفع محتوى من Uri (مثل تسجيل من MediaStore). */
    fun uploadUri(context: Context, uri: Uri, path: String): String? {
        appContext = context.applicationContext
        val full = fullPath(path) ?: return null
        return try {
            val ref = FirebaseStorage.getInstance().reference.child(full)
            context.contentResolver.openInputStream(uri)?.use { stream ->
                Tasks.await(ref.putStream(stream))
            } ?: return null
            Tasks.await(ref.downloadUrl)?.toString()
        } catch (e: Exception) {
            null
        }
    }

    /** حذف ملف قديم لتوفير المساحة المجانية. */
    fun delete(path: String) {
        val full = fullPath(path) ?: return
        try {
            Tasks.await(FirebaseStorage.getInstance().reference.child(full).delete())
        } catch (e: Exception) { /* تجاهل */ }
    }

    /** بديل عند فشل الرفع: تحويل صغير لـ Base64 (حد أقصى ~250KB). */
    fun inlineData(file: File, maxBytes: Int = 250_000): String? {
        return try {
            if (!file.exists() || file.length() == 0L || file.length() > maxBytes) return null
            val bytes = file.readBytes()
            val mime = when (file.extension.lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "m4a", "mp4", "aac" -> "audio/mp4"
                "3gp" -> "audio/3gpp"
                else -> "application/octet-stream"
            }
            "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }
}
