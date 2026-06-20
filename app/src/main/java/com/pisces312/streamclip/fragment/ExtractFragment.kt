package com.pisces312.streamclip.fragment

import com.pisces312.streamclip.R
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import com.pisces312.streamclip.databinding.FragmentExtractBinding
import com.pisces312.streamclip.service.FFmpegService
import com.pisces312.streamclip.util.FileUtils
import com.pisces312.streamclip.util.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExtractFragment : Fragment() {

    private var _binding: FragmentExtractBinding? = null
    private val binding get() = _binding!!
    private var selectedVideoUri: Uri? = null
    private var mediaInfo: com.pisces312.streamclip.model.MediaInfo? = null

    private val pickVideo = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                selectedVideoUri = uri
                SettingsManager.setLastVideoDir(requireContext(), uri)
                val fileName = getFileName(uri)
                binding.tvFileName.text = fileName
                binding.btnExecute.isEnabled = true
                updateInputStatus(uri)
                probeMediaInfo(uri)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExtractBinding.inflate(inflater, container, false)
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
            executeExtract()
        }

        // 处理外部传入的视频 URI（通过"用音频提取打开"）
        val externalUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arguments?.getParcelable(com.pisces312.streamclip.ui.ExtractActivity.ARG_EXTERNAL_VIDEO_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            arguments?.getParcelable(com.pisces312.streamclip.ui.ExtractActivity.ARG_EXTERNAL_VIDEO_URI)
        }
        externalUri?.let { uri ->
            arguments?.remove(com.pisces312.streamclip.ui.ExtractActivity.ARG_EXTERNAL_VIDEO_URI)
            view.post {
                handleExternalVideo(uri)
            }
        }
    }

    /**
     * 处理外部传入的视频（从"用音频提取打开"Intent）
     */
    fun handleExternalVideo(uri: Uri) {
        selectedVideoUri = uri
        SettingsManager.setLastVideoDir(requireContext(), uri)
        val fileName = getFileName(uri)
        binding.tvFileName.text = fileName
        binding.btnExecute.isEnabled = true
        updateInputStatus(uri)
        probeMediaInfo(uri)
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

    /**
     * 用 ffprobe 获取音频信息并显示
     */
    private fun probeMediaInfo(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val pathResult = FileUtils.getPathResultFromUri(requireContext(), uri) ?: return@launch
            val info = withContext(Dispatchers.IO) {
                FFmpegService.probeMediaInfo(pathResult.path)
            }
            if (info != null) {
                mediaInfo = info
                val audio = info.audio
                if (audio != null) {
                    binding.tvAudioInfo.visibility = View.VISIBLE
                    binding.tvAudioInfo.text = "🎵 音频: ${audio.codec} | ${info.audioSampleRate}Hz | ${audio.channelLayout}"
                }
            }
        }
    }

    private fun executeExtract() {
        val uri = selectedVideoUri ?: run {
            Toast.makeText(requireContext(), getString(R.string.please_select_video), Toast.LENGTH_SHORT).show()
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

            // Use extension from probed info
            val extension = mediaInfo?.audioExtension ?: "aac"

            val outputDir = SettingsManager.getOutputDir(requireContext(), sourceFile)
            val outputName = SettingsManager.getOutputFileName(
                requireContext(),
                sourceFile.name,
                "audio",
                extension
            )
            val outputFile = java.io.File(outputDir, outputName)

            val result = FFmpegService.extractAudio(requireContext(), inputPath, outputFile.absolutePath)

            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                binding.btnExecute.isEnabled = true
                binding.progressBar.progress = 100
                requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                if (result.success) {
                    FileUtils.scanFile(requireContext(), outputFile)
                    updateOutputStatus(outputFile)
                    Toast.makeText(requireContext(), getString(R.string.extract_complete, outputFile.name), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.failed, result.error), Toast.LENGTH_LONG).show()
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
