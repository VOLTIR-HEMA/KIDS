package com.kids.monitor.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** جمع الموقع عبر LocationManager فقط (بدون Play Services) لضمان العمل على كل الأجهزة. */
object LocationCollector {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasBackgroundPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun manager(context: Context): LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    /**
     * كل المزوّدات متاحة للتسجيل حتى لو أبلغ النظام أنها معطّلة
     * (بعض الأجهزة تعيد false رغم أن GPS يعمل، فيُسجَّل الطلب ويُقبل ما يصل).
     */
    private fun allProviders(): List<String> = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    )

    fun lastLocation(context: Context): Map<String, Any>? {
        if (!hasPermission(context)) return null
        val lm = manager(context) ?: return null
        var best: Location? = null
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            try {
                val loc = lm.getLastKnownLocation(provider) ?: continue
                if (best == null || loc.time > best!!.time) best = loc
            } catch (e: SecurityException) {
                /* الصلاحية ملغاة */
            } catch (e: Exception) {
                /* مزوّد غير متاح */
            }
        }
        return best?.toMap()
    }

    /** محاولة الحصول على موقع حديث لمدة قصيرة، ثم الرجوع لآخر موقع معروف. */
    fun freshLocation(context: Context, timeoutMs: Long = 12000): Map<String, Any>? {
        if (!hasPermission(context)) return null
        val lm = manager(context) ?: return null
        val latch = CountDownLatch(1)
        var result: Location? = null
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                result = location
                latch.countDown()
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        var registered = 0
        for (provider in allProviders()) {
            try {
                lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                registered++
            } catch (e: Exception) { /* تجاهل */ }
        }
        if (registered > 0) latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        try { lm.removeUpdates(listener) } catch (e: Exception) { /* تجاهل */ }
        return (result ?: lastLocationLocation(lm))?.toMap()
    }

    private fun lastLocationLocation(lm: LocationManager): Location? {
        var best: Location? = null
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                val loc = lm.getLastKnownLocation(provider) ?: continue
                if (best == null || loc.time > best!!.time) best = loc
            } catch (e: Exception) { /* تجاهل */ }
        }
        return best
    }

    /**
     * بدء تحديثات مستمرة كل 30 ثانية. يُرجع كائنًا لإيقاف التحديثات لاحقاً.
     */
    fun startUpdates(
        context: Context,
        minTimeMs: Long = 30_000L,
        onLocation: (Map<String, Any>) -> Unit,
    ): Any? {
        if (!hasPermission(context)) return null
        val lm = manager(context) ?: return null
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                try { onLocation(location.toMap()) } catch (e: Exception) { /* تجاهل */ }
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        var registered = false
        for (provider in allProviders()) {
            try {
                lm.requestLocationUpdates(provider, minTimeMs, 0f, listener, Looper.getMainLooper())
                registered = true
            } catch (e: Exception) { /* تجاهل */ }
        }
        return if (registered) listener else null
    }

    fun stopUpdates(context: Context, token: Any?) {
        val listener = token as? LocationListener ?: return
        val lm = manager(context) ?: return
        try { lm.removeUpdates(listener) } catch (e: Exception) { /* تجاهل */ }
    }

    private fun Location.toMap(): Map<String, Any> = mapOf(
        "lat" to latitude,
        "lng" to longitude,
        "accuracy" to accuracy.toDouble(),
        "speed" to if (hasSpeed()) speed.toDouble() else 0.0,
        "address" to "",
        "timestamp" to System.currentTimeMillis(),
    )
}
