package io.github.saeeddev94.xray.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import io.github.saeeddev94.xray.R
import io.github.saeeddev94.xray.database.Link

class LinkAdapter : ListAdapter<Link, LinkAdapter.LinkHolder>(diffCallback) {

    var onEditClick: (link: Link) -> Unit = {}
    var onDeleteClick: (link: Link) -> Unit = {}

    override fun onCreateViewHolder(parent: ViewGroup, type: Int) = LinkHolder(
        LayoutInflater.from(parent.context).inflate(
            R.layout.layout_link_item, parent, false
        )
    )

    override fun onBindViewHolder(holder: LinkHolder, position: Int) {
        holder.bind(position)
    }

    inner class LinkHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val card = view.findViewById<MaterialCardView>(R.id.linkCard)
        private val name = view.findViewById<TextView>(R.id.linkName)
        private val type = view.findViewById<TextView>(R.id.linkType)
        private val edit = view.findViewById<LinearLayout>(R.id.linkEdit)
        private val delete = view.findViewById<LinearLayout>(R.id.linkDelete)

        fun bind(index: Int) {
            val link = getItem(index)
            if (link.isActive) {
                card.setCardBackgroundColor(Color.parseColor("#1E293B"))
                card.strokeColor = Color.parseColor("#334155")
            } else {
                card.setCardBackgroundColor(Color.parseColor("#0F172A"))
                card.strokeColor = Color.parseColor("#1E293B")
            }
            name.text = link.name
            type.text = link.type.name
            edit.setOnClickListener { onEditClick(link) }
            delete.setOnClickListener { onDeleteClick(link) }
        }
    }

    companion object {
        private val diffCallback = object : DiffUtil.ItemCallback<Link>() {
            override fun areItemsTheSame(oldItem: Link, newItem: Link): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Link, newItem: Link): Boolean =
                oldItem == newItem
        }
    }
}
