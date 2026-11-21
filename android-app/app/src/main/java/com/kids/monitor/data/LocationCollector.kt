package com.kids.monitor.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.FieldValue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object LocationCollector {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun bestLastKnownLocation(context: Context): Location? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        var best: Location? = null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        for (provider in providers) {
            try {
                val loc = lm.getLastKnownLocation(provider) ?: continue
                if (best == null || loc.time > best!!.time) best = loc
            } catch (e: SecurityException) {
                // تجاهل: الصلاحية قد تكون ملغاة
            } catch (e: IllegalArgumentException) {
                // تجاهل: مزوّد غير متاح
            }
        }
        return best
    }

    fun lastLocation(context: Context): Map<String, Any>? =
        bestLastKnownLocation(context)?.toMap()

    /** محاولة الحصول على موقع حديث لمدة قصيرة، ثم الرجوع لآخر موقع معروف. */
    fun freshLocation(context: Context, timeoutMs: Long = 8000): Map<String, Any>? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
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
        var registered = false
        try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper(),
            )
            registered = true
        } catch (e: SecurityException) {
            return null
        } catch (e: IllegalArgumentException) {
            registered = false
        }
        if (!registered) {
            try {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 0L, 0f, listener, Looper.getMainLooper(),
                )
                registered = true
            } catch (e: SecurityException) {
                return null
            } catch (e: IllegalArgumentException) {
                return null
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        try {
            lm.removeUpdates(listener)
        } catch (e: Exception) {
            // تجاهل
        }
        val finalLoc: Location? = result ?: bestLastKnownLocation(context)
        return finalLoc?.toMap()
    }

    private fun Location.toMap(): Map<String, Any> = mapOf(
        "lat" to latitude,
        "lng" to longitude,
        "accuracy" to accuracy.toDouble(),
        "address" to "",
        "timestamp" to FieldValue.serverTimestamp(),
    )
}