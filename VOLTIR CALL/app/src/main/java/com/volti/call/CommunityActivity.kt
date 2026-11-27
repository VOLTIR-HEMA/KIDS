package com.volti.call

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.ListenerRegistration

/** مجتمع: رسائل، أعضاء، طلبات انضمام، وإعدادات. */
class CommunityActivity : AppCompatActivity() {

    private lateinit var cid: String
    private var community: Community? = null
    private var msgs: List<CMsg> = emptyList()
    private var reg: ListenerRegistration? = null

    private lateinit var titleView: TextView
    private lateinit var subView: TextView
    private lateinit var messageList: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var joinBar: LinearLayout
    private lateinit var joinText: TextView
    private lateinit var joinButton: TextView
    private lateinit var inputRow: LinearLayout
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cid = intent.getStringExtra("cid").orEmpty()
        if (cid.isBlank()) {
            finish()
            return
        }
        setContentView(build())
        Repo.loadCommunity(cid) { c ->
            community = c
            applyCommunity()
        }
        reg = Repo.listenCommunityMessages(cid) { m ->
            msgs = m
            renderMessages()
        }
    }

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#ECE5DD"))
        }
        titleView = label(this, "مجتمع", 17f, Color.WHITE, true)
        subView = label(this, "", 11f, Color.parseColor("#D6F5EC"))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#008069"))
            setPadding(dp(6), dp(10), dp(6), dp(10))
        }
        bar.addView(TextView(this).apply {
            text = "‹"
            textSize = 30f
            setTextColor(Color.WHITE)
            setPadding(dp(10), 0, dp(6), dp(3))
            setOnClickListener { finish() }
        })
        bar.addView(column(this, titleView, subView).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) }
        })
        bar.addView(textAction("الأعضاء") { membersDialog() })
        bar.addView(textAction("خيارات") { settingsDialog() })
        root.addView(bar)

        joinBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFF8E1"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
        }
        joinText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#5D4037"))
            maxLines = 3
        }
        joinButton = TextView(this).apply {
            text = "انضمام"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundBg(Color.parseColor("#00A884"), dp(20).toFloat())
            setPadding(dp(20), dp(9), dp(20), dp(9))
            isClickable = true
            setOnClickListener { doJoin() }
        }
        joinBar.addView(joinText)
        joinBar.addView(joinButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(10), 0, 0)
        })
        root.addView(joinBar)

        scroll = ScrollView(this)
        messageList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(12))
        }
        scroll.addView(messageList)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        input = editText(this, "رسالة للمجتمع")
        inputRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(TextView(this).apply {
            text = "إرسال"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = roundBg(Color.parseColor("#00A884"), dp(20).toFloat())
            setPadding(dp(16), dp(9), dp(16), dp(9))
            setOnClickListener { send() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(6)
        })
        root.addView(inputRow)
        return root
    }

    private fun textAction(t: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = t
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(6), dp(8), dp(6))
            setOnClickListener { onClick() }
        }

    private fun applyCommunity() {
        val c = community
        if (c == null) {
            toast("المجتمع غير متاح")
            return
        }
        titleView.text = c.name
        val policy = if (c.privacy == "approval") "انضمام بموافقة" else "انضمام مفتوح"
        val posting = if (c.postPolicy == "admins") "النشر للمشرف" else "النشر للجميع"
        subView.text = c.members.toString() + " عضو · " + policy + " · " + posting

        val isOwner = c.owner == Repo.uid()
        val canPost = c.joined && (c.postPolicy == "all" || isOwner)
        when {
            c.joined -> {
                joinBar.visibility = View.GONE
                inputRow.visibility = if (canPost) View.VISIBLE else View.GONE
            }
            c.waiting -> {
                joinBar.visibility = View.VISIBLE
                joinText.text = "طلب انضمامك بانتظار موافقة المشرف."
                joinButton.visibility = View.GONE
                inputRow.visibility = View.GONE
            }
            else -> {
                joinBar.visibility = View.VISIBLE
                joinText.text = if (c.privacy == "approval")
                    "الانضمام يتطلب موافقة المشرف. أرسل طلباً وسيتم إبلاغك."
                else
                    "هذا المجتمع مفتوح — اضغط انضمام للمشاركة في الرسائل."
                joinButton.visibility = View.VISIBLE
                joinButton.text = if (c.privacy == "approval") "طلب انضمام" else "انضمام"
                inputRow.visibility = View.GONE
            }
        }
    }

    private fun doJoin() {
        val c = community ?: return
        Repo.joinCommunity(c) { ok ->
            if (!ok) {
                toast("تعذر الانضمام — راجع القواعد")
                return@joinCommunity
            }
            toast(if (c.privacy == "approval") "تم إرسال طلب الانضمام" else "تم الانضمام")
            Repo.loadCommunity(cid) { x ->
                community = x
                applyCommunity()
            }
        }
    }

    private fun renderMessages() {
        messageList.removeAllViews()
        if (msgs.isEmpty()) {
            messageList.addView(TextView(this).apply {
                text = "لا توجد رسائل بعد"
                textSize = 13f
                setTextColor(Color.parseColor("#667781"))
                gravity = Gravity.CENTER
                setPadding(0, dp(30), 0, dp(10))
            })
            return
        }
        val me = Repo.uid()
        for (m in msgs) {
            val mine = m.sender == me
            val bubble = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = roundBg(if (mine) Color.parseColor("#DCF8C6") else Color.WHITE, dp(14).toFloat())
            }
            if (!mine) {
                bubble.addView(label(this, m.senderName.ifBlank { "عضو" }, 12f, Color.parseColor("#008069"), true))
            }
            bubble.addView(TextView(this).apply {
                text = m.text
                textSize = 15f
                setTextColor(Color.parseColor("#111B21"))
            })
            bubble.addView(label(this, Fb.clock(m.at), 10f, Color.parseColor("#8696A0")).apply {
                gravity = if (mine) Gravity.END else Gravity.START
            })
            messageList.addView(bubble, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(if (mine) dp(60) else 0, dp(3), if (mine) 0 else dp(60), dp(3))
            })
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun send() {
        val t = input.text.toString().trim()
        if (t.isBlank()) return
        val c = community
        if (c == null || !c.joined) {
            toast("انضم للمجتمع أولاً")
            return
        }
        if (c.postPolicy == "admins" && c.owner != Repo.uid()) {
            toast("النشر في هذا المجتمع للمشرف فقط")
            return
        }
        Repo.sendCommunityMessage(cid, t)
        input.setText("")
    }

    private fun membersDialog() {
        val c = community ?: return
        Repo.communityMemberIds(cid) { members, requests ->
            val names = ArrayList<String>()
            var remaining = members.size
            val items = ArrayList<Pair<String, String>>() // uid, label
            if (remaining == 0) showMembers(c, items, requests)
            for (uid in members) {
                Repo.loadUser(uid) { p ->
                    items.add(uid to (p?.name ?: uid))
                    remaining--
                    if (remaining == 0) showMembers(c, items, requests)
                }
            }
        }
    }

    private fun showMembers(c: Community, items: ArrayList<Pair<String, String>>, requests: List<String>) {
        val lines = items.map { (uid, name) ->
            name + (if (uid == c.owner) "  — مشرف" else "") + (if (uid == Repo.uid()) "  (أنت)" else "")
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("الأعضاء (" + items.size + ")")
            .setItems(lines) { _, i ->
                val uid = items[i].first
                if (uid == Repo.uid()) return@setItems
                AlertDialog.Builder(this)
                    .setTitle(items[i].second)
                    .setItems(arrayOf("محادثة", "مكالمة صوتية")) { _, j ->
                        val p = Person(uid, items[i].second, "", "", false, 0L)
                        Repo.isContact(uid) { ok ->
                            if (!ok) {
                                toast("أضفه لجهات الاتصال من تبويب الدردشات أولاً")
                                return@isContact
                            }
                            if (j == 0) {
                                Repo.openChat(uid, p.name) { _ ->
                                    startActivity(
                                        android.content.Intent(this, ChatActivity::class.java)
                                            .putExtra("peer", uid)
                                            .putExtra("name", p.name),
                                    )
                                }
                            } else {
                                CallActivity.start(this, uid, p.name, false)
                            }
                        }
                    }
                    .show()
            }
            .setPositiveButton("إغلاق", null)
            .show()
        if (requests.isNotEmpty() && c.owner == Repo.uid()) {
            val list = requests.map { it }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("طلبات انضمام (" + requests.size + ")")
                .setItems(list) { _, i ->
                    Repo.approveJoin(cid, list[i]) {
                        toast("تمت الموافقة")
                        Repo.loadCommunity(cid) { x ->
                            community = x
                            applyCommunity()
                        }
                    }
                }
                .setNeutralButton("رفض الكل") { _, _ ->
                    for (u in requests) Repo.declineJoin(cid, u)
                }
                .setNegativeButton("إغلاق", null)
                .show()
        }
    }

    private fun settingsDialog() {
        val c = community ?: return
        val isOwner = c.owner == Repo.uid()
        if (!isOwner) {
            AlertDialog.Builder(this)
                .setTitle("خيارات")
                .setItems(arrayOf("مغادرة المجتمع")) { _, _ ->
                    confirm(this, "مغادرة المجتمع", "لن تصلك رسائل " + c.name + " بعد المغادرة.") {
                        Repo.leaveCommunity(cid) { finish() }
                    }
                }
                .setNegativeButton("إلغاء", null)
                .show()
            return
        }
        val items = arrayOf(
            "طريقة الانضمام: " + (if (c.privacy == "approval") "بموافقة" else "مفتوح"),
            "النشر: " + (if (c.postPolicy == "admins") "المشرف فقط" else "الجميع"),
            "طلبات الانضمام",
            "حذف المجتمع",
        )
        AlertDialog.Builder(this)
            .setTitle("إعدادات المجتمع")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> AlertDialog.Builder(this)
                        .setTitle("طريقة الانضمام")
                        .setItems(arrayOf("مفتوح — انضمام مباشر", "بموافقة — تحتاج موافقتك")) { _, j ->
                            Repo.updateCommunity(cid, if (j == 1) "approval" else "open", c.postPolicy) {
                                toast("تم التحديث")
                                Repo.loadCommunity(cid) { x ->
                                    community = x
                                    applyCommunity()
                                }
                            }
                        }
                        .show()
                    1 -> AlertDialog.Builder(this)
                        .setTitle("من يرسل في المجتمع؟")
                        .setItems(arrayOf("الجميع", "المشرف فقط")) { _, j ->
                            Repo.updateCommunity(cid, c.privacy, if (j == 1) "admins" else "all") {
                                toast("تم التحديث")
                                Repo.loadCommunity(cid) { x ->
                                    community = x
                                    applyCommunity()
                                }
                            }
                        }
                        .show()
                    2 -> membersDialog()
                    3 -> confirm(this, "حذف المجتمع", "سيُحذف " + c.name + " نهائياً.") {
                        Repo.deleteCommunity(cid) { finish() }
                    }
                }
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    override fun onDestroy() {
        try {
            reg?.remove()
        } catch (e: Exception) {
            /* تجاهل */
        }
        super.onDestroy()
    }

    private fun toast(m: String) {
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()
    }
}
