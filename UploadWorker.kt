package com.kola.autoupload

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class UploadWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        if (!Util.hasMedia(ctx)) return Result.success()
        if (!Util.running.compareAndSet(false, true)) return Result.success()
        try {
            val todo = Util.pending(ctx)
            if (todo.isEmpty()) return Result.success()
            try { setForegroundAsync(Util.foreground(ctx, "Starting… " + todo.size + " photos")) } catch (e: Exception) {}

            val queue = ConcurrentLinkedQueue(todo)
            val ok = AtomicInteger(0)
            val deadline = System.currentTimeMillis() + 8 * 60 * 1000L   // WorkManager runs get ~10 min; next run continues

            val threads = (1..3).map {
                Thread {
                    while (!isStopped && System.currentTimeMillis() < deadline) {
                        val p = queue.poll() ?: break
                        if (p.size <= 0 || p.size > Util.MAX) {      // empty or >32MB: ImgBB can't take it, don't retry forever
                            Util.markDone(ctx, p.id, p.name, null)
                            continue
                        }
                        val url = Util.upload(ctx, p)
                        if (url != null) {
                            Util.markDone(ctx, p.id, p.name, url)
                            val n = ok.incrementAndGet()
                            if (n % 5 == 0) {
                                try { setForegroundAsync(Util.foreground(ctx, "Uploaded " + n + " · " + queue.size + " left")) } catch (e: Exception) {}
                            }
                        }
                    }
                }.also { t -> t.start() }
            }
            threads.forEach { t -> t.join() }

            if (ok.get() > 0 && queue.isNotEmpty() && !isStopped) Util.chain(ctx)
            return Result.success()
        } finally {
            Util.running.set(false)
        }
    }
}
