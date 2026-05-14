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
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.pisces312.streamclip.LogActivity
import com.pisces312.streamclip.MainActivity
import com.pisces312.streamclip.R
import com.pisces312.streamclip.databinding.FragmentSettingsTabBinding
import com.pisces312.streamclip.ui.TabOrderActivity
import com.pisces312.streamclip.util.LocaleHelper
import com.pisces312.streamclip.util.LogCollector
import com.pisces312.streamclip.util.SettingsManager

class SettingsTabFragment : Fragment() {

    private var _binding: FragmentSettingsTabBinding? = null
    private val binding get() = _binding!!

    private val pickDir = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                requireContext().contentResolver.takePersistableUriPermission(uri, takeFlags)

                val filePath = uriToFilepath(uri)
                if (filePath != null) {
                    SettingsManager.setCustomOutputPath(requireContext(), filePath)
                    SettingsManager.setUseSourceDir(requireContext(), false)
                    updateUi()
                    Toast.makeText(requireContext(), R.string.dir_selected, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), R.string.dir_parse_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsTabBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val mainActivity = activity as? MainActivity

        updateUi()

        // 捐赠
        binding.layoutDonate.setOnClickListener {
            mainActivity?.showDonateDialog()
        }

        // 功能排序
        binding.layoutTabOrder.setOnClickListener {
            startActivity(Intent(requireContext(), TabOrderActivity::class.java))
        }

        // 使用原视频目录
        binding.switchUseSourceDir.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.setUseSourceDir(requireContext(), isChecked)
            updateUi()
        }

        // 添加时间戳
        binding.switchAddTimestamp.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.setAddTimestamp(requireContext(), isChecked)
        }

        // 执行时保持常亮
        binding.switchKeepScreenOn.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.setKeepScreenOn(requireContext(), isChecked)
        }

        // 选择自定义目录
        binding.btnSelectDir.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            pickDir.launch(intent)
        }

        // 语言设置
        binding.layoutLanguage.setOnClickListener {
            showLanguageDialog()
        }

        // 清除缓存
        binding.layoutClearCache.setOnClickListener {
            showClearCacheDialog()
        }

        // 日志
        binding.layoutLogs.setOnClickListener {
            startActivity(Intent(requireContext(), LogActivity::class.java))
        }

        // 帮助
        binding.layoutHelp.setOnClickListener { mainActivity?.showGuideDialog() }

        // 关于
        binding.layoutAbout.setOnClickListener { mainActivity?.showAboutDialog() }
    }

    private fun updateUi() {
        val context = requireContext()
        binding.switchUseSourceDir.isChecked = SettingsManager.isUseSourceDir(context)
        binding.switchAddTimestamp.isChecked = SettingsManager.isAddTimestamp(context)
        binding.switchKeepScreenOn.isChecked = SettingsManager.isKeepScreenOn(context)

        val path = when {
            SettingsManager.isUseSourceDir(context) -> getString(R.string.same_as_source)
            else -> SettingsManager.getCustomOutputPath(context) ?: getString(R.string.not_set)
        }
        binding.tvCurrentPath.text = path

        val useSourceDir = SettingsManager.isUseSourceDir(context)
        binding.layoutCustomDir.visibility = if (useSourceDir) View.GONE else View.VISIBLE

        val cacheSize = SettingsManager.getCacheSize(context)
        binding.tvClearCache.text = "${getString(R.string.clear_cache)}（${getString(R.string.cache_size, SettingsManager.formatSize(cacheSize))}）"

        updateLanguageDisplay()
    }

    private fun updateLanguageDisplay() {
        val language = LocaleHelper.getLanguage(requireContext())
        val displayText = when (language) {
            LocaleHelper.LANGUAGE_ZH -> getString(R.string.language_zh)
            LocaleHelper.LANGUAGE_EN -> getString(R.string.language_en)
            else -> getString(R.string.language_follow_system)
        }
        binding.tvLanguageValue.text = displayText
    }

    private fun showLanguageDialog() {
        val options = arrayOf(
            getString(R.string.language_follow_system),
            getString(R.string.language_zh),
            getString(R.string.language_en)
        )
        val values = arrayOf(LocaleHelper.FOLLOW_SYSTEM, LocaleHelper.LANGUAGE_ZH, LocaleHelper.LANGUAGE_EN)
        val currentLanguage = LocaleHelper.getLanguage(requireContext())
        val checkedItem = values.indexOf(currentLanguage).coerceAtLeast(0)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.language)
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                val selected = values[which]
                if (selected != LocaleHelper.getLanguage(requireContext())) {
                    LocaleHelper.setLanguage(requireContext(), selected)
                    requireActivity().recreate()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showClearCacheDialog() {
        val context = requireContext()
        val cacheSize = SettingsManager.getCacheSize(context)

        AlertDialog.Builder(context)
            .setTitle(R.string.clear_cache)
            .setMessage(getString(R.string.clear_cache_confirm, SettingsManager.formatSize(cacheSize)))
            .setPositiveButton(R.string.clear) { _, _ ->
                SettingsManager.clearCache(context)
                updateUi()
                Toast.makeText(context, R.string.cache_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun uriToFilepath(uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        if (uri.scheme != "content") return null

        if (uri.authority == "com.android.externalstorage.documents") {
            val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
            val parts = docId.split(":")
            if (parts.isEmpty()) return null
            val storageId = parts[0]
            val subPath = if (parts.size > 1) parts[1] else ""
            val basePath = if (storageId == "primary") "/storage/emulated/0" else "/storage/$storageId"
            return if (subPath.isNotEmpty()) "$basePath/$subPath" else basePath
        }

        return null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
