package com.pisces312.streamclip.fragment

import com.pisces312.streamclip.R
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.pisces312.streamclip.databinding.FragmentTrim2Binding
import com.pisces312.streamclip.service.FFmpegService
import com.pisces312.streamclip.util.FileUtils
import com.pisces312.streamclip.util.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 无损截取2 — 基于简单版 + RangeSlider 拖动实时预览
 * PlayerView 自带控制器 + RangeSlider 拖动手柄时实时 seek 播放器
 */
class Trim2Fragment : Fragment() {

    private var _binding: FragmentTrim2Binding? = null
    private val binding get() = _binding!!
    private var player: ExoPlayer? = null
    private var selectedVideoUri: Uri? = null
    private var videoDurationMs: Long = 0
    private var sourceFileTimes: Pair<java.nio.file.attribute.FileTime?, java.nio.file.attribute.FileTime?>? = null

    // 记录拖动前的值（毫秒），用于判断拖的是哪个手柄
    private var prevStartMs = 0f
    private var prevEndMs = 1f

    // 标记视频是否已初始化（防止 seek 后 READY 状态重置 slider）
    private var sliderInitialized = false

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

        // RangeSlider 值变化 → 更新时间标签 + 实时 seek 到变化最大的手柄位置
        binding.rangeSlider.addOnChangeListener { slider, _, _ ->
            val values = slider.values
            val startSec = values[0]
            val endSec = values[1]

            binding.tvStartTime.text = "开始: ${formatTime(startSec)}"
            binding.tvEndTime.text = "结束: ${formatTime(endSec)}"

            // 实时预览：seek 到移动幅度更大的手柄位置（seekTarget 已经是毫秒）
            val startDelta = kotlin.math.abs(startSec - prevStartMs)
            val endDelta = kotlin.math.abs(endSec - prevEndMs)
            val seekTarget = if (startDelta >= endDelta) startSec else endSec
            player?.seekTo(seekTarget.toLong())
        }

        // 记录拖动前的值
        binding.rangeSlider.addOnSliderTouchListener(object : com.google.android.material.slider.RangeSlider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.RangeSlider) {
                val values = slider.values
                prevStartMs = values[0]
                prevEndMs = values[1]
                // 拖动时暂停播放
                player?.pause()
            }
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.RangeSlider) {
                // 松手后更新记录
                val values = slider.values
                prevStartMs = values[0]
                prevEndMs = values[1]
            }
        })
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
            // 读取原文件时间戳
            sourceFileTimes = FileUtils.readFileTimes(pathResult.path)
        }
    }

    private fun updateOutputStatus(outputFile: java.io.File) {
        binding.tvStatus.text = "📁 输出: ${outputFile.absolutePath}"
        binding.tvStatus.setTextColor(0xFF2196F3.toInt())
    }

    private fun loadVideo(uri: Uri) {
        player?.release()
        sliderInitialized = false
        player = ExoPlayer.Builder(requireContext()).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            binding.playerView.player = this
            playWhenReady = false

            addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == androidx.media3.common.Player.STATE_READY && !sliderInitialized) {
                        sliderInitialized = true
                        videoDurationMs = duration
                        // Round up to nearest second to satisfy stepSize constraint
                        val durationRounded = ((duration + 999) / 1000) * 1000

                        prevStartMs = 0f
                        prevEndMs = durationRounded.toFloat()

                        binding.rangeSlider.valueFrom = 0f
                        binding.rangeSlider.valueTo = durationRounded.toFloat()
                        binding.rangeSlider.stepSize = 1000f  // 1-second precision
                        binding.rangeSlider.values = listOf(0f, durationRounded.toFloat())
                        binding.tvDuration.text = formatDuration(duration)
                        binding.tvStartTime.text = "${getString(R.string.start_time)}: 00:00"
                        binding.tvEndTime.text = "结束: ${formatTime(duration.toFloat())}"
                    }
                }
            })
        }

        val fileName = getFileName(uri)
        binding.tvFileName.text = fileName
    }

    private fun executeTrim() {
        val uri = selectedVideoUri ?: run {
            Toast.makeText(requireContext(), getString(R.string.please_select_video), Toast.LENGTH_SHORT).show()
            return
        }

        val values = binding.rangeSlider.values
        val startMs = values[0]
        val endMs = values[1]

        if (endMs - startMs < 1000) {
            Toast.makeText(requireContext(), getString(R.string.trim_duration_at_least_1s), Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            binding.btnExecute.isEnabled = false
            if (SettingsManager.isKeepScreenOn(requireContext())) {
                requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            val pathResult = FileUtils.getPathResultFromUri(requireContext(), uri) ?: run {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), getString(R.string.cannot_read_file), Toast.LENGTH_SHORT).show()
                    binding.progressBar.visibility = View.GONE
                    binding.btnExecute.isEnabled = true
                    requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

            // Lossless trim (-c copy) completes instantly, no progress callback needed
            val result = FFmpegService.trimVideo(
                requireContext(),
                inputPath,
                outputFile.absolutePath,
                startMs.toDouble() / 1000.0,
                (endMs - startMs).toDouble() / 1000.0
            )

            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                binding.btnExecute.isEnabled = true
                binding.progressBar.progress = 100
                requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                if (result.success) {
                    FileUtils.scanFile(requireContext(), outputFile)
                    // 恢复时间戳
                    sourceFileTimes?.let { (creation, modified) ->
                        FileUtils.applyFileTimes(outputFile.absolutePath, creation, modified)
                    }
                    updateOutputStatus(outputFile)
                    Toast.makeText(requireContext(), getString(R.string.trim_complete, outputFile.name), Toast.LENGTH_LONG).show()
                } else {
                    val errorMsg = result.error ?: "未知错误"
                    android.util.Log.e("Trim2Fragment", "Trim failed: $errorMsg")
                    Toast.makeText(requireContext(), getString(R.string.failed, errorMsg), Toast.LENGTH_LONG).show()
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

    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", mins, secs)
    }

    // Precision: seconds only — ms-level seek is inaccurate and causes preview desync
    private fun formatTime(ms: Float): String {
        val totalMs = ms.toLong()
        val mins = totalMs / 60000
        val secs = (totalMs % 60000) / 1000
        return String.format("%02d:%02d", mins, secs)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        player?.release()
        player = null
        _binding = null
    }
}
