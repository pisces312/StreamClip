package com.pisces312.streamclip.fragment

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.pisces312.streamclip.R
import com.pisces312.streamclip.databinding.FragmentMetadataBinding
import com.pisces312.streamclip.model.VideoMetadata
import com.pisces312.streamclip.service.MetadataService
import com.pisces312.streamclip.util.FileUtils
import com.pisces312.streamclip.util.LogCollector
import com.pisces312.streamclip.util.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MetadataFragment : Fragment() {

    private var _binding: FragmentMetadataBinding? = null
    private val binding get() = _binding!!

    private var originalMetadata: VideoMetadata = VideoMetadata()
    private var videoPath: String? = null

    private val videoPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { handleSelectedVideo(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMetadataBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.etVideoPath.setOnClickListener { pickVideo() }
        binding.btnSelectVideo.setOnClickListener { pickVideo() }

        binding.btnSave.setOnClickListener { saveMetadata() }
        binding.btnReset.setOnClickListener { resetFields() }

        setupFieldListeners()

        // 处理外部传入的视频 URI（通过"用元数据编辑打开"）
        arguments?.getParcelable<Uri>(com.pisces312.streamclip.ui.MetadataActivity.ARG_EXTERNAL_VIDEO_URI)?.let { uri ->
            arguments?.remove(com.pisces312.streamclip.ui.MetadataActivity.ARG_EXTERNAL_VIDEO_URI)
            view.post {
                handleExternalVideo(uri)
            }
        }
    }

    /**
     * 处理外部传入的视频（从"用元数据编辑打开"Intent）
     */
    fun handleExternalVideo(uri: Uri) {
        handleSelectedVideo(uri)
    }

    private fun pickVideo() {
        try {
            videoPicker.launch(arrayOf("video/*"))
        } catch (e: Exception) {
            LogCollector.e("MetadataFragment", "Failed to open picker: ${e.message}")
            Toast.makeText(requireContext(), R.string.cannot_open_file, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleSelectedVideo(uri: Uri) {
        val path = resolvePath(uri)
        if (path == null) {
            Toast.makeText(requireContext(), R.string.cannot_get_path, Toast.LENGTH_SHORT).show()
            return
        }

        videoPath = path
        binding.etVideoPath.setText(path)

        setLoading(true)
        val job = viewLifecycleOwner.lifecycleScope.launch {
            val result = MetadataService.readMetadata(path)
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                setLoading(false)
                if (result.success && result.data != null) {
                    originalMetadata = result.data
                    populateFields(result.data)
                    updateSaveButton()
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.metadata_read_failed, result.error),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun resolvePath(uri: Uri): String? {
        return FileUtils.getPathFromUri(requireContext(), uri)
    }

    private fun populateFields(metadata: VideoMetadata) {
        binding.etTitle.setText(metadata.title)
        binding.etArtist.setText(metadata.artist)
        binding.etCreationTime.setText(metadata.creationTime)
        binding.etLocation.setText(metadata.location)
        binding.etComment.setText(metadata.comment)
    }

    private fun getCurrentMetadata(): VideoMetadata {
        return VideoMetadata(
            title = binding.etTitle.text.toString().trim(),
            artist = binding.etArtist.text.toString().trim(),
            creationTime = binding.etCreationTime.text.toString().trim(),
            location = binding.etLocation.text.toString().trim(),
            comment = binding.etComment.text.toString().trim()
        )
    }

    private fun setupFieldListeners() {
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updateSaveButton()
            }
        }
        binding.etTitle.addTextChangedListener(watcher)
        binding.etArtist.addTextChangedListener(watcher)
        binding.etCreationTime.addTextChangedListener(watcher)
        binding.etLocation.addTextChangedListener(watcher)
        binding.etComment.addTextChangedListener(watcher)
    }

    private fun updateSaveButton() {
        val current = getCurrentMetadata()
        val hasChanges = current.isDifferentFrom(originalMetadata)
        binding.btnSave.isEnabled = hasChanges
        binding.btnReset.isEnabled = hasChanges
    }

    private fun resetFields() {
        populateFields(originalMetadata)
    }

    private fun saveMetadata() {
        val path = videoPath
        if (path == null) {
            Toast.makeText(requireContext(), R.string.please_select_video, Toast.LENGTH_SHORT).show()
            return
        }

        val current = getCurrentMetadata()
        if (!current.isDifferentFrom(originalMetadata)) {
            Toast.makeText(requireContext(), R.string.metadata_no_changes, Toast.LENGTH_SHORT).show()
            return
        }

        val outputPath = MetadataService.generateOutputPath(path)

        setLoading(true)
        if (SettingsManager.isKeepScreenOn(requireContext())) {
            requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val result = MetadataService.saveMetadata(path, outputPath, current, originalMetadata)
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                setLoading(false)
                requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                if (result.success) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.metadata_save_success, outputPath),
                        Toast.LENGTH_LONG
                    ).show()
                    // Update original to reflect saved state
                    originalMetadata = current
                    updateSaveButton()
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.metadata_save_failed, result.error),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.tvStatus.visibility = if (loading) View.VISIBLE else View.GONE
        binding.tvStatus.text = if (loading) getString(R.string.processing) else ""
        binding.btnSave.isEnabled = false
        binding.btnReset.isEnabled = false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
