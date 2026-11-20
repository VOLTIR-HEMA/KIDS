package com.kids.monitor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.firebase.auth.FirebaseAuth

class MainActivity : Activity() {
    private lateinit var auth: FirebaseAuth
    private lateinit var root: LinearLayout
    private var emailInput: EditText? = null
    private var passwordInput: EditText? = null
    private var statusText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = FirebaseAuth.getInstance()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 32, 32, 32)
        }
        setContentView(root)
        render()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startSharing()
            } else {
                showMessage("يلزم السماح بالإشعارات لإظهار إشعار المزامنة المستمر.")
            }
        }
    }

    private fun render() {
        root.removeAllViews()
        emailInput = null
        passwordInput = null
        statusText = null
        addText("مشاركة حالة الجهاز", 24f)
        addText(
            "تتم مشاركة نسبة البطارية وحالة الشحن والاتصال، ووقت آخر تحديث، " +
                "باستخدام معرّف عشوائي لربط الجهاز بحسابك. " +
                "لا يقرأ التطبيق الموقع أو الرسائل أو المكالمات أو الصور أو الإشعارات، " +
                "ولا يستقبل أوامر من لوحة التحكم. يمكنك إيقاف المشاركة أو تسجيل الخروج في أي وقت.",
            16f,
        )

        val user = auth.currentUser
        if (user == null) {
            val email = EditText(this).apply {
                hint = "البريد الإلكتروني"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            }
            val password = EditText(this).apply {
                hint = "كلمة المرور"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            emailInput = email
            passwordInput = password
            root.addView(email, fieldParams())
            root.addView(password, fieldParams())
            addButton("تسجيل الدخول") { authenticate(createAccount = false) }
            addButton("إنشاء حساب") { authenticate(createAccount = true) }
        } else {
            addText("تم تسجيل الدخول: ${user.email.orEmpty()}", 16f)
            statusText = addText("", 16f)
            updateSharingStatus()
            addButton("بدء مشاركة الحالة") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        NOTIFICATION_PERMISSION_REQUEST,
                    )
                } else {
                    startSharing()
                }
            }
            addButton("إيقاف المشاركة") {
                stopService(Intent(this, StatusSyncService::class.java))
                updateSharingStatus()
            }
            addButton("تسجيل الخروج") {
                stopService(Intent(this, StatusSyncService::class.java))
                auth.signOut()
                render()
            }
        }
    }

    private fun authenticate(createAccount: Boolean) {
        val email = emailInput?.text?.toString()?.trim().orEmpty()
        val password = passwordInput?.text?.toString().orEmpty()
        if (email.isBlank() || password.isBlank()) {
            showMessage("أدخل البريد الإلكتروني وكلمة المرور.")
            return
        }
        val task = if (createAccount) {
            auth.createUserWithEmailAndPassword(email, password)
        } else {
            auth.signInWithEmailAndPassword(email, password)
        }
        task.addOnCompleteListener(this) { result ->
            if (result.isSuccessful) {
                render()
            } else {
                showMessage("تعذر تسجيل الدخول: ${result.exception?.localizedMessage ?: "خطأ غير معروف"}")
            }
        }
    }

    private fun startSharing() {
        val intent = Intent(this, StatusSyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        statusText?.text = "جارٍ بدء خدمة المزامنة..."
        root.postDelayed({ updateSharingStatus() }, 500)
    }

    private fun updateSharingStatus() {
        val enabled = getSharedPreferences(StatusSyncService.PREFERENCES, MODE_PRIVATE)
            .getBoolean(StatusSyncService.SHARING_ENABLED, false)
        statusText?.text = if (enabled) {
            "مشاركة الحالة مفعّلة، والخدمة تعمل بإشعار ظاهر."
        } else {
            "مشاركة الحالة متوقفة."
        }
    }

    private fun showMessage(message: String) {
        val messageView = TextView(this).apply {
            text = message
            textSize = 14f
            setPadding(0, 12, 0, 12)
        }
        root.addView(messageView, fieldParams())
    }

    private fun addText(text: String, size: Float): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 12)
            root.addView(this, fieldParams())
        }

    private fun addButton(label: String, action: () -> Unit) {
        val button = Button(this).apply {
            text = label
            setOnClickListener { action() }
        }
        root.addView(button, fieldParams())
    }

    private fun fieldParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = 8
        }

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST = 41
    }
}
