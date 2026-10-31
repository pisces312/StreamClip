package com.pisces312.streamclip.util

import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.pisces312.streamclip.R
import com.pisces312.streamclip.databinding.DialogOutputResultBinding
import java.io.File

/**
 * 处理成功弹窗：标题 + 文件名 + 绝对路径 + 打开视频 / 所在位置 / 复制路径 / 分享。
 *
 * 弹窗不自动关闭，用户可以直接接着分享或定位，返回后仍可再次操作。
 */
object ResultDialogHelper {

    fun show(context: Context, title: String, file: File) {
        val binding = DialogOutputResultBinding.inflate(LayoutInflater.from(context))
        binding.tvResultTitle.text = title
        binding.tvResultFileName.text = file.name
        binding.tvResultPath.text = file.absolutePath

        val dialog = AlertDialog.Builder(context)
            .setView(binding.root)
            .setNegativeButton(R.string.close, null)
            .create()

        binding.btnResultOpenVideo.setOnClickListener {
            FileOpenHelper.openVideo(context, file)
        }
        binding.btnResultOpenLocation.setOnClickListener {
            FileOpenHelper.revealWithFeedback(context, file)
        }
        binding.btnResultCopyPath.setOnClickListener {
            FileOpenHelper.copyPath(context, file.absolutePath)
        }
        binding.btnResultShare.setOnClickListener {
            FileOpenHelper.shareVideo(context, file)
        }

        dialog.show()
    }
}
