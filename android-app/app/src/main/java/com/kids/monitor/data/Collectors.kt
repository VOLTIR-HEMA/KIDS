package com.kids.monitor.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import java.io.File

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
                if (number.isNotBlank()) out.add(mapOf("name" to name, "number" to number))
            }
        }
        return out
    }

    fun callLog(context: Context, limit: Int = 100): List<Map<String, Any>> {
        if (!Permissions.has(context, android.Manifest.permission.READ_CALL_LOG)) return emptyList()
        val out = mutableListOf<Map<String, Any>>()
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DURATION,
                CallLog.Calls.DATE,
            ),
            null,
            null,
            "${CallLog.Calls.DATE} DESC",
        ) ?: return out
        cursor.use {
            while (it.moveToNext() && out.size < limit) {
                val number = it.getString(1) ?: ""
                if (number.isBlank()) continue
                val type = when (it.getInt(3)) {
                    CallLog.Calls.INCOMING_TYPE -> "incoming"
                    CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                    CallLog.Calls.MISSED_TYPE -> "missed"
                    CallLog.Calls.REJECTED_TYPE -> "rejected"
                    else -> "other"
                }
                out.add(
                    mapOf(
                        "callId" to it.getLong(0),
                        "name" to (it.getString(2) ?: ""),
                        "number" to number,
                        "type" to type,
                        "duration" to it.getLong(4),
                        "timestamp" to it.getLong(5),
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
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.TYPE,
                Telephony.Sms.DATE,
            ),
            null,
            null,
            "${Telephony.Sms.DATE} DESC",
        ) ?: return out
        cursor.use {
            while (it.moveToNext() && out.size < limit) {
                val number = it.getString(1) ?: ""
                if (number.isBlank()) continue
                val type = when (it.getInt(3)) {
                    Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
                    Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "sent"
                    Telephony.Sms.MESSAGE_TYPE_FAILED -> "failed"
                    else -> "inbox"
                }
                out.add(
                    mapOf(
                        "smsId" to it.getLong(0),
                        "name" to "",
                        "number" to number,
                        "body" to (it.getString(2) ?: ""),
                        "type" to type,
                        "timestamp" to it.getLong(4),
                    ),
                )
            }
        }
        return out
    }

    fun media(context: Context, limit: Int = 50): List<Map<String, Any>> {
        if (!hasMediaPermission(context)) return emptyList()
        val out = mutableListOf<Map<String, Any>>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        )
        val collections = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        )
        for (uri in collections) {
            val cursor = context.contentResolver.query(
                uri, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            ) ?: continue
            cursor.use {
                while (it.moveToNext() && out.size < limit) {
                    val id = it.getLong(0)
                    val name = it.getString(1) ?: continue
                    val mime = it.getString(2) ?: ""
                    val type = when {
                        mime.startsWith("image") -> "image"
                        mime.startsWith("video") -> "video"
                        mime.startsWith("audio") -> "audio"
                        else -> "file"
                    }
                    val contentUri = Uri.withAppendedPath(uri, id.toString())
                    out.add(
                        mapOf(
                            "mediaId" to id,
                            "collection" to uri.toString(),
                            "name" to name,
                            "url" to "",
                            "uri" to contentUri.toString(),
                            "type" to type,
                            "mimeType" to mime,
                            "size" to it.getLong(3),
                            "path" to (it.getString(5) ?: ""),
                            "album" to (it.getString(6) ?: ""),
                            "timestamp" to it.getLong(4) * 1000L,
                        ),
                    )
                }
            }
        }
        return out
    }

    /** إنشاء نسخة مصغّرة من صورة/فيديو لتقليل حجم الرفع والبيانات. */
    fun thumbnail(context: Context, uri: Uri, maxPx: Int = 1024, quality: Int = 80): File? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            var sample = 1
            while (options.outWidth / sample > maxPx || options.outHeight / sample > maxPx) sample *= 2
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: return null
            val scaled = if (bitmap.width > maxPx || bitmap.height > maxPx) {
                val ratio = minOf(maxPx.toFloat() / bitmap.width, maxPx.toFloat() / bitmap.height)
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true,
                )
            } else bitmap
            val file = File(context.cacheDir, "thumb_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            file
        } catch (e: Exception) {
            null
        }
    }

    /** نسخة مصغّرة لفيديو عبر MediaStore (أندرويد 29+) أو ThumbnailUtils. */
    fun videoThumbnail(context: Context, uri: Uri, maxPx: Int = 1024): File? {
        return try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, android.util.Size(maxPx, maxPx), null)
            } else {
                @Suppress("DEPRECATION")
                android.media.ThumbnailUtils.createVideoThumbnail(
                    uri.path ?: return null,
                    MediaStore.Images.Thumbnails.MINI_KIND,
                ) ?: return null
            }
            val file = File(context.cacheDir, "vthumb_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            bitmap.recycle()
            file
        } catch (e: Exception) {
            null
        }
    }

    private fun hasMediaPermission(context: Context): Boolean =
        Permissions.mediaPermissions().any { Permissions.has(context, it) }
}
