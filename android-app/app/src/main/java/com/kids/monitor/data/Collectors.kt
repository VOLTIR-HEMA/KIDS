package com.kids.monitor.data

import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony

/** قراءة البيانات من مزودي المحتوى في النظام بعد التحقق من الصلاحيات. */
object Collectors {

    fun contacts(context: Context): List<Map<String, Any>> {
        if (!Permissions.has(context, android.Manifest.permission.READ_CONTACTS)) return emptyList()
        val out = mutableListOf<Map<String, Any>>()
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null,
            null,
            null,
        ) ?: return out
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(0) ?: ""
                val number = it.getString(1) ?: ""
                if (number.isNotBlank()) {
                    out.add(mapOf("name" to name, "number" to number))
                }
            }
        }
        return out
    }

    fun callLog(context: Context, limit: Int = 100): List<Map<String, Any>> {
        if (!Permissions.has(context, android.Manifest.permission.READ_CALL_LOG)) return emptyList()
        val out = mutableListOf<Map<String, Any>>()
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DURATION, CallLog.Calls.DATE),
            null,
            null,
            "${CallLog.Calls.DATE} DESC",
        ) ?: return out
        cursor.use {
            while (it.moveToNext() && out.size < limit) {
                val number = it.getString(0) ?: ""
                if (number.isBlank()) continue
                val type = when (it.getInt(2)) {
                    CallLog.Calls.INCOMING_TYPE -> "incoming"
                    CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                    CallLog.Calls.MISSED_TYPE -> "missed"
                    else -> "other"
                }
                out.add(
                    mapOf(
                        "name" to (it.getString(1) ?: ""),
                        "number" to number,
                        "type" to type,
                        "duration" to it.getLong(3),
                        "timestamp" to it.getLong(4),
                    ),
                )
            }
        }
        return out
    }

    fun messages(context: Context, limit: Int = 100): List<Map<String, Any>> {
        if (!Permissions.has(context, android.Manifest.permission.READ_SMS)) return emptyList()
        val out = mutableListOf<Map<String, Any>>()
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.TYPE, Telephony.Sms.DATE),
            null,
            null,
            "${Telephony.Sms.DATE} DESC",
        ) ?: return out
        cursor.use {
            while (it.moveToNext() && out.size < limit) {
                val number = it.getString(0) ?: ""
                if (number.isBlank()) continue
                val type = if (it.getInt(2) == Telephony.Sms.MESSAGE_TYPE_SENT) "sent" else "inbox"
                out.add(
                    mapOf(
                        "name" to "",
                        "number" to number,
                        "body" to (it.getString(1) ?: ""),
                        "type" to type,
                        "timestamp" to it.getLong(3),
                    ),
                )
            }
        }
        return out
    }

    fun media(context: Context, limit: Int = 50): List<Map<String, Any>> {
        val images = ContextCompat_mediaPermission(context)
        if (!images) return emptyList()
        val out = mutableListOf<Map<String, Any>>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        val collections = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        )
        for (uri in collections) {
            val cursor = context.contentResolver.query(uri, projection, null, null, null) ?: continue
            cursor.use {
                while (it.moveToNext() && out.size < limit) {
                    val name = it.getString(1) ?: continue
                    val mime = it.getString(2) ?: ""
                    val type = when {
                        mime.startsWith("image") -> "image"
                        mime.startsWith("video") -> "video"
                        mime.startsWith("audio") -> "audio"
                        else -> "file"
                    }
                    out.add(
                        mapOf(
                            "name" to name,
                            "url" to "",
                            "uri" to uri.toString(),
                            "type" to type,
                            "mimeType" to mime,
                            "size" to it.getLong(3),
                            "timestamp" to it.getLong(4) * 1000L,
                        ),
                    )
                }
            }
        }
        return out
    }

    private fun ContextCompat_mediaPermission(context: Context): Boolean {
        return Permissions.has(context, android.Manifest.permission.READ_MEDIA_IMAGES) ||
            Permissions.has(context, android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}
