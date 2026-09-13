package com.dvstrip.player

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("dvstrip", Context.MODE_PRIVATE)

    var playerPackage: String?
        get() = sp.getString("player_pkg", null)
        set(v) = sp.edit().putString("player_pkg", v).apply()

    var playerActivity: String?
        get() = sp.getString("player_cls", null)
        set(v) = sp.edit().putString("player_cls", v).apply()

    var playerLabel: String?
        get() = sp.getString("player_label", null)
        set(v) = sp.edit().putString("player_label", v).apply()

    var alwaysProxy: Boolean
        get() = sp.getBoolean("always_proxy", false)
        set(v) = sp.edit().putBoolean("always_proxy", v).apply()

    val hasPlayer: Boolean get() = playerPackage != null && playerActivity != null
}
