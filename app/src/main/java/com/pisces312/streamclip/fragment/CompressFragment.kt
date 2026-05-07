package com.pisces312.streamclip.fragment

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.pisces312.streamclip.databinding.FragmentCompressBinding
import com.pisces312.streamclip.model.CompressConfig
import com.pisces312.streamclip.service.FFmpegService
import com.pisces312.streamclip.util.FileUtils
import com.pisces312.streamclip.util.LogCollector
import com.pisces312.streamclip.util.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        binding.tabLayout.addOnTabSelectedListener(object :
            com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                isHardwareTab = tab?.position == 0
                binding.panelHardware.visibility = if (isHardwareTab) View.VISIBLE else View.GONE
                binding.panelSoftware.visibility = if (isHardwareTab) View.GONE else View.VISIBLE
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })
    }

    private fun setupHardwarePanel() {
        // Encoder
        binding.spinnerEncoderHw.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            CompressConfig.HW_ENCODERS.map { it.second }
        )

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
        // Encoder
        binding.spinnerEncoderSw.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            CompressConfig.SW_ENCODERS.map { it.second }
        )

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
            .setPositiveButton("确定", null)
            .show()
    }

    private fun handleVideoSelected(uri: Uri) {
        val path = FileUtils.getPathFromUri(requireContext(), uri)
        if (path != null) {
            videoPath = path
            binding.tvSelectedFile.text = java.io.File(path).name
            SettingsManager.setLastVideoDir(requireContext(), uri)
        } else {
            Toast.makeText(requireContext(), "无法获取文件路径", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(requireContext(), "请先选择视频", Toast.LENGTH_SHORT).show()
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
            binding.tvProgress.text = "压缩中..."
            binding.btnCompress.isEnabled = false

            lifecycleScope.launch {
                val command = config.toFFmpegCommand(path, outPath)
                LogCollector.d("Compress", "Command: $command")

                val result = FFmpegService.executeCommand(command, outPath) { progress ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        binding.progressBar.progress = progress.percent
                        binding.tvProgress.text = "${progress.percent}% - ${progress.message}"
                    }
                }

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.tvProgress.visibility = View.GONE
                    binding.btnCompress.isEnabled = true

                    if (result.success) {
                        val outFileName = outPath.substring(outPath.lastIndexOf('/') + 1)
                        FileUtils.scanFile(requireContext(), java.io.File(outPath))
                        Toast.makeText(requireContext(), "压缩完成: $outFileName", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(requireContext(), "压缩失败: ${result.error}", Toast.LENGTH_LONG).show()
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
}
