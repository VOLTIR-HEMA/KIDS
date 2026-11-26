package com.volti.call

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.widget.doOnTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

private const val REQ_IMAGE = 501
private const val REQ_VIDEO = 502

private fun plainRow(children: List<View>): LinearLayout {
    val ctx = children.first().context
    return LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
        children.forEach { addView(it) }
    }
}

private data class ChatEntry(
    val peer: String,
    val title: String,
    val subtitle: String,
    val time: Long,
    val unread: Int,
    val avatarText: String,
    val online: Boolean,
    val lockedChatId: String,
)

private fun Context.toast(m: String) {
    android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()
}

/** عرض حالة (نص/صورة/فيديو) للطرفين. */
private fun viewStatusRow(ctx: Context, s: StatusRow) {
    when (s.type) {
        "image", "video" -> {
            android.widget.Toast.makeText(ctx, "جارٍ تحميل الحالة…", android.widget.Toast.LENGTH_SHORT).show()
            Repo.loadMedia(s.media, s.chunks) { bytes ->
                if (bytes == null || bytes.isEmpty()) {
                    android.widget.Toast.makeText(ctx, "تعذر تحميل الحالة", android.widget.Toast.LENGTH_LONG).show()
                    return@loadMedia
                }
                if (s.type == "image") {
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val iv = ImageView(ctx).apply {
                        setImageBitmap(bmp)
                        adjustViewBounds = true
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                    val box = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
                        addView(iv)
                        if (s.text.isNotBlank()) addView(label(ctx, s.text, 14f, Color.parseColor("#111B21")).apply {
                            setPadding(0, ctx.dp(8), 0, 0)
                            maxLines = 4
                        })
                    }
                    AlertDialog.Builder(ctx).setTitle(s.name).setView(box).setPositiveButton("إغلاق", null).show()
                } else {
                    try {
                        val f = File(ctx.cacheDir, "story_" + System.currentTimeMillis() + ".mp4")
                        f.writeBytes(bytes)
                        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW)
                                .setDataAndType(uri, s.mime.ifBlank { "video/mp4" })
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(ctx, "تعذر تشغيل الفيديو", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        else -> AlertDialog.Builder(ctx)
            .setTitle(s.name)
            .setMessage(s.text + "\n\n" + Fb.dayClock(s.at))
            .setPositiveButton("إغلاق", null)
            .show()
    }
}

// ------------------------------------------------------------------ الدردشات

class ChatsTab(act: MainActivity) : Tab(act) {

    private val ctx: Context get() = act
    private var chats: List<Chat> = emptyList()
    private var contacts: List<Person> = emptyList()
    private var statuses: List<StatusRow> = emptyList()
    private var requests: List<Req> = emptyList()
    private var query = ""
    private var adapter: RecyclerView.Adapter<*>? = null
    private var listView: RecyclerView? = null
    private var emptyView: TextView? = null
    private var statusStrip: LinearLayout? = null
    private var searchBox: EditText? = null
    private var requestsBar: TextView? = null

    override fun build(): View {
        val root = FrameLayout(ctx).apply { setBackgroundColor(Color.parseColor("#F7F8FA")) }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        col.addView(
            topBar(
                ctx, "VOLTIR CALL", "مكالمات ودردشة",
                null,
                listOf(
                    iconButton(ctx, "🔍") {
                        searchBox?.visibility = if (searchBox?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    },
                    iconButton(ctx, "⚙") { act.openSettings() },
                ),
            ),
        )

        val u = Fb.auth.currentUser
        if (u != null && !u.isEmailVerified) {
            val bar = TextView(ctx).apply {
                text = "✉️ بريدك غير مؤكد — اضغط لإرسال رابط التأكيد"
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#B26A00"))
                setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))
                setOnClickListener {
                    try { u.sendEmailVerification() } catch (e: Exception) { /* تجاهل */ }
                    ctx.toast("تم إرسال رابط التأكيد إلى بريدك")
                }
            }
            col.addView(bar)
        }

        requestsBar = TextView(ctx).apply {
            visibility = View.GONE
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#008069"))
            setPadding(ctx.dp(12), ctx.dp(9), ctx.dp(12), ctx.dp(9))
            setOnClickListener { showRequests() }
        }
        col.addView(requestsBar)

        val search = editText(ctx, "بحث في المحادثات أو جهات الاتصال")
        search.visibility = View.GONE
        search.doOnTextChanged { s, _, _, _ ->
            query = s?.toString().orEmpty()
            adapter?.notifyDataSetChanged()
            emptyView?.visibility = if (rows().isEmpty()) View.VISIBLE else View.GONE
        }
        searchBox = search
        col.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(ctx.dp(10), ctx.dp(6), ctx.dp(10), 0)
        })

        val strip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
        }
        statusStrip = strip
        col.addView(
            HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                addView(strip)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val list = RecyclerView(ctx).apply { layoutManager = LinearLayoutManager(ctx) }
        adapter = ChatAdapter()
        list.adapter = adapter
        listView = list
        col.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        emptyView = TextView(ctx).apply {
            text = "لا توجد محادثات أو جهات اتصال بعد\nاضغط «رسالة جديدة» وابحث باسم المستخدم"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#667781"))
            visibility = View.GONE
        }
        col.addView(emptyView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(64)))

        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val fab = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        fab.addView(pillButton(ctx, "💬 رسالة جديدة") { newContactDialog(false) })
        fab.addView(gap(ctx, 0, 8))
        fab.addView(pillButton(ctx, "📞 مكالمة جديدة") { newContactDialog(true) })
        root.addView(fab, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, ctx.dp(14), ctx.dp(14))
        })

        Repo.listenChats { list2 ->
            chats = list2
            adapter?.notifyDataSetChanged()
            emptyView?.visibility = if (rows().isEmpty()) View.VISIBLE else View.GONE
        }
        Repo.listenContacts { c ->
            contacts = c
            renderStatuses()
            adapter?.notifyDataSetChanged()
            emptyView?.visibility = if (rows().isEmpty()) View.VISIBLE else View.GONE
        }
        Repo.listenStatus { rows ->
            statuses = rows
            renderStatuses()
        }
        Repo.listenRequests { r ->
            requests = r
            requestsBar?.visibility = if (r.isEmpty()) View.GONE else View.VISIBLE
            requestsBar?.text = "👥 لديك " + r.size + " طلب صداقة — اضغط للمراجعة"
        }
        return root
    }

    private fun rows(): List<ChatEntry> {
        val out = ArrayList<ChatEntry>()
        for (c in chats) {
            val contact = contacts.firstOrNull { it.uid == c.peer }
            out.add(
                ChatEntry(
                    c.peer,
                    contact?.label?.ifBlank { c.peerName } ?: c.peerName,
                    preview(c),
                    c.lastAt,
                    c.unread,
                    c.peerName,
                    contact?.online == true,
                    c.id,
                ),
            )
        }
        val chatted = chats.map { it.peer }.toHashSet()
        for (p in contacts) {
            if (chatted.contains(p.uid)) continue
            out.add(
                ChatEntry(
                    p.uid,
                    p.label,
                    "@" + p.username + (if (p.phone.isNotBlank()) " · " + p.phone else ""),
                    0L,
                    0,
                    p.label,
                    p.online,
                    "",
                ),
            )
        }
        var list: List<ChatEntry> = out
        if (query.isNotBlank()) {
            list = list.filter { it.title.contains(query, true) || it.subtitle.contains(query, true) }
        }
        return list.sortedWith(compareByDescending<ChatEntry> { it.time }.thenBy { it.title })
    }

    private fun preview(c: Chat): String {
        val tick = if (c.lastSender == Repo.uid()) "✓✓ " else ""
        return tick + when (c.lastType) {
            "image" -> "📷 صورة"
            "video" -> "🎬 فيديو"
            "audio" -> "🎤 رسالة صوتية"
            "doc" -> "📄 مستند"
            "location" -> "📍 موقع"
            "contact" -> "👤 جهة اتصال"
            else -> c.lastText
        }
    }

    private fun renderStatuses() {
        val strip = statusStrip ?: return
        strip.removeAllViews()
        val mine = avatarView(ctx, Fb.myName(ctx).ifBlank { "أنا" }, 54)
        strip.addView(statusCell(mine, "حالتي", false) {
            askText(ctx, "نشر حالة نصية", "اكتب حالتك (تختفي بعد 24 ساعة)") { text ->
                if (text.isNotBlank()) {
                    Repo.postStatus(text, 0xFF00A884.toInt())
                    ctx.toast("تم نشر الحالة")
                }
            }
        })
        val seen = HashSet<String>()
        for (s in statuses) {
            if (s.uid == Repo.uid() || !seen.add(s.uid)) continue
            val p = contacts.firstOrNull { it.uid == s.uid } ?: continue
            strip.addView(statusCell(avatarView(ctx, p.label, 54), p.label, p.online) {
                viewStatusRow(ctx, s)
            })
        }
    }

    private fun statusCell(a: View, name: String, isOnline: Boolean, onClick: () -> Unit): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(ctx.dp(6), 0, ctx.dp(6), 0)
            isClickable = true
            setOnClickListener { onClick() }
        }
        val holder = FrameLayout(ctx).apply {
            addView(View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke(ctx.dp(2), Color.parseColor("#00A884"))
                }
                layoutParams = FrameLayout.LayoutParams(ctx.dp(62), ctx.dp(62))
            })
            addView(a, FrameLayout.LayoutParams(ctx.dp(54), ctx.dp(54)).apply {
                leftMargin = ctx.dp(4)
                topMargin = ctx.dp(4)
            })
            if (isOnline) {
                addView(View(ctx).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#25D366"))
                        setStroke(ctx.dp(2), Color.WHITE)
                    }
                }, FrameLayout.LayoutParams(ctx.dp(14), ctx.dp(14)).apply {
                    leftMargin = ctx.dp(44)
                    topMargin = ctx.dp(42)
                })
            }
        }
        col.addView(holder, LinearLayout.LayoutParams(ctx.dp(66), ctx.dp(66)))
        col.addView(label(ctx, name, 12f, Color.parseColor("#111B21")).apply {
            gravity = Gravity.CENTER
            maxWidth = ctx.dp(72)
        })
        return col
    }

    private inner class ChatAdapter : RecyclerView.Adapter<ChatAdapter.VH>() {

        inner class VH(v: LinearLayout) : RecyclerView.ViewHolder(v) {
            val avatar: TextView = v.getChildAt(0) as TextView
            val name: TextView = ((v.getChildAt(1) as LinearLayout).getChildAt(0) as TextView)
            val preview: TextView = ((v.getChildAt(1) as LinearLayout).getChildAt(1) as TextView)
            val time: TextView = ((v.getChildAt(2) as LinearLayout).getChildAt(0) as TextView)
            val badgeBox: LinearLayout = v.getChildAt(2) as LinearLayout
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val av = avatarView(ctx, "", 46)
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ctx.dp(10), 0, ctx.dp(6), 0)
                addView(label(ctx, "", 16f, Color.parseColor("#111B21"), true))
                addView(label(ctx, "", 13f, Color.parseColor("#667781")))
            }
            val right = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(label(ctx, "", 11f, Color.parseColor("#667781")))
            }
            val row = plainRow(listOf(av, col, right))
            row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(66))
            return VH(row)
        }

        override fun getItemCount(): Int = rows().size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = rows()[position]
            holder.avatar.text = if (e.avatarText.isBlank()) "?" else e.avatarText.take(1).uppercase()
            holder.avatar.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(nameColor(e.title)) }
            holder.name.text = e.title + (if (e.online) "  ●" else "")
            holder.name.setTextColor(if (e.online) Color.parseColor("#008069") else Color.parseColor("#111B21"))
            holder.preview.text = e.subtitle
            holder.time.text = if (e.time > 0) Fb.dayClock(e.time) else ""
            if (e.unread > 0) {
                val b = badgeDot(ctx, e.unread)
                if (holder.badgeBox.childCount < 2 && b != null) holder.badgeBox.addView(b)
            } else if (holder.badgeBox.childCount > 1) {
                holder.badgeBox.removeViewAt(1)
            }
            holder.itemView.setOnClickListener {
                val locked = e.lockedChatId.isNotBlank() && Fb.prefs(ctx).getBoolean("lock_" + e.lockedChatId, false)
                if (locked) {
                    askText(ctx, "دردشة مقفلة", "أدخل الرمز (الافتراضي 1234)", InputType.TYPE_CLASS_NUMBER) { pin ->
                        if (pin == "1234") openChat(e.peer, e.title) else ctx.toast("رمز خاطئ")
                    }
                } else {
                    openChat(e.peer, e.title)
                }
            }
        }
    }

    private fun openChat(peer: String, name: String) {
        Repo.openChat(peer, name) { _ ->
            ctx.startActivity(
                Intent(ctx, ChatActivity::class.java)
                    .putExtra("peer", peer)
                    .putExtra("name", name),
            )
        }
    }

    private fun showRequests() {
        if (requests.isEmpty()) return
        val labels = requests.map { it.fromName + "  @" + it.fromUsername }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle("طلبات الصداقة")
            .setItems(labels) { _, i ->
                val r = requests[i]
                AlertDialog.Builder(ctx)
                    .setTitle(r.fromName)
                    .setMessage("@" + r.fromUsername + "\n\nقبول الطلب يضيفكما لجهات اتصال بعضكما.")
                    .setPositiveButton("قبول") { _, _ ->
                        Repo.acceptRequest(r) { ok -> ctx.toast(if (ok) "تم القبول" else "تعذر القبول") }
                    }
                    .setNegativeButton("رفض") { _, _ -> Repo.declineRequest(r) }
                    .show()
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun newContactDialog(forCall: Boolean) {
        val field = editText(ctx, "@اسم المستخدم أو رقم الهاتف")
        val wrap = FrameLayout(ctx).apply {
            setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), 0)
            addView(field)
        }
        AlertDialog.Builder(ctx)
            .setTitle(if (forCall) "مكالمة جديدة" else "رسالة جديدة")
            .setMessage("ابحث عن مستخدم مسجّل باسم المستخدم (@) أو رقم هاتفه.")
            .setView(wrap)
            .setPositiveButton("بحث") { _, _ ->
                val q = field.text.toString().trim()
                if (q.isBlank()) {
                    ctx.toast("اكتب اسم المستخدم أو الرقم")
                    return@setPositiveButton
                }
                Repo.findUser(q) { p ->
                    if (p == null) ctx.toast("لا يوجد مستخدم بهذا الاسم أو الرقم")
                    else if (forCall) callDialog(p) else foundDialog(p)
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun foundDialog(p: Person) {
        val isContact = contacts.any { it.uid == p.uid }
        val info = "@" + p.username + (if (p.phone.isNotBlank()) "\n" + p.phone else "") +
            if (isContact) "\n✔ من جهات اتصالك" else "\nليس من جهات اتصالك"
        AlertDialog.Builder(ctx)
            .setTitle(p.name)
            .setMessage(info)
            .setItems(arrayOf("💬 محادثة", "➕ إضافة جهة اتصال", "👥 طلب صداقة", "📞 مكالمة صوتية", "🎥 مكالمة مرئية")) { _, i ->
                when (i) {
                    0 -> openChat(p.uid, p.label)
                    1 -> addContactDialog(p)
                    2 -> Repo.sendRequest(p) { ok -> ctx.toast(if (ok) "تم إرسال طلب الصداقة" else "تعذر إرسال الطلب") }
                    3 -> guardCall(p) { CallActivity.start(ctx, p.uid, p.label, false) }
                    4 -> guardCall(p) { CallActivity.start(ctx, p.uid, p.label, true) }
                }
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun addContactDialog(p: Person) {
        askText(ctx, "إضافة جهة اتصال", "اكتب الاسم الذي تحبه (اختياري)") { alias ->
            Repo.addContact(p, alias.ifBlank { p.name }) { ok ->
                ctx.toast(if (ok) "تمت الإضافة باسم " + alias.ifBlank { p.name } else "تعذر الحفظ")
            }
        }
    }

    private fun callDialog(p: Person) {
        if (contacts.none { it.uid == p.uid }) {
            AlertDialog.Builder(ctx)
                .setTitle(p.name)
                .setMessage("لا يمكن الاتصال بشخص قبل أن يكون من جهات اتصالك. أضفه أولاً.")
                .setPositiveButton("إضافة جهة اتصال") { _, _ -> addContactDialog(p) }
                .setNegativeButton("إلغاء", null)
                .show()
            return
        }
        AlertDialog.Builder(ctx)
            .setTitle("مكالمة مع " + p.label)
            .setItems(arrayOf("📞 مكالمة صوتية", "🎥 مكالمة مرئية")) { _, i ->
                CallActivity.start(ctx, p.uid, p.label, i == 1)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun guardCall(p: Person, action: () -> Unit) {
        if (contacts.any { it.uid == p.uid }) {
            action()
        } else {
            AlertDialog.Builder(ctx)
                .setTitle(p.name)
                .setMessage("المكالمات متاحة لجهات الاتصال فقط. أضفه أولاً.")
                .setPositiveButton("إضافة") { _, _ -> addContactDialog(p) }
                .setNegativeButton("إلغاء", null)
                .show()
        }
    }

    override fun onShown() {
        Repo.setOnline(true)
    }

    companion object {
        fun startChat(ctx: Context, peer: Person) {
            Repo.openChat(peer.uid, peer.label) { _ ->
                ctx.startActivity(
                    Intent(ctx, ChatActivity::class.java)
                        .putExtra("peer", peer.uid)
                        .putExtra("name", peer.label),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ التحديثات

class UpdatesTab(act: MainActivity) : Tab(act) {
    private val ctx: Context get() = act
    private var rows: List<StatusRow> = emptyList()
    private var contacts: List<Person> = emptyList()
    private var adapter: RecyclerView.Adapter<*>? = null

    override fun build(): View {
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        root.addView(topBar(ctx, "التحديثات", "حالات نصية وصور وفيديو (حتى دقيقتين)", null, listOf(iconButton(ctx, "＋") { composer() })))
        val list = RecyclerView(ctx).apply { layoutManager = LinearLayoutManager(ctx) }
        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            inner class VH(v: LinearLayout) : RecyclerView.ViewHolder(v) {
                val avatar: TextView = v.getChildAt(0) as TextView
                val name: TextView = ((v.getChildAt(1) as LinearLayout).getChildAt(0) as TextView)
                val body: TextView = ((v.getChildAt(1) as LinearLayout).getChildAt(1) as TextView)
                val time: TextView = ((v.getChildAt(1) as LinearLayout).getChildAt(2) as TextView)
            }

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val av = avatarView(ctx, "", 48)
                val col = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(ctx.dp(10), 0, 0, 0)
                    addView(label(ctx, "", 16f, Color.parseColor("#111B21"), true))
                    addView(label(ctx, "", 13f, Color.parseColor("#667781")))
                    addView(label(ctx, "", 11f, Color.parseColor("#8696A0")))
                }
                val row = plainRow(listOf(av, col))
                row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(76))
                return VH(row)
            }

            override fun getItemCount(): Int = visible().size

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val h = holder as VH
                val s = visible()[position]
                h.avatar.text = s.name.take(1).uppercase()
                h.avatar.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(s.color) }
                h.name.text = s.name + (if (s.uid == Repo.uid()) " (أنا)" else "")
                h.body.text = when (s.type) {
                    "image" -> "📷 صورة" + (if (s.text.isNotBlank()) " — " + s.text else "")
                    "video" -> "🎬 فيديو" + (if (s.text.isNotBlank()) " — " + s.text else "")
                    else -> s.text
                }
                h.time.text = Fb.dayClock(s.at)
                h.itemView.setOnClickListener { viewStatusRow(ctx, s) }
            }
        }
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        Repo.listenStatus { r -> rows = r; adapter?.notifyDataSetChanged() }
        Repo.listenContacts { c -> contacts = c; adapter?.notifyDataSetChanged() }
        return root
    }

    private fun visible(): List<StatusRow> {
        val mine = Repo.uid()
        val contactIds = contacts.map { it.uid }.toHashSet()
        return rows.filter { it.uid == mine || contactIds.contains(it.uid) }
    }

    private fun composer() {
        val items = arrayOf("✍️ حالة نصية", "🖼 صورة مع تعليق", "🎬 فيديو مع تعليق (حتى دقيقتين)")
        AlertDialog.Builder(ctx).setTitle("إضافة تحديث").setItems(items) { _, i ->
            when (i) {
                0 -> askText(ctx, "حالة نصية", "اكتب حالتك (تختفي بعد 24 ساعة)") { text ->
                    if (text.isNotBlank()) {
                        Repo.postStatus(text, 0xFF00A884.toInt())
                        ctx.toast("تم النشر")
                    }
                }
                1 -> act.startActivityForResult(
                    Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),
                    REQ_IMAGE,
                )
                2 -> act.startActivityForResult(
                    Intent(Intent.ACTION_GET_CONTENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE),
                    REQ_VIDEO,
                )
            }
        }.show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != android.app.Activity.RESULT_OK) return
        val uri: Uri = data?.data ?: return
        when (requestCode) {
            REQ_IMAGE -> askCaption { caption ->
                val bytes = Repo.compressImage(ctx, uri, 1600, 75, 2_500_000)
                if (bytes == null || bytes.isEmpty()) {
                    ctx.toast("تعذر قراءة الصورة")
                    return@askCaption
                }
                ctx.toast("جارٍ نشر الصورة…")
                Repo.storeMedia(bytes) { media, chunks ->
                    if (media.isBlank()) ctx.toast("فشل الرفع — راجع قواعد Firestore")
                    else {
                        Repo.postMediaStatus("image", media, chunks, "image/jpeg", caption)
                        ctx.toast("تم نشر الصورة")
                    }
                }
            }
            REQ_VIDEO -> {
                val dur = videoDuration(uri)
                if (dur > 120_000) {
                    ctx.toast("الحد الأقصى للفيديو دقيقتان — هذا المقطع " + (dur / 1000) + " ثانية")
                    return
                }
                askCaption { caption ->
                    val bytes = Repo.bytesFrom(ctx, uri)
                    if (bytes == null || bytes.isEmpty()) {
                        ctx.toast("تعذر قراءة الفيديو")
                        return@askCaption
                    }
                    if (bytes.size > Repo.MAX_MEDIA) {
                        ctx.toast("الفيديو أكبر من 15 ميجا — اختر مقطعاً أقصر")
                        return@askCaption
                    }
                    ctx.toast("جارٍ نشر الفيديو…")
                    Repo.storeMedia(bytes) { media, chunks ->
                        if (media.isBlank()) ctx.toast("فشل الرفع — راجع قواعد Firestore")
                        else {
                            Repo.postMediaStatus("video", media, chunks, "video/mp4", caption)
                            ctx.toast("تم نشر الفيديو")
                        }
                    }
                }
            }
        }
    }

    private fun askCaption(cb: (String) -> Unit) {
        val field = editText(ctx, "تعليق على الحالة (اختياري)")
        val wrap = FrameLayout(ctx).apply {
            setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), 0)
            addView(field)
        }
        AlertDialog.Builder(ctx)
            .setTitle("تعليق")
            .setView(wrap)
            .setPositiveButton("نشر") { _, _ -> cb(field.text.toString().trim()) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun videoDuration(uri: Uri): Long = try {
        val mmr = android.media.MediaMetadataRetriever()
        mmr.setDataSource(ctx, uri)
        val d = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        mmr.release()
        d
    } catch (e: Exception) {
        0L
    }
}

// ------------------------------------------------------------------ المجتمعات

class CommunitiesTab(act: MainActivity) : Tab(act) {
    private val ctx: Context get() = act
    private var rows: List<Community> = emptyList()
    private var adapter: RecyclerView.Adapter<*>? = null

    override fun build(): View {
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        root.addView(topBar(ctx, "المجتمعات", "أنشئ مجتمعاً وادعُ أصدقاءك", null, listOf(iconButton(ctx, "＋") {
            askText(ctx, "مجتمع جديد", "اسم المجتمع") { n ->
                if (n.isNotBlank()) Repo.createCommunity(n)
            }
        })))
        val list = RecyclerView(ctx).apply { layoutManager = LinearLayoutManager(ctx) }
        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            inner class VH(v: LinearLayout) : RecyclerView.ViewHolder(v) {
                val avatar: TextView = v.getChildAt(0) as TextView
                val name: TextView = ((v.getChildAt(1) as LinearLayout).getChildAt(0) as TextView)
                val sub: TextView = ((v.getChildAt(1) as LinearLayout).getChildAt(1) as TextView)
                val join: TextView = v.getChildAt(2) as TextView
            }

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val av = avatarView(ctx, "👥", 46)
                val col = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(ctx.dp(10), 0, ctx.dp(8), 0)
                    addView(label(ctx, "", 16f, Color.parseColor("#111B21"), true))
                    addView(label(ctx, "", 12f, Color.parseColor("#667781")))
                }
                val join = pillButton(ctx, "انضمام", true) { }
                val row = plainRow(listOf(av, col, join))
                row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(70))
                return VH(row)
            }

            override fun getItemCount(): Int = rows.size

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val h = holder as VH
                val c = rows[position]
                h.name.text = c.name
                h.sub.text = c.members.toString() + " عضو"
                h.join.text = if (c.joined) "عضو ✓" else "انضمام"
                h.join.setOnClickListener {
                    if (!c.joined) {
                        Repo.joinCommunity(c.id)
                        ctx.toast("تم الانضمام")
                    }
                }
            }
        }
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        Repo.listenCommunities { r -> rows = r; adapter?.notifyDataSetChanged() }
        return root
    }
}

// ------------------------------------------------------------------ المكالمات

class CallsTab(act: MainActivity) : Tab(act) {
    private val ctx: Context get() = act
    private var rows: List<CallRow> = emptyList()
    private var people: List<Person> = emptyList()
    private var adapter: RecyclerView.Adapter<*>? = null

    override fun build(): View {
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        root.addView(topBar(ctx, "المكالمات", "صوتية ومرئية", null, listOf(iconButton(ctx, "⚙") { act.openSettings() })))

        val quick = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
        }
        val callBtn = quickCell("📞", "مكالمة") { pickPerson { p -> chooseType(p) } }
        val schedBtn = quickCell("🗓", "جدولة") { schedule() }
        val padBtn = quickCell("⌨️", "لوحة أرقام") { dialPad() }
        val favBtn = quickCell("⭐", "مفضلة") { favorites() }
        for (b in listOf(callBtn, schedBtn, padBtn, favBtn)) {
            quick.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(quick)

        val list = RecyclerView(ctx).apply { layoutManager = LinearLayoutManager(ctx) }
        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            inner class VH(v: LinearLayout) : RecyclerView.ViewHolder(v) {
                val icon: TextView = v.getChildAt(0) as TextView
                val avatar: TextView = v.getChildAt(1) as TextView
                val name: TextView = ((v.getChildAt(2) as LinearLayout).getChildAt(0) as TextView)
                val sub: TextView = ((v.getChildAt(2) as LinearLayout).getChildAt(1) as TextView)
            }

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val icon = TextView(ctx).apply { textSize = 18f; setPadding(0, 0, ctx.dp(8), 0) }
                val av = avatarView(ctx, "", 42)
                val col = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(ctx.dp(10), 0, 0, 0)
                    addView(label(ctx, "", 16f, Color.parseColor("#111B21"), true))
                    addView(label(ctx, "", 12f, Color.parseColor("#667781")))
                }
                val row = plainRow(listOf(icon, av, col))
                row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(64))
                return VH(row)
            }

            override fun getItemCount(): Int = rows.size

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val h = holder as VH
                val c = rows[position]
                val contact = people.firstOrNull { it.uid == c.peer }
                val name = contact?.label ?: c.peerName
                h.icon.text = if (c.outgoing) "↗" else if (c.state == "missed") "✖" else "↙"
                h.icon.setTextColor(if (c.state == "missed") Color.parseColor("#E53935") else Color.parseColor("#00A884"))
                h.avatar.text = name.take(1).uppercase()
                h.avatar.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(nameColor(name)) }
                h.name.text = name
                val kind = when {
                    c.state == "missed" -> "فائتة"
                    c.outgoing -> "صادرة"
                    else -> "واردة"
                }
                val dur = if (c.durationMs > 0) " · " + Fb.duration(c.durationMs) else ""
                h.sub.text = kind + " · " + Fb.dayClock(c.at) + dur
                h.itemView.setOnClickListener {
                    if (contact == null) {
                        ctx.toast("أضف جهة الاتصال أولاً لتتمكن من الاتصال")
                    } else {
                        CallActivity.start(ctx, contact.uid, contact.label, c.video)
                    }
                }
            }
        }
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        Repo.listenContacts { p -> people = p; adapter?.notifyDataSetChanged() }
        Repo.listenCalls { r -> rows = r; adapter?.notifyDataSetChanged() }
        return root
    }

    private fun quickCell(icon: String, text: String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(ctx.dp(4), ctx.dp(8), ctx.dp(4), ctx.dp(8))
            isClickable = true
            setBackground(roundBg(Color.parseColor("#E7F8F3"), ctx.dp(14).toFloat()))
            addView(TextView(ctx).apply { this.text = icon; textSize = 20f; gravity = Gravity.CENTER })
            addView(TextView(ctx).apply {
                this.text = text
                textSize = 12f
                setTextColor(Color.parseColor("#008069"))
                gravity = Gravity.CENTER
            })
            setOnClickListener { onClick() }
        }
    }

    private fun pickPerson(cb: (Person) -> Unit) {
        if (people.isEmpty()) {
            ctx.toast("لا توجد جهات اتصال — أضف جهة اتصال من تبويب الدردشات")
            return
        }
        val names = people.map { it.label + (if (it.online) "  ●" else "") }.toTypedArray()
        AlertDialog.Builder(ctx).setTitle("اختر من جهات اتصالك").setItems(names) { _, i -> cb(people[i]) }.show()
    }

    private fun chooseType(p: Person) {
        AlertDialog.Builder(ctx)
            .setTitle(p.label)
            .setItems(arrayOf("📞 مكالمة صوتية", "🎥 مكالمة مرئية", "💬 محادثة")) { _, i ->
                when (i) {
                    0 -> CallActivity.start(ctx, p.uid, p.label, false)
                    1 -> CallActivity.start(ctx, p.uid, p.label, true)
                    else -> ChatsTab.startChat(ctx, p)
                }
            }
            .show()
    }

    private fun schedule() {
        pickPerson { p ->
            askText(ctx, "جدولة مكالمة مع " + p.label, "بعد كم دقيقة؟", InputType.TYPE_CLASS_NUMBER) { mins ->
                val m = mins.toIntOrNull() ?: 0
                if (m <= 0) return@askText
                val at = System.currentTimeMillis() + m * 60_000L
                Fb.prefs(ctx).edit().putLong("sched_" + p.uid, at).apply()
                ctx.toast("تمت الجدولة " + Fb.clock(at))
            }
        }
    }

    private fun dialPad() {
        val input = editText(ctx, "رقم الهاتف المسجل", InputType.TYPE_CLASS_PHONE)
        val wrap = FrameLayout(ctx).apply { setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), 0); addView(input) }
        AlertDialog.Builder(ctx)
            .setTitle("لوحة الأرقام")
            .setMessage("اكتب رقم شخص مسجل في VOLTIR CALL")
            .setView(wrap)
            .setPositiveButton("اتصال") { _, _ ->
                val num = input.text.toString().trim()
                if (num.isBlank()) return@setPositiveButton
                Repo.findUser(num) { p ->
                    if (p == null) {
                        ctx.toast("الرقم غير مسجل")
                    } else if (people.none { it.uid == p.uid }) {
                        ctx.toast("أضف " + p.name + " إلى جهات الاتصال أولاً")
                    } else {
                        CallActivity.start(ctx, p.uid, p.label, false)
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun favorites() {
        val favs = Fb.prefs(ctx).getStringSet("favs", emptySet()) ?: emptySet()
        if (people.isEmpty()) {
            ctx.toast("لا توجد جهات اتصال")
            return
        }
        val names = people.map { (if (favs.contains(it.uid)) "⭐ " else "") + it.label }.toTypedArray()
        AlertDialog.Builder(ctx).setTitle("المفضلة — اضغط للتبديل").setItems(names) { _, i ->
            val p = people[i]
            val set = HashSet(favs)
            if (!set.add(p.uid)) set.remove(p.uid)
            Fb.prefs(ctx).edit().putStringSet("favs", set).apply()
            ctx.toast(if (set.contains(p.uid)) "أُضيف للمفضلة" else "أُزيل")
        }.show()
    }
}
