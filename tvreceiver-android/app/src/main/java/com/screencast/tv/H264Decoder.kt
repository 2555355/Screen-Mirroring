// SPDX-License-Identifier: GPL-3.0-or-later
// Screen-Mirroring - 跨平台手机投屏软件
// Copyright (C) 2025 Screen-Mirroring Contributors
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <https://www.gnu.org/licenses/>.

package com.screencast.tv

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * H.264 / H.265 解码器：把发射端推过来的 annexb 帧解码并渲染到 [surface]。
 *
 * - 自动识别 H.264(AVC) 或 H.265(HEVC)：根据 NAL unit type 判断。
 * - H.264：收集 SPS(type=7)+PPS(type=8)，csd-0=SPS, csd-1=PPS。
 * - H.265：收集 VPS(type=32)+SPS(type=33)+PPS(type=34)，csd-0=VPS+SPS+PPS 合并。
 * - 集齐 CSD 后配置 MediaCodec 并 start，之后每帧直接 queueInputBuffer 并 drain。
 */
class H264Decoder(
    private val surface: Surface,
    /** 解析出视频真实宽高后回调，用于保持比例渲染避免拉伸。 */
    private val onVideoSize: ((Int, Int) -> Unit)? = null
) {

    companion object {
        private const val TAG = "H264Decoder"
    }

    /** 当前流的 MIME，收到第一组 CSD 时确定。 */
    private var mime: String = MediaFormat.MIMETYPE_VIDEO_AVC

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var codec: MediaCodec? = null
    private var configured = false

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var vps: ByteArray? = null  // 仅 HEVC 使用
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
            vps = null
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

    /**
     * 从 data 里解析出所有 NAL，更新 vps/sps/pps；返回是否已集齐。
     * 自动识别 H.264（SPS=7,PPS=8）或 HEVC（VPS=32,SPS=33,PPS=34）。
     */
    private fun collectCSD(data: ByteArray): Boolean {
        forEachNal(data) { type, nal ->
            when (type) {
                // H.264 NAL types
                7 -> {
                    sps = nal
                    mime = MediaFormat.MIMETYPE_VIDEO_AVC
                    val (w, h) = parseSpsResolution(nal)
                    if (w > 0 && h > 0) {
                        videoWidth = w
                        videoHeight = h
                        Log.i(TAG, "AVC SPS: ${w}x${h}")
                        onVideoSize?.invoke(w, h)
                    }
                }
                8 -> pps = nal
                // HEVC NAL types（nal_unit_type = (nal[0] >> 1) & 0x3F，已在 forEachNal 解码）
                32 -> { vps = nal; mime = MediaFormat.MIMETYPE_VIDEO_HEVC }
                33 -> {
                    sps = nal
                    mime = MediaFormat.MIMETYPE_VIDEO_HEVC
                    // HEVC SPS 解析较复杂，这里用默认宽高，让解码器从 output format 获取
                    Log.i(TAG, "HEVC SPS 收到")
                }
                34 -> pps = nal
            }
        }
        // H.264: SPS+PPS 即可；HEVC: VPS+SPS+PPS 都要
        return if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
            vps != null && sps != null && pps != null
        } else {
            sps != null && pps != null
        }
    }

    @Synchronized
    private fun configure() {
        val w = if (videoWidth > 0) videoWidth else 1280
        val h = if (videoHeight > 0) videoHeight else 720
        val format = MediaFormat.createVideoFormat(mime, w, h)
        if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
            // HEVC: csd-0 需包含 VPS+SPS+PPS（用 startcode 分隔），csd-1 可不设
            val merged = ByteArrayOutputStream()
            vps?.let { merged.write(byteArrayOf(0, 0, 0, 1)); merged.write(it) }
            sps?.let { merged.write(byteArrayOf(0, 0, 0, 1)); merged.write(it) }
            pps?.let { merged.write(byteArrayOf(0, 0, 0, 1)); merged.write(it) }
            format.setByteBuffer("csd-0", ByteBuffer.wrap(merged.toByteArray()))
        } else {
            // H.264: csd-0=SPS, csd-1=PPS
            sps?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
            pps?.let { format.setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
        }
        val c = MediaCodec.createDecoderByType(mime)
        // 低延迟：优先配置，部分平台支持
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        c.configure(format, surface, null, 0)
        c.start()
        codec = c
        configured = true
        Log.i(TAG, "decoder configured: $mime ${w}x${h}")
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
        vps = null
    }

    /**
     * 遍历 annexb 数据中的每个 NAL，回调 (nal_unit_type, nal_data)。
     * 自动适配 H.264（type = nal[0] & 0x1F）和 HEVC（type = (nal[0] >> 1) & 0x3F）。
     * 通过判断哪个解析命中已知的 CSD type 来决定返回哪种 type。
     */
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
                val b0 = nal[0].toInt() and 0xFF
                val avcType = b0 and 0x1F
                val hevcType = (b0 shr 1) and 0x3F
                // H.264: SPS=7 PPS=8；HEVC: VPS=32 SPS=33 PPS=34
                // 同一个 b0 不可能让 avcType 命中 7/8 且 hevcType 命中 32/33/34
                val type = when {
                    avcType == 7 || avcType == 8 -> avcType
                    hevcType == 32 || hevcType == 33 || hevcType == 34 -> hevcType
                    else -> avcType // 非 CSD NAL，用 AVC type（对 slice header 等无影响）
                }
                block(type, nal)
            }
            i = if (j < n) j else n
        }
    }
}
