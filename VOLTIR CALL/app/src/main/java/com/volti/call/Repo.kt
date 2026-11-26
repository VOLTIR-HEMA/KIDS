package com.volti.call

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import java.io.ByteArrayOutputStream

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

data class Person(
    val uid: String,
    val name: String,
    val username: String,
    val phone: String,
    val online: Boolean,
    val lastSeen: Long,
    val alias: String = "",
    val isContact: Boolean = false,
) {
    val label: String get() = alias.ifBlank { name }
}

data class StatusRow(
    val uid: String,
    val name: String,
    val type: String,
    val text: String,
    val color: Int,
    val at: Long,
    val media: String,
    val chunks: Int,
    val mime: String,
)

data class Community(val id: String, val name: String, val members: Int, val joined: Boolean)

data class Req(
    val id: String,
    val from: String,
    val fromName: String,
    val fromUsername: String,
    val at: Long,
    val status: String,
)

object Repo {

    const val USERNAMES = "volti_usernames"
    const val CONTACTS = "volti_contacts"
    const val REQUESTS = "volti_requests"
    const val BLOCKS = "volti_blocks"
    const val MEDIA = "volti_media"
    const val ITEMS = "items"
    const val CHUNKS = "chunks"

    const val CHUNK = 400_000
    const val MAX_MEDIA = 15 * 1024 * 1024
    const val MAX_BYTES = 600_000

    var appContext: Context? = null

    fun uid(): String = Fb.uid().orEmpty()

    fun myUsername(ctx: Context): String = Fb.prefs(ctx).getString("username", "").orEmpty()

    // ---------------------------------------------------------- الحساب

    fun usernameTaken(username: String, cb: (Boolean) -> Unit) {
        Fb.db.collection(USERNAMES).document(username.lowercase()).get()
            .addOnSuccessListener { cb(it.exists()) }
            .addOnFailureListener { cb(false) }
    }

    fun registerProfile(ctx: Context, name: String, username: String, email: String, phone: String, done: (Boolean) -> Unit) {
        val id = uid()
        if (id.isBlank()) {
            done(false)
            return
        }
        val now = System.currentTimeMillis()
        val batch = Fb.db.batch()
        batch.set(
            Fb.db.collection(Fb.USERS).document(id),
            mapOf(
                "name" to name,
                "username" to username,
                "email" to email,
                "phone" to phone,
                "online" to true,
                "createdAt" to now,
                "updatedAt" to now,
                "lastSeen" to now,
            ),
        )
        batch.set(
            Fb.db.collection(USERNAMES).document(username.lowercase()),
            mapOf("uid" to id, "username" to username),
        )
        batch.commit().addOnCompleteListener {
            Fb.prefs(ctx).edit()
                .putString("name", name)
                .putString("username", username)
                .putString("email", email)
                .putString("phone", phone)
                .apply()
            done(it.isSuccessful)
        }
    }

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

    fun toPerson(d: DocumentSnapshot): Person = Person(
        d.id,
        d.getString("name").orEmpty().ifBlank { "مستخدم" },
        d.getString("username").orEmpty(),
        d.getString("phone").orEmpty(),
        d.getBoolean("online") == true,
        d.getLong("lastSeen") ?: 0L,
    )

    fun loadUser(id: String, cb: (Person?) -> Unit) {
        if (id.isBlank()) {
            cb(null)
            return
        }
        Fb.db.collection(Fb.USERS).document(id).get()
            .addOnSuccessListener { d -> cb(if (d.exists()) toPerson(d) else null) }
            .addOnFailureListener { cb(null) }
    }

    fun findUser(query: String, cb: (Person?) -> Unit) {
        val raw = query.trim().removePrefix("@")
        if (raw.isBlank()) {
            cb(null)
            return
        }
        Fb.db.collection(USERNAMES).document(raw.lowercase()).get()
            .addOnSuccessListener { d ->
                val id = d.getString("uid")
                if (!id.isNullOrBlank()) loadUser(id, cb) else findPhone(raw, cb)
            }
            .addOnFailureListener { findPhone(raw, cb) }
    }

    private fun findPhone(phone: String, cb: (Person?) -> Unit) {
        Fb.db.collection(Fb.USERS).whereEqualTo("phone", phone).limit(1).get()
            .addOnSuccessListener { snap ->
                val d = snap.documents.firstOrNull()
                cb(if (d != null) toPerson(d) else null)
            }
            .addOnFailureListener { cb(null) }
    }

    // ---------------------------------------------------------- جهات الاتصال

    fun listenContacts(cb: (List<Person>) -> Unit): List<ListenerRegistration> {
        val me = uid()
        if (me.isBlank()) return emptyList()
        val contactMap = LinkedHashMap<String, Person>()
        val live = HashMap<String, Person>()

        fun emit() {
            val list = contactMap.values.map { c ->
                val u = live[c.uid]
                if (u == null) c else c.copy(
                    name = u.name.ifBlank { c.name },
                    username = u.username.ifBlank { c.username },
                    phone = u.phone.ifBlank { c.phone },
                    online = u.online,
                    lastSeen = u.lastSeen,
                )
            }
            cb(list.sortedWith(compareByDescending<Person> { it.online }.thenBy { it.label }))
        }

        val r1 = Fb.db.collection(CONTACTS).document(me).collection(ITEMS).addSnapshotListener { snap, _ ->
            contactMap.clear()
            snap?.documents?.forEach { d ->
                contactMap[d.id] = Person(
                    d.id,
                    d.getString("peerName").orEmpty().ifBlank { d.id },
                    d.getString("peerUsername").orEmpty(),
                    d.getString("peerPhone").orEmpty(),
                    false,
                    0L,
                    d.getString("alias").orEmpty(),
                    true,
                )
            }
            emit()
        }
        val r2 = Fb.db.collection(Fb.USERS).addSnapshotListener { snap, _ ->
            live.clear()
            snap?.documents?.forEach { d -> live[d.id] = toPerson(d) }
            emit()
        }
        return listOf(r1, r2)
    }

    fun isContact(peer: String, cb: (Boolean) -> Unit) {
        val me = uid()
        if (me.isBlank() || peer.isBlank()) {
            cb(false)
            return
        }
        Fb.db.collection(CONTACTS).document(me).collection(ITEMS).document(peer).get()
            .addOnSuccessListener { cb(it.exists()) }
            .addOnFailureListener { cb(false) }
    }

    fun contactOf(peer: String, cb: (Person?) -> Unit) {
        val me = uid()
        if (me.isBlank()) {
            cb(null)
            return
        }
        Fb.db.collection(CONTACTS).document(me).collection(ITEMS).document(peer).get()
            .addOnSuccessListener { d ->
                if (!d.exists()) {
                    cb(null)
                } else {
                    cb(
                        Person(
                            peer,
                            d.getString("peerName").orEmpty().ifBlank { peer },
                            d.getString("peerUsername").orEmpty(),
                            d.getString("peerPhone").orEmpty(),
                            false,
                            0L,
                            d.getString("alias").orEmpty(),
                            true,
                        ),
                    )
                }
            }
            .addOnFailureListener { cb(null) }
    }

    fun addContact(peer: Person, alias: String, done: (Boolean) -> Unit) {
        val me = uid()
        if (me.isBlank() || peer.uid.isBlank()) {
            done(false)
            return
        }
        Fb.db.collection(CONTACTS).document(me).collection(ITEMS).document(peer.uid).set(
            mapOf(
                "alias" to alias,
                "peerName" to peer.name,
                "peerUsername" to peer.username,
                "peerPhone" to peer.phone,
                "at" to System.currentTimeMillis(),
            ),
            SetOptions.merge(),
        ).addOnCompleteListener { done(it.isSuccessful) }
    }

    fun removeContact(peer: String) {
        val me = uid()
        if (me.isBlank() || peer.isBlank()) return
        Fb.db.collection(CONTACTS).document(me).collection(ITEMS).document(peer).delete()
    }

    // ---------------------------------------------------------- طلبات الصداقة

    fun sendRequest(peer: Person, done: (Boolean) -> Unit) {
        val me = uid()
        val ctx = appContext ?: return
        if (me.isBlank() || peer.uid.isBlank()) {
            done(false)
            return
        }
        val id = me + "_" + peer.uid
        Fb.db.collection(REQUESTS).document(id).set(
            mapOf(
                "from" to me,
                "to" to peer.uid,
                "fromName" to Fb.myName(ctx).ifBlank { "مستخدم" },
                "fromUsername" to myUsername(ctx),
                "at" to System.currentTimeMillis(),
                "status" to "pending",
            ),
        ).addOnCompleteListener { done(it.isSuccessful) }
    }

    fun listenRequests(cb: (List<Req>) -> Unit): ListenerRegistration? {
        val me = uid()
        if (me.isBlank()) return null
        return Fb.db.collection(REQUESTS).whereEqualTo("to", me).limit(40)
            .addSnapshotListener { snap, _ ->
                if (snap == null) {
                    cb(emptyList())
                    return@addSnapshotListener
                }
                val out = ArrayList<Req>()
                for (d in snap.documents) {
                    val st = d.getString("status").orEmpty()
                    if (st != "pending") continue
                    out.add(
                        Req(
                            d.id,
                            d.getString("from").orEmpty(),
                            d.getString("fromName").orEmpty().ifBlank { "مستخدم" },
                            d.getString("fromUsername").orEmpty(),
                            d.getLong("at") ?: 0L,
                            st,
                        ),
                    )
                }
                cb(out.sortedByDescending { it.at })
            }
    }

    fun acceptRequest(r: Req, done: (Boolean) -> Unit) {
        val me = uid()
        val ctx = appContext ?: return
        if (me.isBlank()) {
            done(false)
            return
        }
        val myName = Fb.myName(ctx).ifBlank { "مستخدم" }
        val myUser = myUsername(ctx)
        val now = System.currentTimeMillis()
        val batch = Fb.db.batch()
        batch.update(Fb.db.collection(REQUESTS).document(r.id), "status", "accepted")
        batch.set(
            Fb.db.collection(CONTACTS).document(me).collection(ITEMS).document(r.from),
            mapOf(
                "alias" to r.fromName,
                "peerName" to r.fromName,
                "peerUsername" to r.fromUsername,
                "at" to now,
            ),
            SetOptions.merge(),
        )
        batch.set(
            Fb.db.collection(CONTACTS).document(r.from).collection(ITEMS).document(me),
            mapOf(
                "alias" to myName,
                "peerName" to myName,
                "peerUsername" to myUser,
                "at" to now,
            ),
            SetOptions.merge(),
        )
        batch.commit().addOnCompleteListener { done(it.isSuccessful) }
    }

    fun declineRequest(r: Req) {
        Fb.db.collection(REQUESTS).document(r.id).update("status", "declined")
    }

    // ---------------------------------------------------------- الحظر

    fun setBlocked(peer: String, blocked: Boolean, done: (() -> Unit)? = null) {
        val me = uid()
        if (me.isBlank() || peer.isBlank()) {
            done?.invoke()
            return
        }
        val ref = Fb.db.collection(BLOCKS).document(me).collection(ITEMS).document(peer)
        val task = if (blocked) ref.set(mapOf("at" to System.currentTimeMillis())) else ref.delete()
        task.addOnCompleteListener { done?.invoke() }
    }

    fun isBlocked(peer: String, cb: (Boolean) -> Unit) {
        val me = uid()
        if (me.isBlank()) {
            cb(false)
            return
        }
        Fb.db.collection(BLOCKS).document(me).collection(ITEMS).document(peer).get()
            .addOnSuccessListener { cb(it.exists()) }
            .addOnFailureListener { cb(false) }
    }

    fun blockedMe(peer: String, cb: (Boolean) -> Unit) {
        if (peer.isBlank() || uid().isBlank()) {
            cb(false)
            return
        }
        Fb.db.collection(BLOCKS).document(peer).collection(ITEMS).document(uid()).get()
            .addOnSuccessListener { cb(it.exists()) }
            .addOnFailureListener { cb(false) }
    }

    // ---------------------------------------------------------- المحادثات

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
        val ctx = appContext ?: return
        val id = Fb.chatId(me, peerUid)
        val doc = Fb.db.collection(Fb.CHATS).document(id)
        doc.set(
            mapOf(
                "members" to listOf(me, peerUid),
                "names" to mapOf(me to Fb.myName(ctx), peerUid to peerName),
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
        val bytes = if (type == "image") compressImage(ctx, uri, 1280, 70, 550_000) else bytesFrom(ctx, uri)
        if (bytes == null || bytes.isEmpty()) {
            android.widget.Toast.makeText(ctx, "تعذر قراءة الملف", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (bytes.size > MAX_BYTES) {
            android.widget.Toast.makeText(
                ctx,
                "حجم الملف " + (bytes.size / 1024) + " كيلوبايت — الحد " + (MAX_BYTES / 1024) +
                    ". اختر ملفاً أصغر.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }
        val data = HashMap<String, Any>(extra)
        data["type"] = type
        data["name"] = displayName
        data["mime"] = mime
        data["expiresSec"] = expiresSec
        data["data64"] = Base64.encodeToString(bytes, Base64.NO_WRAP)
        if (type == "image" || type == "video") data["text"] = ""
        sendMessage(chatId, peerUid, data)
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

    // ---------------------------------------------------------- المكالمات

    fun listenCalls(cb: (List<CallRow>) -> Unit): List<ListenerRegistration> {
        val me = uid()
        if (me.isBlank()) return emptyList()
        val map = HashMap<String, CallRow>()
        val emit = { cb(map.values.sortedByDescending { it.at }) }
        fun parse(id: String, d: DocumentSnapshot): CallRow {
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

    // ---------------------------------------------------------- الحالات

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
                            it["type"]?.toString().orEmpty().ifBlank { "text" },
                            it["text"]?.toString().orEmpty(),
                            (it["color"] as? Number)?.toInt() ?: 0xFF00A884.toInt(),
                            at,
                            it["media"]?.toString().orEmpty(),
                            (it["chunks"] as? Number)?.toInt() ?: 0,
                            it["mime"]?.toString().orEmpty(),
                        ),
                    )
                }
            }
            out.sortByDescending { it.at }
            cb(out)
        }

    fun postStatus(text: String, color: Int) {
        if (text.isBlank()) return
        postItem(
            mapOf(
                "type" to "text",
                "text" to text,
                "color" to color,
                "at" to System.currentTimeMillis(),
            ),
        )
    }

    fun postMediaStatus(type: String, media: String, chunks: Int, mime: String, caption: String) {
        postItem(
            mapOf(
                "type" to type,
                "text" to caption,
                "media" to media,
                "chunks" to chunks,
                "mime" to mime,
                "color" to 0xFF00A884.toInt(),
                "at" to System.currentTimeMillis(),
            ),
        )
    }

    private fun postItem(item: Map<String, Any>) {
        val me = uid()
        val ctx = appContext ?: return
        if (me.isBlank()) return
        Fb.db.collection(Fb.STATUS).document(me).set(
            mapOf(
                "name" to Fb.myName(ctx).ifBlank { "مستخدم" },
                "username" to myUsername(ctx),
                "items" to FieldValue.arrayUnion(item),
                "updatedAt" to System.currentTimeMillis(),
            ),
            SetOptions.merge(),
        )
    }

    /** رفع وسائط الحالات على شكل أجزاء 400KB داخل Firestore. */
    fun storeMedia(bytes: ByteArray, cb: (String, Int) -> Unit) {
        val total = (bytes.size + CHUNK - 1) / CHUNK
        if (total <= 0) {
            cb("", 0)
            return
        }
        val doc = Fb.db.collection(MEDIA).document()
        var i = 0
        fun step() {
            if (i >= total) {
                cb(doc.id, total)
                return
            }
            val start = i * CHUNK
            val end = minOf(bytes.size, start + CHUNK)
            val part = Base64.encodeToString(bytes.copyOfRange(start, end), Base64.NO_WRAP)
            doc.collection(CHUNKS).document(i.toString()).set(mapOf("n" to i, "data64" to part))
                .addOnSuccessListener { i++; step() }
                .addOnFailureListener { cb("", 0) }
        }
        step()
    }

    fun loadMedia(media: String, chunks: Int, cb: (ByteArray?) -> Unit) {
        if (media.isBlank() || chunks <= 0) {
            cb(null)
            return
        }
        val out = ByteArrayOutputStream()
        val doc = Fb.db.collection(MEDIA).document(media)
        var i = 0
        fun step() {
            if (i >= chunks) {
                cb(out.toByteArray())
                return
            }
            doc.collection(CHUNKS).document(i.toString()).get()
                .addOnSuccessListener { d ->
                    val b64 = d.getString("data64")
                    if (b64.isNullOrBlank()) {
                        cb(null)
                        return@addOnSuccessListener
                    }
                    try {
                        out.write(Base64.decode(b64, Base64.NO_WRAP))
                    } catch (e: Exception) {
                        cb(null)
                        return@addOnSuccessListener
                    }
                    i++
                    step()
                }
                .addOnFailureListener { cb(null) }
        }
        step()
    }

    // ---------------------------------------------------------- وسائط

    fun bytesFrom(ctx: Context, uri: Uri): ByteArray? = try {
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) {
        null
    }

    fun compressImage(ctx: Context, uri: Uri, maxPx: Int, quality: Int, maxBytes: Int): ByteArray? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while ((bounds.outWidth / sample) > maxPx || (bounds.outHeight / sample) > maxPx) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp: Bitmap = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null
            var out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            var guard = 0
            while (out.size() > maxBytes && bmp.width > 320 && guard < 10) {
                guard++
                bmp = Bitmap.createScaledBitmap(bmp, bmp.width * 3 / 4, bmp.height * 3 / 4, true)
                out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 65, out)
            }
            out.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------- المجتمعات

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
        val ctx = appContext ?: return
        if (me.isBlank()) return
        Fb.db.collection(Fb.CHATS).document(chatId).set(
            mapOf(
                "members" to listOf(me, peerUid),
                "names" to mapOf(me to Fb.myName(ctx), peerUid to peerName),
            ),
            SetOptions.merge(),
        )
    }
}
