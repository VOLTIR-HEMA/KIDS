package com.volti.call

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var content: FrameLayout
    private lateinit var navItems: List<LinearLayout>
    private var tabs: List<Tab> = emptyList()
    private var index = 0
    private var ready = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRoot())
        route()
    }

    private fun route() {
        val u = Fb.auth.currentUser
        if (u == null || u.email.isNullOrBlank()) {
            goAuth()
            return
        }
        u.reload().addOnCompleteListener {
            if (!u.isEmailVerified) {
                goAuth()
            } else {
                afterAuth()
            }
        }
    }

    private fun goAuth() {
        startActivity(Intent(this, AuthActivity::class.java))
        finish()
    }

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F7F8FA"))
        }

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(0, dp(6), 0, dp(8))
        }
        val defs = listOf("الدردشات", "التحديثات", "المجتمعات", "المكالمات")
        val items = ArrayList<LinearLayout>()
        for ((i, d) in defs.withIndex()) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                setPadding(0, dp(8), 0, dp(6))
                addView(navIcon(this@MainActivity, i, Color.parseColor("#667781")))
                addView(TextView(this@MainActivity).apply {
                    text = d
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(3), 0, 0)
                })
            }
            item.setOnClickListener { showTab(i) }
            items.add(item)
            nav.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        navItems = items
        root.addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun authError() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        box.addView(TextView(this).apply {
            text = "⚠️ تعذر تسجيل الدخول"
            textSize = 20f
            setTextColor(Color.parseColor("#111B21"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = "فعّل تسجيل الدخول المجهول (Anonymous) من Firebase Console ← Authentication ← Sign-in method، ثم أعد المحاولة."
            textSize = 14f
            setTextColor(Color.parseColor("#667781"))
            setPadding(0, dp(10), 0, dp(16))
        })
        box.addView(pillButton(this, "إعادة المحاولة") {
            recreate()
        })
        content.removeAllViews()
        content.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun afterAuth() {
        val u = Fb.auth.currentUser
        if (u == null) {
            goAuth()
            return
        }
        if (Fb.myName(this).isBlank() || Fb.prefs(this).getString("username", "").orEmpty().isBlank()) {
            Repo.loadUser(u.uid) { p ->
                if (p != null) {
                    Fb.prefs(this).edit()
                        .putString("name", p.name)
                        .putString("username", p.username)
                        .putString("phone", p.phone)
                        .putString("email", u.email.orEmpty())
                        .apply()
                }
                startAll()
            }
        } else {
            startAll()
        }
    }

    private fun startAll() {
        if (ready) {
            showTab(index)
            return
        }
        ready = true
        tabs = listOf(ChatsTab(this), UpdatesTab(this), CommunitiesTab(this), CallsTab(this))
        Perm.ask(this)
        if (!Perm.isBatteryExempt(this) && !Fb.prefs(this).getBoolean("asked_battery", false)) {
            Fb.prefs(this).edit().putBoolean("asked_battery", true).apply()
            AlertDialog.Builder(this)
                .setTitle("تشغيل دائم في الخلفية")
                .setMessage("لتلقي المكالمات والرسائل بدون انقطاع، اسمح للتطبيق بالعمل في الخلفية (تجاهل تحسين البطارية).")
                .setPositiveButton("السماح") { _, _ -> Perm.requestBattery(this) }
                .setNegativeButton("لاحقاً", null)
                .show()
        }
        Repo.setOnline(true)
        try { NotifyService.start(this) } catch (e: Exception) { /* تجاهل */ }
        showTab(0)
        handler.postDelayed({ try { Update.prompt(this) } catch (e: Exception) { /* تجاهل */ } }, 1500)
    }

    private fun showTab(i: Int) {
        if (!ready) return
        index = i
        content.removeAllViews()
        content.addView(tabs[i].view(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        for ((n, item) in navItems.withIndex()) {
            val color = if (n == i) Color.parseColor("#00A884") else Color.parseColor("#667781")
            (item.getChildAt(0) as NavIcon).color = color
            (item.getChildAt(1) as TextView).setTextColor(color)
            item.setBackgroundColor(if (n == i) Color.parseColor("#E7F8F3") else Color.TRANSPARENT)
        }
        tabs[i].onShown()
    }

    override fun onResume() {
        super.onResume()
        if (!ready) return
        if (Fb.auth.currentUser?.isEmailVerified != true) {
            goAuth()
            return
        }
        Repo.setOnline(true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == Perm.CODE && grantResults.any { it != android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            android.widget.Toast.makeText(this, "بعض الأذونات لم تُمنح — قد لا تعمل المكالمات", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (ready) tabs.forEach { it.onActivityResult(requestCode, resultCode, data) }
    }

    fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    fun openContacts() {
        startActivity(Intent(this, ContactsActivity::class.java))
    }
}

/** تبويب أساسي. */
abstract class Tab(val act: MainActivity) {
    private var cached: View? = null
    fun view(): View {
        cached?.let { return it }
        val v = build()
        cached = v
        return v
    }
    abstract fun build(): View
    open fun onShown() {}
    open fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {}
}
