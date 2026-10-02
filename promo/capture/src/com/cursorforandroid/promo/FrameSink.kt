package com.cursorforandroid.promo

import android.graphics.Bitmap
import kotlinx.serialization.json.JsonObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue

/**
 * Where captured frames go: one H.264 file per segment (a segment keeps one canvas size), fed the [Compositor]'s raw
 * BGRA by a writer thread so encoding overlaps the next frame's render, and beside it a JSON line per frame with its
 * marks and touches. In preview mode every Nth frame is saved as a PNG instead, and nothing is encoded.
 */
class FrameSink(private val dir: File, private val preview: Int) : AutoCloseable {

    private class Segment(
        val name: String,
        val width: Int,
        val height: Int,
        val process: Process?,
        val meta: BufferedWriter,
        val queue: ArrayBlockingQueue<ByteArray>,
        val writer: Thread?,
    )

    private var segment: Segment? = null
    private val pool = ArrayBlockingQueue<ByteArray>(POOL)
    private var poolSize = 0

    var frame: Int = 0
        private set

    val open: Boolean get() = segment != null

    /** Whether the frame about to be written will be kept; the director skips rendering the rest in preview mode. */
    fun wants(): Boolean = preview <= 0 || frame % preview == 0

    fun open(name: String, width: Int, height: Int) {
        close()
        require(width % 2 == 0 && height % 2 == 0) { "Segment $name must have even dimensions, not ${width}x$height" }
        dir.mkdirs()
        frame = 0
        val meta = File(dir, "$name.jsonl").bufferedWriter()
        val queue = ArrayBlockingQueue<ByteArray>(2)
        if (preview > 0) {
            File(dir, name).apply { deleteRecursively(); mkdirs() }
            segment = Segment(name, width, height, null, meta, queue, null)
            return
        }
        val process = ProcessBuilder(
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "rawvideo", "-pix_fmt", "bgra", "-s", "${width}x$height", "-framerate", "60", "-i", "pipe:0",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "12", "-pix_fmt", "yuv444p", "-g", "120",
            "-movflags", "+faststart", File(dir, "$name.mp4").absolutePath,
        ).redirectErrorStream(true).redirectOutput(File(dir, "$name.ffmpeg.log")).start()
        val writer = Thread({
            val stdin = process.outputStream.buffered(1 shl 20)
            while (true) {
                val bytes = queue.take()
                if (bytes.isEmpty()) break
                stdin.write(bytes)
                pool.offer(bytes)
            }
            stdin.close()
        }, "promo-ffmpeg-$name").apply { isDaemon = true; start() }
        segment = Segment(name, width, height, process, meta, queue, writer)
    }

    /** A frame's worth of BGRA to render into and hand to [write]: one the encoder has finished with, or a new one. */
    fun buffer(): ByteArray {
        val s = checkNotNull(segment) { "No segment open" }
        return pool.poll() ?: if (poolSize < POOL) ByteArray(s.width * s.height * 4).also { poolSize++ } else pool.take()
    }

    fun write(pixels: ByteArray, meta: JsonObject) {
        val s = checkNotNull(segment) { "No segment open" }
        require(pixels.size == s.width * s.height * 4) { "Frame holds ${pixels.size} bytes, segment ${s.name} is ${s.width}x${s.height}" }
        s.meta.write(meta.toString())
        s.meta.newLine()
        if (s.process == null) {
            png(pixels, s.width, s.height, File(dir, "${s.name}/${"%05d".format(frame)}.png"))
            pool.offer(pixels)
        } else {
            s.queue.put(pixels)
        }
        frame++
    }

    /** Counts a frame that preview mode chose not to render, so frame numbers stay those of the full capture. */
    fun skip(meta: JsonObject) {
        val s = checkNotNull(segment) { "No segment open" }
        s.meta.write(meta.toString())
        s.meta.newLine()
        frame++
    }

    override fun close() {
        val s = segment ?: return
        segment = null
        s.meta.close()
        if (s.process != null) {
            s.queue.put(ByteArray(0))
            s.writer?.join()
            val code = s.process.waitFor()
            check(code == 0) { "ffmpeg failed for ${s.name} ($code): ${File(dir, "${s.name}.ffmpeg.log").readText()}" }
        }
        pool.clear()
        poolSize = 0
        println("promo: segment ${s.name} closed after $frame frames (${s.width}x${s.height})")
    }

    companion object {
        private const val POOL = 4

        /** Writes the [Compositor]'s BGRA [pixels], rows packed, as a PNG: a bitmap's memory here is in the same order. */
        fun png(pixels: ByteArray, width: Int, height: Int, file: File) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
