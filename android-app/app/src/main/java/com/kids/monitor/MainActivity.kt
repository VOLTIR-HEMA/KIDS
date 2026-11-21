package com.kids.monitor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.kids.monitor.data.DeviceInfo
import com.kids.monitor.data.FirestoreSync

class MainActivity : Activity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var root: LinearLayout
    private var emailInput: EditText? = null
    private var passwordInput: EditText? = null
    private var statusText: TextView? = null
    private var messageText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirestoreSync.init(this)
        auth = FirebaseAuth.getInstance()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 28, 28, 28)
        }
        setContentView(ScrollView(this).apply { addView(root) })
        render()
    }

    override fun onResume() {
        super.onResume()
        if (auth.currentUser != null) updateStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                MonitorService.start(this)
                updateStatus()
            } else {
                showMessage("يلزم السماح بالإشعارات لإظهار إشعار المزامنة المستمر.")
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SCREEN_SHARE && resultCode == RESULT_OK && data != null) {
            val intent = Intent(this, ScreenShareService::class.java)
                .putExtra(ScreenShareService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ScreenShareService.EXTRA_RESULT_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            showMessage("تم بدء مشاركة الشاشة.")
        }
    }

    private fun render() {
        root.removeAllViews()
        emailInput = null
        passwordInput = null
        statusText = null
        messageText = null

        addText("مشاركة حالة الجهاز", 24f)
        addText(
            "سجّل دخولك لربط هذا الجهاز بحسابك، ثم فعّل الأذونات المطلوبة من شاشة الإعدادات. " +
                "يمكنك إيقاف المشاركة أو تسجيل الخروج في أي وقت.",
            14f,
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
            messageText = addText("", 13f)
        } else {
            addText("تم تسجيل الدخول: ${user.email.orEmpty()}", 15f)
            addText("معرّف الجهاز: ${FirestoreSync.deviceId(this).take(8)}", 12f)
            statusText = addText("", 14f)
            updateStatus()
            addButton("الأذونات والإعدادات") {
                startActivity(Intent(this, PermissionsActivity::class.java))
            }
            addButton("بدء مشاركة الحالة") { requestNotificationsThenStart() }
            addButton("إيقاف المشاركة") {
                stopService(Intent(this, MonitorService::class.java))
                updateStatus()
            }
            addButton("مشاركة الشاشة") { startScreenShare() }
            addButton("مزامنة الآن") {
                MonitorService.syncNow(this)
                showMessage("تم طلب مزامنة فورية.")
            }
            addButton("تسجيل الخروج") {
                stopService(Intent(this, MonitorService::class.java))
                auth.signOut()
                render()
            }
            messageText = addText("", 13f)
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

    private fun requestNotificationsThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATIONS,
            )
        } else {
            MonitorService.start(this)
            updateStatus()
        }
    }

    private fun startScreenShare() {
        val manager = getSystemService(MediaProjectionManager::class.java) ?: return
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_SCREEN_SHARE)
    }

    private fun updateStatus() {
        val enabled = getSharedPreferences(MonitorService.PREFERENCES, MODE_PRIVATE)
            .getBoolean(MonitorService.SHARING_ENABLED, false)
        val online = DeviceInfo.online(this)
        statusText?.text = if (enabled) {
            "المزامنة تعمل · البطارية ${DeviceInfo.battery(this)}٪ · " +
                if (online) "متصل بالإنترنت" else "غير متصل"
        } else {
            "مشاركة الحالة متوقفة."
        }
    }

    private fun showMessage(message: String) {
        messageText?.text = message
    }

    private fun addText(text: String, size: Float): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 10)
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
        ).apply { bottomMargin = 8 }

    companion object {
        private const val REQ_NOTIFICATIONS = 41
        private const val REQ_SCREEN_SHARE = 42
    }
}
