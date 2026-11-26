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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(build())
    }

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F7F8FA"))
        }
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
        emailField = editText(this, "البريد الإلكتروني")
        passField = editText(this, "كلمة المرور (6 أحرف على الأقل)").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        phoneField = editText(this, "رقم الهاتف (اختياري)", android.text.InputType.TYPE_CLASS_PHONE)
        for (f in listOf(nameField, userField, emailField, passField, phoneField)) {
            body.addView(f, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(8))
            })
        }
        body.addView(pillButton(this, "إنشاء الحساب وإرسال رابط التأكيد") { register() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, dp(6), 0, dp(18)) })

        body.addView(TextView(this).apply {
            text = "لديك حساب بالفعل؟"
            textSize = 15f
            setTextColor(Color.parseColor("#111B21"))
            setPadding(0, dp(4), 0, dp(8))
        })
        body.addView(pillButton(this, "تسجيل الدخول بنفس البريد وكلمة المرور", false) { login() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        body.addView(TextView(this).apply {
            text = "سيصلك بريد تأكيد من Firebase على جيميلك بعد الإنشاء. بدون تأكيد البريد تبقى بعض الميزات محدودة."
            textSize = 12f
            setTextColor(Color.parseColor("#667781"))
            setPadding(0, dp(18), 0, 0)
        })

        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

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
                    try { u.sendEmailVerification() } catch (e: Exception) { /* تجاهل */ }
                    toast("تم إنشاء الحساب @" + username + " — تحقق من بريدك")
                    goMain()
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
                if (u.isEmailVerified) {
                    toast("مرحباً " + p.name)
                } else {
                    toast("مرحباً — لم يتم تأكيد البريد بعد")
                }
                goMain()
            }
        }
    }

    private fun signOutIfAnonymous() {
        val u = Fb.auth.currentUser
        if (u != null && u.email.isNullOrBlank()) {
            try { Fb.auth.signOut() } catch (e: Exception) { /* تجاهل */ }
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

    private fun toast(m: String) {
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_LONG).show()
    }
}
