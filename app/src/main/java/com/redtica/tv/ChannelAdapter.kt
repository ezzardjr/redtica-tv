package com.redtica.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChannelAdapter(
    private val channels: List<Channel>,
    private val onPick: (Int) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.Holder>() {

    var current = 0
        set(value) {
            val old = field
            field = value
            notifyItemChanged(old)
            notifyItemChanged(value)
        }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val num: TextView = v.findViewById(R.id.num)
        val name: TextView = v.findViewById(R.id.name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun getItemCount() = channels.size

    override fun onBindViewHolder(h: Holder, position: Int) {
        h.num.text = channels[position].num.toString()
        h.name.text = channels[position].name
        h.itemView.isSelected = position == current
        h.itemView.setOnClickListener { onPick(h.bindingAdapterPosition) }
    }
}
