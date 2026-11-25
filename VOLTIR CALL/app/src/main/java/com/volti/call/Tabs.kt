package com.volti.call

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doOnTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

private fun Context.fixed(v: View, h: Int = ViewGroup.LayoutParams.MATCH_PARENT): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)

private fun plainRow(children: List<View>): LinearLayout {
    val ctx = children.first().context
    return LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
        children.forEach { addView(it) }
    }
}

// ------------------------------------------------------------------ الدردشات

class ChatsTab(act: MainActivity) : Tab(act) {

    private val ctx: Context get() = act
    private var chats: List<Chat> = emptyList()
    private var statusRows: List<StatusRow> = emptyList()
    private var online: Map<String, Boolean> = emptyMap()
    private var query = ""
    private var adapter: RecyclerView.Adapter<*>? = null
    private var emptyView: TextView? = null
    private var statusStrip: LinearLayout? = null
    private var searchBox: android.widget.EditText? = null

    override fun build(): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F7F8FA"))
        }
        root.addView(
            topBar(
                ctx, "VOLTIR CALL", "مكالمات ودردشة مجانية",
                null,
                listOf(
                    iconButton(ctx, "🔍") { searchBox?.visibility = if (searchBox?.visibility == View.VISIBLE) View.GONE else View.VISIBLE },
                    iconButton(ctx, "⚙") { act.openSettings() },
                ),
            ),
        )

        val search = editText(ctx, "بحث في المحادثات")
        search.visibility = View.GONE
        search.doOnTextChanged { s, _, _, _ -> query = s?.toString().orEmpty(); adapter?.notifyDataSetChanged() }
        searchBox = search
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(ctx.dp(10), ctx.dp(6), ctx.dp(10), 0)
        })

        val strip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
        }
        statusStrip = strip
        val hScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }
        root.addView(hScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
        }
        adapter = ChatAdapter()
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        emptyView = TextView(ctx).apply {
            text = "لا توجد محادثات بعد\nاضغط 🔍 لبدء محادثة جديدة"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#667781"))
            visibility = View.GONE
        }
        root.addView(emptyView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(70)))

        Repo.listenChats { list2 ->
            chats = list2
            adapter?.notifyDataSetChanged()
            emptyView?.visibility = if (list2.isEmpty()) View.VISIBLE else View.GONE
        }
        Repo.listenStatus { rows ->
            statusRows = rows
            renderStatuses()
        }
        Repo.listenPeople { people ->
            online = people.associate { it.uid to it.online }
            renderStatuses()
        }
        return root
    }

    private fun renderStatuses() {
        val strip = statusStrip ?: return
        strip.removeAllViews()
        val mine = avatarView(ctx, Fb.myName(ctx).ifBlank { "أنا" }, 54)
        strip.addView(statusCell(mine, "حالتي", true) {
            askText(ctx, "نشر حالة", "اكتب حالتك (تختفي بعد 24 ساعة)") { text ->
                if (text.isNotBlank()) {
                    Repo.postStatus(text, 0xFF00A884.toInt())
                    android.widget.Toast.makeText(ctx, "تم نشر الحالة", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        })
        val seen = HashSet<String>()
        for (s in statusRows) {
            if (s.uid == Repo.uid() || !seen.add(s.uid)) continue
            val a = avatarView(ctx, s.name, 54)
            strip.addView(statusCell(a, s.name, online[s.uid] == true) {
                AlertDialog.Builder(ctx)
                    .setTitle(s.name)
                    .setMessage(s.text + "\n\n" + Fb.dayClock(s.at))
                    .setPositiveButton("إغلاق", null)
                    .show()
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

        override fun getItemCount(): Int = filtered().size

        private fun filtered(): List<Chat> =
            if (query.isBlank()) chats else chats.filter { it.peerName.contains(query, true) || it.lastText.contains(query, true) }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val c = filtered()[position]
            holder.avatar.text = if (c.peerName.isBlank()) "?" else c.peerName.take(1).uppercase()
            holder.avatar.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(nameColor(c.peerName)) }
            holder.name.text = c.peerName
            val tick = if (c.lastSender == Repo.uid()) "✓✓ " else ""
            holder.preview.text = tick + when (c.lastType) {
                "image" -> "📷 صورة"
                "audio" -> "🎤 رسالة صوتية"
                "doc" -> "📄 مستند"
                "location" -> "📍 موقع"
                "contact" -> "👤 جهة اتصال"
                else -> c.lastText
            }
            holder.time.text = if (c.lastAt > 0) Fb.dayClock(c.lastAt) else ""
            if (c.unread > 0) {
                val b = badgeDot(ctx, c.unread)
                if (holder.badgeBox.childCount < 2 && b != null) holder.badgeBox.addView(b)
            } else if (holder.badgeBox.childCount > 1) {
                holder.badgeBox.removeViewAt(1)
            }
            holder.itemView.setOnClickListener {
                val locked = Fb.prefs(ctx).getBoolean("lock_" + c.id, false)
                if (locked) {
                    askText(ctx, "دردشة مقفلة", "أدخل الرمز (الافتراضي 1234)", InputType.TYPE_CLASS_NUMBER) { pin ->
                        if (pin == "1234") openChat(c) else android.widget.Toast.makeText(ctx, "رمز خاطئ", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else {
                    openChat(c)
                }
            }
        }

        private fun openChat(c: Chat) {
            ctx.startActivity(
                Intent(ctx, ChatActivity::class.java)
                    .putExtra("peer", c.peer)
                    .putExtra("name", c.peerName),
            )
        }
    }

    override fun onShown() {
        Repo.setOnline(true)
    }

    companion object {
        fun startChat(ctx: Context, peer: Person) {
            Repo.openChat(peer.uid, peer.name) { id ->
                ctx.startActivity(
                    Intent(ctx, ChatActivity::class.java)
                        .putExtra("peer", peer.uid)
                        .putExtra("name", peer.name),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ التحديثات

class UpdatesTab(act: MainActivity) : Tab(act) {
    private val ctx: Context get() = act
    private var rows: List<StatusRow> = emptyList()
    private var adapter: RecyclerView.Adapter<*>? = null

    override fun build(): View {
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        root.addView(topBar(ctx, "التحديثات", "حالات تختفي بعد 24 ساعة", null, listOf(iconButton(ctx, "＋") { post() })))
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
            override fun getItemCount(): Int = rows.size
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val h = holder as VH
                val s = rows[position]
                h.avatar.text = s.name.take(1).uppercase()
                h.avatar.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(s.color) }
                h.name.text = s.name
                h.body.text = s.text
                h.time.text = Fb.dayClock(s.at)
                h.itemView.setOnClickListener {
                    AlertDialog.Builder(ctx).setTitle(s.name).setMessage(s.text).setPositiveButton("إغلاق", null).show()
                }
            }
        }
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        Repo.listenStatus { r -> rows = r; adapter?.notifyDataSetChanged() }
        return root
    }

    private fun post() {
        askText(ctx, "نشر تحديث", "اكتب حالتك (تختفي بعد 24 ساعة)") { text ->
            if (text.isBlank()) return@askText
            val colors = arrayOf("أخضر", "أزرق", "بنفسجي", "برتقالي")
            val values = intArrayOf(0xFF00A884.toInt(), 0xFF3B7BBF.toInt(), 0xFF7F66FF.toInt(), 0xFFE67E22.toInt())
            AlertDialog.Builder(ctx).setTitle("لون الحالة").setItems(colors) { _, i ->
                Repo.postStatus(text, values[i])
                android.widget.Toast.makeText(ctx, "تم النشر", android.widget.Toast.LENGTH_SHORT).show()
            }.show()
        }
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
                        android.widget.Toast.makeText(ctx, "تم الانضمام", android.widget.Toast.LENGTH_SHORT).show()
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
                h.icon.text = if (c.outgoing) "↗" else if (c.state == "missed") "✖" else "↙"
                h.icon.setTextColor(if (c.state == "missed") Color.parseColor("#E53935") else Color.parseColor("#00A884"))
                h.avatar.text = c.peerName.take(1).uppercase()
                h.avatar.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(nameColor(c.peerName)) }
                h.name.text = c.peerName
                val kind = when {
                    c.state == "missed" -> "فائتة"
                    c.outgoing -> "صادرة"
                    else -> "واردة"
                }
                val dur = if (c.durationMs > 0) " · " + Fb.duration(c.durationMs) else ""
                h.sub.text = kind + " · " + Fb.dayClock(c.at) + dur
                h.itemView.setOnClickListener {
                    CallActivity.start(ctx, c.peer, c.peerName, c.video)
                }
            }
        }
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        Repo.listenPeople { p -> people = p }
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
            android.widget.Toast.makeText(ctx, "لا يوجد مستخدمون مسجلون بعد", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val names = people.map { it.name + if (it.online) "  ●" else "" }.toTypedArray()
        AlertDialog.Builder(ctx).setTitle("اختر شخصاً").setItems(names) { _, i -> cb(people[i]) }.show()
    }

    private fun chooseType(p: Person) {
        AlertDialog.Builder(ctx)
            .setTitle(p.name)
            .setItems(arrayOf("📞 مكالمة صوتية", "🎥 مكالمة مرئية", "💬 محادثة")) { _, i ->
                when (i) {
                    0 -> CallActivity.start(ctx, p.uid, p.name, false)
                    1 -> CallActivity.start(ctx, p.uid, p.name, true)
                    else -> ChatsTab.startChat(ctx, p)
                }
            }
            .show()
    }

    private fun schedule() {
        pickPerson { p ->
            askText(ctx, "جدولة مكالمة مع " + p.name, "بعد كم دقيقة؟", InputType.TYPE_CLASS_NUMBER) { mins ->
                val m = mins.toIntOrNull() ?: 0
                if (m <= 0) return@askText
                val at = System.currentTimeMillis() + m * 60_000L
                Fb.prefs(ctx).edit().putLong("sched_" + p.uid, at).apply()
                android.widget.Toast.makeText(ctx, "تمت الجدولة " + Fb.clock(at), android.widget.Toast.LENGTH_LONG).show()
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
                Fb.db.collection(Fb.USERS).whereEqualTo("phone", num).limit(1).get()
                    .addOnSuccessListener { snap ->
                        val d = snap.documents.firstOrNull()
                        if (d == null) {
                            android.widget.Toast.makeText(ctx, "الرقم غير مسجل", android.widget.Toast.LENGTH_LONG).show()
                        } else {
                            CallActivity.start(ctx, d.id, d.getString("name").orEmpty().ifBlank { num }, false)
                        }
                    }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun favorites() {
        val favs = Fb.prefs(ctx).getStringSet("favs", emptySet()) ?: emptySet()
        if (people.isEmpty()) {
            android.widget.Toast.makeText(ctx, "لا يوجد مستخدمون", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val names = people.map { (if (favs.contains(it.uid)) "⭐ " else "") + it.name }.toTypedArray()
        AlertDialog.Builder(ctx).setTitle("المفضلة — اضغط للتبديل").setItems(names) { _, i ->
            val p = people[i]
            val set = HashSet(favs)
            if (!set.add(p.uid)) set.remove(p.uid)
            Fb.prefs(ctx).edit().putStringSet("favs", set).apply()
            android.widget.Toast.makeText(ctx, if (set.contains(p.uid)) "أُضيف للمفضلة" else "أُزيل", android.widget.Toast.LENGTH_SHORT).show()
        }.show()
    }
}
