package com.kola.autoupload

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 3000) }
    }

    private fun prefs() = getSharedPreferences("cfg", Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (18 * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        fun tv(t: String, size: Float): TextView = TextView(this).apply { text = t; textSize = size }
        fun btn(t: String, action: () -> Unit): Button = Button(this).apply { text = t; setOnClickListener { action() } }

        root.addView(tv("☁️ Auto Photo Uploader", 22f))
        root.addView(tv("Uploads every photo in your gallery to ImgBB by itself — including new photos you take later — even when the app is closed.", 13f).apply { setPadding(0, pad / 2, 0, pad / 2) })
        status = tv("…", 15f).apply { setPadding(0, pad / 2, 0, pad / 2) }
        root.addView(status)
        root.addView(btn("▶ Allow & start auto upload") { start() })
        root.addView(CheckBox(this).apply {
            text = "Upload on Wi-Fi only (saves mobile data)"
            isChecked = prefs().getBoolean("wifi", true)
            setOnCheckedChangeListener { _, v ->
                prefs().edit().putBoolean("wifi", v).apply()
                if (Util.hasMedia(this@MainActivity)) Util.schedule(this@MainActivity)
            }
        })
        root.addView(btn("🔋 Allow background (battery setting)") {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e: Exception) {}
        })
        root.addView(btn("📄 Save all links to Downloads (CSV)") { exportLinks() })
        root.addView(tv("Tip: when Android asks for photo access, choose “Allow all” — not “Select photos”.", 12f).apply { setPadding(0, pad / 2, 0, 0) })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() { super.onResume(); handler.post(ticker) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(ticker) }

    private fun start() {
        val perms = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermissions(perms.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (Util.hasMedia(this)) {
            Util.schedule(this)
            Toast.makeText(this, "Auto upload started ✔", Toast.LENGTH_LONG).show()
            refresh()
        } else {
            Toast.makeText(this, "Photo permission is needed to upload.", Toast.LENGTH_LONG).show()
        }
    }

    private fun refresh() {
        val ctx = applicationContext
        Thread {
            val has = Util.hasMedia(ctx)
            val done = Util.loadDone(ctx).size
            val left = if (has) Util.pending(ctx).size else -1
            val msg = if (!has) "Not started yet — tap “Allow & start”."
                      else if (left == 0) "✅ Everything is uploaded ($done photos). New photos will upload automatically."
                      else "☁️ Uploaded: $done\n⏳ Waiting: $left" + (if (Util.running.get()) "\nUploading now…" else "\nNext check within ~15 min (or when Wi-Fi is available).")
            runOnUiThread { status.text = msg }
        }.start()
    }

    private fun exportLinks() {
        val f = File(filesDir, "links.csv")
        if (!f.exists()) { Toast.makeText(this, "No uploads yet.", Toast.LENGTH_SHORT).show(); return }
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "imgbb_links_" + System.currentTimeMillis() + ".csv")
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
        if (uri == null) { Toast.makeText(this, "Could not save file.", Toast.LENGTH_SHORT).show(); return }
        contentResolver.openOutputStream(uri)?.use { o ->
            o.write("name,url\n".toByteArray())
            f.inputStream().use { it.copyTo(o) }
        }
        Toast.makeText(this, "Saved in Downloads ✔", Toast.LENGTH_LONG).show()
    }
}
