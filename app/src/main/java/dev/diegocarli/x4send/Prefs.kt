package dev.diegocarli.x4send

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("x4send", Context.MODE_PRIVATE)

    var host: String
        get() = sp.getString("host", "crosspoint.local") ?: "crosspoint.local"
        set(v) = sp.edit().putString("host", v).apply()

    var folder: String
        get() = sp.getString("folder", "/") ?: "/"
        set(v) = sp.edit().putString("folder", v).apply()

    var autoSort: Boolean
        get() = sp.getBoolean("autoSort", true)
        set(v) = sp.edit().putBoolean("autoSort", v).apply()

    var fast: Boolean
        get() = sp.getBoolean("fast", false)
        set(v) = sp.edit().putBoolean("fast", v).apply()
}
