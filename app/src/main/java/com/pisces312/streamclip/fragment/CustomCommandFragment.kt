package com.pisces312.streamclip.fragment

import com.pisces312.streamclip.R
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.pisces312.streamclip.databinding.FragmentCustomCommandBinding
import com.pisces312.streamclip.service.FFmpegService
import com.pisces312.streamclip.util.LogCollector
import com.pisces312.streamclip.util.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CustomCommandFragment : Fragment() {

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

        binding.btnExecute.setOnClickListener {
            executeCustomCommand()
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

        val logDialog = showFfmpegLogDialog(command)

        val job = viewLifecycleOwner.lifecycleScope.launch {
            LogCollector.d("CustomCommand", "Command: $command")

            val result = FFmpegService.executeCommand(
                command,
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
                    }
                },
                onLog = { logLine ->
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                        if (_binding == null) return@launch
                        logDialog?.addLog(logLine)
                    }
                }
            )

            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                binding.tvProgress.visibility = View.GONE
                binding.btnExecute.isEnabled = true
                binding.progressBar.progress = 100
                requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                logDialog?.onComplete(result.success)

                if (result.success) {
                    Toast.makeText(requireContext(), getString(R.string.command_complete), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.command_failed, result.error), Toast.LENGTH_LONG).show()
                }
            }
        }

        logDialog?.onCancel = {
            job.cancel()
            Toast.makeText(requireContext(), R.string.cancelled, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showFfmpegLogDialog(command: String): FfmpegLogDialog {
        val dialogView = layoutInflater.inflate(com.pisces312.streamclip.R.layout.dialog_ffmpeg_log, null)
        val tvCommand = dialogView.findViewById<android.widget.TextView>(com.pisces312.streamclip.R.id.tvCommand)
        val recyclerLogs = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(com.pisces312.streamclip.R.id.recyclerLogs)
        val btnCopy = dialogView.findViewById<android.widget.Button>(com.pisces312.streamclip.R.id.btnCopy)
        val btnCancel = dialogView.findViewById<android.widget.Button>(com.pisces312.streamclip.R.id.btnCancel)

        tvCommand.text = command

        val adapter = com.pisces312.streamclip.adapter.FfmpegLogAdapter()
        recyclerLogs.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        recyclerLogs.adapter = adapter

        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnCopy.setOnClickListener {
            val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("FFmpeg Log", "Command:\n$command\n\nLogs:\n${adapter.getAllLogs()}")
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
        }

        var onCancelCallback: (() -> Unit)? = null
        btnCancel.setOnClickListener {
            onCancelCallback?.invoke()
            btnCancel.isEnabled = false
            btnCancel.text = getString(R.string.cancelling)
        }

        dialog.show()

        return object : FfmpegLogDialog {
            override fun addLog(log: FFmpegService.LogLine) {
                adapter.addLog(log)
                recyclerLogs.scrollToPosition(adapter.itemCount - 1)
            }
            override fun onComplete(success: Boolean) {
                onCancelCallback = null
                btnCancel.isEnabled = true
                btnCancel.text = if (success) getString(R.string.done) else getString(R.string.close)
            }
            override var onCancel: (() -> Unit)?
                get() = onCancelCallback
                set(value) { onCancelCallback = value }
        }
    }

    interface FfmpegLogDialog {
        fun addLog(log: FFmpegService.LogLine)
        fun onComplete(success: Boolean)
        var onCancel: (() -> Unit)?
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
