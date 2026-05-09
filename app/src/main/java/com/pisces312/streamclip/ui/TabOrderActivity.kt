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

    private val tabInfo = mapOf(
        "trim" to Pair(R.string.title_trim, R.drawable.ic_video),
        "trim2" to Pair(R.string.title_trim2, R.drawable.ic_video),
        "merge" to Pair(R.string.title_merge, R.drawable.ic_merge),
        "extract" to Pair(R.string.title_extract, R.drawable.ic_extract),
        "compress" to Pair(R.string.title_compress, R.drawable.ic_compress),
        "custom" to Pair(R.string.title_custom, R.drawable.ic_terminal)
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

    private fun setupRecyclerView() {
        val order = TabOrderManager.getOrder(this)
        val items = order.mapNotNull { id ->
            tabInfo[id]?.let { (titleRes, iconRes) ->
                TabOrderAdapter.TabItem(id, getString(titleRes), iconRes)
            }
        }.toMutableList()

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
