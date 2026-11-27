package com.volti.call

import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** تحديث التطبيق عبر ملف versions.json المستضاف على GitHub Pages. */
object Update {

    const val MANIFEST = "https://voltir-hema.github.io/KIDS/updates/versions.json"

    data class Info(
        val url: String,
        val code: Int,
        val version: String,
        val notes: String,
        val size: Long,
    )

    fun installedCode(ctx: Context): Int = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
    } catch (e: Exception) {
        0
    }

    fun version(ctx: Context): String = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        pi.versionName + " (" + installedCode(ctx) + ")"
    } catch (e: Exception) {
        "?"
    }

    fun manifestUrl(ctx: Context): String =
        Fb.prefs(ctx).getString("update_url", "").orEmpty().ifBlank { MANIFEST }

    fun check(ctx: Context, cb: (Info?) -> Unit) {
        val base = manifestUrl(ctx)
        Thread {
            var info: Info? = null
            try {
                val c = URL(base).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 12000
                c.setRequestProperty("Cache-Control", "no-cache")
                val txt = c.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(txt)
                val cur = root.optJSONObject("current")
                if (cur != null) {
                    val code = cur.optInt("code", 0)
                    if (code > installedCode(ctx)) {
                        val file = cur.optString("file")
                        val dir = base.substringBeforeLast('/', "")
                        val url = if (file.startsWith("http")) file else dir + "/" + file.trimStart('/')
                        info = Info(
                            url,
                            code,
                            cur.optString("name", code.toString()),
                            cur.optString("notes", ""),
                            cur.optLong("size", 0L),
                        )
                    }
                }
            } catch (e: Exception) {
                info = null
            }
            Handler(Looper.getMainLooper()).post { cb(info) }
        }.start()
    }

    fun download(ctx: Context, info: Info, onProgress: (Int) -> Unit, cb: (File?) -> Unit) {
        Thread {
            var out: File? = null
            try {
                val c = URL(info.url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 300000
                c.instanceFollowRedirects = true
                val total = c.contentLengthLong.takeIf { it > 0 } ?: info.size
                val f = File(ctx.cacheDir, "volti_update.apk")
                if (f.exists()) f.delete()
                c.inputStream.use { ins ->
                    f.outputStream().use { os ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val n = ins.read(buf)
                            if (n <= 0) break
                            os.write(buf, 0, n)
                            done += n
                            val p = if (total > 0) ((done * 100) / total).toInt() else 0
                            if (p != last) {
                                last = p
                                Handler(Looper.getMainLooper()).post { onProgress(p.coerceIn(0, 100)) }
                            }
                        }
                    }
                }
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

    /** يفحص التحديث عند فتح التطبيق ويعرض نافذة تحديث مع شريط تحميل. */
    fun prompt(act: Activity) {
        check(act) { info ->
            if (info == null || act.isFinishing) return@check
            val mb = String.format(Locale.US, "%.1f", info.size / 1048576.0)
            AlertDialog.Builder(act)
                .setTitle("تحديث متاح — " + info.version)
                .setMessage(
                    (if (info.notes.isBlank()) "نسخة جديدة من VOLTIR CALL جاهزة." else info.notes) +
                        "\n\nالحجم: " + mb + " ميجا",
                )
                .setPositiveButton("تحديث الآن") { _, _ -> downloadDialog(act, info) }
                .setNegativeButton("لاحقاً", null)
                .show()
        }
    }

    fun downloadDialog(act: Activity, info: Info) {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(20), act.dp(16), act.dp(20), act.dp(4))
        }
        val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val txt = TextView(act).apply {
            text = "جارٍ التنزيل… 0%"
            textSize = 13f
            setPadding(0, act.dp(8), 0, 0)
        }
        box.addView(bar)
        box.addView(txt)
        val dlg = AlertDialog.Builder(act)
            .setTitle("تنزيل التحديث")
            .setView(box)
            .setCancelable(false)
            .create()
        dlg.show()
        download(act, info, { p ->
            bar.progress = p
            txt.text = "جارٍ التنزيل… " + p + "%"
        }) { f ->
            if (act.isFinishing) return@download
            dlg.dismiss()
            if (f == null) {
                android.widget.Toast.makeText(act, "فشل التنزيل — تحقق من الإنترنت", android.widget.Toast.LENGTH_LONG).show()
                return@download
            }
            install(act, f)
        }
    }
}
