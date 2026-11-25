package com.volti.call

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import java.io.File

class ChatActivity : AppCompatActivity() {

    private lateinit var peerUid: String
    private lateinit var peerName: String
    private var chatId = ""
    private var messages: List<Msg> = emptyList()
    private var adapter: RecyclerView.Adapter<*>? = null
    private var listView: RecyclerView? = null
    private var input: EditText? = null
    private var micBtn: TextView? = null
    private var searchField: EditText? = null
    private var searchQuery = ""
    private var mediaOnly = false
    private var recorder: MediaRecorder? = null
    private var recFile: File? = null
    private var player: MediaPlayer? = null
    private var lastMarkedCount = -1

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) upload(uri, "image", "photo_" + System.currentTimeMillis() + ".jpg", "image/jpeg")
    }

    private val pickDoc = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val name = displayName(uri) ?: "document_" + System.currentTimeMillis()
            upload(uri, "doc", name, contentResolver.getType(uri) ?: "application/octet-stream")
        }
    }

    private val pickContact = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) {
            val uri = res.data?.data ?: return@registerForActivityResult
            try {
                val c = contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)
                c?.use {
                    if (it.moveToFirst()) {
                        val n = it.getString(0).orEmpty()
                        val p = it.getString(1).orEmpty()
                        send(mapOf("type" to "contact", "contactName" to n, "contactPhone" to p, "text" to n))
                    }
                }
            } catch (e: Exception) { /* تجاهل */ }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        peerUid = intent.getStringExtra("peer").orEmpty()
        peerName = intent.getStringExtra("name").orEmpty().ifBlank { peerUid }
        if (peerUid.isBlank()) {
            finish()
            return
        }
        setContentView(build())
        Repo.openChat(peerUid, peerName) { id ->
            chatId = id
            loadMessages()
        }
    }

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EFE7DE"))
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#008069"))
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        bar.addView(TextView(this).apply {
            text = "‹"
            textSize = 30f
            setTextColor(Color.WHITE)
            setPadding(dp(10), 0, dp(6), dp(3))
            setOnClickListener { finish() }
        })
        bar.addView(avatarView(this, peerName, 38))
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, 0, 0)
        }
        titleCol.addView(label(this, peerName, 16f, Color.WHITE, true))
        titleCol.addView(label(this, "محادثة مشفرة عبر Firebase", 11f, Color.parseColor("#D6F5EC")))
        bar.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(iconButton(this, "📞") { CallActivity.start(this, peerUid, peerName, false) })
        bar.addView(iconButton(this, "🎥") { CallActivity.start(this, peerUid, peerName, true) })
        bar.addView(iconButton(this, "⋮") { menu() })
        root.addView(bar)

        val search = editText(this, "بحث في الرسائل")
        search.visibility = View.GONE
        search.doOnTextChangedCompat { s -> searchQuery = s; adapter?.notifyDataSetChanged() }
        searchField = search
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(10), dp(6), dp(10), 0)
        })

        val list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@ChatActivity) }
        adapter = MsgAdapter()
        list.adapter = adapter
        listView = list
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#F0F2F5"))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        inputRow.addView(iconButton(this, "📎") { attachMenu() })
        val field = editText(this, "اكتب رسالة")
        input = field
        inputRow.addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val mic = iconButton(this, "🎤") { toggleRecording() }
        micBtn = mic
        inputRow.addView(mic)
        inputRow.addView(iconButton(this, "➤") { sendText() })
        root.addView(inputRow)
        return root
    }

    private fun menu() {
        val items = arrayOf("🔍 بحث", "🖼 الوسائط والروابط", "⏳ رسائل ذاتية الاختفاء", "🔒 قفل الدردشة")
        AlertDialog.Builder(this).setTitle("خيارات").setItems(items) { _, i ->
            when (i) {
                0 -> searchField?.visibility = if (searchField?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                1 -> mediaMenu()
                2 -> disappearing()
                3 -> lockChat()
            }
        }.show()
    }

    private fun mediaMenu() {
        val mediaCount = messages.count { it.type == "image" || it.type == "video" || it.type == "doc" }
        val links = messages.count { it.type == "text" && (it.text.contains("http://") || it.text.contains("https://")) }
        AlertDialog.Builder(this)
            .setTitle("الوسائط والروابط")
            .setItems(arrayOf("عرض الوسائط (" + mediaCount + ")", "عرض الروابط (" + links + ")", "عرض الكل")) { _, i ->
                mediaOnly = i == 0
                if (i == 1) {
                    val texts = messages.filter { it.type == "text" && (it.text.contains("http") || it.text.contains("www.")) }
                    if (texts.isEmpty()) {
                        android.widget.Toast.makeText(this, "لا توجد روابط", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        AlertDialog.Builder(this).setTitle("الروابط")
                            .setItems(texts.map { it.text }.toTypedArray()) { _, k ->
                                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(texts[k].text))) } catch (e: Exception) { /* تجاهل */ }
                            }.show()
                    }
                }
                adapter?.notifyDataSetChanged()
            }
            .show()
    }

    private fun disappearing() {
        val labels = arrayOf("إيقاف", "ساعة واحدة", "24 ساعة", "7 أيام")
        val secs = intArrayOf(0, 3600, 86400, 604800)
        AlertDialog.Builder(this).setTitle("رسائل ذاتية الاختفاء").setItems(labels) { _, i ->
            Fb.prefs(this).edit().putInt("exp_" + chatId, secs[i]).apply()
            android.widget.Toast.makeText(this, "تم: " + labels[i], android.widget.Toast.LENGTH_SHORT).show()
        }.show()
    }

    private fun lockChat() {
        val key = "lock_" + chatId
        val locked = Fb.prefs(this).getBoolean(key, false)
        confirm(this, "قفل الدردشة", if (locked) "هل تريد فتح القفل؟ (الرمز 1234)" else "سيُطلب رمز 1234 لفتح الدردشة. متابعة؟") {
            Fb.prefs(this).edit().putBoolean(key, !locked).apply()
            android.widget.Toast.makeText(this, if (!locked) "تم القفل" else "تم إلغاء القفل", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadMessages() {
        if (chatId.isBlank()) return
        Repo.listenMessages(chatId) { msgs ->
            messages = msgs
            adapter?.notifyDataSetChanged()
            listView?.post { listView?.scrollToPosition((messages.size - 1).coerceAtLeast(0)) }
            if (msgs.size != lastMarkedCount) {
                lastMarkedCount = msgs.size
                Repo.markRead(chatId, peerUid, msgs)
            }
        }
    }

    private fun sendText() {
        val text = input?.text?.toString()?.trim().orEmpty()
        if (text.isBlank() || chatId.isBlank()) return
        input?.setText("")
        send(mapOf("type" to "text", "text" to text))
    }

    private fun expires(): Int = Fb.prefs(this).getInt("exp_" + chatId, 0)

    private fun send(data: Map<String, Any>) {
        Repo.sendMessage(chatId, peerUid, data + ("expiresSec" to expires()))
    }

    private fun upload(uri: Uri, type: String, name: String, mime: String) {
        if (chatId.isBlank()) return
        android.widget.Toast.makeText(this, "جارٍ إرسال الملف…", android.widget.Toast.LENGTH_SHORT).show()
        Repo.uploadAndSend(this, chatId, peerUid, uri, type, name, mime, emptyMap(), expires())
    }

    private fun attachMenu() {
        val items = arrayOf("🖼 صورة", "📄 مستند", "📍 موقع", "👤 جهة اتصال")
        AlertDialog.Builder(this).setTitle("إرفاق").setItems(items) { _, i ->
            when (i) {
                0 -> pickImage.launch("image/*")
                1 -> pickDoc.launch("*/*")
                2 -> sendLocation()
                3 -> pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
            }
        }.show()
    }

    private fun sendLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            android.widget.Toast.makeText(this, "صلاحية الموقع غير مفعّلة", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (loc == null) {
                android.widget.Toast.makeText(this, "تعذر تحديد الموقع، جرّب مرة أخرى", android.widget.Toast.LENGTH_LONG).show()
                return
            }
            send(mapOf("type" to "location", "lat" to loc.latitude, "lng" to loc.longitude, "text" to "موقعي"))
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "تعذر تحديد الموقع", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleRecording() {
        if (recorder == null) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                android.widget.Toast.makeText(this, "صلاحية المايك غير مفعّلة", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            try {
                val f = File(cacheDir, "voice_" + System.currentTimeMillis() + ".m4a")
                val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }
                mr.setAudioSource(MediaRecorder.AudioSource.MIC)
                mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mr.setAudioSamplingRate(44100)
                mr.setAudioEncodingBitRate(64000)
                mr.setOutputFile(f.absolutePath)
                mr.prepare()
                mr.start()
                recorder = mr
                recFile = f
                micBtn?.setTextColor(Color.parseColor("#E53935"))
                android.widget.Toast.makeText(this, "جارٍ التسجيل… اضغط لإيقاف والإرسال", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "تعذر التسجيل", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            val f = recFile
            try { recorder?.stop() } catch (e: Exception) { /* تجاهل */ }
            try { recorder?.release() } catch (e: Exception) { /* تجاهل */ }
            recorder = null
            recFile = null
            micBtn?.setTextColor(Color.WHITE)
            if (f != null && f.exists() && f.length() > 1024) {
                upload(Uri.fromFile(f), "audio", f.name, "audio/mp4")
                f.delete()
            }
        }
    }

    private fun displayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun onDestroy() {
        try { player?.release() } catch (e: Exception) { /* تجاهل */ }
        try { recorder?.release() } catch (e: Exception) { /* تجاهل */ }
        super.onDestroy()
    }

    private fun play(url: String) {
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(url)
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "تعذر التشغيل", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private inner class MsgAdapter : RecyclerView.Adapter<MsgAdapter.VH>() {

        inner class VH(v: LinearLayout) : RecyclerView.ViewHolder(v) {
            val bubble: LinearLayout = v.getChildAt(0) as LinearLayout
            val content: LinearLayout = bubble.getChildAt(0) as LinearLayout
            val foot: TextView = bubble.getChildAt(1) as TextView
        }

        private fun visible(): List<Msg> {
            var list = messages
            if (searchQuery.isNotBlank()) list = list.filter { it.text.contains(searchQuery, true) }
            if (mediaOnly) list = list.filter { it.type == "image" || it.type == "video" || it.type == "doc" }
            return list
        }

        override fun getItemCount(): Int = visible().size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val content = LinearLayout(this@ChatActivity).apply { orientation = LinearLayout.VERTICAL }
            val foot = TextView(this@ChatActivity).apply {
                textSize = 10f
                setTextColor(Color.parseColor("#667781"))
                gravity = Gravity.END
                setPadding(0, dp(3), 0, 0)
            }
            val bubble = LinearLayout(this@ChatActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(9), dp(6), dp(9), dp(6))
                addView(content)
                addView(foot)
            }
            val row = LinearLayout(this@ChatActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(8), dp(3), dp(8), dp(3))
                addView(bubble)
            }
            return VH(row)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val m = visible()[position]
            val mine = m.sender == Repo.uid()
            val row = holder.itemView as LinearLayout
            row.gravity = if (mine) Gravity.END else Gravity.START
            holder.bubble.background = roundBg(
                if (mine) Color.parseColor("#D9FDD3") else Color.WHITE,
                dp(12).toFloat(),
            )
            holder.content.removeAllViews()
            when (m.type) {
                "image" -> {
                    val iv = ImageView(this@ChatActivity).apply {
                        adjustViewBounds = true
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        layoutParams = LinearLayout.LayoutParams(dp(210), dp(160))
                        setOnClickListener { showImage(m.url) }
                    }
                    Glide.with(this@ChatActivity).load(m.url).into(iv)
                    holder.content.addView(iv)
                }
                "video" -> holder.content.addView(mediaLine("🎬 فيديو — اضغط للفتح") { openUrl(m.url) })
                "audio" -> holder.content.addView(mediaLine("🎤 رسالة صوتية — تشغيل") { play(m.url) })
                "doc" -> holder.content.addView(mediaLine("📄 " + (m.name.ifBlank { "مستند" })) { openUrl(m.url) })
                "location" -> holder.content.addView(mediaLine("📍 موقع — فتح الخريطة") {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + m.lat + "," + m.lng + "?q=" + m.lat + "," + m.lng))) } catch (e: Exception) { /* تجاهل */ }
                })
                "contact" -> {
                    val t = TextView(this@ChatActivity).apply {
                        text = "👤 " + m.contactName + "\n" + m.contactPhone
                        setTextColor(Color.parseColor("#111B21"))
                        textSize = 15f
                        setPadding(0, dp(2), 0, dp(2))
                        setOnClickListener {
                            try { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + m.contactPhone))) } catch (e: Exception) { /* تجاهل */ }
                        }
                    }
                    holder.content.addView(t)
                }
                else -> {
                    val t = TextView(this@ChatActivity).apply {
                        text = m.text
                        textSize = 15f
                        setTextColor(Color.parseColor("#111B21"))
                        setTextIsSelectable(true)
                        setPadding(0, dp(1), 0, dp(1))
                    }
                    holder.content.addView(t)
                }
            }
            val tick = if (mine) {
                if (m.readBy.contains(peerUid)) " ✓✓" else " ✓"
            } else ""
            holder.foot.text = Fb.clock(m.at) + tick
            holder.foot.setTextColor(if (mine && m.readBy.contains(peerUid)) Color.parseColor("#34B7F1") else Color.parseColor("#667781"))
        }

        private fun mediaLine(text: String, onClick: () -> Unit): TextView =
            TextView(this@ChatActivity).apply {
                this.text = text
                textSize = 15f
                setTextColor(Color.parseColor("#0B57D0"))
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(2), 0, dp(2))
                setOnClickListener { onClick() }
            }
    }

    private fun showImage(url: String) {
        val iv = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        Glide.with(this).load(url).into(iv)
        val wrap = FrameLayout(this).apply { setPadding(dp(8), dp(8), dp(8), dp(8)); addView(iv) }
        AlertDialog.Builder(this).setView(wrap).setPositiveButton("إغلاق", null).show()
    }

    private fun openUrl(url: String) {
        if (url.isBlank()) return
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) {
            android.widget.Toast.makeText(this, "تعذر الفتح", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

/** doOnTextChanged مبسط لتجنب مشاكل الاستيراد. */
fun EditText.doOnTextChangedCompat(onChange: (String) -> Unit) {
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { onChange(s?.toString().orEmpty()) }
        override fun afterTextChanged(s: android.text.Editable?) {}
    })
}
