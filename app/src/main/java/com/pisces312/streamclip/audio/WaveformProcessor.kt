package com.pisces312.streamclip.audio

import java.nio.ShortBuffer

/**
 * 从 PCM 采样数据计算波形可视化所需的帧增益数据。
 * 参考 Ringdroid 的 WaveformView.computeDoublesForAllZoomLevels()。
 *
 * 将采样按固定帧大小（1024 samples/frame）分组，计算每帧的峰值，
 * 然后生成多级缩放数据。
 */
object WaveformProcessor {

    const val SAMPLES_PER_FRAME = 1024
    private const val NUM_ZOOM_LEVELS = 5

    data class WaveformData(
        val numFrames: Int,
        val frameGains: IntArray,        // 0-255 normalized gains
        val numZoomLevels: Int,
        val lenByZoomLevel: IntArray,
        val valuesByZoomLevel: Array<DoubleArray>,
        val zoomFactorByZoomLevel: DoubleArray,
        val initialZoomLevel: Int
    )

    /**
     * 计算波形数据
     */
    fun process(samples: ShortBuffer, channels: Int, numSamples: Int): WaveformData {
        val numFrames = if (numSamples % SAMPLES_PER_FRAME == 0) {
            numSamples / SAMPLES_PER_FRAME
        } else {
            numSamples / SAMPLES_PER_FRAME + 1
        }

        // 1. 计算每帧的 RMS 增益
        val frameGains = IntArray(numFrames)
        samples.rewind()

        for (i in 0 until numFrames) {
            var maxVal = 0
            for (j in 0 until SAMPLES_PER_FRAME) {
                if (samples.remaining() > 0) {
                    var value = 0
                    for (k in 0 until channels) {
                        if (samples.remaining() > 0) {
                            value += Math.abs(samples.get().toInt())
                        }
                    }
                    value /= channels.coerceAtLeast(1)
                    if (value > maxVal) maxVal = value
                } else {
                    break
                }
            }
            frameGains[i] = Math.sqrt(maxVal.toDouble()).toInt()
        }
        samples.rewind()

        // 2. 平滑处理（3 点移动平均）
        val smoothedGains = DoubleArray(numFrames)
        when {
            numFrames == 1 -> smoothedGains[0] = frameGains[0].toDouble()
            numFrames == 2 -> {
                smoothedGains[0] = frameGains[0].toDouble()
                smoothedGains[1] = frameGains[1].toDouble()
            }
            else -> {
                smoothedGains[0] = frameGains[0] / 2.0 + frameGains[1] / 2.0
                for (i in 1 until numFrames - 1) {
                    smoothedGains[i] = (frameGains[i - 1] / 3.0 + frameGains[i] / 3.0 + frameGains[i + 1] / 3.0)
                }
                smoothedGains[numFrames - 1] = frameGains[numFrames - 2] / 2.0 + frameGains[numFrames - 1] / 2.0
            }
        }

        // 3. 归一化到 0-255
        var maxGain = 1.0
        for (i in 0 until numFrames) {
            if (smoothedGains[i] > maxGain) maxGain = smoothedGains[i]
        }
        var scaleFactor = 1.0
        if (maxGain > 255.0) scaleFactor = 255.0 / maxGain

        // 构建 256 bin 直方图
        val gainHist = IntArray(256)
        maxGain = 0.0
        for (i in 0 until numFrames) {
            var smoothedGain = (smoothedGains[i] * scaleFactor).toInt()
            if (smoothedGain < 0) smoothedGain = 0
            if (smoothedGain > 255) smoothedGain = 255
            if (smoothedGain > maxGain) maxGain = smoothedGain.toDouble()
            gainHist[smoothedGain]++
        }

        // 5% 下限
        var minGain = 0.0
        var sum = 0
        while (minGain < 255 && sum < numFrames / 20) {
            sum += gainHist[minGain.toInt()]
            minGain++
        }

        // 99% 上限
        sum = 0
        while (maxGain > 2 && sum < numFrames / 100) {
            sum += gainHist[maxGain.toInt()]
            maxGain--
        }

        // 计算归一化高度
        val heights = DoubleArray(numFrames)
        val range = maxGain - minGain
        for (i in 0 until numFrames) {
            var value = (smoothedGains[i] * scaleFactor - minGain) / range
            if (value < 0.0) value = 0.0
            if (value > 1.0) value = 1.0
            heights[i] = value * value  // 平方使小波形更小
        }

        // 4. 生成多级缩放数据
        val lenByZoomLevel = IntArray(NUM_ZOOM_LEVELS)
        val zoomFactorByZoomLevel = DoubleArray(NUM_ZOOM_LEVELS)
        val valuesByZoomLevel = Array(NUM_ZOOM_LEVELS) { DoubleArray(0) }

        // Level 0: 2x 放大（插值）
        lenByZoomLevel[0] = numFrames * 2
        zoomFactorByZoomLevel[0] = 2.0
        valuesByZoomLevel[0] = DoubleArray(lenByZoomLevel[0])
        if (numFrames > 0) {
            valuesByZoomLevel[0][0] = 0.5 * heights[0]
            valuesByZoomLevel[0][1] = heights[0]
        }
        for (i in 1 until numFrames) {
            valuesByZoomLevel[0][2 * i] = 0.5 * (heights[i - 1] + heights[i])
            valuesByZoomLevel[0][2 * i + 1] = heights[i]
        }

        // Level 1: 原始
        lenByZoomLevel[1] = numFrames
        zoomFactorByZoomLevel[1] = 1.0
        valuesByZoomLevel[1] = heights.copyOf()

        // Level 2-4: 逐级减半
        for (j in 2 until NUM_ZOOM_LEVELS) {
            lenByZoomLevel[j] = lenByZoomLevel[j - 1] / 2
            valuesByZoomLevel[j] = DoubleArray(lenByZoomLevel[j])
            zoomFactorByZoomLevel[j] = zoomFactorByZoomLevel[j - 1] / 2.0
            for (i in 0 until lenByZoomLevel[j]) {
                valuesByZoomLevel[j][i] = 0.5 * (valuesByZoomLevel[j - 1][2 * i] + valuesByZoomLevel[j - 1][2 * i + 1])
            }
        }

        // 初始缩放级别
        val initialZoomLevel = when {
            numFrames > 5000 -> 3
            numFrames > 1000 -> 2
            numFrames > 300 -> 1
            else -> 0
        }

        return WaveformData(
            numFrames = numFrames,
            frameGains = frameGains,
            numZoomLevels = NUM_ZOOM_LEVELS,
            lenByZoomLevel = lenByZoomLevel,
            valuesByZoomLevel = valuesByZoomLevel,
            zoomFactorByZoomLevel = zoomFactorByZoomLevel,
            initialZoomLevel = initialZoomLevel
        )
    }
}
