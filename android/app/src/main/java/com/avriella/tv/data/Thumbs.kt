package com.avriella.tv.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/** Makes and caches little preview pictures for each video (off the main thread). */
object Thumbs {
    private val exec = Executors.newFixedThreadPool(2)
    private val mem = LruCache<String, Bitmap>(60)
    private val main = Handler(Looper.getMainLooper())
    private val failed = HashSet<String>()

    fun load(ctx: Context, v: Video, iv: ImageView) {
        iv.tag = v.id
        mem.get(v.id)?.let { iv.setImageBitmap(it); return }
        iv.setImageDrawable(null)
        if (v.id in failed) return
        val app = ctx.applicationContext
        exec.execute {
            val bmp = try { getOrCreate(app, v) } catch (e: Throwable) { null }
            if (bmp != null) mem.put(v.id, bmp) else synchronized(failed) { failed.add(v.id) }
            main.post { if (iv.tag == v.id && bmp != null) iv.setImageBitmap(bmp) }
        }
    }

    private fun getOrCreate(ctx: Context, v: Video): Bitmap? {
        val dir = File(ctx.filesDir, "thumbs").apply { mkdirs() }
        val f = File(dir, "${v.id}.jpg")
        if (f.exists()) {
            if (v.durationMs == 0L) readDuration(ctx, v)
            return BitmapFactory.decodeFile(f.path)
        }
        val uri = Uri.parse(v.uri)
        var bmp: Bitmap? = null
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (dur > 0 && v.durationMs == 0L) main.post { v.durationMs = dur; Library.save() }
            // Skip past intros / black frames in long compilations.
            val atMs = if (dur > 10 * 60_000) 95_000L else dur / 3
            bmp = if (Build.VERSION.SDK_INT >= 27)
                r.getScaledFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270)
            else r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Exception) {
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
        if (bmp == null && Build.VERSION.SDK_INT >= 29 && uri.authority == "media") {
            try { bmp = ctx.contentResolver.loadThumbnail(uri, Size(480, 270), null) } catch (_: Exception) {}
        }
        bmp?.let { b ->
            val scaled = if (b.width > 640) Bitmap.createScaledBitmap(b, 640, 640 * b.height / b.width, true) else b
            FileOutputStream(f).use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            return scaled
        }
        return null
    }

    private fun readDuration(ctx: Context, v: Video) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, Uri.parse(v.uri))
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (dur > 0) main.post { v.durationMs = dur; Library.save() }
        } catch (_: Exception) {
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    fun forget(id: String) { mem.remove(id) }
}
