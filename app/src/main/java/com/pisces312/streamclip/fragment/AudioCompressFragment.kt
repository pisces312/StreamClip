package com.pisces312.streamclip.fragment

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.pisces312.streamclip.R
import com.pisces312.streamclip.databinding.FragmentAudioCompressBinding
import com.pisces312.streamclip.model.CompressConfig
import com.pisces312.streamclip.service.FFmpegService
import com.pisces312.streamclip.util.FileUtils
import com.pisces312.streamclip.util.LogCollector
import com.pisces312.streamclip.util.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AudioCompressFragment : Fragment() {

    interface FfmpegLogDialog {
        fun addLog(log: FFmpegService.LogLine)
        fun onComplete(success: Boolean)
        fun updateProgress(progress: FFmpegService.Progress)
        var onCancel: (() -> Unit)?
    }

    private var _binding: FragmentAudioCompressBinding? = null
    private val binding get() = _binding!!
    private var videoPath: String? = null
    private var originalMediaInfo: com.pisces312.streamclip.model.MediaInfo? = null
    private var isAudioOnlyInput: Boolean = false
    private var sourceFileTimes: Pair<java.nio.file.attribute.FileTime?, java.nio.file.attribute.FileTime?>? = null

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
        _binding = FragmentAudioCompressBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupSpinners()
        setupButtons()
        setupHelpButtons()
    }

    private fun setupSpinners() {
        // Audio Encoder
        binding.spinnerAudioEncoder.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            CompressConfig.AUDIO_ENCODERS.map { it.second }
        )
        binding.spinnerAudioEncoder.setSelection(1) // AAC default

        // Audio Bitrate
        binding.spinnerAudioBitrate.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            CompressConfig.AUDIO_BITRATES.map { it.second }
        )
        binding.spinnerAudioBitrate.setSelection(3) // 128k

        // Audio Sample Rate
        binding.spinnerAudioSampleRate.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            CompressConfig.AUDIO_SAMPLE_RATES.map { it.second }
        )
        binding.spinnerAudioSampleRate.setSelection(2) // 44100

        setupAudioVisibilityListener()
    }

    private fun setupAudioVisibilityListener() {
        binding.spinnerAudioEncoder.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val encoder = CompressConfig.AUDIO_ENCODERS[position].first
                binding.panelAudioOptions.visibility = if (encoder == "copy") View.GONE else View.VISIBLE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.panelAudioOptions.visibility = View.VISIBLE
    }

    private fun setupHelpButtons() {
        val helpMap = mapOf(
            binding.btnHelpAudioEncoder to "audio",
            binding.btnHelpAudioBitrate to "audioBitrate",
            binding.btnHelpAudioSampleRate to "audioSampleRate"
        )
        helpMap.forEach { (view, key) ->
            view.setOnClickListener { showHelpDialog(key) }
        }
    }

    private fun showHelpDialog(key: String) {
        val title = when (key) {
            "audio" -> "音频编码"
            "audioBitrate" -> "音频码率"
            "audioSampleRate" -> "音频采样率"
            else -> "帮助"
        }
        val message = CompressConfig.HELP_TEXTS[key] ?: "暂无说明"
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }

    private fun setupButtons() {
        binding.btnSelectVideo.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    SettingsManager.getLastVideoDir(requireContext())?.let { uri ->
                        putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, uri)
                    }
                }
            }
            // Also allow video files
            intent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
            pickVideo.launch(intent)
        }

        binding.btnCompress.setOnClickListener {
            if (videoPath != null) {
                executeCompress()
            } else {
                Toast.makeText(requireContext(), getString(R.string.please_select_video), Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnCompress.text = getString(R.string.start_audio_compress)
    }

    private fun handleVideoSelected(uri: Uri) {
        val path = FileUtils.getPathFromUri(requireContext(), uri)
        if (path != null) {
            videoPath = path
            binding.cardOutputInfo.visibility = View.GONE
            SettingsManager.setLastVideoDir(requireContext(), uri)
            sourceFileTimes = FileUtils.readFileTimes(path)
            isAudioOnlyInput = false
            originalMediaInfo = null

            lifecycleScope.launch(Dispatchers.IO) {
                val info = FFmpegService.probeMediaInfo(path)
                withContext(Dispatchers.Main) {
                    if (info != null) {
                        originalMediaInfo = info
                        isAudioOnlyInput = info.video == null
                        if (info.video != null) {
                            showVideoInfo()
                        } else if (info.audio != null) {
                            showAudioInfo(path, info)
                        } else {
                            Toast.makeText(requireContext(), "无法识别文件格式", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        } else {
            Toast.makeText(requireContext(), getString(R.string.cannot_get_path), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAudioInfo(path: String, info: com.pisces312.streamclip.model.MediaInfo) {
        binding.tvOriginalPath.text = path
        val lines = mutableListOf<String>()
        val audio = info.audio
        lines.add("音频: ${audio?.codec ?: ""} ${info.audioSampleRate}Hz ${audio?.channelLayout ?: ""}")
        binding.tvOriginalInfo.text = lines.joinToString("\n")
        binding.cardOriginalInfo.visibility = View.VISIBLE
    }

    private fun showVideoInfo() {
        val info = originalMediaInfo ?: return
        binding.tvOriginalPath.text = info.path
        val lines = mutableListOf<String>()
        lines.add("视频: ${info.videoCodec} ${info.resolution} ${info.frameRate}")
        lines.add("音频: ${info.audioCodec} ${info.audioSampleRateStr} ${info.audioBitrateKbps}")
        binding.tvOriginalInfo.text = lines.joinToString("\n")
        binding.cardOriginalInfo.visibility = View.VISIBLE
    }

    private fun executeCompress() {
        val path = videoPath ?: return

        val audioEncoder = CompressConfig.AUDIO_ENCODERS[binding.spinnerAudioEncoder.selectedItemPosition].first
        val audioBitrate = CompressConfig.AUDIO_BITRATES[binding.spinnerAudioBitrate.selectedItemPosition].first
        val audioSampleRate = CompressConfig.AUDIO_SAMPLE_RATES[binding.spinnerAudioSampleRate.selectedItemPosition].first

        val sourceFile = java.io.File(path)
        val outputDir = SettingsManager.getOutputDir(requireContext(), sourceFile)

        // Determine output extension based on input type and encoder
        val outputExt = if (isAudioOnlyInput) {
            when (audioEncoder) {
                "aac" -> "m4a"
                "libmp3lame" -> "mp3"
                "flac" -> "flac"
                "libopus" -> "opus"
                else -> originalMediaInfo?.audioExtension ?: "m4a"
            }
        } else {
            "mp4"
        }

        val outputName = SettingsManager.getOutputFileName(
            requireContext(), sourceFile.name, "audio_compressed", outputExt
        )
        val outPath = java.io.File(outputDir, outputName).absolutePath

        val cmd = buildString {
            append("-y -i \"$path\" ")
            if (!isAudioOnlyInput) {
                append("-c:v copy ")
            }
            append("-c:a $audioEncoder ")
            if (audioEncoder != "copy") {
                if (audioBitrate != "copy") {
                    append("-b:a ${audioBitrate}k ")
                }
                if (audioSampleRate != "copy") {
                    append("-ar $audioSampleRate ")
                }
            }
            if (isAudioOnlyInput) {
                append("-vn ")
            }
            append("\"$outPath\"")
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.tvProgress.visibility = View.VISIBLE
        binding.tvProgress.text = getString(R.string.compressing)
        binding.btnCompress.isEnabled = false

        if (SettingsManager.isKeepScreenOn(requireContext())) {
            requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val logDialog = showFfmpegLogDialog(cmd)

        val compressJob = viewLifecycleOwner.lifecycleScope.launch {
            val totalTimeMs = originalMediaInfo?.durationMs ?: -1L
            LogCollector.d("AudioCompress", "Command: $cmd")

            logDialog.onCancel = {
                FFmpegService.cancelCurrentSession()
            }

            val result = FFmpegService.executeCommand(
                cmd,
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
                requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                logDialog.updateProgress(
                    FFmpegService.Progress(
                        percent = 100,
                        processedTimeMs = totalTimeMs.coerceAtLeast(0),
                        totalTimeMs = totalTimeMs,
                        outputSizeBytes = java.io.File(outPath).length()
                    )
                )
                logDialog.onComplete(result.success)

                if (result.success) {
                    val outFileName = outPath.substring(outPath.lastIndexOf('/') + 1)
                    FileUtils.scanFile(requireContext(), java.io.File(outPath))
                    val shootingDate = originalMediaInfo?.creationTime.orEmpty()
                    if (shootingDate.isNotEmpty()) {
                        FileUtils.applyShootingDate(outPath, shootingDate)
                    } else {
                        sourceFileTimes?.let { (creation, modified) ->
                            FileUtils.applyFileTimes(outPath, creation, modified)
                        }
                    }
                    Toast.makeText(requireContext(), getString(R.string.compress_complete, outFileName), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.compress_failed, result.error), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showFfmpegLogDialog(command: String): FfmpegLogDialog {
        val dialogView = layoutInflater.inflate(R.layout.dialog_ffmpeg_log, null)
        val tvCommand = dialogView.findViewById<TextView>(R.id.tvCommand)
        val tvLogs = dialogView.findViewById<TextView>(R.id.tvLogs)
        val btnCopy = dialogView.findViewById<Button>(R.id.btnCopy)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        tvCommand.text = command

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()

        val logBuilder = StringBuilder()

        btnCopy.setOnClickListener {
            val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("FFmpeg Log", "Command:\n$command\n\nLogs:\n$logBuilder")
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
        }

        var onCancelCallback: (() -> Unit)? = null
        val cancelHandler = Handler(Looper.getMainLooper())
        btnCancel.setOnClickListener {
            val callback = onCancelCallback
            if (callback != null) {
                callback()
                onCancelCallback = null
                btnCancel.isEnabled = false
                btnCancel.text = getString(R.string.cancelling)
                cancelHandler.postDelayed({
                    if (dialog.isShowing) {
                        btnCancel.isEnabled = true
                        btnCancel.text = getString(R.string.close)
                    }
                }, 500)
            } else {
                dialog.dismiss()
            }
        }

        dialog.show()

        return object : FfmpegLogDialog {
            override fun addLog(log: FFmpegService.LogLine) {
                logBuilder.append(log.text).append("\n")
                tvLogs.text = logBuilder.toString()
                (tvLogs.parent as? android.widget.ScrollView)?.post {
                    (tvLogs.parent as? android.widget.ScrollView)?.fullScroll(android.view.View.FOCUS_DOWN)
                }
            }
            override fun onComplete(success: Boolean) {
                onCancelCallback = null
                btnCancel.isEnabled = true
                btnCancel.text = if (success) getString(R.string.done) else getString(R.string.close)
            }
            override var onCancel: (() -> Unit)?
                get() = onCancelCallback
                set(value) { onCancelCallback = value }
            override fun updateProgress(progress: FFmpegService.Progress) {}
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
