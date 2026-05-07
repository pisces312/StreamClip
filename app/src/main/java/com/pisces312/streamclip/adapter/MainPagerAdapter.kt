package com.pisces312.streamclip.adapter

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.pisces312.streamclip.fragment.TrimSimpleFragment
import com.pisces312.streamclip.fragment.Trim2Fragment
import com.pisces312.streamclip.fragment.MergeFragment
import com.pisces312.streamclip.fragment.ExtractFragment
import com.pisces312.streamclip.fragment.CompressFragment

class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 5

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> TrimSimpleFragment()
            1 -> Trim2Fragment()
            2 -> MergeFragment()
            3 -> ExtractFragment()
            4 -> CompressFragment()
            else -> throw IllegalArgumentException("Invalid position: $position")
        }
    }
}
