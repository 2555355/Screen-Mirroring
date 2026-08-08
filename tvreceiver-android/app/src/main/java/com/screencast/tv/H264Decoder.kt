package com.screencast.tv

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * H.264 解码器：把发射端推过来的 annexb 帧解码并渲染到 [surface]。
 *
 * - 第一次收到含 SPS/PPS 的帧时，用 csd-0/csd-1 配置 MediaCodec 并 start。
 * - 之后每一帧直接 queueInputBuffer，并 drain 输出到 surface。
 */
class H264Decoder(private val surface: Surface) {

    companion object {
        private const val TAG = "H264Decoder"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    }

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var codec: MediaCodec? = null
    private var configured = false

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    private val queue = LinkedBlockingQueue<Pair<Long, ByteArray>>(300)
    private val info = MediaCodec.BufferInfo()

    fun start() {
        running = true
        thread = Thread({ decodeLoop() }, "h264-decode").also { it.start() }
    }

    fun feed(timestampUs: Long, data: ByteArray) {
        queue.offer(timestampUs to data)
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        synchronized(this) {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            codec = null
            configured = false
            sps = null
            pps = null
        }
        queue.clear()
    }

    private fun decodeLoop() {
        while (running) {
            val item = queue.poll(50, TimeUnit.MILLISECONDS) ?: continue
            val (ts, data) = item
            try {
                if (!configured) {
                    if (!collectCSD(data)) {
                        // 暂时还没集齐 SPS+PPS，继续等下一帧
                        continue
                    }
                    configure()
                    continue // SPS/PPS 配置帧不再喂给解码器
                }
                queueInputFrame(ts, data)
                drainOutput()
            } catch (e: Exception) {
                Log.e(TAG, "decode error", e)
                // 出错后重置，等下一组 SPS/PPS 重新初始化
                reset()
            }
        }
    }

    /** 从 data 里解析出所有 NAL，更新 sps/pps；返回是否已集齐。 */
    private fun collectCSD(data: ByteArray): Boolean {
        forEachNal(data) { type, nal ->
            when (type) {
                7 -> sps = nal
                8 -> pps = nal
            }
        }
        return sps != null && pps != null
    }

    @Synchronized
    private fun configure() {
        val format = MediaFormat.createVideoFormat(MIME, 1920, 1080)
        sps?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
        pps?.let { format.setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
        val c = MediaCodec.createDecoderByType(MIME)
        c.configure(format, surface, null, 0)
        c.start()
        codec = c
        configured = true
        Log.i(TAG, "decoder configured")
    }

    @Synchronized
    private fun queueInputFrame(ts: Long, data: ByteArray) {
        val c = codec ?: return
        val inIdx = c.dequeueInputBuffer(10_000)
        if (inIdx >= 0) {
            val buf: ByteBuffer = c.getInputBuffer(inIdx) ?: return
            buf.clear()
            buf.put(data)
            c.queueInputBuffer(inIdx, 0, data.size, ts, 0)
        }
    }

    @Synchronized
    private fun drainOutput() {
        val c = codec ?: return
        while (true) {
            val outIdx = c.dequeueOutputBuffer(info, 0)
            when {
                outIdx >= 0 -> c.releaseOutputBuffer(outIdx, true) // 渲染到 surface
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = c.outputFormat
                    Log.i(TAG, "output format: ${fmt.getInteger(MediaFormat.KEY_WIDTH)}x${fmt.getInteger(MediaFormat.KEY_HEIGHT)}")
                }
                else -> return
            }
        }
    }

    @Synchronized
    private fun reset() {
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        configured = false
        sps = null
        pps = null
    }

    /** 遍历 annexb 数据中的每个 NAL，回调 (nal_unit_type, nal_data)。 */
    private inline fun forEachNal(data: ByteArray, block: (type: Int, nal: ByteArray) -> Unit) {
        var i = 0
        val n = data.size
        while (i + 3 <= n) {
            var scLen = 0
            if (i + 4 <= n && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            ) {
                scLen = 4
            } else if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                scLen = 3
            }
            if (scLen == 0) {
                i++
                continue
            }
            val nalStart = i + scLen
            var j = nalStart + 1
            while (j < n) {
                if (j + 3 <= n && data[j] == 0.toByte() && data[j + 1] == 0.toByte() &&
                    (data[j + 2] == 1.toByte() ||
                        (data[j + 2] == 0.toByte() && j + 4 <= n && data[j + 3] == 1.toByte()))
                ) break
                j++
            }
            if (j > nalStart) {
                val nal = data.copyOfRange(nalStart, j)
                block(nal[0].toInt() and 0x1F, nal)
            }
            i = if (j < n) j else n
        }
    }
}
