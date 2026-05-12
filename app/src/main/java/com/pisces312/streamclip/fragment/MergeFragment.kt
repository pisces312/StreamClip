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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.appcompat.app.AlertDialog
import com.pisces312.streamclip.adapter.VideoListAdapter
import com.pisces312.streamclip.databinding.FragmentMergeBinding
import com.pisces312.streamclip.model.MediaInfo
import com.pisces312.streamclip.service.FFmpegService
import com.pisces312.streamclip.util.FileUtils
import com.pisces312.streamclip.util.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MergeFragment : Fragment() {

    private var _binding: FragmentMergeBinding? = null
    private val binding get() = _binding!!
    private val videoUris = mutableListOf<Uri>()
    private lateinit var adapter: VideoListAdapter

    private val pickVideos = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.clipData?.let { clipData ->
                for (i in 0 until clipData.itemCount) {
                    val uri = clipData.getItemAt(i).uri
                    videoUris.add(uri)
                    if (i == 0) {
                        SettingsManager.setLastVideoDir(requireContext(), uri)
                    }
                }
                adapter.notifyDataSetChanged()
                updateUi()
                updateInputStatus()
            } ?: result.data?.data?.let { uri ->
                videoUris.add(uri)
                SettingsManager.setLastVideoDir(requireContext(), uri)
                adapter.notifyDataSetChanged()
                updateUi()
                updateInputStatus()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMergeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = VideoListAdapter(videoUris) { position ->
            videoUris.removeAt(position)
            adapter.notifyDataSetChanged()
            updateUi()
            updateInputStatus()
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.btnAddVideo.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    SettingsManager.getLastVideoDir(requireContext())?.let { uri ->
                        putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, uri)
                    }
                }
            }
            pickVideos.launch(intent)
        }

        binding.btnExecute.setOnClickListener {
            executeMerge()
        }
    }

    private fun updateUi() {
        binding.tvVideoCount.text = "已选择 ${videoUris.size} 个视频"
        binding.btnExecute.isEnabled = videoUris.size >= 2
    }

    private fun updateInputStatus() {
        if (videoUris.isEmpty()) {
            binding.tvStatus.visibility = View.GONE
            return
        }
        var directCount = 0
        var cacheCount = 0
        for (uri in videoUris) {
            val result = FileUtils.getPathResultFromUri(requireContext(), uri)
            if (result != null) {
                if (result.isDirectRead) directCount++ else cacheCount++
            }
        }
        binding.tvStatus.visibility = View.VISIBLE
        val parts = mutableListOf<String>()
        if (directCount > 0) parts.add("直读: $directCount")
        if (cacheCount > 0) parts.add("缓存: $cacheCount")
        if (cacheCount > 0) {
            binding.tvStatus.text = "⚠️ ${parts.joinToString(", ")}"
            binding.tvStatus.setTextColor(0xFFFF9800.toInt())
        } else {
            binding.tvStatus.text = "✅ ${parts.joinToString(", ")}"
            binding.tvStatus.setTextColor(0xFF4CAF50.toInt())
        }
    }

    private fun updateOutputStatus(outputFile: java.io.File) {
        binding.tvStatus.text = "📁 输出: ${outputFile.absolutePath}"
        binding.tvStatus.setTextColor(0xFF2196F3.toInt())
    }

    private fun executeMerge() {
        if (videoUris.size < 2) {
            Toast.makeText(requireContext(), getString(R.string.select_at_least_2), Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            binding.btnExecute.isEnabled = false
            if (SettingsManager.isKeepScreenOn(requireContext())) {
                requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            val paths = mutableListOf<String>()
            for (uri in videoUris) {
                val pathResult = FileUtils.getPathResultFromUri(requireContext(), uri)
                if (pathResult != null) {
                    paths.add(pathResult.path)
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), getString(R.string.cannot_read_video), Toast.LENGTH_SHORT).show()
                        binding.progressBar.visibility = View.GONE
                        binding.btnExecute.isEnabled = true
                        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    return@launch
                }
            }

            // Probe video info for all files
            val mediaInfos = mutableListOf<MediaInfo>()
            for (path in paths) {
                val info = FFmpegService.probeMediaInfo(path)
                if (info == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), getString(R.string.cannot_probe_video, java.io.File(path).name), Toast.LENGTH_SHORT).show()
                        binding.progressBar.visibility = View.GONE
                        binding.btnExecute.isEnabled = true
                        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    return@launch
                }
                mediaInfos.add(info)
            }

            // Check compatibility
            val firstInfo = mediaInfos[0]
            val incompatibleFiles = mutableListOf<Pair<String, List<String>>>()
            for (i in 1 until mediaInfos.size) {
                val info = mediaInfos[i]
                if (!firstInfo.isCompatibleWith(info)) {
                    val fields = firstInfo.getIncompatibleFields(info)
                    incompatibleFiles.add(java.io.File(info.path).name to fields)
                }
            }

            if (incompatibleFiles.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.btnExecute.isEnabled = true
                    requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    showIncompatibleDialog(firstInfo, incompatibleFiles)
                }
                return@launch
            }

            val firstSourceFile = java.io.File(paths[0])
            val outputDir = SettingsManager.getOutputDir(requireContext(), firstSourceFile)
            val outputName = SettingsManager.getOutputFileName(
                requireContext(),
                firstSourceFile.name,
                "merged",
                "mp4"
            )
            val outputFile = java.io.File(outputDir, outputName)

            val result = FFmpegService.mergeVideos(requireContext(), paths, outputFile.absolutePath)

            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                binding.btnExecute.isEnabled = true
                binding.progressBar.progress = 100
                requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                if (result.success) {
                    FileUtils.scanFile(requireContext(), outputFile)
                    updateOutputStatus(outputFile)
                    Toast.makeText(requireContext(), getString(R.string.merge_complete, outputFile.name), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.failed, result.error), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showIncompatibleDialog(
        firstInfo: MediaInfo,
        incompatibleFiles: List<Pair<String, List<String>>>
    ) {
        val message = buildString {
            appendLine("以下视频参数不一致，无法无损合并：")
            appendLine()
            appendLine("参考视频: ${java.io.File(firstInfo.path).name}")
            appendLine("  分辨率: ${firstInfo.resolution}")
            appendLine("  视频编码: ${firstInfo.videoCodec}")
            appendLine("  音频编码: ${firstInfo.audioCodec}")
            appendLine("  帧率: ${firstInfo.frameRate}")
            appendLine("  像素格式: ${firstInfo.pixelFormat}")
            appendLine("  旋转: ${firstInfo.rotation}°")
            appendLine()
            appendLine("不兼容视频:")
            for ((fileName, fields) in incompatibleFiles) {
                appendLine("  $fileName")
                appendLine("    差异: ${fields.joinToString(", ")}")
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.incompatible_params))
            .setMessage(message)
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
