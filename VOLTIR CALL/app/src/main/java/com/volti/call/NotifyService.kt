package com.volti.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.ListenerRegistration

/** خدمة خلفية: إشعارات الرسائل والمكالمات الواردة حتى مع إغلاق الواجهة. */
class NotifyService : Service() {

    companion object {
        const val CH_SERVICE = "volti_service"
        const val CH_CALLS = "volti_calls"
        const val CH_MSGS = "volti_msgs"
        const val ID_SERVICE = 4101

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, NotifyService::class.java))
            } catch (e: Exception) { /* تجاهل */ }
        }
    }

    private val regs = ArrayList<ListenerRegistration>()
    private val callsSeen = HashSet<String>()
    private val unread = HashMap<String, Int>()
    private var primed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val n = NotificationCompat.Builder(this, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("VOLTIR CALL يعمل")
            .setContentText("جاهز لاستقبال المكالمات والرسائل")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        try {
            ServiceCompat.startForeground(this, ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            try { startForeground(ID_SERVICE, n) } catch (e2: Exception) { /* تجاهل */ }
        }
        if (Fb.auth.currentUser == null) {
            Fb.ensure(this) { ok -> if (ok) listen() }
        } else {
            listen()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Fb.auth.currentUser != null && regs.isEmpty()) listen()
        return START_STICKY
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "الخدمة", NotificationManager.IMPORTANCE_MIN),
        )
        val call = NotificationChannel(CH_CALLS, "المكالمات الواردة", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "تنبيه المكالمات الواردة"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 300, 500)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
        }
        val msg = NotificationChannel(CH_MSGS, "الرسائل", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "تنبيه الرسائل الجديدة"
            enableVibration(true)
        }
        nm.createNotificationChannel(call)
        nm.createNotificationChannel(msg)
    }

    private fun listen() {
        val me = Repo.uid()
        if (me.isBlank()) return
        listenCalls(me)
        listenChats(me)
    }

    private fun listenCalls(me: String) {
        regs.add(
            Fb.db.collection(Fb.CALLS).whereEqualTo("to", me).limit(20).addSnapshotListener { snap, _ ->
                if (snap == null) return@addSnapshotListener
                val now = System.currentTimeMillis()
                for (d in snap.documents) {
                    val state = d.getString("state").orEmpty()
                    val fresh = now - (d.getLong("createdAt") ?: 0L) < 120_000L
                    if (state == "ringing" && fresh) {
                        if (callsSeen.add(d.id)) notifyCall(d)
                    } else if (state != "ringing") {
                        callsSeen.remove(d.id)
                        try { NotificationManagerCompat.from(this).cancel(d.id.hashCode()) } catch (e: Exception) { /* تجاهل */ }
                    }
                }
            },
        )
    }

    private fun notifyCall(d: DocumentSnapshot) {
        val from = d.getString("from").orEmpty()
        val name = d.getString("fromName").orEmpty().ifBlank { from }
        val video = d.getBoolean("video") == true
        val intent = Intent(this, CallActivity::class.java)
            .putExtra("callId", d.id)
            .putExtra("peer", from)
            .putExtra("name", name)
            .putExtra("video", video)
            .putExtra("incoming", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(this, d.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CH_CALLS)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(if (video) "🎥 مكالمة مرئية واردة" else "📞 مكالمة صوتية واردة")
            .setContentText(name)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        post(d.id.hashCode(), n)
    }

    private fun listenChats(me: String) {
        regs.add(
            Fb.db.collection(Fb.CHATS).whereArrayContains("members", me).addSnapshotListener { snap, _ ->
                if (snap == null) return@addSnapshotListener
                for (d in snap.documents) {
                    val unreadMap = d.get("unread") as? Map<*, *>
                    val count = (unreadMap?.get(me) as? Number)?.toInt() ?: 0
                    val prev = unread[d.id] ?: count
                    unread[d.id] = count
                    if (!primed) continue
                    if (count > prev && d.getString("lastSender").orEmpty() != me) notifyMessage(d, count)
                }
                primed = true
            },
        )
    }

    private fun notifyMessage(d: DocumentSnapshot, count: Int) {
        val me = Repo.uid()
        val members = (d.get("members") as? List<*>)?.map { it?.toString().orEmpty() }.orEmpty()
        val peer = members.firstOrNull { it != me }.orEmpty()
        val names = d.get("names") as? Map<*, *>
        val name = names?.get(peer)?.toString().orEmpty().ifBlank { "رسالة جديدة" }
        val text = d.getString("lastText").orEmpty().ifBlank { "رسالة جديدة" }
        if (peer.isBlank()) return
        val intent = Intent(this, ChatActivity::class.java)
            .putExtra("peer", peer)
            .putExtra("name", name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(this, d.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CH_MSGS)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(name)
            .setContentText(text)
            .setNumber(count)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        post(d.id.hashCode(), n)
    }

    private fun post(id: Int, n: Notification) {
        try {
            NotificationManagerCompat.from(this).notify(id, n)
        } catch (e: Exception) { /* إذن الإشعارات غير ممنوح */ }
    }

    override fun onDestroy() {
        for (r in regs) try { r.remove() } catch (e: Exception) { /* تجاهل */ }
        regs.clear()
        super.onDestroy()
    }
}
