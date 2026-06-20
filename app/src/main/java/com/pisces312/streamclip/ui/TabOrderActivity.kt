package com.pisces312.streamclip.ui

import android.os.Bundle
import android.widget.Toast
import com.pisces312.streamclip.BaseActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pisces312.streamclip.R
import com.pisces312.streamclip.adapter.TabOrderAdapter
import com.pisces312.streamclip.databinding.ActivityTabOrderBinding
import com.pisces312.streamclip.util.TabOrderManager

class TabOrderActivity : BaseActivity() {

    private lateinit var binding: ActivityTabOrderBinding
    private lateinit var adapter: TabOrderAdapter

    private val tabTitles = mapOf(
        "trim" to R.string.title_trim,
        "trim2" to R.string.title_trim2,
        "merge" to R.string.title_merge,
        "extract" to R.string.title_extract,
        "compress" to R.string.title_compress,
        "native_compress" to R.string.title_native_compress,
        "audio_compress" to R.string.title_audio_compress,
        "audio_editor" to R.string.title_audio_editor,
        "custom" to R.string.title_custom,
        "metadata" to R.string.title_metadata,
        "settings" to R.string.title_menu
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTabOrderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.tab_order_title)

        setupRecyclerView()
        setupButtons()
    }

    private fun getTabIdByTitle(title: String): String? {
        return tabTitles.entries.find { getString(it.value) == title }?.key
    }

    private fun setupRecyclerView() {
        val currentTabTitles = intent.getStringArrayExtra("current_tabs")
        val items = if (currentTabTitles != null) {
            currentTabTitles.mapNotNull { title ->
                val id = getTabIdByTitle(title) ?: return@mapNotNull null
                val iconRes = TabOrderManager.TAB_ICONS[id] ?: return@mapNotNull null
                TabOrderAdapter.TabItem(id, title, iconRes)
            }.toMutableList()
        } else {
            val order = TabOrderManager.getOrder(this)
            order.mapNotNull { id ->
                val titleRes = tabTitles[id] ?: return@mapNotNull null
                val iconRes = TabOrderManager.TAB_ICONS[id] ?: return@mapNotNull null
                TabOrderAdapter.TabItem(id, getString(titleRes), iconRes)
            }.toMutableList()
        }

        adapter = TabOrderAdapter(items)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                adapter.moveItem(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })
        touchHelper.attachToRecyclerView(binding.recyclerView)
    }

    private fun setupButtons() {
        binding.btnSave.setOnClickListener {
            TabOrderManager.saveOrder(this, adapter.getCurrentOrder())
            Toast.makeText(this, R.string.tab_order_saved, Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnReset.setOnClickListener {
            TabOrderManager.resetOrder(this)
            Toast.makeText(this, R.string.tab_order_reset, Toast.LENGTH_SHORT).show()
            recreate()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
