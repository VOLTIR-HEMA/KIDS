package com.volti.call

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doOnTextChanged

/** جهات الاتصال: القائمة، البحث باليوزر/الرقم، طلبات الصداقة. */
class ContactsActivity : AppCompatActivity() {

    private lateinit var body: LinearLayout
    private lateinit var search: EditText
    private var contacts: List<Person> = emptyList()
    private var requests: List<Req> = emptyList()
    private var found: Person? = null
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(build())
        Repo.listenContacts { c ->
            contacts = c
            render()
        }
        Repo.listenRequests { r ->
            requests = r
            render()
        }
    }

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F7F8FA"))
        }
        root.addView(topBar(this, "جهات الاتصال", "أصدقاؤك في VOLTIR CALL", { finish() }))

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(4))
        }
        search = editText(this, "ابحث باسم المستخدم (@) أو رقم الهاتف", InputType.TYPE_CLASS_TEXT)
        search.doOnTextChanged { s, _, _, _ ->
            query = s?.toString().orEmpty()
            if (query.isBlank()) {
                found = null
                render()
            }
        }
        searchRow.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchRow.addView(TextView(this).apply {
            text = "بحث"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackground(roundBg(Color.parseColor("#00A884"), dp(20).toFloat()))
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setOnClickListener { doSearch() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8)
        })
        root.addView(searchRow)

        val scroll = ScrollView(this)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(24))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun doSearch() {
        val q = query.trim()
        if (q.isBlank()) {
            toast("اكتب اسم المستخدم أو الرقم")
            return
        }
        toast("جارٍ البحث…")
        Repo.findUser(q) { p ->
            found = p
            if (p == null) toast("لا يوجد مستخدم مطابق")
            render()
        }
    }

    private fun render() {
        body.removeAllViews()

        val f = found
        if (f != null) {
            section("نتيجة البحث")
            val isContact = contacts.any { it.uid == f.uid }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundBg(Color.WHITE, dp(14).toFloat())
            }
            card.addView(row(this, avatarView(this, f.name, 44), gap(this, 10, 0), column(
                this,
                label(this, f.name, 16f, Color.parseColor("#111B21"), true),
                label(this, "@" + f.username + (if (f.phone.isNotBlank()) " · " + f.phone else ""), 12f, Color.parseColor("#667781")),
            ).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }))
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            if (isContact) {
                actions.addView(smallButton("محادثة") { openChat(f) }, weight())
                actions.addView(smallButton("مكالمة") { call(f, false) }, weight())
                actions.addView(smallButton("مرئية") { call(f, true) }, weight())
            } else {
                actions.addView(smallButton("إضافة جهة اتصال") { addContact(f) }, weight())
                actions.addView(smallButton("طلب صداقة") { request(f) }, weight())
                actions.addView(smallButton("محادثة") { openChat(f) }, weight())
            }
            card.addView(actions)
            body.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(2), 0, dp(2), dp(10))
            })
        }

        if (requests.isNotEmpty()) {
            section("طلبات الصداقة (" + requests.size + ")")
            for (r in requests) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = roundBg(Color.WHITE, dp(14).toFloat())
                }
                card.addView(avatarView(this, r.fromName, 42))
                card.addView(column(
                    this,
                    label(this, r.fromName, 15f, Color.parseColor("#111B21"), true),
                    label(this, "@" + r.fromUsername, 12f, Color.parseColor("#667781")),
                ).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) }
                })
                card.addView(smallButton("قبول") {
                    Repo.acceptRequest(r) { ok ->
                        toast(if (ok) "تم القبول — أصبحتما جهتي اتصال" else "تعذر القبول")
                    }
                })
                card.addView(gap(this, 6, 0))
                card.addView(smallButton("رفض", false) { Repo.declineRequest(r) })
                body.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(2), 0, dp(2), dp(8))
                })
            }
        }

        section("جهات اتصالي (" + contacts.size + ")")
        if (contacts.isEmpty()) {
            body.addView(TextView(this).apply {
                text = "لا توجد جهات اتصال بعد.\nابحث باسم المستخدم أو الرقم وأضفه."
                textSize = 14f
                setTextColor(Color.parseColor("#667781"))
                setPadding(dp(6), dp(6), dp(6), dp(10))
                maxLines = 4
            })
        }
        for (p in contacts) {
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(9), dp(12), dp(9))
                setBackgroundColor(Color.WHITE)
                isClickable = true
                setOnClickListener { contactMenu(p) }
            }
            rowView.addView(avatarView(this, p.label, 44))
            val sub = if (p.alias.isNotBlank() && p.alias != p.name) {
                p.name + " · @" + p.username
            } else {
                "@" + p.username + (if (p.phone.isNotBlank()) " · " + p.phone else "")
            }
            rowView.addView(column(
                this,
                label(this, p.label + (if (p.online) "  ●" else ""), 15f, Color.parseColor("#111B21"), true),
                label(this, sub, 12f, Color.parseColor("#667781")),
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) }
            })
            rowView.addView(smallButton("مكالمة") { call(p, false) })
            body.addView(rowView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            body.addView(divider(this))
        }
    }

    private fun contactMenu(p: Person) {
        AlertDialog.Builder(this)
            .setTitle(p.label)
            .setItems(arrayOf("محادثة", "مكالمة صوتية", "مكالمة مرئية", "تعديل الاسم المخصص", "حذف من جهات الاتصال")) { _, i ->
                when (i) {
                    0 -> openChat(p)
                    1 -> call(p, false)
                    2 -> call(p, true)
                    3 -> askText(this, "الاسم المخصص", "اكتب الاسم الذي تحبه", InputType.TYPE_CLASS_TEXT) { alias ->
                        Repo.addContact(p, alias.ifBlank { p.name }) { ok -> toast(if (ok) "تم التحديث" else "تعذر الحفظ") }
                    }
                    4 -> confirm(this, "حذف جهة الاتصال", "لن تتمكن من مكالمة " + p.label + " بعد الحذف.") {
                        Repo.removeContact(p.uid)
                        toast("تم الحذف")
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun addContact(p: Person) {
        askText(this, "إضافة جهة اتصال", "الاسم المخصص (اختياري)") { alias ->
            Repo.addContact(p, alias.ifBlank { p.name }) { ok ->
                toast(if (ok) "تمت الإضافة" else "تعذر الحفظ — راجع القواعد")
            }
        }
    }

    private fun request(p: Person) {
        Repo.sendRequest(p) { ok -> toast(if (ok) "تم إرسال طلب الصداقة" else "تعذر الإرسال") }
    }

    private fun openChat(p: Person) {
        Repo.openChat(p.uid, p.label) { _ ->
            startActivity(
                Intent(this, ChatActivity::class.java)
                    .putExtra("peer", p.uid)
                    .putExtra("name", p.label),
            )
        }
    }

    private fun call(p: Person, video: Boolean) {
        if (contacts.none { it.uid == p.uid }) {
            AlertDialog.Builder(this)
                .setTitle(p.name)
                .setMessage("المكالمات لجهات الاتصال فقط. أضفه أولاً.")
                .setPositiveButton("إضافة") { _, _ -> addContact(p) }
                .setNegativeButton("إلغاء", null)
                .show()
            return
        }
        CallActivity.start(this, p.uid, p.label, video)
    }

    private fun section(t: String) {
        body.addView(TextView(this).apply {
            text = t
            textSize = 13f
            setTextColor(Color.parseColor("#008069"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(6), dp(14), dp(6), dp(8))
        })
    }

    private fun smallButton(text: String, filled: Boolean = true, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(if (filled) Color.WHITE else Color.parseColor("#008069"))
            background = roundBg(
                if (filled) Color.parseColor("#00A884") else Color.TRANSPARENT,
                dp(16).toFloat(),
                if (filled) 0 else dp(1),
                Color.parseColor("#00A884"),
            )
            setPadding(dp(10), dp(7), dp(10), dp(7))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginStart = dp(3)
        marginEnd = dp(3)
    }

    private fun toast(m: String) {
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()
    }
}
