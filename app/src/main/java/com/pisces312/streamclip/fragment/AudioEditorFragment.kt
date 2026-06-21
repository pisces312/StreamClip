package com.pisces312.streamclip.fragment

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.pisces312.streamclip.R
import com.pisces312.streamclip.databinding.FragmentAudioEditorBinding
import com.pisces312.streamclip.ui.AudioEditorActivity

/**
 * 音频编辑 Tab 入口页。
 * 选择音频文件或开始录音，然后跳转到编辑界面。
 */
class AudioEditorFragment : Fragment() {

    private var _binding: FragmentAudioEditorBinding? = null
    private val binding get() = _binding!!

    private var selectedLoadMode = AudioEditorActivity.LOAD_MODE_C

    private val pickAudioFile = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val intent = Intent(requireContext(), AudioEditorActivity::class.java).apply {
                putExtra(AudioEditorActivity.EXTRA_AUDIO_URI, uri.toString())
                putExtra(AudioEditorActivity.EXTRA_MODE, AudioEditorActivity.MODE_EDIT)
                putExtra(AudioEditorActivity.EXTRA_LOAD_MODE, selectedLoadMode)
            }
            startActivity(intent)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAudioEditorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup load mode dropdown
        val loadModes = listOf(
            getString(R.string.ae_load_mode_c),
            getString(R.string.ae_load_mode_b),
            getString(R.string.ae_load_mode_d),
            getString(R.string.ae_load_mode_a)
        )
        val loadModeAdapter = android.widget.ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            loadModes
        )
        binding.spinnerLoadMode.setAdapter(loadModeAdapter)
        binding.spinnerLoadMode.setText(loadModes[0], false)

        binding.spinnerLoadMode.setOnItemClickListener { _, _, position, _ ->
            selectedLoadMode = when (position) {
                0 -> AudioEditorActivity.LOAD_MODE_C
                1 -> AudioEditorActivity.LOAD_MODE_B
                2 -> AudioEditorActivity.LOAD_MODE_D
                3 -> AudioEditorActivity.LOAD_MODE_A
                else -> AudioEditorActivity.LOAD_MODE_C
            }
        }

        // Material3 + inputType="none" 时首次点击 AutoCompleteTextView 不一定弹 dropdown，显式触发
        binding.spinnerLoadMode.setOnClickListener {
            binding.spinnerLoadMode.showDropDown()
        }

        binding.btnSelectAudio.setOnClickListener {
            pickAudioFile.launch("audio/*")
        }

        binding.btnRecordAudio.setOnClickListener {
            val intent = Intent(requireContext(), AudioEditorActivity::class.java).apply {
                putExtra(AudioEditorActivity.EXTRA_MODE, AudioEditorActivity.MODE_RECORD)
            }
            startActivity(intent)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
