package com.pisces312.streamclip.adapter

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.pisces312.streamclip.fragment.TrimSimpleFragment
import com.pisces312.streamclip.fragment.Trim2Fragment
import com.pisces312.streamclip.fragment.MergeFragment
import com.pisces312.streamclip.fragment.ExtractFragment
import com.pisces312.streamclip.fragment.CompressFragment
import com.pisces312.streamclip.fragment.AudioCompressFragment
import com.pisces312.streamclip.fragment.CustomCommandFragment
import com.pisces312.streamclip.fragment.MetadataFragment

class MainPagerAdapter(
    activity: FragmentActivity,
    private val tabOrder: List<String>
) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = tabOrder.size

    override fun createFragment(position: Int): Fragment {
        return when (tabOrder[position]) {
            "trim" -> TrimSimpleFragment()
            "trim2" -> Trim2Fragment()
            "merge" -> MergeFragment()
            "extract" -> ExtractFragment()
            "compress" -> CompressFragment()
            "audio_compress" -> AudioCompressFragment()
            "custom" -> CustomCommandFragment()
            "metadata" -> MetadataFragment()
            else -> throw IllegalArgumentException("Invalid tab: ${tabOrder[position]}")
        }
    }
}
