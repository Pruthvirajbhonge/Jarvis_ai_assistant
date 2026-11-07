package com.jarvis.assistant.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.R
import com.jarvis.assistant.data.model.BrowserTab

class TabAdapter(
    private var tabs: MutableList<BrowserTab>,
    private val onTabClick: (BrowserTab) -> Unit,
    private val onTabClose: (BrowserTab) -> Unit
) : RecyclerView.Adapter<TabAdapter.TabViewHolder>() {

    class TabViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val iconView: ImageView = itemView.findViewById(R.id.ivTabIcon)
        val titleView: TextView = itemView.findViewById(R.id.tvTabTitle)
        val urlView: TextView = itemView.findViewById(R.id.tvTabUrl)
        val closeBtn: ImageButton = itemView.findViewById(R.id.btnCloseTab)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_browser_tab, parent, false)
        return TabViewHolder(view)
    }

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        val tab = tabs[position]
        holder.titleView.text = tab.title
        holder.urlView.text = tab.url
        if (tab.iconResId != 0) {
            holder.iconView.setImageResource(tab.iconResId)
        } else {
            holder.iconView.setImageResource(R.drawable.ic_tabs)
        }
        holder.itemView.setOnClickListener { onTabClick(tab) }
        holder.closeBtn.setOnClickListener { onTabClose(tab) }
    }

    override fun getItemCount(): Int = tabs.size

    fun updateTabs(newTabs: List<BrowserTab>) {
        tabs.clear()
        tabs.addAll(newTabs)
        notifyDataSetChanged()
    }
}
