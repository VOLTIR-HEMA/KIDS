package com.volti.call

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
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

class SettingsActivity : AppCompatActivity() {

    private lateinit var nameField: EditText
    private lateinit var phoneField: EditText
    private lateinit var iceField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(build())
    }

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F7F8FA"))
        }
        root.addView(topBar(this, "الإعدادات", "الحساب والاتصال والأذونات", { finish() }))

        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }
        scroll.addView(body)

        body.addView(head("الحساب"))
        nameField = editText(this, "الاسم").apply { setText(Fb.myName(this@SettingsActivity)) }
        body.addView(nameField, fieldParams())
        phoneField = editText(this, "رقم الهاتف", android.text.InputType.TYPE_CLASS_PHONE).apply {
            setText(Fb.myPhone(this@SettingsActivity))
        }
        body.addView(phoneField, fieldParams())
        body.addView(pillButton(this, "حفظ الحساب") { saveProfile() }, fieldParams(dp(6)))

        body.addView(head("خوادم TURN / STUN"))
        body.addView(label(this, "سطر لكل خادم:\nstun:host:port\nأو turn:host:port|user|pass\nاتركه فارغاً لاستخدام الخوادم الافتراضية.", 12f, Color.parseColor("#667781")).apply {
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
            toast("تم حفظ الخوادم — تُستخدم في المكالمة القادمة")
        }, fieldParams(dp(6)))

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
        body.addView(label(this, "VOLTIR CALL 1.0 — مكالمات ودردشة عبر Firebase.\nUID: " + Repo.uid(), 12f, Color.parseColor("#667781")).apply {
            maxLines = 4
            setTextIsSelectable(true)
        })

        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
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
