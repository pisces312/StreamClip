package com.pisces312.streamclip.fragment

import com.pisces312.streamclip.R
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.pisces312.streamclip.databinding.FragmentCustomCommandBinding
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.pisces312.streamclip.service.FFmpegService
import com.pisces312.streamclip.util.LogCollector
import com.pisces312.streamclip.util.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CustomCommandFragment : Fragment() {

    enum class CommandType(val displayName: String) {
        FFMPEG("FFmpeg"),
        FFPROBE("FFprobe")
    }

    private var _binding: FragmentCustomCommandBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCustomCommandBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupCommandTypeSpinner()

        binding.btnExecute.setOnClickListener {
            executeCustomCommand()
        }
    }

    private fun setupCommandTypeSpinner() {
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            CommandType.entries.map { it.displayName }
        )
        binding.spinnerCommandType.adapter = adapter
        binding.spinnerCommandType.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                        val hint = when (CommandType.entries[position]) {
                        CommandType.FFMPEG -> getString(R.string.hint_ffmpeg)
                        CommandType.FFPROBE -> getString(R.string.hint_ffprobe)
                    }
                    binding.tvCommandHint.text = hint

                    // 填入示例命令
                    when (CommandType.entries[position]) {
                        CommandType.FFPROBE -> binding.etCommand.setText("-v quiet -print_format json -show_format -show_streams /sdcard/DCIM/Camera/video.mp4")
                        CommandType.FFMPEG -> binding.etCommand.setText("-y -i /storage/emulated/0/DCIM/Camera/VID_20260511_121703.mp4 -c:v libx264 -c:a copy /storage/emulated/0/DCIM/Camera/VID_20260511_121703_compressed_1778686459108.mp4")
                    }
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
    }

    private fun executeCustomCommand() {
        val command = binding.etCommand.text.toString().trim()
        if (command.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.enter_ffmpeg_command), Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.tvProgress.visibility = View.VISIBLE
        binding.tvProgress.text = getString(R.string.executing)
        binding.btnExecute.isEnabled = false
        if (SettingsManager.isKeepScreenOn(requireContext())) {
            requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val commandType = CommandType.entries[binding.spinnerCommandType.selectedItemPosition]
        val inputPath = parseInputPath(command)
        val outputPath = if (commandType == CommandType.FFMPEG) parseOutputPath(command) else null

        val logDialog = showFfmpegLogDialog("${commandType.displayName}: $command")

        val job = viewLifecycleOwner.lifecycleScope.launch {
            val totalTimeMs = if (inputPath != null && commandType == CommandType.FFMPEG) {
                FFmpegService.probeMediaInfo(inputPath)?.durationMs ?: -1L
            } else -1L

            if (commandType == CommandType.FFMPEG) {
                logDialog.updateProgress(FFmpegService.Progress(percent = 0, totalTimeMs = totalTimeMs))
            }

            LogCollector.d("CustomCommand", "Type=$commandType, Command: $command")

            logDialog.onCancel = {
                FFmpegService.cancelCurrentSession()
            }

            val result = when (commandType) {
                CommandType.FFMPEG -> FFmpegService.executeCommand(
                    command,
                    outputPath = outputPath,
                    totalTimeMs = totalTimeMs,
                    onProgress = { progress ->
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                            if (_binding == null) return@launch
                            val percent = if (progress.percent >= 0) progress.percent else 0
                            binding.progressBar.progress = percent
                            binding.tvProgress.text = if (progress.percent >= 0) {
                                "${progress.percent}% - ${progress.message}"
                            } else {
                                progress.message
                            }
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
                CommandType.FFPROBE -> withContext(Dispatchers.IO) {
                    val session = FFprobeKit.execute(command)
                    val success = ReturnCode.isSuccess(session.returnCode)
                    val output = session.output
                    val logs = session.allLogsAsString

                    withContext(Dispatchers.Main) {
                        logDialog.addLog(FFmpegService.LogLine("ReturnCode: ${session.returnCode}"))
                        if (output.isNotEmpty()) {
                            logDialog.addLog(FFmpegService.LogLine("=== OUTPUT ==="))
                            output.lines().forEach { logDialog.addLog(FFmpegService.LogLine(it)) }
                        }
                        if (logs.isNotEmpty() && logs != output) {
                            logDialog.addLog(FFmpegService.LogLine("=== LOGS ==="))
                            logs.lines().forEach { logDialog.addLog(FFmpegService.LogLine(it)) }
                        }
                    }

                    FFmpegService.Result(
                        success = success,
                        outputPath = null,
                        error = if (!success) (logs.ifEmpty { "FFprobe failed" }) else null
                    )
                }
            }

            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                binding.tvProgress.visibility = View.GONE
                binding.btnExecute.isEnabled = true
                binding.progressBar.progress = 100
                requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (commandType == CommandType.FFMPEG) logDialog.updateProgress(
                    FFmpegService.Progress(
                        percent = 100,
                        processedTimeMs = totalTimeMs.coerceAtLeast(0),
                        totalTimeMs = totalTimeMs,
                        outputSizeBytes = if (outputPath != null) java.io.File(outputPath).length() else 0L
                    )
                )
                logDialog.onComplete(result.success)

                if (result.success) {
                    Toast.makeText(requireContext(), getString(R.string.command_complete), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.command_failed, result.error), Toast.LENGTH_LONG).show()
                }
            }
        }

        logDialog.onCancel = {
            FFmpegService.cancelCurrentSession()
            Toast.makeText(requireContext(), R.string.cancelled, Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseInputPath(command: String): String? {
        val regex = "-i\\s+\"?([^\"\\s]+)\"?".toRegex()
        return regex.find(command)?.groupValues?.get(1)
    }

    private fun parseOutputPath(command: String): String? {
        val trimmed = command.trim()
        val lastQuote = trimmed.lastIndexOf('"')
        return if (lastQuote > 0 && trimmed.count { it == '"' } % 2 == 0) {
            val start = trimmed.lastIndexOf('"', lastQuote - 1)
            trimmed.substring(start + 1, lastQuote)
        } else {
            trimmed.substringAfterLast(' ').takeIf { it.isNotEmpty() && !it.startsWith("-") }
        }
    }

    private fun showFfmpegLogDialog(command: String, totalTimeMs: Long = -1): FfmpegLogDialog {
        val dialogView = layoutInflater.inflate(com.pisces312.streamclip.R.layout.dialog_ffmpeg_log, null)
        val tvCommand = dialogView.findViewById<TextView>(com.pisces312.streamclip.R.id.tvCommand)
        val tvLogs = dialogView.findViewById<TextView>(com.pisces312.streamclip.R.id.tvLogs)
        val btnCopy = dialogView.findViewById<Button>(com.pisces312.streamclip.R.id.btnCopy)
        val btnCancel = dialogView.findViewById<Button>(com.pisces312.streamclip.R.id.btnCancel)
        val progressBar = dialogView.findViewById<ProgressBar>(com.pisces312.streamclip.R.id.progressBar)
        val tvPercent = dialogView.findViewById<TextView>(com.pisces312.streamclip.R.id.tvPercent)
        val tvTimeInfo = dialogView.findViewById<TextView>(com.pisces312.streamclip.R.id.tvTimeInfo)
        val tvOutputSize = dialogView.findViewById<TextView>(com.pisces312.streamclip.R.id.tvOutputSize)

        tvCommand.text = command

        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()

        val startTime = System.currentTimeMillis()
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
            override fun updateProgress(progress: FFmpegService.Progress) {
                if (progress.percent >= 0) {
                    progressBar?.progress = progress.percent
                    tvPercent?.text = "${progress.percent}%"
                } else {
                    progressBar?.progress = 0
                    tvPercent?.text = "--%"
                }

                val elapsedRealMs = System.currentTimeMillis() - startTime
                val elapsedStr = formatTime(elapsedRealMs / 1000)
                val remainingStr = if (progress.processedTimeMs >= 0 && progress.percent > 0 && progress.percent < 100) {
                    formatTime((elapsedRealMs / progress.percent.toDouble() * (100 - progress.percent)).toLong() / 1000)
                } else {
                    "--:--"
                }
                tvTimeInfo?.text = "${getString(R.string.progress_elapsed)}: $elapsedStr | ${getString(R.string.progress_remaining)}: $remainingStr"

                val sizeMB = progress.outputSizeBytes / (1024.0 * 1024.0)
                tvOutputSize?.text = "${getString(R.string.progress_output)}: %.1f MB".format(sizeMB)
            }
        }
    }

    private fun formatTime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    interface FfmpegLogDialog {
        fun addLog(log: FFmpegService.LogLine)
        fun onComplete(success: Boolean)
        fun updateProgress(progress: FFmpegService.Progress)
        var onCancel: (() -> Unit)?
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
