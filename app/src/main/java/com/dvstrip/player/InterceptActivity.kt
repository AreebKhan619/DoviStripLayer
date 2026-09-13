package com.dvstrip.player

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

class InterceptActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var percent: TextView
    private lateinit var cancelButton: Button
    private var stripJob: StripEngine.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_intercept)
        prefs = Prefs(this)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        percent = findViewById(R.id.percent)
        cancelButton = findViewById(R.id.cancelButton)
        cancelButton.setOnClickListener {
            stripJob?.cancel()
            finish()
        }

        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }
        if (!prefs.hasPlayer) {
            promptNoPlayer()
            return
        }
        StripEngine.cleanupCache(this)
        analyze(uri)
    }

    override fun onDestroy() {
        stripJob?.cancel()
        super.onDestroy()
    }

    private fun analyze(uri: Uri) {
        status.text = getString(R.string.analyzing)
        lifecycleScope.launch {
            val info = runCatching { MediaProbe.probe(this@InterceptActivity, uri) }.getOrNull()
            if (isFinishing) return@launch
            if (info == null) {
                offerPassThrough(uri, getString(R.string.error_title))
                return@launch
            }
            when (Decision.actionFor(info)) {
                DvAction.FORWARD -> forward(uri, intent.type)
                DvAction.WARN_P5 -> warnProfile5(uri)
                DvAction.STRIP -> strip(uri, info)
            }
        }
    }

    private fun strip(uri: Uri, info: DvInfo) {
        val isLocal = uri.scheme == "file" || uri.scheme == "content"
        val size = info.sizeBytes ?: localFileSize(uri)
        val free = StatFs(cacheDir.absolutePath).availableBytes
        when (Decision.stripMode(isLocal, size, free, prefs.alwaysProxy)) {
            StripMode.PROXY -> {
                status.text = getString(R.string.starting_proxy)
                ProxyService.start(this, uri)
                forward(Uri.parse(ProxyService.streamUrl()), "video/x-matroska")
            }
            StripMode.TEMP_FILE -> {
                val out = StripEngine.cacheFile(this, uri, size, FfCommands.outputExtension(info.container))
                if (out.exists() && StripEngine.okMarker(out).exists()) {
                    forwardFile(out)
                    return
                }
                status.text = getString(R.string.stripping)
                progress.isIndeterminate = false
                stripJob = StripEngine.remuxToFile(this, uri, out, info.durationSec, object : StripEngine.Listener {
                    override fun onProgress(pct: Int) {
                        runOnUiThread {
                            progress.progress = pct
                            percent.text = "$pct%"
                        }
                    }

                    override fun onDone(success: Boolean, message: String?) {
                        runOnUiThread {
                            when {
                                isFinishing || message == "cancelled" -> Unit
                                success -> forwardFile(out)
                                else -> offerPassThrough(uri, getString(R.string.error_title))
                            }
                        }
                    }
                })
            }
        }
    }

    private fun localFileSize(uri: Uri): Long? = when (uri.scheme) {
        "file" -> uri.path?.let { File(it).length().takeIf { l -> l > 0 } }
        "content" -> runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { l -> l > 0 } }
        }.getOrNull()
        else -> null
    }

    private fun warnProfile5(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle(R.string.p5_warning_title)
            .setMessage(R.string.p5_warning_message)
            .setPositiveButton(R.string.play_anyway) { _, _ -> forward(uri, intent.type) }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun offerPassThrough(uri: Uri, title: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setPositiveButton(R.string.pass_through) { _, _ -> forward(uri, intent.type) }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun promptNoPlayer() {
        AlertDialog.Builder(this)
            .setTitle(R.string.no_player_title)
            .setMessage(R.string.no_player_message)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
                finish()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun forwardFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "com.dvstrip.player.files", file)
        val mime = if (file.extension == "mp4") "video/mp4" else "video/x-matroska"
        forward(uri, mime)
    }

    private fun forward(uri: Uri, mime: String?) {
        status.text = getString(R.string.forwarding)
        val target = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime ?: "video/*")
            .setClassName(prefs.playerPackage!!, prefs.playerActivity!!)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.getStringExtra("title")?.let { target.putExtra("title", it) }
        try {
            startActivity(target)
        } catch (e: ActivityNotFoundException) {
            prefs.playerPackage = null
            prefs.playerActivity = null
            promptNoPlayer()
            return
        }
        finish()
    }
}
