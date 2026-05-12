package com.pisces312.streamclip.util

import android.content.Context
import android.content.SharedPreferences
import com.pisces312.streamclip.R

object TabOrderManager {
    private const val PREFS_NAME = "tab_order_prefs"
    private const val KEY_ORDER = "tab_order"

    // Master list of all tabs. New tabs are appended to the end by default.
    // When adding a new tab, also update: TAB_ICONS, MainPagerAdapter, MainActivity, TabOrderActivity.
    val DEFAULT_ORDER = listOf("settings", "trim", "trim2", "merge", "extract", "compress", "audio_compress", "custom", "metadata")

    val TAB_ICONS = mapOf(
        "trim" to R.drawable.ic_video,
        "trim2" to R.drawable.ic_video,
        "merge" to R.drawable.ic_merge,
        "extract" to R.drawable.ic_extract,
        "compress" to R.drawable.ic_compress,
        "audio_compress" to R.drawable.ic_compress,
        "custom" to R.drawable.ic_terminal,
        "metadata" to R.drawable.ic_info,
        "settings" to R.drawable.ic_tab_settings
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getOrder(context: Context): List<String> {
        val prefs = getPrefs(context)
        val saved = prefs.getString(KEY_ORDER, null)

        if (saved == null) {
            return DEFAULT_ORDER
        }

        val savedList = saved.split(",").filter { it in DEFAULT_ORDER }
        val newTabs = DEFAULT_ORDER.filter { it !in savedList }

        if (newTabs.isEmpty() && savedList.size == saved.split(",").size) {
            // No changes needed: no new tabs, no removed tabs
            return savedList
        }

        // Merge: keep user order for existing tabs, append new tabs at the end
        val merged = savedList + newTabs
        saveOrder(context, merged)
        return merged
    }
    
    fun saveOrder(context: Context, order: List<String>) {
        getPrefs(context).edit().putString(KEY_ORDER, order.joinToString(",")).apply()
    }
    
    fun resetOrder(context: Context) {
        getPrefs(context).edit().remove(KEY_ORDER).apply()
    }
}
