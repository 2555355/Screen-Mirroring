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
class H264Decoder(
    private val surface: Surface,
    /** 解析出视频真实宽高后回调，用于保持比例渲染避免拉伸。 */
    private val onVideoSize: ((Int, Int) -> Unit)? = null
) {

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
    /** 从 SPS 解析出的视频宽高，用于配置解码器初始格式。 */
    private var videoWidth = 0
    private var videoHeight = 0

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
                7 -> {
                    sps = nal
                    // 从 SPS 解析视频真实宽高（SPS NAL 第 3 字节起为 profile_idc 等）
                    val (w, h) = parseSpsResolution(nal)
                    if (w > 0 && h > 0) {
                        videoWidth = w
                        videoHeight = h
                        Log.i(TAG, "parsed resolution from SPS: ${w}x${h}")
                        onVideoSize?.invoke(w, h)
                    }
                }
                8 -> pps = nal
            }
        }
        return sps != null && pps != null
    }

    @Synchronized
    private fun configure() {
        // 用从 SPS 解析出的真实宽高，避免硬编码 1920x1080 导致拉伸/错位
        val w = if (videoWidth > 0) videoWidth else 1280
        val h = if (videoHeight > 0) videoHeight else 720
        val format = MediaFormat.createVideoFormat(MIME, w, h)
        sps?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
        pps?.let { format.setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
        val c = MediaCodec.createDecoderByType(MIME)
        c.configure(format, surface, null, 0)
        c.start()
        codec = c
        configured = true
        Log.i(TAG, "decoder configured: ${w}x${h}")
    }

    /**
     * 从 SPS NAL 中解析视频分辨率。
     * SPS 结构：profile_idc(1) constraint flags(1) level_idc(1) seq_parameter_set_id(ue) ...
     * 这里用简化解析：依据 profile_idc 判断是否含 chroma/scaling 信息，再解析 pic_width_in_mbs_minus1 与 pic_height_in_map_units_minus1。
     */
    private fun parseSpsResolution(sps: ByteArray): Pair<Int, Int> {
        return try {
            val r = SpsReader(sps)
            val profileIdc = r.readU(8)
            r.readU(8) // constraint flags
            r.readU(8) // level_idc
            r.readUe()  // seq_parameter_set_id
            if (profileIdc in 100..139) {
                r.readUe() // chroma_format_idc
                if (r.readUe() == 3) r.readU(1) // separate_colour_plane_flag
                r.readUe() // bit_depth_luma_minus8
                r.readUe() // bit_depth_chroma_minus8
                r.readU(1) // qpprime_y_zero_transform_bypass_flag
                if (r.readU(1) == 1) { // seq_scaling_matrix_present_flag
                    val cnt = if (profileIdc in 100..110) 8 else 12
                    for (i in 0 until cnt) {
                        if (r.readU(1) == 1) r.readUe() // scaling list
                    }
                }
            }
            r.readUe() // log2_max_frame_num_minus4
            val pocType = r.readUe() // pic_order_cnt_type
            when (pocType) {
                0 -> r.readUe() // log2_max_pic_order_cnt_lsb_minus4
                1 -> {
                    r.readU(1) // delta_pic_order_always_zero_flag
                    r.readSe() // offset_for_non_ref_pic
                    r.readSe() // offset_for_top_to_bottom_field
                    val n = r.readUe()
                    for (i in 0 until n) r.readSe()
                }
            }
            r.readUe() // max_num_ref_frames
            r.readU(1) // gaps_in_frame_num_value_allowed_flag
            val picWidthInMbsMinus1 = r.readUe()
            val picHeightInMapUnitsMinus1 = r.readUe()
            val frameMbsOnlyFlag = r.readU(1)
            val width = (picWidthInMbsMinus1 + 1) * 16
            // height = (2 - frame_mbs_only_flag) * (picHeightInMapUnitsMinus1 + 1) * 16
            val realHeight = (2 - frameMbsOnlyFlag) * (picHeightInMapUnitsMinus1 + 1) * 16
            Pair(width, realHeight)
        } catch (e: Exception) {
            Log.w(TAG, "parse SPS failed", e)
            Pair(0, 0)
        }
    }

    /** 简易 SPS 位读取器。 */
    private class SpsReader(private val data: ByteArray) {
        private var bytePos = 1 // 跳过 NAL header
        private var bitPos = 0
        private fun nextBit(): Int {
            val b = data[bytePos].toInt() and 0xFF
            val bit = (b shr (7 - bitPos)) and 1
            bitPos++
            if (bitPos == 8) { bitPos = 0; bytePos++ }
            return bit
        }
        fun readU(n: Int): Int {
            var v = 0
            for (i in 0 until n) v = (v shl 1) or nextBit()
            return v
        }
        fun readUe(): Int {
            var zeros = 0
            while (nextBit() == 0 && bytePos < data.size) zeros++
            if (zeros == 0) return 0
            return (1 shl zeros) - 1 + readU(zeros)
        }
        fun readSe(): Int {
            val k = readUe()
            return if (k % 2 == 0) -(k / 2) else (k + 1) / 2
        }
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
