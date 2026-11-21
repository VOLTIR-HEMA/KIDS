package com.kids.monitor.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * قائمة انتظار محلية بسيطة للبيانات التي تعذّر رفعها بسبب انقطاع الإنترنت.
 * تُصرف تلقائياً عند عودة الاتصال.
 */
object PendingStore {

    private const val PREFS = "pending_store"
    private const val KEY = "items"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readArray(context: Context): JSONArray {
        val raw = prefs(context).getString(KEY, null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    fun add(context: Context, item: JSONObject) {
        val arr = readArray(context)
        arr.put(item)
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    fun addMap(context: Context, item: Map<String, Any?>) {
        add(context, JSONObject(item))
    }

    /** إرجاع كل العناصر المعلّقة وتفريغ القائمة. */
    fun drain(context: Context): List<JSONObject> {
        val arr = readArray(context)
        val out = ArrayList<JSONObject>(arr.length())
        for (i in 0 until arr.length()) {
            out.add(arr.optJSONObject(i) ?: continue)
        }
        prefs(context).edit().remove(KEY).apply()
        return out
    }

    fun size(context: Context): Int = readArray(context).length()
}
