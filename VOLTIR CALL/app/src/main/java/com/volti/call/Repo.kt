package com.volti.call

import android.content.Context
import android.net.Uri
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions

data class Chat(
    val id: String,
    val peer: String,
    val peerName: String,
    val lastText: String,
    val lastType: String,
    val lastAt: Long,
    val unread: Int,
    val lastSender: String,
)

data class Msg(
    val id: String,
    val sender: String,
    val type: String,
    val text: String,
    val url: String,
    val name: String,
    val data64: String,
    val mime: String,
    val lat: Double,
    val lng: Double,
    val contactName: String,
    val contactPhone: String,
    val at: Long,
    val readBy: List<String>,
    val expiresAt: Long,
)

data class CallRow(
    val id: String,
    val peer: String,
    val peerName: String,
    val video: Boolean,
    val state: String,
    val at: Long,
    val durationMs: Long,
    val outgoing: Boolean,
)

data class Person(val uid: String, val name: String, val phone: String, val online: Boolean, val lastSeen: Long)

data class StatusRow(val uid: String, val name: String, val text: String, val color: Int, val at: Long)

data class Community(val id: String, val name: String, val members: Int, val joined: Boolean)

object Repo {

    fun uid(): String = Fb.uid().orEmpty()

    fun saveProfile(ctx: Context, name: String, phone: String, done: (() -> Unit)? = null) {
        val id = uid()
        if (id.isBlank()) {
            done?.invoke()
            return
        }
        Fb.prefs(ctx).edit().putString("name", name).putString("phone", phone).apply()
        Fb.db.collection(Fb.USERS).document(id).set(
            mapOf(
                "name" to name,
                "phone" to phone,
                "online" to true,
                "lastSeen" to System.currentTimeMillis(),
                "updatedAt" to System.currentTimeMillis(),
            ),
            SetOptions.merge(),
        ).addOnCompleteListener { done?.invoke() }
    }

    fun setOnline(on: Boolean) {
        val id = uid()
        if (id.isBlank()) return
        Fb.db.collection(Fb.USERS).document(id)
            .set(mapOf("online" to on, "lastSeen" to System.currentTimeMillis()), SetOptions.merge())
    }

    fun listenPeople(cb: (List<Person>) -> Unit): ListenerRegistration? {
        val me = uid()
        if (me.isBlank()) return null
        return Fb.db.collection(Fb.USERS).addSnapshotListener { snap, e ->
            if (e != null || snap == null) {
                cb(emptyList())
                return@addSnapshotListener
            }
            val out = ArrayList<Person>()
            for (d in snap.documents) {
                if (d.id == me) continue
                out.add(
                    Person(
                        d.id,
                        d.getString("name").orEmpty().ifBlank { "مستخدم" },
                        d.getString("phone").orEmpty(),
                        d.getBoolean("online") == true,
                        d.getLong("lastSeen") ?: 0L,
                    ),
                )
            }
            out.sortWith(compareByDescending<Person> { it.online }.thenBy { it.name })
            cb(out)
        }
    }

    fun listenChats(cb: (List<Chat>) -> Unit): ListenerRegistration? {
        val me = uid()
        if (me.isBlank()) return null
        return Fb.db.collection(Fb.CHATS)
            .whereArrayContains("members", me)
            .addSnapshotListener { snap, e ->
                if (e != null || snap == null) {
                    cb(emptyList())
                    return@addSnapshotListener
                }
                val out = ArrayList<Chat>()
                for (d in snap.documents) {
                    val members = (d.get("members") as? List<*>)?.map { it?.toString().orEmpty() } ?: emptyList()
                    val peer = members.firstOrNull { it != me } ?: continue
                    val names = d.get("names") as? Map<*, *>
                    val peerName = names?.get(peer)?.toString().orEmpty().ifBlank { peer }
                    val unreadMap = d.get("unread") as? Map<*, *>
                    val unread = (unreadMap?.get(me) as? Number)?.toInt() ?: 0
                    out.add(
                        Chat(
                            d.id,
                            peer,
                            peerName,
                            d.getString("lastText").orEmpty(),
                            d.getString("lastType").orEmpty(),
                            d.getLong("lastAt") ?: 0L,
                            unread,
                            d.getString("lastSender").orEmpty(),
                        ),
                    )
                }
                out.sortByDescending { it.lastAt }
                cb(out)
            }
    }

    fun openChat(peerUid: String, peerName: String, cb: (String) -> Unit) {
        val me = uid()
        if (me.isBlank() || peerUid.isBlank()) return
        val id = Fb.chatId(me, peerUid)
        val doc = Fb.db.collection(Fb.CHATS).document(id)
        doc.set(
            mapOf(
                "members" to listOf(me, peerUid),
                "names" to mapOf(me to Fb.myName(appContext ?: return), peerUid to peerName),
                "createdAt" to System.currentTimeMillis(),
            ),
            SetOptions.merge(),
        ).addOnCompleteListener { cb(id) }
    }

    fun listenMessages(chatId: String, cb: (List<Msg>) -> Unit): ListenerRegistration =
        Fb.db.collection(Fb.CHATS).document(chatId).collection(Fb.MSGS)
            .orderBy("at")
            .addSnapshotListener { snap, _ ->
                if (snap == null) {
                    cb(emptyList())
                    return@addSnapshotListener
                }
                val now = System.currentTimeMillis()
                val out = ArrayList<Msg>()
                for (d in snap.documents) {
                    val exp = d.getLong("expiresAt") ?: 0L
                    if (exp > 0 && exp < now) continue
                    out.add(
                        Msg(
                            d.id,
                            d.getString("sender").orEmpty(),
                            d.getString("type").orEmpty(),
                            d.getString("text").orEmpty(),
                            d.getString("url").orEmpty(),
                            d.getString("name").orEmpty(),
                            d.getString("data64").orEmpty(),
                            d.getString("mime").orEmpty(),
                            (d.get("lat") as? Number)?.toDouble() ?: 0.0,
                            (d.get("lng") as? Number)?.toDouble() ?: 0.0,
                            d.getString("contactName").orEmpty(),
                            d.getString("contactPhone").orEmpty(),
                            d.getLong("at") ?: 0L,
                            (d.get("readBy") as? List<*>)?.map { it?.toString().orEmpty() } ?: emptyList(),
                            exp,
                        ),
                    )
                }
                cb(out)
            }

    fun sendMessage(chatId: String, peerUid: String, data: Map<String, Any>) {
        val me = uid()
        if (me.isBlank()) return
        val now = System.currentTimeMillis()
        val chat = Fb.db.collection(Fb.CHATS).document(chatId)
        val msg = HashMap<String, Any>(data)
        msg["sender"] = me
        msg["at"] = now
        msg["readBy"] = listOf(me)
        val type = data["type"]?.toString().orEmpty()
        val expiresSec = (data["expiresSec"] as? Number)?.toInt() ?: 0
        msg.remove("expiresSec")
        if (expiresSec > 0) msg["expiresAt"] = now + expiresSec * 1000L
        val preview = when (type) {
            "image" -> "📷 صورة"
            "video" -> "🎬 فيديو"
            "audio" -> "🎤 رسالة صوتية"
            "doc" -> "📄 مستند"
            "location" -> "📍 موقع"
            "contact" -> "👤 جهة اتصال"
            else -> data["text"]?.toString().orEmpty()
        }
        val batch = Fb.db.batch()
        batch.set(chat.collection(Fb.MSGS).document(), msg)
        batch.set(
            chat,
            mapOf(
                "members" to listOf(me, peerUid),
                "lastText" to preview,
                "lastType" to type,
                "lastAt" to now,
                "lastSender" to me,
                "expiresSec" to expiresSec,
            ),
            SetOptions.merge(),
        )
        batch.set(chat, mapOf("unread" to mapOf(peerUid to FieldValue.increment(1))), SetOptions.merge())
        batch.commit()
    }

    /** إرفاق بدون Storage: ضغط الصور وتضمين الملف Base64 داخل مستند Firestore (حد ~600KB). */
    fun uploadAndSend(
        ctx: Context,
        chatId: String,
        peerUid: String,
        uri: Uri,
        type: String,
        displayName: String,
        mime: String,
        extra: Map<String, Any> = emptyMap(),
        expiresSec: Int = 0,
    ) {
        val bytes = if (type == "image") shrinkImage(ctx, uri) else readBytes(ctx, uri)
        if (bytes == null || bytes.isEmpty()) {
            android.widget.Toast.makeText(ctx, "تعذر قراءة الملف", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (bytes.size > MAX_BYTES) {
            android.widget.Toast.makeText(
                ctx,
                "حجم الملف " + (bytes.size / 1024) + " كيلوبايت — الحد " + (MAX_BYTES / 1024) +
                    ". اختر ملفاً أصغر (Firebase Storage غير مفعّل على الخطة المجانية).",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }
        val data = HashMap<String, Any>(extra)
        data["type"] = type
        data["name"] = displayName
        data["mime"] = mime
        data["expiresSec"] = expiresSec
        data["data64"] = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        if (type == "image" || type == "video") data["text"] = ""
        sendMessage(chatId, peerUid, data)
    }

    const val MAX_BYTES = 600_000

    private fun readBytes(ctx: Context, uri: Uri): ByteArray? = try {
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) {
        null
    }

    /** تصغير الصورة إلى 1280px وضغطها JPEG حتى تلائم حد Firestore. */
    private fun shrinkImage(ctx: Context, uri: Uri): ByteArray? {
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            while ((bounds.outWidth / sample) > 1280 || (bounds.outHeight / sample) > 1280) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = ctx.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, opts)
            } ?: return null
            var out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
            var guard = 0
            while (out.size() > 550_000 && bmp.width > 320 && guard < 8) {
                guard++
                bmp = android.graphics.Bitmap.createScaledBitmap(bmp, bmp.width * 3 / 4, bmp.height * 3 / 4, true)
                out = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 65, out)
            }
            out.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    fun markRead(chatId: String, peerUid: String, messages: List<Msg>) {
        val me = uid()
        if (me.isBlank()) return
        val chat = Fb.db.collection(Fb.CHATS).document(chatId)
        chat.set(mapOf("unread" to mapOf(me to 0)), SetOptions.merge())
        var n = 0
        for (m in messages.asReversed()) {
            if (n >= 40) break
            if (m.sender == me || m.readBy.contains(me)) continue
            chat.collection(Fb.MSGS).document(m.id).update("readBy", FieldValue.arrayUnion(me))
            n++
        }
    }

    fun listenCalls(cb: (List<CallRow>) -> Unit): List<ListenerRegistration> {
        val me = uid()
        if (me.isBlank()) return emptyList()
        val map = HashMap<String, CallRow>()
        val emit = {
            cb(map.values.sortedByDescending { it.at })
        }
        fun parse(id: String, d: com.google.firebase.firestore.DocumentSnapshot): CallRow {
            val from = d.getString("from").orEmpty()
            val outgoing = from == me
            val peer = if (outgoing) d.getString("to").orEmpty() else from
            val peerName = if (outgoing) d.getString("toName").orEmpty() else d.getString("fromName").orEmpty()
            val started = d.getLong("startedAt") ?: 0L
            val ended = d.getLong("endedAt") ?: 0L
            var dur = 0L
            if (started > 0 && ended > started) dur = ended - started
            return CallRow(
                id,
                peer,
                peerName.ifBlank { peer },
                d.getBoolean("video") == true,
                d.getString("state").orEmpty(),
                d.getLong("createdAt") ?: 0L,
                dur,
                outgoing,
            )
        }
        val a = Fb.db.collection(Fb.CALLS).whereEqualTo("from", me).limit(60)
            .addSnapshotListener { snap, _ ->
                if (snap != null) {
                    for (d in snap.documents) map[d.id] = parse(d.id, d)
                    emit()
                }
            }
        val b = Fb.db.collection(Fb.CALLS).whereEqualTo("to", me).limit(60)
            .addSnapshotListener { snap, _ ->
                if (snap != null) {
                    for (d in snap.documents) map[d.id] = parse(d.id, d)
                    emit()
                }
            }
        return listOf(a, b)
    }

    fun listenStatus(cb: (List<StatusRow>) -> Unit): ListenerRegistration =
        Fb.db.collection(Fb.STATUS).addSnapshotListener { snap, _ ->
            if (snap == null) {
                cb(emptyList())
                return@addSnapshotListener
            }
            val out = ArrayList<StatusRow>()
            val now = System.currentTimeMillis()
            for (d in snap.documents) {
                val name = d.getString("name").orEmpty().ifBlank { "مستخدم" }
                @Suppress("UNCHECKED_CAST")
                val items = d.get("items") as? List<Map<String, Any>> ?: emptyList()
                for (it in items) {
                    val at = (it["at"] as? Number)?.toLong() ?: 0L
                    if (now - at > 24L * 3600 * 1000) continue
                    out.add(
                        StatusRow(
                            d.id,
                            name,
                            it["text"]?.toString().orEmpty(),
                            (it["color"] as? Number)?.toInt() ?: 0xFF00A884.toInt(),
                            at,
                        ),
                    )
                }
            }
            out.sortByDescending { it.at }
            cb(out)
        }

    fun postStatus(text: String, color: Int) {
        val me = uid()
        if (me.isBlank() || text.isBlank()) return
        val item = mapOf(
            "text" to text,
            "color" to color,
            "at" to System.currentTimeMillis(),
        )
        Fb.db.collection(Fb.STATUS).document(me).set(
            mapOf(
                "name" to Fb.myName(appContext ?: return).ifBlank { "مستخدم" },
                "items" to FieldValue.arrayUnion(item),
                "updatedAt" to System.currentTimeMillis(),
            ),
            SetOptions.merge(),
        )
    }

    fun listenCommunities(cb: (List<Community>) -> Unit): ListenerRegistration =
        Fb.db.collection(Fb.COMMUNITIES).addSnapshotListener { snap, _ ->
            if (snap == null) {
                cb(emptyList())
                return@addSnapshotListener
            }
            val me = uid()
            val out = ArrayList<Community>()
            for (d in snap.documents) {
                val members = (d.get("members") as? List<*>)?.map { it?.toString().orEmpty() } ?: emptyList()
                out.add(
                    Community(
                        d.id,
                        d.getString("name").orEmpty().ifBlank { "مجتمع" },
                        members.size,
                        members.contains(me),
                    ),
                )
            }
            cb(out)
        }

    fun createCommunity(name: String) {
        val me = uid()
        if (me.isBlank() || name.isBlank()) return
        Fb.db.collection(Fb.COMMUNITIES).add(
            mapOf(
                "name" to name,
                "owner" to me,
                "members" to listOf(me),
                "at" to System.currentTimeMillis(),
            ),
        )
    }

    fun joinCommunity(id: String) {
        val me = uid()
        if (me.isBlank()) return
        Fb.db.collection(Fb.COMMUNITIES).document(id).update("members", FieldValue.arrayUnion(me))
    }

    fun ensureChatDoc(chatId: String, peerUid: String, peerName: String) {
        val me = uid()
        if (me.isBlank()) return
        Fb.db.collection(Fb.CHATS).document(chatId).set(
            mapOf(
                "members" to listOf(me, peerUid),
                "names" to mapOf(me to Fb.myName(appContext ?: return), peerUid to peerName),
            ),
            SetOptions.merge(),
        )
    }

    var appContext: Context? = null
}
