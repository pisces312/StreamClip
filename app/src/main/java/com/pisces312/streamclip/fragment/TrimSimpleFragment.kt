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
import com.pisces312.streamclip.databinding.FragmentTrimSimpleBinding
import com.pisces312.streamclip.service.FFmpegService
import com.pisces312.streamclip.util.FileUtils
import com.pisces312.streamclip.util.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 简单版无损截取 — 基于原始可工作版本
 * PlayerView 自带控制器 + RangeSlider 选范围
 */
class TrimSimpleFragment : Fragment() {

    private var _binding: FragmentTrimSimpleBinding? = null
    private val binding get() = _binding!!
    private var player: ExoPlayer? = null
    private var selectedVideoUri: Uri? = null
    private var videoDurationMs: Long = 0
    private var sourceFileTimes: Pair<java.nio.file.attribute.FileTime?, java.nio.file.attribute.FileTime?>? = null

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
        _binding = FragmentTrimSimpleBinding.inflate(inflater, container, false)
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

        binding.rangeSlider.addOnChangeListener { slider, _, _ ->
            val values = slider.values
            binding.tvStartTime.text = "开始: ${formatTime(values[0])}"
            binding.tvEndTime.text = "结束: ${formatTime(values[1])}"
        }
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
        player = ExoPlayer.Builder(requireContext()).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            binding.playerView.player = this
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == androidx.media3.common.Player.STATE_READY && videoDurationMs == 0L) {
                        videoDurationMs = duration
                        binding.rangeSlider.valueFrom = 0f
                        binding.rangeSlider.valueTo = duration / 1000f
                        binding.rangeSlider.values = listOf(0f, duration / 1000f)
                        binding.tvDuration.text = formatDuration(duration)
                        binding.tvStartTime.text = "${getString(R.string.start_time)}: 00:00"
                        binding.tvEndTime.text = "结束: ${formatTime(duration / 1000f)}"
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
        val startSec = values[0]
        val endSec = values[1]

        if (endSec - startSec < 1) {
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
                startSec.toDouble(),
                (endSec - startSec).toDouble()
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
                    android.util.Log.e("TrimSimpleFragment", "Trim failed: $errorMsg")
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

    private fun formatTime(seconds: Float): String {
        val mins = seconds.toInt() / 60
        val secs = seconds.toInt() % 60
        return String.format("%02d:%02d", mins, secs)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        player?.release()
        player = null
        _binding = null
    }
}
