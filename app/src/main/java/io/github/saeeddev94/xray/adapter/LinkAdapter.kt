package io.github.saeeddev94.xray.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import io.github.saeeddev94.xray.R
import io.github.saeeddev94.xray.database.Link
import io.github.saeeddev94.xray.helper.FormatHelper

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
        private val address = view.findViewById<TextView>(R.id.linkAddress)
        private val expire = view.findViewById<TextView>(R.id.linkExpire)
        private val trafficText = view.findViewById<TextView>(R.id.linkTrafficText)
        private val trafficProgress = view.findViewById<ProgressBar>(R.id.linkTrafficProgress)
        private val announcement = view.findViewById<TextView>(R.id.linkAnnouncement)
        private val edit = view.findViewById<LinearLayout>(R.id.linkEdit)
        private val delete = view.findViewById<LinearLayout>(R.id.linkDelete)

        fun bind(index: Int) {
            val link = getItem(index)
            if (link.isActive) {
                card.setCardBackgroundColor(Color.parseColor("#1A1B24"))
                card.strokeColor = Color.parseColor("#3A75FF")
                card.strokeWidth = 2
            } else {
                card.setCardBackgroundColor(Color.parseColor("#14151C"))
                card.strokeColor = Color.parseColor("#282A38")
                card.strokeWidth = 1
            }

            name.text = link.name.ifBlank { "Подписка #${link.id}" }
            address.text = link.address

            expire.text = FormatHelper.formatExpiration(link.expire)
            trafficText.text = FormatHelper.formatTrafficUsage(link.upload, link.download, link.total)
            trafficProgress.progress = FormatHelper.calculateTrafficProgress(link.upload, link.download, link.total)

            if (!link.announcement.isNullOrBlank()) {
                announcement.text = link.announcement
                announcement.isVisible = true
            } else {
                announcement.isVisible = false
            }

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
