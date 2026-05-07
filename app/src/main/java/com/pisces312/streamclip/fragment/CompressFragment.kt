package com.pisces312.streamclip.fragment

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import java.io.File

class CompressFragment : Fragment() {

    private var _binding: FragmentCompressBinding? = null
    private val binding get() = _binding!!
    private var selectedVideoUri: Uri? = null
    private var videoPath: String? = null

    private val pickVideo = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                selectedVideoUri = uri
                requireContext().contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                videoPath = FileUtils.getPathResultFromUri(requireContext(), uri)?.path
                binding.tvSelectedFile.text = videoPath ?: uri.toString()
                LogCollector.d("Compress", "Selected: $videoPath")
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCompressBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSpinners()
        setupQualitySeekBar()
        setupButtons()
    }

    private fun setupSpinners() {
        binding.spinnerEncoder.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item,
            CompressConfig.ENCODERS.map { it.second }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.spinnerEncoder.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val encoderKey = CompressConfig.ENCODERS[position].first
                val isHardware = encoderKey in listOf("h264_mediacodec", "hevc_mediacodec")
                updateQualityControl(isHardware)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })

        binding.spinnerRateControl.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item,
            CompressConfig.RATE_CONTROLS.map { it.second }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.spinnerResolution.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item,
            CompressConfig.RESOLUTIONS.map { it.second }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.spinnerFrameRate.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item,
            CompressConfig.FRAME_RATES.map { it.second }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.spinnerPreset.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item,
            CompressConfig.PRESETS.map { it.second }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.spinnerAudioEncoder.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item,
            CompressConfig.AUDIO_ENCODERS.map { it.second }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.spinnerOutputFormat.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item,
            CompressConfig.FORMATS.map { it.second }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    private fun setupQualitySeekBar() {
        binding.seekBarQuality.max = 51
        binding.seekBarQuality.progress = 23
        binding.tvQualityValue.text = "23"

        binding.seekBarQuality.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val encoderKey = CompressConfig.ENCODERS[binding.spinnerEncoder.selectedItemPosition].first
                val isHardware = encoderKey in listOf("h264_mediacodec", "hevc_mediacodec")
                if (isHardware) {
                    binding.tvQualityValue.text = "${progress * 200}Kbps"
                } else {
                    binding.tvQualityValue.text = progress.toString()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateQualityControl(isHardware: Boolean) {
        if (isHardware) {
            binding.seekBarQuality.isEnabled = true
            binding.seekBarQuality.max = 25
            binding.seekBarQuality.progress = 5
            binding.tvQualityValue.text = "1000Kbps"
            binding.tvQualityValue.text = "1000Kbps"
        } else {
            binding.seekBarQuality.isEnabled = true
            binding.seekBarQuality.max = 51
            binding.seekBarQuality.progress = 23
            binding.tvQualityValue.text = "23"
            binding.tvQualityValue.text = "23"
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
                    binding.btnCompress.isEnabled = true

                    if (result.success) {
                        val outFileName = outPath.substring(outPath.lastIndexOf('/') + 1)
                        FileUtils.scanFile(requireContext(), java.io.File(outPath))
                        Toast.makeText(requireContext(), "压缩完成: $outFileName", Toast.LENGTH_LONG).show()
                        binding.tvProgress.text = "完成: $outPath"
                    } else {
                        Toast.makeText(requireContext(), "压缩失败: ${result.error}", Toast.LENGTH_LONG).show()
                        binding.tvProgress.text = "失败: ${result.error}"
                    }
                }
            }
        }
    }

    private fun buildConfig(): CompressConfig {
        return CompressConfig(
            encoder = CompressConfig.ENCODERS[binding.spinnerEncoder.selectedItemPosition].first,
            rateControl = CompressConfig.RATE_CONTROLS[binding.spinnerRateControl.selectedItemPosition].first,
            qualityValue = binding.seekBarQuality.progress,
            resolution = CompressConfig.RESOLUTIONS[binding.spinnerResolution.selectedItemPosition].first,
            frameRate = CompressConfig.FRAME_RATES[binding.spinnerFrameRate.selectedItemPosition].first,
            preset = CompressConfig.PRESETS[binding.spinnerPreset.selectedItemPosition].first,
            audioEncoder = CompressConfig.AUDIO_ENCODERS[binding.spinnerAudioEncoder.selectedItemPosition].first,
            outputFormat = CompressConfig.FORMATS[binding.spinnerOutputFormat.selectedItemPosition].first
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
