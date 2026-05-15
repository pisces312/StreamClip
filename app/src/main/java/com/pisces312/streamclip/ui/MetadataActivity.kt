package com.pisces312.streamclip.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.pisces312.streamclip.BaseActivity
import com.pisces312.streamclip.fragment.MetadataFragment

/**
 * 独立的元数据编辑 Activity，用于接收外部"用元数据编辑打开"Intent
 */
class MetadataActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            val fragment = MetadataFragment()

            // 将外部传入的视频 URI 传给 Fragment
            intent.data?.let { uri ->
                fragment.arguments = Bundle().apply {
                    putParcelable(ARG_EXTERNAL_VIDEO_URI, uri)
                }
            }

            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, fragment)
                .commit()
        }
    }

    companion object {
        const val ARG_EXTERNAL_VIDEO_URI = "external_video_uri"
    }
}
