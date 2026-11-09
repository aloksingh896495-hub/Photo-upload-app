package com.kola.autoupload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class Item(val id: Long, val uri: Uri, val name: String, val size: Long)

object Util {
    const val KEY = "6754d0321222e9f58c723a5081ecfc1f"   // your ImgBB API key
    const val MAX = 32L * 1024 * 1024
    private const val CHANNEL = "upload"
    private val lock = Any()
    val running = AtomicBoolean(false)

    fun hasMedia(ctx: Context): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES
                else android.Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    }

    fun wifiOnly(ctx: Context): Boolean =
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("wifi", true)

    private fun doneFile(ctx: Context) = File(ctx.filesDir, "done.txt")
    private fun linksFile(ctx: Context) = File(ctx.filesDir, "links.csv")

    fun loadDone(ctx: Context): HashSet<Long> {
        val s = HashSet<Long>()
        val f = doneFile(ctx)
        if (f.exists()) f.forEachLine { l -> l.trim().toLongOrNull()?.let { s.add(it) } }
        return s
    }

    fun markDone(ctx: Context, id: Long, name: String, url: String?) {
        synchronized(lock) {
            doneFile(ctx).appendText("$id\n")
            if (url != null) linksFile(ctx).appendText("\"" + name.replace("\"", "\"\"") + "\",\"" + url + "\"\n")
        }
    }

    fun queryAll(ctx: Context): List<Item> {
        val out = ArrayList<Item>()
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE
        )
        ctx.contentResolver.query(base, proj, null, null, MediaStore.Images.Media.DATE_ADDED + " DESC")?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                out.add(Item(id, ContentUris.withAppendedId(base, id), c.getString(iName) ?: "photo_$id.jpg", c.getLong(iSize)))
            }
        }
        return out
    }

    fun pending(ctx: Context): List<Item> {
        val done = loadDone(ctx)
        return queryAll(ctx).filter { it.id !in done }
    }

    /** Uploads one photo. Returns the ImgBB link, or null if it failed. */
    fun upload(ctx: Context, p: Item): String? {
        for (attempt in 0 until 3) {
            var c: HttpURLConnection? = null
            try {
                val boundary = "----kola" + System.nanoTime()
                val safeName = p.name.replace("\"", "_").replace("\r", "").replace("\n", "")
                val head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"" + safeName +
                        "\"\r\nContent-Type: application/octet-stream\r\n\r\n").toByteArray()
                val tail = ("\r\n--" + boundary + "--\r\n").toByteArray()
                c = URL("https://api.imgbb.com/1/upload?key=$KEY").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 30000
                c.readTimeout = 180000
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                c.setFixedLengthStreamingMode(head.size.toLong() + p.size + tail.size.toLong())
                c.outputStream.use { os ->
                    os.write(head)
                    ctx.contentResolver.openInputStream(p.uri)!!.use { ins -> ins.copyTo(os) }
                    os.write(tail)
                }
                val code = c.responseCode
                if (code == 429) { Thread.sleep(20000); continue }
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code in 200..299) {
                    val j = JSONObject(text)
                    if (j.optBoolean("success")) return j.getJSONObject("data").getString("url")
                }
            } catch (e: Exception) {
                try { Thread.sleep(2000L * (attempt + 1)) } catch (ie: InterruptedException) { return null }
            } finally {
                c?.disconnect()
            }
        }
        return null
    }

    fun foreground(ctx: Context, text: String): ForegroundInfo {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Photo upload", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Uploading photos to ImgBB")
            .setContentText(text)
            .setOngoing(true)
            .build()
        return ForegroundInfo(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun constraints(ctx: Context): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly(ctx)) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

    /** Start now + keep checking for new photos every 15 minutes (survives app close and reboot). */
    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.enqueueUniquePeriodicWork(
            "periodic", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES).setConstraints(constraints(ctx)).build()
        )
        wm.enqueueUniqueWork(
            "now", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<UploadWorker>().setConstraints(constraints(ctx)).build()
        )
    }

    /** Called by a finished run that still has photos left, so a big gallery keeps going without waiting. */
    fun chain(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "now", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<UploadWorker>().setConstraints(constraints(ctx)).build()
        )
    }
}
