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
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.pisces312.streamclip.databinding.FragmentTrimSimpleBinding
import com.pisces312.streamclip.service.FFmpegService
import com.pisces312.streamclip.util.FileUtils
import com.pisces312.streamclip.util.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 无损截取 — 自定义进度条 + 点击播放/暂停
 * 精确到秒，支持输入框和拖动两种方式设置范围
 */
@UnstableApi
class TrimSimpleFragment : Fragment() {

    private var _binding: FragmentTrimSimpleBinding? = null
    private val binding get() = _binding!!
    private var player: ExoPlayer? = null
    private var selectedVideoUri: Uri? = null
    private var videoDurationMs: Long = 0
    private var sourceFileTimes: Pair<java.nio.file.attribute.FileTime?, java.nio.file.attribute.FileTime?>? = null

    // 当前截取范围（秒）
    private var startSec: Int = 0
    private var endSec: Int = 0

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

        // 点击视频画面播放/暂停
        binding.videoContainer.setOnClickListener {
            togglePlayPause()
        }

        // 自定义进度条回调
        binding.trimSeekBar.setOnRangeChangeListener(object : com.pisces312.streamclip.ui.TrimSeekBar.OnRangeChangeListener {
            override fun onRangeChanged(start: Int, end: Int, fromUser: Boolean) {
                startSec = start
                endSec = end
                updateTimeButtons()
                if (fromUser) {
                    // 拖动时更新播放器到开始位置
                    player?.seekTo(startSec * 1000L)
                }
            }
        })

        // 开始时间按钮
        binding.btnStartTime.setOnClickListener {
            showTimeInputDialog(true)
        }

        // 结束时间按钮
        binding.btnEndTime.setOnClickListener {
            showTimeInputDialog(false)
        }

        binding.btnExecute.setOnClickListener {
            executeTrim()
        }
    }

    private fun togglePlayPause() {
        player?.let { p ->
            if (p.isPlaying) {
                p.pause()
                binding.ivPlayIndicator.visibility = View.VISIBLE
            } else {
                p.play()
                binding.ivPlayIndicator.visibility = View.GONE
            }
        }
    }

    private fun showTimeInputDialog(isStart: Boolean) {
        val currentSec = if (isStart) startSec else endSec
        val currentText = formatTime(currentSec)

        val input = android.widget.EditText(requireContext()).apply {
            setText(currentText)
            hint = "MM:SS"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSelection(currentText.length)
        }

        // 实时验证输入格式
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(if (isStart) R.string.start_time else R.string.end_time)
            .setView(input)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = input.text.toString()
            val sec = parseTimeInput(text)
            if (sec != null && sec >= 0 && sec <= videoDurationMs / 1000) {
                if (isStart) {
                    startSec = sec.coerceAtMost(endSec - 1)
                } else {
                    endSec = sec.coerceAtLeast(startSec + 1)
                }
                updateTimeButtons()
                binding.trimSeekBar.setRange(startSec, endSec)
                player?.seekTo(startSec * 1000L)
                dialog.dismiss()
            } else {
                input.error = "格式错误，请使用 MM:SS"
            }
        }
    }

    private fun parseTimeInput(text: String): Int? {
        val parts = text.split(":")
        return when (parts.size) {
            2 -> {
                val mins = parts[0].toIntOrNull() ?: return null
                val secs = parts[1].toIntOrNull() ?: return null
                mins * 60 + secs
            }
            1 -> parts[0].toIntOrNull()
            else -> null
        }
    }

    private fun updateTimeButtons() {
        binding.btnStartTime.text = formatTime(startSec)
        binding.btnEndTime.text = formatTime(endSec)
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
            playWhenReady = false

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY && videoDurationMs == 0L) {
                        videoDurationMs = duration
                        val durationSec = (duration / 1000).toInt()

                        startSec = 0
                        endSec = durationSec

                        binding.trimSeekBar.durationSec = durationSec
                        binding.trimSeekBar.setRange(0, durationSec)
                        binding.tvDuration.text = formatDuration(duration)
                        updateTimeButtons()

                        // 限制播放范围
                        updateClippingRange()
                    }
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    // 播放超出范围时回到开始
                    if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                        if (currentPosition >= endSec * 1000L) {
                            seekTo(startSec * 1000L)
                            if (playWhenReady) {
                                play()
                            }
                        }
                    }
                }
            })
        }

        val fileName = getFileName(uri)
        binding.tvFileName.text = fileName
    }

    private fun updateClippingRange() {
        selectedVideoUri?.let { uri ->
            val clippingConfig = androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(startSec * 1000L)
                .setEndPositionMs(endSec * 1000L)
                .build()
            val mediaItem = androidx.media3.common.MediaItem.Builder()
                .setUri(uri)
                .setClippingConfiguration(clippingConfig)
                .build()
            player?.setMediaItem(mediaItem)
            player?.prepare()
        }
    }

    private fun executeTrim() {
        val uri = selectedVideoUri ?: run {
            Toast.makeText(requireContext(), getString(R.string.please_select_video), Toast.LENGTH_SHORT).show()
            return
        }

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
                requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                if (result.success) {
                    FileUtils.scanFile(requireContext(), outputFile)
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

    private fun formatTime(sec: Int): String {
        val mins = sec / 60
        val secs = sec % 60
        return String.format("%02d:%02d", mins, secs)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        player?.release()
        player = null
        _binding = null
    }
}
