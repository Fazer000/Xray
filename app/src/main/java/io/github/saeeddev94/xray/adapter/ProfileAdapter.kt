package io.github.saeeddev94.xray.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import io.github.saeeddev94.xray.R
import io.github.saeeddev94.xray.Settings
import io.github.saeeddev94.xray.dto.ProfileList
import io.github.saeeddev94.xray.helper.PingHelper
import io.github.saeeddev94.xray.helper.ProfileTouchHelper
import io.github.saeeddev94.xray.viewmodel.ProfileViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class ProfileAdapter(
    private val scope: CoroutineScope,
    private val settings: Settings,
    private val profileViewModel: ProfileViewModel,
    private val profiles: ArrayList<ProfileList>,
    private val profileSelect: (index: Int, profile: ProfileList) -> Unit,
    private val profileEdit: (profile: ProfileList) -> Unit,
    private val profileDelete: (profile: ProfileList) -> Unit,
) : RecyclerView.Adapter<ProfileAdapter.ViewHolder>(), ProfileTouchHelper.ProfileTouchCallback {

    private val pingMap = ConcurrentHashMap<Long, String>()

    fun pingAll() {
        profiles.forEach { profile ->
            testPing(profile)
        }
    }

    private fun testPing(profile: ProfileList) {
        val hostAndPort = PingHelper.extractHostAndPort(profile.config) ?: run {
            pingMap[profile.id] = "⚡️ N/A"
            val pos = profiles.indexOf(profile)
            if (pos != -1) notifyItemChanged(pos)
            return
        }

        pingMap[profile.id] = "⚡️ ..."
        val pos = profiles.indexOf(profile)
        if (pos != -1) notifyItemChanged(pos)

        scope.launch(Dispatchers.IO) {
            val ms = PingHelper.ping(hostAndPort.first, hostAndPort.second)
            val resultText = if (ms >= 0) "⚡️ ${ms}ms" else "⚡️ Error"
            pingMap[profile.id] = resultText
            withContext(Dispatchers.Main) {
                val currentPos = profiles.indexOfFirst { it.id == profile.id }
                if (currentPos != -1) notifyItemChanged(currentPos)
            }
        }
    }

    override fun onCreateViewHolder(container: ViewGroup, type: Int): ViewHolder {
        val linearLayout = LinearLayout(container.context)
        val item: View = LayoutInflater.from(container.context).inflate(
            R.layout.item_recycler_main, linearLayout, false
        )
        return ViewHolder(item)
    }

    override fun getItemCount(): Int {
        return profiles.size
    }

    override fun onBindViewHolder(holder: ViewHolder, index: Int) {
        val profile = profiles[index]
        val isSelected = settings.selectedProfile == profile.id
        
        if (isSelected) {
            holder.activeIndicator.setBackgroundResource(R.drawable.ic_dot_status_active)
            holder.profileCard.setCardBackgroundColor(Color.parseColor("#0F291E"))
            holder.profileCard.strokeColor = Color.parseColor("#10B981")
            holder.profileCard.strokeWidth = 2
        } else {
            holder.activeIndicator.setBackgroundResource(R.drawable.ic_dot_status_inactive)
            holder.profileCard.setCardBackgroundColor(Color.parseColor("#1E293B"))
            holder.profileCard.strokeColor = Color.parseColor("#334155")
            holder.profileCard.strokeWidth = 1
        }

        val fullTitle = profile.name
        val bracketStart = fullTitle.indexOf('[')
        val bracketEnd = fullTitle.lastIndexOf(']')

        if (bracketStart != -1 && bracketEnd > bracketStart) {
            val mainTitle = fullTitle.substring(0, bracketStart).trim()
            val bracketDetails = fullTitle.substring(bracketStart + 1, bracketEnd).trim()
            holder.profileName.text = if (mainTitle.isNotBlank()) mainTitle else fullTitle
            holder.profileAddress.text = bracketDetails
            holder.profileAddress.isVisible = true
        } else {
            holder.profileName.text = fullTitle
            val protoAndAddr = PingHelper.extractProtocolAndAddress(profile.config)
            if (protoAndAddr != null) {
                holder.profileAddress.text = "${protoAndAddr.first} • ${protoAndAddr.second}"
                holder.profileAddress.isVisible = true
            } else {
                holder.profileAddress.isVisible = false
            }
        }

        val pingText = pingMap[profile.id] ?: "⚡️ -"
        holder.profilePingBtn.text = pingText
        when {
            pingText.contains("ms") -> {
                val msVal = pingText.removePrefix("⚡️ ").removeSuffix("ms").trim().toLongOrNull() ?: 0L
                holder.profilePingBtn.setTextColor(
                    when {
                        msVal < 150 -> Color.parseColor("#10B981")
                        msVal < 300 -> Color.parseColor("#F59E0B")
                        else -> Color.parseColor("#EF4444")
                    }
                )
            }
            pingText.contains("Error") -> holder.profilePingBtn.setTextColor(Color.parseColor("#EF4444"))
            pingText.contains("...") -> holder.profilePingBtn.setTextColor(Color.parseColor("#94A3B8"))
            else -> holder.profilePingBtn.setTextColor(Color.parseColor("#38BDF8"))
        }

        holder.profilePingBtn.setOnClickListener {
            testPing(profile)
        }

        holder.profileCard.setOnClickListener {
            profileSelect(index, profile)
        }
        holder.profileEdit.setOnClickListener {
            profileEdit(profile)
        }
        holder.profileDelete.setOnClickListener {
            profileDelete(profile)
        }
    }

    override fun onItemMoved(fromPosition: Int, toPosition: Int): Boolean {
        profiles.add(toPosition, profiles.removeAt(fromPosition))
        notifyItemMoved(fromPosition, toPosition)
        if (toPosition > fromPosition) {
            notifyItemRangeChanged(fromPosition, toPosition - fromPosition + 1)
        } else {
            notifyItemRangeChanged(toPosition, fromPosition - toPosition + 1)
        }
        return true
    }

    override fun onItemMoveCompleted(startPosition: Int, endPosition: Int) {
        val isMoveUp = startPosition > endPosition
        val start = if (isMoveUp) profiles[endPosition + 1] else profiles[endPosition - 1]
        val end = profiles[endPosition]
        scope.launch {
            if (isMoveUp) profileViewModel.moveUp(start.index, end.index, end.id)
            else profileViewModel.moveDown(start.index, end.index, end.id)
        }
    }

    class ViewHolder(item: View) : RecyclerView.ViewHolder(item) {
        var activeIndicator: View = item.findViewById(R.id.activeIndicator)
        var profileCard: MaterialCardView = item.findViewById(R.id.profileCard)
        var profileName: TextView = item.findViewById(R.id.profileName)
        var profileAddress: TextView = item.findViewById(R.id.profileAddress)
        var profilePingBtn: TextView = item.findViewById(R.id.profilePingBtn)
        var profileEdit: LinearLayout = item.findViewById(R.id.profileEdit)
        var profileDelete: LinearLayout = item.findViewById(R.id.profileDelete)
    }
}
