package com.dvstrip.player

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var playerButton: Button
    private lateinit var cacheSize: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)

        playerButton = findViewById(R.id.playerButton)
        cacheSize = findViewById(R.id.cacheSize)
        val alwaysProxyCheck = findViewById<CheckBox>(R.id.alwaysProxyCheck)
        val clearCacheButton = findViewById<Button>(R.id.clearCacheButton)

        alwaysProxyCheck.isChecked = prefs.alwaysProxy
        alwaysProxyCheck.setOnCheckedChangeListener { _, checked -> prefs.alwaysProxy = checked }

        playerButton.setOnClickListener { pickPlayer() }
        clearCacheButton.setOnClickListener {
            StripEngine.cleanupCache(this, maxAgeMs = 0)
            updateCacheSize()
        }

        updatePlayerLabel()
        updateCacheSize()
    }

    private fun pickPlayer() {
        val players = PlayerRegistry.installedPlayers(this)
        if (players.isEmpty()) {
            Toast.makeText(this, R.string.no_players_found, Toast.LENGTH_LONG).show()
            return
        }
        val labels = players.map { "${it.label}  (${it.packageName})" }.toTypedArray()
        val current = players.indexOfFirst {
            it.packageName == prefs.playerPackage && it.activityName == prefs.playerActivity
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pick_player_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val p = players[which]
                prefs.playerPackage = p.packageName
                prefs.playerActivity = p.activityName
                prefs.playerLabel = p.label
                updatePlayerLabel()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updatePlayerLabel() {
        playerButton.text = prefs.playerLabel ?: getString(R.string.settings_target_player_none)
    }

    private fun updateCacheSize() {
        val bytes = StripEngine.cacheDir(this).listFiles()?.sumOf { it.length() } ?: 0L
        val human = when {
            bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / 1073741824.0)
            bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
            else -> "$bytes B"
        }
        cacheSize.text = getString(R.string.settings_cache_size, human)
    }
}
