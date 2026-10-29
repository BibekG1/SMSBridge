package com.smsbridge.main

import android.content.Context
import android.content.SharedPreferences

object FilterManager {
    private const val PREFS_NAME = "smsbridge_filters"
    private const val KEY_FILTERED_APPS = "filtered_apps"
    private const val KEY_FILTER_ENABLED = "filter_enabled"
    private const val KEY_PORT = "bridge_port"
    const val DEFAULT_PORT = 65432

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var isFilterEnabled: Boolean
        get() = prefs.getBoolean(KEY_FILTER_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_FILTER_ENABLED, v).apply()

    var bridgePort: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(v) = prefs.edit().putInt(KEY_PORT, v).apply()

    var filteredApps: Set<String>
        get() = prefs.getStringSet(KEY_FILTERED_APPS, emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet(KEY_FILTERED_APPS, v).apply()

    fun shouldForward(packageName: String?): Boolean {
        if (!isFilterEnabled) return true
        if (packageName == null) return true
        return filteredApps.contains(packageName)
    }

    fun addFilteredApp(packageName: String) {
        filteredApps = filteredApps + packageName
    }

    fun removeFilteredApp(packageName: String) {
        filteredApps = filteredApps - packageName
    }
}
