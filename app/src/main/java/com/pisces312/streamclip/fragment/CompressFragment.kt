package com.pisces312.streamclip.fragment

import com.pisces312.streamclip.R
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.pisces312.streamclip.databinding.FragmentCompressBinding
import com.pisces312.streamclip.model.CompressConfig
import com.pisces312.streamclip.service.FFmpegService
import com.pisces312.streamclip.adapter.FfmpegLogAdapter
import com.pisces312.streamclip.util.FileUtils
import com.pisces312.streamclip.util.LogCollector
import com.pisces312.streamclip.util.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.appcompat.app.AlertDialog


class CompressFragment : Fragment() {

    private var _binding: FragmentCompressBinding? = null
    private val binding get() = _binding!!
    private var videoPath: String? = null
    private var isHardwareTab = true

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleVideoSelected(uri)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCompressBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTabLayout()
        setupHardwarePanel()
        setupSoftwarePanel()
        setupButtons()
        setupHelpButtons()
    }

    private fun setupTabLayout() {
        binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                isHardwareTab = tab?.position == 0
                binding.panelHardware.visibility = if (isHardwareTab) View.VISIBLE else View.GONE
                binding.panelSoftware.visibility = if (isHardwareTab) View.GONE else View.VISIBLE
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })
        isHardwareTab = true
        binding.panelSoftware.visibility = View.GONE
    }

    private fun setupHardwarePanel() {
        // Encoder (default H.265)
        binding.spinnerEncoderHw.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            CompressConfig.HW_ENCODERS.map { it.second }
        )
        binding.spinnerEncoderHw.setSelection(1) // H.265 default

        // Bitrate
        binding.spinnerBitrate.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            CompressConfig.BITRATES.map { it.second }
        )
        binding.spinnerBitrate.setSelection(2) // 2 Mbps default

        // Speed
        binding.spinnerSpeed.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            CompressConfig.SPEEDS.map { it.second }
        )
        binding.spinnerSpeed.setSelection(1) // balanced

        // Resolution
        binding.spinnerResolutionHw.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            CompressConfig.RESOLUTIONS.map { it.second }
        )

        // Audio
        binding.spinnerAudioHw.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            CompressConfig.AUDIO_ENCODERS.map { it.second }
        )
        binding.spinnerAudioHw.setSelection(0) // copy
    }

    private fun setupSoftwarePanel() {
        // Encoder (default H.265)
        binding.spinnerEncoderSw.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            CompressConfig.SW_ENCODERS.map { it.second }
        )
        binding.spinnerEncoderSw.setSelection(1) // H.265 default

        // CRF SeekBar
        binding.seekBarCrf.max = 51
        binding.seekBarCrf.progress = 23
        binding.seekBarCrf.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvCrfValue.text = progress.toString()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Preset
        binding.spinnerPresetSw.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            CompressConfig.PRESETS.map { it.second }
        )
        binding.spinnerPresetSw.setSelection(5) // medium

        // Resolution
        binding.spinnerResolutionSw.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            CompressConfig.RESOLUTIONS.map { it.second }
        )

        // Audio
        binding.spinnerAudioSw.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            CompressConfig.AUDIO_ENCODERS.map { it.second }
        )
        binding.spinnerAudioSw.setSelection(0) // copy
    }

    private fun setupHelpButtons() {
        val helpMap = mapOf(
            binding.btnHelpEncoderHw to "encoder",
            binding.btnHelpEncoderSw to "encoder",
            binding.btnHelpBitrate to "bitrate",
            binding.btnHelpCrf to "crf",
            binding.btnHelpSpeed to "speed",
            binding.btnHelpPreset to "preset",
            binding.btnHelpResolutionHw to "resolution",
            binding.btnHelpResolutionSw to "resolution",
            binding.btnHelpAudioHw to "audio",
            binding.btnHelpAudioSw to "audio"
        )

        helpMap.forEach { (view, key) ->
            view.setOnClickListener {
                showHelpDialog(key)
            }
        }
    }

    private fun showHelpDialog(key: String) {
        val title = when (key) {
            "encoder" -> "编码器"
            "bitrate" -> "码率"
            "crf" -> "CRF 质量"
            "speed" -> "速度质量平衡"
            "preset" -> "预设速度"
            "resolution" -> "分辨率"
            "audio" -> "音频编码"
            else -> "帮助"
        }
        val message = CompressConfig.HELP_TEXTS[key] ?: "暂无说明"

        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }

    private var sourceFileTimes: Pair<java.nio.file.attribute.FileTime?, java.nio.file.attribute.FileTime?>? = null

    private fun handleVideoSelected(uri: Uri) {
        val path = FileUtils.getPathFromUri(requireContext(), uri)
        if (path != null) {
            videoPath = path
            binding.tvSelectedFile.text = java.io.File(path).name
            SettingsManager.setLastVideoDir(requireContext(), uri)

            // 读取原文件时间戳
            sourceFileTimes = FileUtils.readFileTimes(path)
        } else {
            Toast.makeText(requireContext(), getString(R.string.cannot_get_path), Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupButtons() {
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

        binding.btnCompress.setOnClickListener {
            val path = videoPath
            if (path == null) {
                Toast.makeText(requireContext(), getString(R.string.please_select_video), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val config = buildConfig()
            val sourceFile = java.io.File(path)
            val outputDir = SettingsManager.getOutputDir(requireContext(), sourceFile)
            val outputName = SettingsManager.getOutputFileName(
                requireContext(),
                sourceFile.name,
                "compressed",
                "mp4"
            )
            val outPath = java.io.File(outputDir, outputName).absolutePath

            binding.progressBar.visibility = View.VISIBLE
            binding.tvProgress.visibility = View.VISIBLE
            binding.tvProgress.text = getString(R.string.compressing)
            binding.btnCompress.isEnabled = false

            // 根据设置保持屏幕常亮
            if (SettingsManager.isKeepScreenOn(requireContext())) {
                requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            // Get video duration
            val totalTimeMs = FFmpegService.getDurationMs(path)

            // Show log dialog
            val logDialog = showFfmpegLogDialog(config.toFFmpegCommand(path, outPath))

            viewLifecycleOwner.lifecycleScope.launch {
                val command = config.toFFmpegCommand(path, outPath)
                LogCollector.d("Compress", "Command: $command")

                val result = FFmpegService.executeCommand(
                    command,
                    outPath,
                    totalTimeMs = totalTimeMs,
                    onProgress = { progress ->
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                            if (_binding == null) return@launch
                            binding.progressBar.progress = progress.percent
                            binding.tvProgress.text = "${progress.percent}%"
                            logDialog.updateProgress(progress)
                        }
                    },
                    onLog = { logLine ->
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                            if (_binding == null) return@launch
                            logDialog.addLog(logLine)
                        }
                    }
                )

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.tvProgress.visibility = View.GONE
                    binding.btnCompress.isEnabled = true
                    binding.progressBar.progress = 100
                    // 清除屏幕常亮
                    requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    logDialog.onComplete(result.success)

                    if (result.success) {
                        val outFileName = outPath.substring(outPath.lastIndexOf('/') + 1)
                        FileUtils.scanFile(requireContext(), java.io.File(outPath))

                        // 恢复时间戳
                        sourceFileTimes?.let { (creation, modified) ->
                            FileUtils.applyFileTimes(outPath, creation, modified)
                        }

                        // 验证 GPS metadata 是否保留
                        val sourceLocation = videoPath?.let { FFmpegService.probeLocation(it) }
                        val outputLocation = FFmpegService.probeLocation(outPath)
                        LogCollector.d("CompressFragment", "Source location: $sourceLocation")
                        LogCollector.d("CompressFragment", "Output location: $outputLocation")

                        Toast.makeText(requireContext(), getString(R.string.compress_complete, outFileName), Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.compress_failed, result.error), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun buildConfig(): CompressConfig {
        return if (isHardwareTab) {
            CompressConfig(
                encoder = CompressConfig.HW_ENCODERS[binding.spinnerEncoderHw.selectedItemPosition].first,
                bitrate = CompressConfig.BITRATES[binding.spinnerBitrate.selectedItemPosition].first,
                resolution = CompressConfig.RESOLUTIONS[binding.spinnerResolutionHw.selectedItemPosition].first,
                speed = CompressConfig.SPEEDS[binding.spinnerSpeed.selectedItemPosition].first,
                audioEncoder = CompressConfig.AUDIO_ENCODERS[binding.spinnerAudioHw.selectedItemPosition].first,
                isHardware = true
            )
        } else {
            CompressConfig(
                encoder = CompressConfig.SW_ENCODERS[binding.spinnerEncoderSw.selectedItemPosition].first,
                crf = binding.seekBarCrf.progress,
                resolution = CompressConfig.RESOLUTIONS[binding.spinnerResolutionSw.selectedItemPosition].first,
                preset = CompressConfig.PRESETS[binding.spinnerPresetSw.selectedItemPosition].first,
                audioEncoder = CompressConfig.AUDIO_ENCODERS[binding.spinnerAudioSw.selectedItemPosition].first,
                isHardware = false
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun showFfmpegLogDialog(command: String, totalTimeMs: Long = -1): FfmpegLogDialog {
        val dialogView = layoutInflater.inflate(com.pisces312.streamclip.R.layout.dialog_ffmpeg_log, null)
        val tvCommand = dialogView.findViewById<TextView>(com.pisces312.streamclip.R.id.tvCommand)
        val recyclerLogs = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(com.pisces312.streamclip.R.id.recyclerLogs)
        val btnCopy = dialogView.findViewById<Button>(com.pisces312.streamclip.R.id.btnCopy)
        val btnClose = dialogView.findViewById<Button>(com.pisces312.streamclip.R.id.btnClose)
        val progressBar = dialogView.findViewById<android.widget.ProgressBar>(com.pisces312.streamclip.R.id.progressBar)
        val tvPercent = dialogView.findViewById<TextView>(com.pisces312.streamclip.R.id.tvPercent)
        val tvTimeInfo = dialogView.findViewById<TextView>(com.pisces312.streamclip.R.id.tvTimeInfo)
        val tvOutputSize = dialogView.findViewById<TextView>(com.pisces312.streamclip.R.id.tvOutputSize)

        tvCommand.text = command

        val adapter = FfmpegLogAdapter()
        recyclerLogs.layoutManager = LinearLayoutManager(requireContext())
        recyclerLogs.adapter = adapter

        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()

        val startTime = System.currentTimeMillis()

        btnCopy.setOnClickListener {
            val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("FFmpeg Log", "Command:\n$command\n\nLogs:\n${adapter.getAllLogs()}")
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

        return object : FfmpegLogDialog {
            override fun addLog(log: FFmpegService.LogLine) {
                adapter.addLog(log)
                recyclerLogs.scrollToPosition(adapter.itemCount - 1)
            }
            override fun onComplete(success: Boolean) {
                btnClose.isEnabled = true
                btnClose.text = if (success) getString(R.string.done) else getString(R.string.close)
            }
            override fun updateProgress(progress: FFmpegService.Progress) {
                // percent 为 -1 表示未知总时长
                if (progress.percent >= 0) {
                    progressBar?.progress = progress.percent
                    tvPercent?.text = "${progress.percent}%"
                } else {
                    progressBar?.progress = 0
                    tvPercent?.text = "--%"
                }

                // Calculate elapsed and estimated remaining time
                val elapsedRealMs = System.currentTimeMillis() - startTime

                // Format time info
                val elapsedStr = formatTime(elapsedRealMs / 1000)
                // estimatedRemainingMs 为 -1 表示无法预估
                val remainingStr = if (progress.processedTimeMs >= 0 && progress.percent > 0 && progress.percent < 100) {
                    formatTime((elapsedRealMs / progress.percent.toDouble() * (100 - progress.percent)).toLong() / 1000)
                } else {
                    "--:--"
                }
                tvTimeInfo?.text = "已用: $elapsedStr | 预估: $remainingStr"

                // Format output size
                val sizeMB = progress.outputSizeBytes / (1024.0 * 1024.0)
                tvOutputSize?.text = "输出: %.1f MB".format(sizeMB)
            }

            private fun formatTime(seconds: Long): String {
                val h = seconds / 3600
                val m = (seconds % 3600) / 60
                val s = seconds % 60
                return if (h > 0) {
                    "%d:%02d:%02d".format(h, m, s)
                } else {
                    "%02d:%02d".format(m, s)
                }
            }
        }
    }

    interface FfmpegLogDialog {
        fun addLog(log: FFmpegService.LogLine)
        fun onComplete(success: Boolean)
        fun updateProgress(progress: FFmpegService.Progress)
    }
}
