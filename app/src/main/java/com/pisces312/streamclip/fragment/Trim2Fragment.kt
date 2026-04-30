package com.pisces312.streamclip.fragment

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.pisces312.streamclip.databinding.FragmentTrim2Binding
import com.pisces312.streamclip.service.FFmpegService
import com.pisces312.streamclip.util.FileUtils
import com.pisces312.streamclip.util.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Trim2Fragment : Fragment() {

    private var _binding: FragmentTrim2Binding? = null
    private val binding get() = _binding!!
    private var player: ExoPlayer? = null
    private var selectedVideoUri: Uri? = null
    private var videoDurationMs: Long = 0

    // 缩放相关
    private var zoomLevel = 1
    private val zoomSteps = intArrayOf(1, 2, 4, 8, 16, 32)
    private var viewStartSec = 0f
    private var viewEndSec = 1f

    // 绝对截取范围（不受缩放影响）
    private var absStartSec = 0f
    private var absEndSec = 1f

    // 上一次 slider 值，用于判断拖的是哪个手柄
    private var prevStartSec = 0f
    private var prevEndSec = 1f

    // 输入框联动标记
    private var isUpdatingFromSlider = false
    private var isUpdatingFromEditText = false

    // 是否正在拖动 RangeSlider 手柄
    private var isDraggingThumb = false

    // 标记：是否需要 seek（仅松手后 seek 一次）
    private var pendingSeekOnStop = false

    // 播放位置更新 Runnable
    private val playbackUpdateRunnable = object : Runnable {
        override fun run() {
            updatePlaybackIndicator()
            binding.root.postDelayed(this, 50)
        }
    }

    private val pickVideo = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                selectedVideoUri = uri
                SettingsManager.setLastVideoDir(requireContext(), uri)
                loadVideo(uri)
                updateInputStatus(uri)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTrim2Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnSelectVideo.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    SettingsManager.getLastVideoDir(requireContext())?.let { uri ->
                        putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, uri)
                    }
                }
            }
            pickVideo.launch(intent)
        }

        binding.btnExecute.setOnClickListener {
            executeTrim()
        }

        // 播放/暂停按钮
        binding.btnPlayPause.setOnClickListener {
            player?.let { p ->
                if (p.isPlaying) {
                    p.pause()
                } else {
                    val currentSec = p.currentPosition / 1000f
                    if (currentSec < absStartSec || currentSec >= absEndSec) {
                        p.seekTo((absStartSec * 1000).toLong())
                    }
                    p.play()
                }
                updatePlayPauseIcon()
            }
        }

        // RangeSlider 值变化 → 只更新范围和UI，不 seek
        binding.rangeSlider.addOnChangeListener { slider, _, _ ->
            if (isUpdatingFromEditText) return@addOnChangeListener

            val values = slider.values
            isUpdatingFromSlider = true

            absStartSec = values[0]
            absEndSec = values[1]

            binding.etStartTime.setText(formatSecondsMs(absStartSec))
            binding.etEndTime.setText(formatSecondsMs(absEndSec))
            binding.tvSelectedDuration.text = "选中: ${formatTimeMs(absEndSec - absStartSec)}"

            updateOverview()
            isUpdatingFromSlider = false
        }

        // RangeSlider 触摸事件
        binding.rangeSlider.addOnSliderTouchListener(object : com.google.android.material.slider.RangeSlider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.RangeSlider) {
                isDraggingThumb = true
                pendingSeekOnStop = true
                prevStartSec = absStartSec
                prevEndSec = absEndSec
                player?.pause()
                updatePlayPauseIcon()
            }
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.RangeSlider) {
                isDraggingThumb = false
                // 判断拖的是哪个手柄 → seek 到对应位置
                if (pendingSeekOnStop) {
                    pendingSeekOnStop = false
                    val startDelta = kotlin.math.abs(absStartSec - prevStartSec)
                    val endDelta = kotlin.math.abs(absEndSec - prevEndSec)
                    val seekTarget = if (startDelta >= endDelta) absStartSec else absEndSec
                    player?.seekTo((seekTarget * 1000).toLong())
                }
            }
        })

        // 精确时间输入框（毫秒精度）
        setupEditText(binding.etStartTime) { value ->
            if (videoDurationMs <= 0) return@setupEditText
            val maxVal = videoDurationMs / 1000f
            val clamped = value.coerceIn(0f, maxVal)
            if (clamped < absEndSec) {
                absStartSec = clamped
                syncSliderFromAbsolute()
                player?.seekTo((absStartSec * 1000).toLong())
            }
        }
        setupEditText(binding.etEndTime) { value ->
            if (videoDurationMs <= 0) return@setupEditText
            val maxVal = videoDurationMs / 1000f
            val clamped = value.coerceIn(0f, maxVal)
            if (clamped > absStartSec) {
                absEndSec = clamped
                syncSliderFromAbsolute()
                player?.seekTo((absEndSec * 1000).toLong())
            }
        }

        // 缩放按钮
        binding.btnZoomIn.setOnClickListener { changeZoom(1) }
        binding.btnZoomOut.setOnClickListener { changeZoom(-1) }
    }

    private fun updatePlayPauseIcon() {
        player?.let { p ->
            if (p.isPlaying) {
                binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
            } else {
                binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            }
        }
    }

    private fun setupEditText(et: EditText, onCommit: (Float) -> Unit) {
        et.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                commitEditText(et, onCommit)
            }
        }
        et.setOnEditorActionListener { _, _, _ ->
            commitEditText(et, onCommit)
            et.clearFocus()
            true
        }
    }

    private fun commitEditText(et: EditText, onCommit: (Float) -> Unit) {
        if (isUpdatingFromSlider) return
        val text = et.text.toString().trim()
        val value = text.toFloatOrNull()
        if (value != null && videoDurationMs > 0) {
            isUpdatingFromEditText = true
            onCommit(value)
            isUpdatingFromEditText = false
        }
    }

    private fun syncSliderFromAbsolute() {
        // 缩放状态下，需要调整 viewStart/viewEnd 以容纳绝对范围
        val totalSec = videoDurationMs / 1000f
        if (zoomLevel > 1) {
            val visibleRange = totalSec / zoomLevel
            // 如果绝对范围超出当前可见窗口，平移窗口
            if (absStartSec < viewStartSec) {
                viewStartSec = absStartSec
                viewEndSec = viewStartSec + visibleRange
            }
            if (absEndSec > viewEndSec) {
                viewEndSec = absEndSec
                viewStartSec = viewEndSec - visibleRange
            }
            // 边界修正
            viewStartSec = viewStartSec.coerceIn(0f, totalSec - visibleRange)
            viewEndSec = viewStartSec + visibleRange

            binding.rangeSlider.valueFrom = viewStartSec
            binding.rangeSlider.valueTo = viewEndSec
        }

        binding.rangeSlider.values = listOf(absStartSec, absEndSec)
        binding.etStartTime.setText(formatSecondsMs(absStartSec))
        binding.etEndTime.setText(formatSecondsMs(absEndSec))
        binding.tvSelectedDuration.text = "选中: ${formatTimeMs(absEndSec - absStartSec)}"
        updateOverview()
    }

    private fun updateInputStatus(uri: Uri) {
        val pathResult = FileUtils.getPathResultFromUri(requireContext(), uri)
        if (pathResult != null) {
            binding.tvStatus.visibility = View.VISIBLE
            if (pathResult.isDirectRead) {
                binding.tvStatus.text = "✅ 直读: ${pathResult.path}"
                binding.tvStatus.setTextColor(0xFF4CAF50.toInt())
            } else {
                binding.tvStatus.text = "⚠️ 缓存: ${java.io.File(pathResult.path).name} (已复制)"
                binding.tvStatus.setTextColor(0xFFFF9800.toInt())
            }
        }
    }

    private fun updateOutputStatus(outputFile: java.io.File) {
        binding.tvStatus.text = "📁 输出: ${outputFile.absolutePath}"
        binding.tvStatus.setTextColor(0xFF2196F3.toInt())
    }

    private fun loadVideo(uri: Uri) {
        player?.release()
        player = ExoPlayer.Builder(requireContext()).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            binding.playerView.player = this
            playWhenReady = false

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        videoDurationMs = duration
                        val durationSec = duration / 1000f

                        absStartSec = 0f
                        absEndSec = durationSec
                        prevStartSec = 0f
                        prevEndSec = durationSec
                        viewStartSec = 0f
                        viewEndSec = durationSec
                        zoomLevel = 1

                        binding.rangeSlider.valueFrom = 0f
                        binding.rangeSlider.valueTo = durationSec
                        binding.rangeSlider.values = listOf(0f, durationSec)

                        binding.tvDuration.text = "总时长: ${formatTimeMs(durationSec)}"
                        binding.tvSelectedDuration.visibility = View.VISIBLE
                        binding.tvSelectedDuration.text = "选中: ${formatTimeMs(durationSec)}"

                        binding.etStartTime.isEnabled = true
                        binding.etEndTime.isEnabled = true
                        binding.etStartTime.setText("0.000")
                        binding.etEndTime.setText(formatSecondsMs(durationSec))
                        binding.btnZoomIn.isEnabled = durationSec > 10
                        binding.btnZoomOut.isEnabled = false
                        binding.tvZoomLevel.text = "1x"

                        binding.btnPlayPause.visibility = View.VISIBLE
                        binding.playbackIndicator.visibility = View.VISIBLE
                        binding.overviewBar.visibility = View.VISIBLE
                        updatePlayPauseIcon()
                        updateOverview()

                        seekTo(0)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePlayPauseIcon()
                    if (isPlaying) {
                        binding.root.post(playbackUpdateRunnable)
                    }
                }
            })

            addListener(object : Player.Listener {
                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    // 只在播放时检查越界，拖动手柄时不检查
                    if (!isDraggingThumb) {
                        checkPlaybackBounds()
                    }
                }
            })
        }

        val fileName = getFileName(uri)
        binding.tvFileName.text = fileName
    }

    private fun checkPlaybackBounds() {
        player?.let { p ->
            if (!p.isPlaying) return
            val currentSec = p.currentPosition / 1000f
            // 播放到终点后回到起点
            if (currentSec >= absEndSec) {
                p.seekTo((absStartSec * 1000).toLong())
            }
        }
    }

    private fun updatePlaybackIndicator() {
        player?.let { p ->
            val currentSec = p.currentPosition / 1000f

            if (videoDurationMs > 0 && viewEndSec > viewStartSec) {
                val fraction = (currentSec - viewStartSec) / (viewEndSec - viewStartSec)
                if (fraction in 0f..1f) {
                    val sliderWidth = binding.rangeSlider.width - binding.rangeSlider.paddingStart - binding.rangeSlider.paddingEnd
                    val indicatorX = binding.rangeSlider.paddingStart + fraction * sliderWidth
                    binding.playbackIndicator.translationX = indicatorX - binding.playbackIndicator.width / 2f
                    binding.playbackIndicator.visibility = View.VISIBLE
                } else {
                    binding.playbackIndicator.visibility = View.INVISIBLE
                }
            }

            if (p.isPlaying && currentSec >= absEndSec) {
                p.seekTo((absStartSec * 1000).toLong())
            }
        }
    }

    private fun updateOverview() {
        if (videoDurationMs <= 0) return
        val totalSec = videoDurationMs / 1000f
        val rangeLeft = absStartSec / totalSec
        val rangeRight = absEndSec / totalSec
        val viewLeft = viewStartSec / totalSec
        val viewRight = viewEndSec / totalSec

        binding.overviewBar.post {
            val barWidth = binding.overviewBar.width.toFloat()

            val rangeView = binding.overviewRange
            val rangeLp = rangeView.layoutParams
            rangeLp.width = ((rangeRight - rangeLeft) * barWidth).toInt()
            rangeView.layoutParams = rangeLp
            rangeView.translationX = rangeLeft * barWidth

            val vpView = binding.overviewViewport
            val vpLp = vpView.layoutParams
            vpLp.width = ((viewRight - viewLeft) * barWidth).toInt()
            vpView.layoutParams = vpLp
            vpView.translationX = viewLeft * barWidth
        }
    }

    private fun changeZoom(direction: Int) {
        val currentIdx = zoomSteps.indexOf(zoomLevel)
        val newIdx = (currentIdx + direction).coerceIn(0, zoomSteps.lastIndex)
        if (newIdx == currentIdx) return

        zoomLevel = zoomSteps[newIdx]
        binding.tvZoomLevel.text = "${zoomLevel}x"

        val totalSec = videoDurationMs / 1000f
        val center = (absStartSec + absEndSec) / 2f
        val visibleRange = totalSec / zoomLevel

        viewStartSec = (center - visibleRange / 2f).coerceIn(0f, totalSec - visibleRange)
        viewEndSec = viewStartSec + visibleRange

        // 先将手柄值钳制到新窗口范围内，再设置 valueFrom/valueTo
        if (absStartSec < viewStartSec) absStartSec = viewStartSec
        if (absEndSec > viewEndSec) absEndSec = viewEndSec

        // 关键：先设置 values 到安全范围，再改 valueFrom/valueTo
        binding.rangeSlider.values = listOf(absStartSec.coerceIn(viewStartSec, viewEndSec), absEndSec.coerceIn(viewStartSec, viewEndSec))
        binding.rangeSlider.valueFrom = viewStartSec
        binding.rangeSlider.valueTo = viewEndSec
        // 再次设置 values 确保 valueFrom/valueTo 已生效
        binding.rangeSlider.values = listOf(absStartSec, absEndSec)

        binding.etStartTime.setText(formatSecondsMs(absStartSec))
        binding.etEndTime.setText(formatSecondsMs(absEndSec))
        binding.tvSelectedDuration.text = "选中: ${formatTimeMs(absEndSec - absStartSec)}"

        binding.btnZoomIn.isEnabled = newIdx < zoomSteps.lastIndex
        binding.btnZoomOut.isEnabled = newIdx > 0

        updateOverview()
    }

    private fun executeTrim() {
        val uri = selectedVideoUri ?: run {
            Toast.makeText(requireContext(), "请先选择视频", Toast.LENGTH_SHORT).show()
            return
        }

        val startSec = absStartSec
        val endSec = absEndSec

        if (endSec - startSec < 1) {
            Toast.makeText(requireContext(), "截取时长至少1秒", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            binding.btnExecute.isEnabled = false

            val pathResult = FileUtils.getPathResultFromUri(requireContext(), uri) ?: run {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "无法读取文件", Toast.LENGTH_SHORT).show()
                    binding.progressBar.visibility = View.GONE
                    binding.btnExecute.isEnabled = true
                }
                return@launch
            }

            val inputPath = pathResult.path
            val sourceFile = java.io.File(inputPath)
            val outputDir = SettingsManager.getOutputDir(requireContext(), sourceFile)
            val outputName = SettingsManager.getOutputFileName(
                requireContext(),
                sourceFile.name,
                "trimmed",
                "mp4"
            )
            val outputFile = java.io.File(outputDir, outputName)

            val result = FFmpegService.trimVideo(
                requireContext(),
                inputPath,
                outputFile.absolutePath,
                startSec.toDouble(),
                (endSec - startSec).toDouble()
            )

            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                binding.btnExecute.isEnabled = true

                if (result.success) {
                    FileUtils.scanFile(requireContext(), outputFile)
                    updateOutputStatus(outputFile)
                    Toast.makeText(requireContext(), "截取完成: ${outputFile.name}", Toast.LENGTH_LONG).show()
                } else {
                    val errorMsg = result.error ?: "未知错误"
                    android.util.Log.e("Trim2Fragment", "Trim failed: $errorMsg")
                    Toast.makeText(requireContext(), "失败: $errorMsg", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "Unknown"
        requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) name = cursor.getString(index) ?: name
            }
        }
        return name
    }

    /** 格式化秒数为 MM:SS.mmm（毫秒精度） */
    private fun formatTimeMs(seconds: Float): String {
        val totalMs = (seconds * 1000).toLong()
        val mins = totalMs / 60000
        val secs = (totalMs % 60000) / 1000
        val ms = totalMs % 1000
        return String.format("%02d:%02d.%03d", mins, secs, ms)
    }

    /** 格式化秒数为纯数字（毫秒精度，如 12.345） */
    private fun formatSecondsMs(seconds: Float): String {
        return String.format("%.3f", seconds)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.root.removeCallbacks(playbackUpdateRunnable)
        player?.release()
        player = null
        _binding = null
    }
}
