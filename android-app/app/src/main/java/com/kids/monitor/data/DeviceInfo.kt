package com.kids.monitor.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs

/** معلومات حالة الجهاز: البطارية، الشحن، الشبكة، التخزين، الموديل. */
object DeviceInfo {

    fun battery(context: Context): Int {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val capacity = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (capacity in 0..100) return capacity
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100) / scale else 0
    }

    fun charging(context: Context): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    fun online(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** نوع الاتصال فقط؛ اسم الشبكة (SSID) يتطلب صلاحيات موقع إضافية في الأندرويد الحديث. */
    fun connection(context: Context): Map<String, Any> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return mapOf("type" to "", "ssid" to "")
        val network = cm.activeNetwork
        val caps = if (network != null) cm.getNetworkCapabilities(network) else null
        val type = when {
            caps == null -> ""
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> ""
        }
        return mapOf("type" to type, "ssid" to "")
    }

    fun storage(): Map<String, Any> {
        return try {
            val stat = StatFs(Environment.getDataDirectory().absolutePath)
            val total = stat.totalBytes
            val free = stat.availableBytes
            mapOf("used" to (total - free), "total" to total)
        } catch (e: Exception) {
            mapOf("used" to 0L, "total" to 0L)
        }
    }

    fun model(): Map<String, Any> = mapOf(
        "name" to Build.MODEL,
        "model" to Build.MODEL,
        "platform" to "android",
        "version" to Build.VERSION.RELEASE,
    )

    /** شرائح الاتصال المتاحة (لتحديد الخط عند إجراء مكالمة). */
    fun sims(context: Context): List<Map<String, Any>> {
        return try {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
                ?: return emptyList()
            tm.callCapablePhoneAccounts.mapIndexed { index, handle ->
                val account = try { tm.getPhoneAccount(handle) } catch (e: Exception) { null }
                mapOf(
                    "index" to index,
                    "label" to (account?.label?.toString() ?: "SIM ${index + 1}"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** لقطة كاملة للحالة تُكتب في وثيقة الجهاز. */
    fun status(context: Context, online: Boolean): Map<String, Any> {
        val map = linkedMapOf<String, Any>(
            "battery" to battery(context),
            "charging" to charging(context),
            "online" to online,
            "lastSeen" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
            "connection" to connection(context),
            "storage" to storage(),
            "sims" to sims(context),
            "syncEnabled" to true,
        )
        map.putAll(model())
        return map
    }
}
