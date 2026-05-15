package com.pisces312.streamclip.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.pisces312.streamclip.BaseActivity
import com.pisces312.streamclip.fragment.TrimSimpleFragment

/**
 * 独立的视频剪辑 Activity，用于接收外部"用视频剪辑打开"Intent
 */
class TrimActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            val fragment = TrimSimpleFragment()

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
