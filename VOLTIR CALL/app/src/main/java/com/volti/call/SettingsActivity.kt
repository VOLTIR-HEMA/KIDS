package com.volti.call

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var nameField: EditText
    private lateinit var phoneField: EditText
    private lateinit var iceField: EditText
    private lateinit var urlField: EditText
    private lateinit var updateStatus: TextView
    private lateinit var updateBtn: TextView
    private var pending: Update.Info? = null
    private var autoChecked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(build())
        autoCheck()
    }

    override fun onResume() {
        super.onResume()
        if (!autoChecked) autoCheck()
    }

    private fun autoCheck() {
        val base = Fb.prefs(this).getString("update_url", "").orEmpty()
        if (base.isNotBlank()) {
            autoChecked = true
            checkUpdate(false)
        }
    }

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F7F8FA"))
        }
        root.addView(topBar(this, "الإعدادات", "الحساب والتحديث والاتصال", { finish() }))

        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(28))
        }
        scroll.addView(body)

        // ---------------- التحديث
        body.addView(head("تحديث التطبيق"))
        updateStatus = label(this, "النسخة الحالية: " + Update.version(this), 13f, Color.parseColor("#667781")).apply {
            setPadding(0, 0, 0, dp(6))
        }
        body.addView(updateStatus)
        updateBtn = pillButton(this, "⬇️ تحديث الآن") { doUpdate() }
        updateBtn.visibility = View.GONE
        body.addView(updateBtn, fieldParams())
        urlField = editText(this, "http://192.168.1.5:8765").apply {
            setText(Fb.prefs(this@SettingsActivity).getString("update_url", "").orEmpty())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        body.addView(urlField, fieldParams(dp(6)))
        body.addView(pillButton(this, "حفظ عنوان خادم التحديث", false) {
            Fb.prefs(this).edit().putString("update_url", urlField.text.toString().trim()).apply()
            toast("تم الحفظ — جارٍ التحقق")
            pending = null
            checkUpdate(true)
        }, fieldParams(dp(6)))
        body.addView(pillButton(this, "التحقق من التحديث", false) { checkUpdate(true) }, fieldParams(dp(6)))
        body.addView(pillButton(this, "السماح بتثبيت التطبيقات (مطلوب للتحديث)", false) {
            Update.openInstallSettings(this)
        }, fieldParams(dp(6)))

        // ---------------- الحساب
        body.addView(head("الحساب"))
        val u = Fb.auth.currentUser
        body.addView(label(this, "@" + Repo.myUsername(this) + "   ·   " + (u?.email.orEmpty()), 13f, Color.parseColor("#111B21")).apply {
            setPadding(0, 0, 0, dp(4))
            maxLines = 2
        })
        body.addView(
            label(
                this,
                if (u?.isEmailVerified == true) "✔ البريد مؤكد" else "✉️ البريد غير مؤكد — اضغط لإرسال رابط التأكيد",
                12f,
                if (u?.isEmailVerified == true) Color.parseColor("#008069") else Color.parseColor("#B26A00"),
            ).apply {
                setPadding(0, 0, 0, dp(8))
                setOnClickListener {
                    if (u != null && !u.isEmailVerified) {
                        try { u.sendEmailVerification() } catch (e: Exception) { /* تجاهل */ }
                        toast("تم إرسال رابط التأكيد")
                    }
                }
            },
        )
        nameField = editText(this, "الاسم").apply { setText(Fb.myName(this@SettingsActivity)) }
        body.addView(nameField, fieldParams())
        phoneField = editText(this, "رقم الهاتف (اختياري)", android.text.InputType.TYPE_CLASS_PHONE).apply {
            setText(Fb.myPhone(this@SettingsActivity))
        }
        body.addView(phoneField, fieldParams())
        body.addView(pillButton(this, "حفظ الحساب") { saveProfile() }, fieldParams(dp(6)))

        // ---------------- ICE
        body.addView(head("خوادم TURN / STUN"))
        body.addView(label(this, "سطر لكل خادم:\nstun:host:port\nأو turn:host:port|user|pass\nاتركه فارغاً للخوادم الافتراضية.", 12f, Color.parseColor("#667781")).apply {
            setPadding(0, 0, 0, dp(8))
            maxLines = 6
        })
        iceField = editText(this, "stun:stun.l.google.com:19302", android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            minLines = 4
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            setText(Fb.prefs(this@SettingsActivity).getString("ice", "").orEmpty())
        }
        body.addView(iceField, fieldParams())
        body.addView(pillButton(this, "حفظ خوادم ICE") {
            Fb.prefs(this).edit().putString("ice", iceField.text.toString().trim()).apply()
            toast("تم حفظ الخوادم")
        }, fieldParams(dp(6)))

        // ---------------- الأذونات
        body.addView(head("الأذونات والعمل في الخلفية"))
        body.addView(pillButton(this, "منح أذونات الكاميرا والمايك والإشعارات", false) { Perm.ask(this) }, fieldParams(dp(6)))
        body.addView(pillButton(this, "تجاهل تحسين البطارية", false) { Perm.requestBattery(this) }, fieldParams(dp(6)))
        body.addView(pillButton(this, "إعدادات إشعارات التطبيق", false) {
            try {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            } catch (e: Exception) {
                toast("تعذر فتح الإعدادات")
            }
        }, fieldParams(dp(6)))

        body.addView(head("حول"))
        body.addView(label(this, "VOLTIR CALL " + Update.version(this) + "\nUID: " + Repo.uid(), 12f, Color.parseColor("#667781")).apply {
            maxLines = 4
            setTextIsSelectable(true)
        })

        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun checkUpdate(manual: Boolean) {
        val base = Fb.prefs(this).getString("update_url", "").orEmpty()
        if (base.isBlank()) {
            if (manual) toast("أدخل عنوان خادم التحديث أولاً (يظهر عند تشغيل السكريبت)")
            return
        }
        updateStatus.visibility = View.VISIBLE
        updateStatus.text = "جارٍ التحقق من التحديث…"
        Update.check(this, base) { info ->
            pending = info
            if (info == null) {
                updateStatus.text = "لا يوجد تحديث جديد — النسخة الحالية " + Update.version(this)
                updateBtn.visibility = View.GONE
                if (manual) toast("أنت على آخر نسخة")
            } else {
                val mb = String.format(Locale.US, "%.1f", info.size / 1048576.0)
                updateStatus.text = "يتوفر تحديث جديد (" + info.version + ") — " + mb + " ميجا"
                updateBtn.visibility = View.VISIBLE
            }
        }
    }

    private fun doUpdate() {
        val info = pending ?: return
        updateStatus.text = "جارٍ تنزيل التحديث…"
        updateBtn.visibility = View.GONE
        Update.download(this, info) { f ->
            if (f == null) {
                updateStatus.text = "فشل التنزيل — تحقق من الشبكة والخادم"
                updateBtn.visibility = View.VISIBLE
                return@download
            }
            updateStatus.text = "اكتمل التنزيل — أكمل التثبيت من النافذة"
            Update.install(this, f)
        }
    }

    private fun head(t: String): TextView =
        label(this, t, 16f, Color.parseColor("#008069"), true).apply {
            setPadding(0, dp(18), 0, dp(8))
        }

    private fun fieldParams(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, top, 0, dp(6))
        }

    private fun saveProfile() {
        val n = nameField.text.toString().trim().ifBlank { "مستخدم" }
        val p = phoneField.text.toString().trim()
        Repo.saveProfile(this, n, p) { toast("تم حفظ الحساب") }
    }

    private fun toast(m: String) {
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()
    }
}
