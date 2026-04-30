package com.pisces312.streamclip.fragment

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.pisces312.streamclip.databinding.FragmentSettingsBinding
import com.pisces312.streamclip.util.SettingsManager

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val pickDir = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                // 持久化权限
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                requireContext().contentResolver.takePersistableUriPermission(uri, takeFlags)

                // 保存路径
                SettingsManager.setCustomOutputPath(requireContext(), uri.toString())
                SettingsManager.setUseSourceDir(requireContext(), false)

                updateUi()
                Toast.makeText(requireContext(), "已选择输出目录", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 加载当前设置
        updateUi()

        // 使用原视频目录
        binding.switchUseSourceDir.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.setUseSourceDir(requireContext(), isChecked)
            updateUi()
        }

        // 添加时间戳
        binding.switchAddTimestamp.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.setAddTimestamp(requireContext(), isChecked)
        }

        // 选择自定义目录
        binding.btnSelectDir.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            pickDir.launch(intent)
        }

        // 打开输出目录
        binding.btnOpenDir.setOnClickListener {
            openOutputDir()
        }

        // 清除缓存
        binding.btnClearCache.setOnClickListener {
            showClearCacheDialog()
        }
    }

    private fun updateUi() {
        val context = requireContext()
        binding.switchUseSourceDir.isChecked = SettingsManager.isUseSourceDir(context)
        binding.switchAddTimestamp.isChecked = SettingsManager.isAddTimestamp(context)

        // 显示当前输出路径
        val path = when {
            SettingsManager.isUseSourceDir(context) -> "与原视频相同目录"
            else -> SettingsManager.getCustomOutputPath(context) ?: "未设置"
        }
        binding.tvCurrentPath.text = path

        // 自定义目录选择按钮状态
        binding.btnSelectDir.isEnabled = !SettingsManager.isUseSourceDir(context)

        // 显示缓存大小
        val cacheSize = SettingsManager.getCacheSize(context)
        binding.tvCacheSize.text = "缓存大小: ${SettingsManager.formatSize(cacheSize)}"
    }

    private fun openOutputDir() {
        val context = requireContext()
        val path = when {
            SettingsManager.isUseSourceDir(context) -> {
                Toast.makeText(context, "使用原视频目录，请自行前往查看", Toast.LENGTH_SHORT).show()
                return
            }
            else -> SettingsManager.getCustomOutputPath(context)
        }

        if (path.isNullOrEmpty()) {
            Toast.makeText(context, "未设置输出目录", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri = Uri.parse(path)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "resource/folder")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开目录: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showClearCacheDialog() {
        val context = requireContext()
        val cacheSize = SettingsManager.getCacheSize(context)

        AlertDialog.Builder(context)
            .setTitle("清除缓存")
            .setMessage("当前缓存大小: ${SettingsManager.formatSize(cacheSize)}\n\n确定要清除所有缓存和日志吗？")
            .setPositiveButton("清除") { _, _ ->
                SettingsManager.clearCache(context)
                updateUi()
                Toast.makeText(context, "缓存已清除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}