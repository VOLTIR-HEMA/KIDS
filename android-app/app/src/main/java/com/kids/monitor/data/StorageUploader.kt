package com.kids.monitor.data

import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.firebase.storage.FirebaseStorage
import java.io.File

object StorageUploader {

    /** رفع ملف إلى Firebase Storage وإرجاع رابط التنزيل (العملية متزامنة وتُستخدم من خيط خلفية). */
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
}
