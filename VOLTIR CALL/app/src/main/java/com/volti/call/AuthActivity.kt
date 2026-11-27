package com.volti.call

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class AuthActivity : AppCompatActivity() {

    private lateinit var nameField: EditText
    private lateinit var userField: EditText
    private lateinit var emailField: EditText
    private lateinit var passField: EditText
    private lateinit var phoneField: EditText
    private lateinit var root: LinearLayout
    private var lastSent = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F7F8FA"))
        }
        setContentView(root)
        val u = Fb.auth.currentUser
        when {
            u == null -> form()
            u.email.isNullOrBlank() -> {
                signOutSilently()
                form()
            }
            u.isEmailVerified -> goMain()
            else -> verify(u.email.orEmpty())
        }
    }

    // ------------------------------------------------------------ شاشة التسجيل

    private fun form() {
        root.removeAllViews()
        root.addView(topBar(this, "VOLTIR CALL", "إنشاء حساب بالبريد أو الدخول"))
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
        }
        scroll.addView(body)

        body.addView(TextView(this).apply {
            text = "إنشاء حساب جديد"
            textSize = 18f
            setTextColor(Color.parseColor("#008069"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(10))
        })

        nameField = editText(this, "الاسم الظاهر")
        userField = editText(this, "اسم المستخدم (بالإنجليزي، فريد)")
        emailField = editText(this, "البريد الإلكتروني (إجباري)")
        passField = editText(this, "كلمة المرور (6 أحرف على الأقل)").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        phoneField = editText(this, "رقم الهاتف (اختياري)", android.text.InputType.TYPE_CLASS_PHONE)
        for (f in listOf(nameField, userField, emailField, passField, phoneField)) {
            body.addView(f, fieldParams())
        }
        body.addView(pillButton(this, "إنشاء الحساب وإرسال رابط التأكيد") { register() }, fieldParams(dp(6)))

        body.addView(TextView(this).apply {
            text = "لديك حساب بالفعل؟"
            textSize = 15f
            setTextColor(Color.parseColor("#111B21"))
            setPadding(0, dp(10), 0, dp(8))
        })
        body.addView(pillButton(this, "تسجيل الدخول بنفس البريد وكلمة المرور", false) { login() }, fieldParams())

        body.addView(TextView(this).apply {
            text = "لن يُفتح التطبيق قبل تأكيد البريد الإلكتروني من رسالة Firebase على جيميلك."
            textSize = 12f
            setTextColor(Color.parseColor("#667781"))
            setPadding(0, dp(16), 0, 0)
        })

        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    // ------------------------------------------------------------ شاشة تأكيد البريد

    private fun verify(email: String, autoSend: Boolean = false) {
        root.removeAllViews()
        root.addView(topBar(this, "تأكيد البريد", "خطوة أخيرة قبل استخدام التطبيق"))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(30), dp(24), dp(24))
        }
        body.addView(TextView(this).apply {
            text = "أرسلنا رابط تأكيد إلى"
            textSize = 15f
            setTextColor(Color.parseColor("#111B21"))
            gravity = android.view.Gravity.CENTER
        })
        body.addView(TextView(this).apply {
            text = email
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#008069"))
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(6), 0, dp(14))
        })
        body.addView(TextView(this).apply {
            text = "افتح الرسالة واضغط الرابط، ثم ارجع هنا واضغط «تحققت — فتح التطبيق».\nلم تجد الرسالة؟ راجع مجلد Spam."
            textSize = 13f
            setTextColor(Color.parseColor("#667781"))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, dp(20))
        })
        body.addView(pillButton(this, "تحققت — فتح التطبيق") { recheck() }, fieldParams(dp(6)))
        body.addView(pillButton(this, "إعادة إرسال رابط التأكيد", false) { resend() }, fieldParams(dp(6)))
        body.addView(pillButton(this, "تسجيل الخروج", false) {
            signOutSilently()
            form()
        }, fieldParams(dp(18)))

        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (autoSend) resend()
    }

    private fun recheck() {
        val u = Fb.auth.currentUser
        if (u == null) {
            form()
            return
        }
        toast("جارٍ التحقق…")
        u.reload().addOnCompleteListener {
            if (u.isEmailVerified) {
                toast("تم تأكيد البريد — مرحباً")
                goMain()
            } else {
                toast("البريد لم يُؤكد بعد — افتح الرابط من الرسالة أولاً")
            }
        }
    }

    private fun resend() {
        val u = Fb.auth.currentUser ?: return
        val now = System.currentTimeMillis()
        if (now - lastSent < 60_000L) {
            toast("انتظر قليلاً قبل إعادة الإرسال")
            return
        }
        lastSent = now
        u.sendEmailVerification()
            .addOnSuccessListener { toast("تم إرسال رابط التأكيد إلى بريدك") }
            .addOnFailureListener { toast("تعذر الإرسال — حاول بعد قليل") }
    }

    // ------------------------------------------------------------ التسجيل والدخول

    private fun register() {
        val name = nameField.text.toString().trim()
        val username = userField.text.toString().trim().lowercase()
        val email = emailField.text.toString().trim()
        val pass = passField.text.toString()
        val phone = phoneField.text.toString().trim()

        if (name.isBlank()) {
            toast("اكتب اسمك")
            return
        }
        if (!Regex("^[a-z0-9_.]{3,20}$").matches(username)) {
            toast("اسم المستخدم: 3-20 حرفاً إنجليزياً أو أرقام أو _ . بدون مسافات")
            return
        }
        if (!Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email)) {
            toast("بريد إلكتروني غير صالح")
            return
        }
        if (pass.length < 6) {
            toast("كلمة المرور 6 أحرف على الأقل")
            return
        }

        Repo.usernameTaken(username) { taken ->
            if (taken) {
                toast("اسم المستخدم @" + username + " محجوز — اختر غيره")
                return@usernameTaken
            }
            signOutIfAnonymous()
            Fb.auth.createUserWithEmailAndPassword(email, pass).addOnCompleteListener { t ->
                val u = Fb.auth.currentUser
                if (!t.isSuccessful || u == null) {
                    toast(err(t.exception))
                    return@addOnCompleteListener
                }
                Repo.registerProfile(this, name, username, email, phone) { ok ->
                    if (!ok) {
                        toast("تعذر حفظ الملف الشخصي — راجع قواعد Firestore")
                        return@registerProfile
                    }
                    lastSent = 0L
                    verify(email, true)
                }
            }
        }
    }

    private fun login() {
        val email = emailField.text.toString().trim()
        val pass = passField.text.toString()
        if (email.isBlank() || pass.isBlank()) {
            toast("اكتب البريد وكلمة المرور")
            return
        }
        signOutIfAnonymous()
        Fb.auth.signInWithEmailAndPassword(email, pass).addOnCompleteListener { t ->
            val u = Fb.auth.currentUser
            if (!t.isSuccessful || u == null) {
                toast(err(t.exception))
                return@addOnCompleteListener
            }
            if (!u.isEmailVerified) {
                verify(u.email.orEmpty())
                return@addOnCompleteListener
            }
            Repo.loadUser(u.uid) { p ->
                if (p == null || p.username.isBlank()) {
                    toast("هذا الحساب بلا ملف شخصي — أنشئ حساباً جديداً")
                    return@loadUser
                }
                Fb.prefs(this).edit()
                    .putString("name", p.name)
                    .putString("username", p.username)
                    .putString("phone", p.phone)
                    .putString("email", u.email.orEmpty())
                    .apply()
                goMain()
            }
        }
    }

    private fun signOutIfAnonymous() {
        val u = Fb.auth.currentUser
        if (u != null && u.email.isNullOrBlank()) signOutSilently()
    }

    private fun signOutSilently() {
        try {
            Fb.auth.signOut()
        } catch (e: Exception) {
            /* تجاهل */
        }
    }

    private fun err(e: Exception?): String {
        val m = e?.message.orEmpty()
        return when {
            m.contains("email-already-in-use") -> "البريد مستخدم بالفعل — سجّل الدخول"
            m.contains("weak-password") -> "كلمة المرور ضعيفة"
            m.contains("invalid-email") -> "بريد إلكتروني غير صالح"
            m.contains("wrong-password") || m.contains("INVALID_LOGIN_CREDENTIALS") || m.contains("invalid-credential") ->
                "البريد أو كلمة المرور غير صحيحة"
            m.contains("network") -> "تحقق من اتصال الإنترنت"
            m.contains("too-many-requests") -> "محاولات كثيرة — حاول بعد قليل"
            m.isBlank() -> "تعذر إتمام العملية"
            else -> m.take(120)
        }
    }

    private fun goMain() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
    }

    private fun fieldParams(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, top, 0, dp(8))
        }

    private fun toast(m: String) {
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_LONG).show()
    }
}
