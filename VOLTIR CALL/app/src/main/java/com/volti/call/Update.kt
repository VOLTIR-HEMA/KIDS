package com.volti.call

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** تحديث التطبيق من خادم محلي (سكريبت server/update_server.py). */
object Update {

    data class Info(val url: String, val size: Long, val sha: String, val version: String)

    fun version(ctx: Context): String = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        pi.versionName + " (" + code + ")"
    } catch (e: Exception) {
        "?"
    }

    fun sha256(f: File): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { String.format("%02x", it) }
    } catch (e: Exception) {
        null
    }

    fun installedSha(ctx: Context): String? = try {
        sha256(File(ctx.applicationInfo.sourceDir))
    } catch (e: Exception) {
        null
    }

    fun check(ctx: Context, base: String, cb: (Info?) -> Unit) {
        val url = base.trim().trimEnd('/')
        if (url.isBlank()) {
            cb(null)
            return
        }
        Thread {
            var info: Info? = null
            try {
                val sha = installedSha(ctx).orEmpty()
                val c = URL(url + "/update.json?sha=" + URLEncoder.encode(sha, "UTF-8")).openConnection() as HttpURLConnection
                c.connectTimeout = 7000
                c.readTimeout = 12000
                val txt = c.inputStream.bufferedReader().use { it.readText() }
                val o = JSONObject(txt)
                if (o.optBoolean("available")) {
                    val u = o.optString("url")
                    val full = if (u.startsWith("http")) u else url + "/" + u.trimStart('/')
                    info = Info(full, o.optLong("size"), o.optString("sha256"), o.optString("version"))
                }
            } catch (e: Exception) {
                info = null
            }
            Handler(Looper.getMainLooper()).post { cb(info) }
        }.start()
    }

    fun download(ctx: Context, info: Info, cb: (File?) -> Unit) {
        Thread {
            var out: File? = null
            try {
                val c = URL(info.url).openConnection() as HttpURLConnection
                c.connectTimeout = 12000
                c.readTimeout = 300000
                val f = File(ctx.cacheDir, "volti_update.apk")
                if (f.exists()) f.delete()
                c.inputStream.use { ins -> f.outputStream().use { os -> ins.copyTo(os, 64 * 1024) } }
                out = if (f.length() > 0) f else null
            } catch (e: Exception) {
                out = null
            }
            val r = out
            Handler(Looper.getMainLooper()).post { cb(r) }
        }.start()
    }

    fun install(ctx: Context, f: File) {
        try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            android.widget.Toast.makeText(ctx, "تعذر بدء التثبيت: " + (e.localizedMessage ?: ""), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun openInstallSettings(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            android.widget.Toast.makeText(ctx, "افتح الإعدادات ← تطبيقات ← إذن تثبيت التطبيقات", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
