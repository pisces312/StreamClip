package com.pisces312.streamclip.fragment

import android.app.Activity
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.pisces312.streamclip.R
import com.pisces312.streamclip.databinding.FragmentNativeCompressBinding
import com.pisces312.streamclip.util.FileUtils
import com.pisces312.streamclip.util.LogCollector
import com.pisces312.streamclip.util.SettingsManager
import com.pisces312.streamclip.videocompressor.EncoderInfo
import com.pisces312.streamclip.videocompressor.NativeCompressConfig
import com.pisces312.streamclip.videocompressor.NativeVideoCompressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class NativeCompressFragment : Fragment() {

    private var _binding: FragmentNativeCompressBinding? = null
    private val binding get() = _binding!!
    private var videoPath: String? = null
    private var originalWidth: Int = 0
    private var originalHeight: Int = 0
    private var originalDurationMs: Long = 0
    private var encoders: List<EncoderInfo> = emptyList()
    private var bitrateOptions: List<Pair<Int, String>> = emptyList()
    private var frameRateOptions: List<Pair<Int, String>> = emptyList()
    private var currentJob: kotlinx.coroutines.Job? = null

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
        _binding = FragmentNativeCompressBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupEncoders()
        setupResolutionSpinner()
        setupBitrateSpinner()
        setupFrameRateSpinner()
        setupIFrameIntervalSeekBar()
        setupButtons()
    }

    private fun setupEncoders() {
        encoders = NativeVideoCompressor.listAvailableEncoders()
        if (encoders.isEmpty()) {
            binding.btnCompress.isEnabled = false
            Toast.makeText(requireContext(), "未找到可用的视频编码器", Toast.LENGTH_LONG).show()
            return
        }
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            encoders.map { it.toString() }
        )
        binding.spinnerEncoder.adapter = adapter
        val defaultIndex = encoders.indexOfFirst { it.mimeType == "video/hevc" && it.isHardware }
            .coerceAtLeast(encoders.indexOfFirst { it.mimeType == "video/hevc" }
                .coerceAtLeast(0))
        binding.spinnerEncoder.setSelection(defaultIndex)
    }

    private fun setupResolutionSpinner() {
        val options = mutableListOf(getString(R.string.resolution_copy))
        binding.spinnerResolution.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            options
        )
    }

    private fun updateResolutionOptions() {
        if (originalWidth == 0 || originalHeight == 0) return
        val options = mutableListOf(getString(R.string.resolution_copy))
        val factors = listOf(
            1.5f to "缩小 1.5x",
            2.0f to "缩小 2.0x",
            2.25f to "缩小 2.25x",
            3.0f to "缩小 3.0x"
        )
        for ((factor, label) in factors) {
            val w = ((originalWidth / factor).toInt() / 2) * 2
            val h = ((originalHeight / factor).toInt() / 2) * 2
            options.add("${w}x${h} ($label)")
        }
        binding.spinnerResolution.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            options
        )
    }

    private fun setupBitrateSpinner() {
        bitrateOptions = listOf(
            0 to "自动",
            1000 to "1 Mbps",
            2000 to "2 Mbps",
            3000 to "3 Mbps",
            4000 to "4 Mbps",
            6000 to "6 Mbps",
            8000 to "8 Mbps"
        )
        binding.spinnerBitrate.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            bitrateOptions.map { it.second }
        )
        binding.spinnerBitrate.setSelection(2)
    }

    private fun setupFrameRateSpinner() {
        frameRateOptions = listOf(
            0 to getString(R.string.cfg_original),
            24 to "24 fps",
            25 to "25 fps",
            30 to "30 fps",
            60 to "60 fps"
        )
        binding.spinnerFrameRate.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            frameRateOptions.map { it.second }
        )
        binding.spinnerFrameRate.setSelection(0)
    }

    private fun setupIFrameIntervalSeekBar() {
        binding.seekBarIFrameInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress.coerceAtLeast(1)
                binding.tvIFrameIntervalValue.text = "${value}s"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.seekBarIFrameInterval.progress = 3
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
            executeCompress(path)
        }
    }

    private fun handleVideoSelected(uri: Uri) {
        val path = FileUtils.getPathFromUri(requireContext(), uri)
        if (path != null) {
            videoPath = path
            SettingsManager.setLastVideoDir(requireContext(), uri)
            binding.cardOutputInfo.visibility = View.GONE
            probeVideoInfo(path)
        } else {
            Toast.makeText(requireContext(), getString(R.string.cannot_get_path), Toast.LENGTH_SHORT).show()
        }
    }

    private fun probeVideoInfo(path: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.let {
                        if (duration > 0 && it.toIntOrNull() != null) it.toInt() * 1000f / duration else 0f
                    } ?: 0f

                originalWidth = w
                originalHeight = h
                originalDurationMs = duration

                val fileSizeMB = File(path).let { f -> if (f.exists()) "%.1f MB".format(f.length() / (1024.0 * 1024.0)) else "N/A" }
                val lines = mutableListOf<String>()
                lines.add("${getString(R.string.info_path)}: $path")
                lines.add("${getString(R.string.info_size)}: $fileSizeMB")
                lines.add("${getString(R.string.info_video)}: ${w}x${h} ${String.format("%.1f", frameRate)}fps ${bitrate / 1000}kbps")
                if (rotation != 0) lines.add("旋转: ${rotation}")

                withContext(Dispatchers.Main) {
                    binding.tvOriginalInfo.text = lines.joinToString("\n")
                    binding.cardOriginalInfo.visibility = View.VISIBLE
                    updateResolutionOptions()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), getString(R.string.cannot_probe_video, e.message), Toast.LENGTH_SHORT).show()
                }
            } finally {
                retriever.release()
            }
        }
    }

    private fun showVideoInfoCard(path: String, infoView: android.widget.TextView, titleView: android.widget.TextView, title: String) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
            val fileSizeMB = File(path).let { f -> if (f.exists()) "%.1f MB".format(f.length() / (1024.0 * 1024.0)) else "N/A" }
            val lines = mutableListOf<String>()
            lines.add("${getString(R.string.info_size)}: $fileSizeMB")
            lines.add("${getString(R.string.info_video)}: ${w}x${h} ${bitrate / 1000}kbps")
            titleView.text = title
            infoView.text = lines.joinToString("\n")
        } catch (_: Exception) {
        } finally {
            retriever.release()
        }
    }

    private fun executeCompress(path: String) {
        val encoder = encoders.getOrNull(binding.spinnerEncoder.selectedItemPosition)
        if (encoder == null) {
            Toast.makeText(requireContext(), "请选择编码器", Toast.LENGTH_SHORT).show()
            return
        }

        val resPos = binding.spinnerResolution.selectedItemPosition
        val (targetW, targetH) = if (resPos == 0) {
            0 to 0
        } else {
            val factors = listOf(1.5f, 2.0f, 2.25f, 3.0f)
            val factor = factors.getOrNull(resPos - 1) ?: 1.0f
            val w = ((originalWidth / factor).toInt() / 2) * 2
            val h = ((originalHeight / factor).toInt() / 2) * 2
            w to h
        }

        val bitrateKbps = bitrateOptions.getOrNull(binding.spinnerBitrate.selectedItemPosition)?.first ?: 0
        val frameRate = frameRateOptions.getOrNull(binding.spinnerFrameRate.selectedItemPosition)?.first ?: 30

        val iFrameInterval = binding.seekBarIFrameInterval.progress.coerceAtLeast(1)

        val config = NativeCompressConfig(
            mimeType = encoder.mimeType,
            encoderName = encoder.name,
            targetWidth = targetW,
            targetHeight = targetH,
            bitrateKbps = bitrateKbps,
            frameRate = frameRate,
            iFrameInterval = iFrameInterval
        )

        val sourceFile = File(path)
        val outputDir = SettingsManager.getOutputDir(requireContext(), sourceFile)
        val outputName = SettingsManager.getOutputFileName(
            requireContext(), sourceFile.name, "compressed_native", "mp4"
        )
        val outPath = File(outputDir, outputName).absolutePath

        binding.progressBar.visibility = View.VISIBLE
        binding.tvProgress.visibility = View.VISIBLE
        binding.tvProgress.text = getString(R.string.native_compressing)
        binding.btnCompress.isEnabled = false

        if (SettingsManager.isKeepScreenOn(requireContext())) {
            requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        currentJob = lifecycleScope.launch {
            LogCollector.i("NativeCompress", "Starting compress: $path -> $outPath")
            LogCollector.i("NativeCompress", "Config: $config")
            val result = NativeVideoCompressor.compressVideo(path, outPath, config) { percent ->
                if (_binding != null) {
                    binding.progressBar.progress = percent.toInt()
                    binding.tvProgress.text = "${percent.toInt()}%"
                }
            }

            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                binding.tvProgress.visibility = View.GONE
                binding.btnCompress.isEnabled = true
                requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                if (result.isSuccess) {
                    LogCollector.i("NativeCompress", "Compress success: $outPath")
                    FileUtils.scanFile(requireContext(), File(outPath))
                    showVideoInfoCard(
                        outPath,
                        binding.tvOutputInfo,
                        binding.tvOutputInfoTitle,
                        getString(R.string.output_video_info)
                    )
                    binding.cardOutputInfo.visibility = View.VISIBLE
                    Toast.makeText(requireContext(), getString(R.string.compress_complete, outputName), Toast.LENGTH_LONG).show()
                } else {
                    val error = result.exceptionOrNull()?.message ?: "unknown"
                    LogCollector.e("NativeCompress", "Compress failed: $error", result.exceptionOrNull() ?: Exception(error))
                    Toast.makeText(requireContext(), getString(R.string.compress_failed, error), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        currentJob?.cancel()
        _binding = null
    }
}
