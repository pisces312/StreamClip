package com.pisces312.streamclip.util

import android.content.Context
import android.content.SharedPreferences
import com.pisces312.streamclip.R

object TabOrderManager {
    private const val PREFS_NAME = "tab_order_prefs"
    private const val KEY_ORDER = "tab_order"
    
    // Default tab order: trim, trim2, merge, extract, compress, custom
    val DEFAULT_ORDER = listOf("trim", "trim2", "merge", "extract", "compress", "audio_compress", "custom")
    
    val TAB_ICONS = mapOf(
        "trim" to R.drawable.ic_video,
        "trim2" to R.drawable.ic_video,
        "merge" to R.drawable.ic_merge,
        "extract" to R.drawable.ic_extract,
        "compress" to R.drawable.ic_compress,
        "audio_compress" to R.drawable.ic_compress,
        "custom" to R.drawable.ic_terminal
    )
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    fun getOrder(context: Context): List<String> {
        val prefs = getPrefs(context)
        val saved = prefs.getString(KEY_ORDER, null)
        return if (saved != null) {
            // filter keeps only valid tab IDs, order is preserved
            saved.split(",").filter { it in DEFAULT_ORDER }
        } else {
            DEFAULT_ORDER.toList()
        }
    }
    
    fun saveOrder(context: Context, order: List<String>) {
        getPrefs(context).edit().putString(KEY_ORDER, order.joinToString(",")).apply()
    }
    
    fun resetOrder(context: Context) {
        getPrefs(context).edit().remove(KEY_ORDER).apply()
    }
}
