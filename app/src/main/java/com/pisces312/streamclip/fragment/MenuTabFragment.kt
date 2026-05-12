package com.pisces312.streamclip.fragment

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.pisces312.streamclip.LogActivity
import com.pisces312.streamclip.MainActivity
import com.pisces312.streamclip.R
import com.pisces312.streamclip.databinding.FragmentMenuTabBinding
import com.pisces312.streamclip.ui.BatchTaskActivity
import com.pisces312.streamclip.ui.TabOrderActivity

class MenuTabFragment : Fragment() {

    private var _binding: FragmentMenuTabBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMenuTabBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val mainActivity = activity as? MainActivity

        binding.btnDonate.setOnClickListener { mainActivity?.showDonateDialog() }
        binding.btnSettings.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .addToBackStack(null)
                .commit()
        }
        binding.btnTabOrder.setOnClickListener {
            startActivity(Intent(requireContext(), TabOrderActivity::class.java))
        }
        binding.btnLogs.setOnClickListener {
            startActivity(Intent(requireContext(), LogActivity::class.java))
        }
        binding.btnBatchTasks.setOnClickListener {
            startActivity(Intent(requireContext(), BatchTaskActivity::class.java))
        }
        binding.btnHelp.setOnClickListener { mainActivity?.showGuideDialog() }
        binding.btnAbout.setOnClickListener { mainActivity?.showAboutDialog() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
