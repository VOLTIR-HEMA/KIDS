package com.volti.call

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** تهيئة Firebase يدوياً على نفس مشروع KIDS (بدون google-services.json). */
object Fb {
    const val PROJECT = "kids-4c141"
    const val BUCKET = "kids-4c141.firebasestorage.app"
    const val API_KEY = "AIzaSyCHEiMJReJzMUlN2WcVID6QFQllCSuH8Vk"
    const val APP_ID = "1:211525897323:android:49c13766eddb6452b1752e"
    const val SENDER = "211525897323"

    const val USERS = "volti_users"
    const val CHATS = "volti_chats"
    const val CALLS = "volti_calls"
    const val STATUS = "volti_status"
    const val COMMUNITIES = "volti_communities"
    const val MSGS = "messages"
    const val ITEMS = "items"

    fun init(ctx: Context) {
        if (FirebaseApp.getApps(ctx).isNotEmpty()) return
        val opts = FirebaseOptions.Builder()
            .setApiKey(API_KEY)
            .setApplicationId(APP_ID)
            .setProjectId(PROJECT)
            .setStorageBucket(BUCKET)
            .setGcmSenderId(SENDER)
            .build()
        FirebaseApp.initializeApp(ctx, opts)
    }

    val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    val storage: FirebaseStorage get() = FirebaseStorage.getInstance()

    fun uid(): String? = auth.currentUser?.uid

    fun prefs(ctx: Context) = ctx.getSharedPreferences("volti", Context.MODE_PRIVATE)
    fun myName(ctx: Context): String = prefs(ctx).getString("name", "").orEmpty()
    fun myPhone(ctx: Context): String = prefs(ctx).getString("phone", "").orEmpty()

    fun chatId(a: String, b: String): String = if (a < b) a + "_" + b else b + "_" + a

    fun ensure(ctx: Context, cb: (Boolean) -> Unit) {
        val a = auth
        if (a.currentUser != null) {
            cb(true)
            return
        }
        a.signInAnonymously().addOnCompleteListener { t -> cb(t.isSuccessful) }
    }

    /** خوادم ICE: كل سطر إما stun:host أو turn:host|user|pass. */
    fun iceServers(ctx: Context): List<String> {
        val raw = prefs(ctx).getString("ice", "").orEmpty().trim()
        if (raw.isBlank()) return listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
            "turn:openrelay.metered.ca:80|openrelayproject|openrelayproject",
            "turn:openrelay.metered.ca:443|openrelayproject|openrelayproject",
        )
        return raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun clock(ms: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    fun dayClock(ms: Long): String {
        val now = System.currentTimeMillis()
        val fmt = if (now - ms < 24L * 3600 * 1000) "HH:mm"
        else SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).let { "dd/MM HH:mm" }
        return SimpleDateFormat(fmt, Locale.getDefault()).format(Date(ms))
    }

    fun duration(ms: Long): String {
        val total = ms / 1000
        val m = total / 60
        val s = total % 60
        return String.format(Locale.US, "%02d:%02d", m, s)
    }
}
