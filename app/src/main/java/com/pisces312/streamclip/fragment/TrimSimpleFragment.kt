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
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.pisces312.streamclip.databinding.FragmentTrimSimpleBinding
import com.pisces312.streamclip.service.FFmpegService
import com.pisces312.streamclip.util.FileUtils
import com.pisces312.streamclip.util.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 简单版无损截取
 * 核心思路：SeekBar 只用于拖动定位，不回写 player 进度到 SeekBar（避免异步冲突）
 * "设为起点/终点"直接从 player 读取当前位置
 */
class TrimSimpleFragment : Fragment() {

    private var _binding: FragmentTrimSimpleBinding? = null
    private val binding get() = _binding!!
    private var player: ExoPlayer? = null
    private var selectedVideoUri: Uri? = null
    private var videoDurationMs: Long = 0

    // 截取范围（秒）
    private var startSec = 0f
    private var endSec = 0f

    // SeekBar 拖动标记
    private var isSeekBarTracking = false

    // 联动标记
    private var isUpdatingFromEditText = false

    // 播放时更新 SeekBar
    private val playbackUpdateRunnable = object : Runnable {
        override fun run() {
            player?.let { p ->
                // 只在播放中且用户没拖 SeekBar 时同步进度
                if (p.isPlaying && !isSeekBarTracking && videoDurationMs > 0) {
                    val progress = ((p.currentPosition.toFloat() / videoDurationMs) * 1000).toInt()
                        .coerceIn(0, 1000)
                    binding.seekBar.progress = progress
                }
                // 播放到终点暂停
                if (p.isPlaying) {
                    val currentSec = p.currentPosition / 1000f
                    if (currentSec >= endSec && endSec > 0) {
                        p.pause()
                        updatePlayPauseIcon()
                    }
                }
            }
            binding.root.postDelayed(this, 100)
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

        // 播放/暂停
        binding.btnPlayPause.setOnClickListener {
            player?.let { p ->
                if (p.isPlaying) {
                    p.pause()
                } else {
                    p.play()
                }
                updatePlayPauseIcon()
            }
        }

        // SeekBar：拖动时 seek 播放器，松手时更新位置
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // 拖动过程中实时 seek（仅用户触发）
                if (fromUser && videoDurationMs > 0) {
                    val targetMs = (progress / 1000f) * videoDurationMs
                    player?.seekTo(targetMs.toLong())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeekBarTracking = true
                player?.pause()
                updatePlayPauseIcon()
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeekBarTracking = false
                // 松手后让 player 的真实位置同步回 SeekBar（防漂移）
                player?.let { p ->
                    if (videoDurationMs > 0) {
                        val progress = ((p.currentPosition.toFloat() / videoDurationMs) * 1000).toInt()
                            .coerceIn(0, 1000)
                        binding.seekBar.progress = progress
                    }
                }
            }
        })

        // 设为起点：直接从 player 读当前位置
        binding.btnSetStart.setOnClickListener {
            player?.let { p ->
                if (videoDurationMs <= 0) return@setOnClickListener
                val currentSec = p.currentPosition / 1000f
                if (currentSec < endSec) {
                    startSec = currentSec
                    binding.etStartTime.setText(formatSecondsMs(startSec))
                    updateSelectedDuration()
                } else {
                    Toast.makeText(requireContext(), "起点必须小于终点", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 设为终点：直接从 player 读当前位置
        binding.btnSetEnd.setOnClickListener {
            player?.let { p ->
                if (videoDurationMs <= 0) return@setOnClickListener
                val currentSec = p.currentPosition / 1000f
                if (currentSec > startSec) {
                    endSec = currentSec
                    binding.etEndTime.setText(formatSecondsMs(endSec))
                    updateSelectedDuration()
                } else {
                    Toast.makeText(requireContext(), "终点必须大于起点", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 精确时间输入框
        setupEditText(binding.etStartTime) { value ->
            if (videoDurationMs <= 0) return@setupEditText
            val maxVal = videoDurationMs / 1000f
            val clamped = value.coerceIn(0f, maxVal)
            if (clamped < endSec) {
                startSec = clamped
                player?.seekTo((startSec * 1000).toLong())
                updateSelectedDuration()
            } else {
                binding.etStartTime.setText(formatSecondsMs(startSec))
            }
        }
        setupEditText(binding.etEndTime) { value ->
            if (videoDurationMs <= 0) return@setupEditText
            val maxVal = videoDurationMs / 1000f
            val clamped = value.coerceIn(0f, maxVal)
            if (clamped > startSec) {
                endSec = clamped
                player?.seekTo((endSec * 1000).toLong())
                updateSelectedDuration()
            } else {
                binding.etEndTime.setText(formatSecondsMs(endSec))
            }
        }
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
        if (isUpdatingFromEditText) return
        val text = et.text.toString().trim()
        val value = text.toFloatOrNull()
        if (value != null && videoDurationMs > 0) {
            isUpdatingFromEditText = true
            onCommit(value)
            isUpdatingFromEditText = false
        }
    }

    private fun updateSelectedDuration() {
        val duration = endSec - startSec
        binding.tvSelectedDuration.text = "选中时长: ${formatTimeMs(duration)}"
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

                        startSec = 0f
                        endSec = durationSec

                        binding.tvDuration.text = "总时长: ${formatTimeMs(durationSec)}"
                        binding.etStartTime.isEnabled = true
                        binding.etEndTime.isEnabled = true
                        binding.etStartTime.setText("0.000")
                        binding.etEndTime.setText(formatSecondsMs(durationSec))
                        binding.btnSetStart.isEnabled = true
                        binding.btnSetEnd.isEnabled = true
                        binding.btnPlayPause.visibility = View.VISIBLE
                        binding.tvSelectedDuration.visibility = View.VISIBLE
                        binding.tvSelectedDuration.text = "选中时长: ${formatTimeMs(durationSec)}"

                        updatePlayPauseIcon()
                        seekTo(0)

                        // 启动播放进度更新
                        binding.root.post(playbackUpdateRunnable)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePlayPauseIcon()
                }
            })
        }

        val fileName = getFileName(uri)
        binding.tvFileName.text = fileName
    }

    private fun executeTrim() {
        val uri = selectedVideoUri ?: run {
            Toast.makeText(requireContext(), "请先选择视频", Toast.LENGTH_SHORT).show()
            return
        }

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
                    android.util.Log.e("TrimSimpleFragment", "Trim failed: $errorMsg")
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
