package com.kids.monitor.data

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.firebase.storage.FirebaseStorage
import java.io.File

object StorageUploader {

    /** رفع ملف إلى Firebase Storage وإرجاع رابط التنزيل (عملية متزامنة وتُستخدم من خيط خلفية). */
    fun uploadSync(file: File, path: String): String? {
        val uid = FirestoreSync.uid() ?: return null
        return try {
            val ref = FirebaseStorage.getInstance().reference.child("media/$uid/$path")
            Tasks.await(ref.putFile(Uri.fromFile(file)))
            val uri = Tasks.await(ref.downloadUrl)
            uri?.toString()
        } catch (e: Exception) {
            null
        }
    }

    /** رفع محتوى من Uri (مثلاً تسجيل صوتي من MediaStore) وإرجاع رابط التنزيل. */
    fun uploadUri(context: Context, uri: Uri, path: String): String? {
        val uid = FirestoreSync.uid() ?: return null
        return try {
            val ref = FirebaseStorage.getInstance().reference.child("media/$uid/$path")
            val stream = context.contentResolver.openInputStream(uri) ?: return null
            Tasks.await(ref.putStream(stream))
            stream.close()
            val downloaded = Tasks.await(ref.downloadUrl)
            downloaded?.toString()
        } catch (e: Exception) {
            null
        }
    }
}
