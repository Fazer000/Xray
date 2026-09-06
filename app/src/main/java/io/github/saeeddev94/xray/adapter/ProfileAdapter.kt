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
import io.github.saeeddev94.xray.helper.LinkHelper
import io.github.saeeddev94.xray.helper.PingHelper
import io.github.saeeddev94.xray.helper.ProfileTouchHelper
import io.github.saeeddev94.xray.viewmodel.ProfileViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    companion object {
        private const val PAYLOAD_PING = "PAYLOAD_PING"
        val pingCache = ConcurrentHashMap<Long, String>()
        private val pingSemaphore = Semaphore(6)
    }

    fun pingAll() {
        val targets = ArrayList(profiles)
        scope.launch(Dispatchers.IO) {
            targets.map { profile ->
                async {
                    pingSemaphore.withPermit {
                        testPingInternal(profile)
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun testPingInternal(profile: ProfileList) {
        val hostAndPort = PingHelper.extractHostAndPort(profile.config)
        if (hostAndPort == null) {
            pingCache[profile.id] = "N/A"
            notifyPingChanged(profile.id)
            return
        }

        pingCache[profile.id] = "..."
        notifyPingChanged(profile.id)

        val ms = PingHelper.ping(hostAndPort.first, hostAndPort.second)
        val resultText = if (ms >= 0) "${ms} ms" else "Error"
        pingCache[profile.id] = resultText
        notifyPingChanged(profile.id)
    }

    private suspend fun notifyPingChanged(profileId: Long) {
        withContext(Dispatchers.Main) {
            val currentPos = profiles.indexOfFirst { it.id == profileId }
            if (currentPos != -1) {
                notifyItemChanged(currentPos, PAYLOAD_PING)
            }
        }
    }

    override fun onCreateViewHolder(container: ViewGroup, type: Int): ViewHolder {
        val item: View = LayoutInflater.from(container.context).inflate(
            R.layout.item_recycler_main, container, false
        )
        return ViewHolder(item)
    }

    override fun getItemCount(): Int {
        return profiles.size
    }

    override fun onBindViewHolder(holder: ViewHolder, index: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_PING)) {
            val profile = profiles[index]
            bindPingBtn(holder, profile)
            return
        }
        super.onBindViewHolder(holder, index, payloads)
    }

    override fun onBindViewHolder(holder: ViewHolder, index: Int) {
        val profile = profiles[index]
        val isSelected = settings.selectedProfile == profile.id

        if (isSelected) {
            holder.activeIndicator.setBackgroundResource(R.drawable.ic_dot_status_active)
            holder.profileCard.setCardBackgroundColor(Color.parseColor("#1D233A"))
            holder.profileCard.strokeColor = Color.parseColor("#3A75FF")
            holder.profileCard.strokeWidth = 2
        } else {
            holder.activeIndicator.setBackgroundResource(R.drawable.ic_dot_status_inactive)
            holder.profileCard.setCardBackgroundColor(Color.parseColor("#1A1B24"))
            holder.profileCard.strokeColor = Color.parseColor("#282A38")
            holder.profileCard.strokeWidth = 1
        }

        val cleanedName = LinkHelper.cleanServerName(profile.name)
        val protoAndAddr = PingHelper.extractProtocolAndAddress(profile.config)

        holder.profileName.text = cleanedName
        if (protoAndAddr != null) {
            holder.profileAddress.text = protoAndAddr.second
            holder.profileAddress.isVisible = true
            holder.badgeProtocol.text = protoAndAddr.first.uppercase()
        } else {
            holder.profileAddress.isVisible = false
            holder.badgeProtocol.text = "PROXY"
        }

        val isSubscriptionProfile = profile.link != null && profile.link!! > 0L
        holder.profileEdit.isVisible = !isSubscriptionProfile
        holder.profileDelete.isVisible = !isSubscriptionProfile

        bindPingBtn(holder, profile)

        holder.profilePingBtn.setOnClickListener {
            scope.launch(Dispatchers.IO) {
                testPingInternal(profile)
            }
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

    private fun bindPingBtn(holder: ViewHolder, profile: ProfileList) {
        val pingText = pingCache[profile.id] ?: "-"
        holder.profilePingBtn.text = pingText
        when {
            pingText.contains("ms") -> {
                val msVal = pingText.replace("ms", "").trim().toLongOrNull() ?: 0L
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
        var badgeProtocol: TextView = item.findViewById(R.id.badgeProtocol)
        var profilePingBtn: TextView = item.findViewById(R.id.profilePingBtn)
        var profileEdit: LinearLayout = item.findViewById(R.id.profileEdit)
        var profileDelete: LinearLayout = item.findViewById(R.id.profileDelete)
    }
}
