package com.kids.monitor

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import com.kids.monitor.data.FirestoreSync
import com.kids.monitor.data.PendingStore
import com.kids.monitor.data.Permissions

/** شاشة واضحة للأذونات العادية وصلاحيات الوصول الخاصة. */
class PermissionsActivity : android.app.Activity() {

    private data class Row(
        val title: String,
        val permissions: List<String>,
        val special: String?,
    )

    private lateinit var rowsContainer: LinearLayout
    private lateinit var summaryText: TextView
    private var rows: List<Row> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirestoreSync.init(this)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(
            TextView(this).apply {
                text = "الأذونات والإعدادات"
                textSize = 22f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 12)
            },
        )
        root.addView(
            TextView(this).apply {
                text = "فعّل ما تحتاجه فقط. كل زر يوضح الحالة ويفتح الطلب المناسب."
                textSize = 13f
                setPadding(0, 0, 0, 16)
            },
        )
        summaryText = TextView(this).apply {
            textSize = 13f
            setPadding(0, 0, 0, 12)
        }
        root.addView(summaryText)

        rowsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(rowsContainer)

        val syncButton = Button(this).apply {
            text = "مزامنة الآن"
            setOnClickListener { MonitorService.syncNow(this@PermissionsActivity) }
        }
        root.addView(syncButton)

        rows = buildRows()
        renderRows()
    }

    override fun onResume() {
        super.onResume()
        renderRows()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        renderRows()
    }

    private fun buildRows(): List<Row> {
        val media = Permissions.mediaPermissions()
        return listOf(
            Row("الإشعارات", listOf(Manifest.permission.POST_NOTIFICATIONS), null),
            Row(
                "الموقع الجغرافي",
                listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                null,
            ),
            Row("سجل المكالمات", listOf(Manifest.permission.READ_CALL_LOG), null),
            Row("الرسائل", listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS), null),
            Row("جهات الاتصال", listOf(Manifest.permission.READ_CONTACTS), null),
            Row("الصور والملفات", media, null),
            Row("الكاميرا", listOf(Manifest.permission.CAMERA), null),
            Row("قراءة الإشعارات", emptyList(), ACTION_NOTIFICATION_LISTENER),
            Row("إمكانية الوصول", emptyList(), ACTION_ACCESSIBILITY),
            Row("مسؤول الجهاز (للقفل وإعادة الضبط)", emptyList(), ACTION_DEVICE_ADMIN),
        )
    }

    private fun renderRows() {
        rowsContainer.removeAllViews()
        var grantedCount = 0
        rows.forEachIndexed { index, row ->
            val granted = isGranted(row)
            if (granted) grantedCount++
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 10, 0, 10)
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = row.title
                textSize = 15f
            })
            info.addView(TextView(this).apply {
                text = if (granted) "مفعّل" else "غير مفعّل"
                textSize = 12f
                setTextColor(
                    if (granted) 0xFF2E7D32.toInt() else 0xFFB71C1C.toInt(),
                )
            })
            val button = Button(this).apply {
                text = if (granted) "فتح" else "تفعيل"
                setOnClickListener { onRowClick(index, row) }
            }
            line.addView(info)
            line.addView(button)
            rowsContainer.addView(line)
        }
        val pending = PendingStore.size(this)
        summaryText.text = "المفعّل: $grantedCount / ${rows.size}" +
            if (pending > 0) " · عناصر معلّقة للرفع: $pending" else ""
    }

    private fun isGranted(row: Row): Boolean = when (row.special) {
        ACTION_NOTIFICATION_LISTENER -> isNotificationListenerEnabled()
        ACTION_ACCESSIBILITY -> isAccessibilityEnabled()
        ACTION_DEVICE_ADMIN -> isDeviceAdmin()
        else -> row.permissions.any { Permissions.has(this, it) }
    }

    private fun onRowClick(index: Int, row: Row) {
        when (row.special) {
            ACTION_NOTIFICATION_LISTENER -> {
                safeStart(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                return
            }
            ACTION_ACCESSIBILITY -> {
                safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return
            }
            ACTION_DEVICE_ADMIN -> {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(
                        DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        ComponentName(this@PermissionsActivity, AdminReceiver::class.java),
                    )
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "مطلوب لتنفيذ أمر قفل الشاشة وإعادة ضبط المصنع من لوحة التحكم.",
                    )
                }
                safeStart(intent)
                return
            }
        }
        if (row.permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                row.permissions.toTypedArray(),
                1000 + index,
            )
        }
    }

    private fun safeStart(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // قد لا تتوفر شاشة الإعدادات على بعض الأجهزة
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        return flat.split(":").any { it.contains(packageName) }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return flat.split(":").any { it.contains(packageName) }
    }

    private fun isDeviceAdmin(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))
    }

    companion object {
        private const val ACTION_NOTIFICATION_LISTENER = "notification_listener"
        private const val ACTION_ACCESSIBILITY = "accessibility"
        private const val ACTION_DEVICE_ADMIN = "device_admin"
    }
}
