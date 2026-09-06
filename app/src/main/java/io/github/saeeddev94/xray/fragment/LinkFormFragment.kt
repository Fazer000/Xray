package io.github.saeeddev94.xray.fragment

import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.saeeddev94.xray.R
import io.github.saeeddev94.xray.database.Link
import java.net.URI

class LinkFormFragment(
    private val link: Link = Link(),
    private val onConfirm: () -> Unit = {},
) : BottomSheetDialogFragment() {

    private var selectedType = Link.Type.Subscription

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.layout_link_form, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val dialogTitleText = view.findViewById<TextView>(R.id.dialogTitleText)
        val typeSubscriptionBtn = view.findViewById<TextView>(R.id.typeSubscriptionBtn)
        val typeJsonBtn = view.findViewById<TextView>(R.id.typeJsonBtn)
        val addressEditText = view.findViewById<EditText>(R.id.addressEditText)
        val nameEditText = view.findViewById<EditText>(R.id.nameEditText)
        val userAgentEditText = view.findViewById<EditText>(R.id.userAgentEditText)
        val isActiveSwitch = view.findViewById<MaterialSwitch>(R.id.isActiveSwitch)
        val btnPasteUrl = view.findViewById<TextView>(R.id.btnPasteUrl)
        val btnClose = view.findViewById<TextView>(R.id.btnClose)
        val btnSave = view.findViewById<TextView>(R.id.btnSave)

        dialogTitleText.text = if (link.id == 0L) {
            getString(R.string.newLink)
        } else {
            getString(R.string.editLink)
        }

        selectedType = link.type
        updateTypeToggle(typeSubscriptionBtn, typeJsonBtn)

        typeSubscriptionBtn.setOnClickListener {
            selectedType = Link.Type.Subscription
            updateTypeToggle(typeSubscriptionBtn, typeJsonBtn)
        }

        typeJsonBtn.setOnClickListener {
            selectedType = Link.Type.Json
            updateTypeToggle(typeSubscriptionBtn, typeJsonBtn)
        }

        nameEditText.setText(link.name)
        addressEditText.setText(link.address)
        userAgentEditText.setText(link.userAgent)
        isActiveSwitch.isChecked = if (link.id == 0L) true else link.isActive

        btnPasteUrl.setOnClickListener {
            runCatching {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                if (!clipText.isNullOrBlank()) {
                    addressEditText.setText(clipText)
                    Toast.makeText(requireContext(), "Ссылка вставлена из буфера", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnClose.setOnClickListener {
            dismiss()
        }

        btnSave.setOnClickListener {
            val address = addressEditText.text.toString().trim()
            if (io.github.saeeddev94.xray.helper.HappHelper.isHappUrl(address)) {
                // Valid Happ scheme
            } else {
                val uri = runCatching { URI(address) }.getOrNull()
                if (uri == null) {
                    Toast.makeText(requireContext(), getString(R.string.invalidLink), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (uri.scheme != "https" && uri.scheme != "http") {
                    Toast.makeText(requireContext(), getString(R.string.onlyHttps), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

            link.type = selectedType
            link.name = nameEditText.text.toString().trim()
            link.address = address
            link.userAgent = userAgentEditText.text.toString().trim().ifBlank { null }
            link.isActive = isActiveSwitch.isChecked

            onConfirm()
            dismiss()
        }
    }

    private fun updateTypeToggle(subBtn: TextView, jsonBtn: TextView) {
        if (selectedType == Link.Type.Subscription) {
            subBtn.setBackgroundResource(R.drawable.bg_action_pill)
            subBtn.setTextColor(resources.getColor(android.R.color.white, null))
            jsonBtn.setBackgroundResource(android.R.color.transparent)
            jsonBtn.setTextColor(android.graphics.Color.parseColor("#8E92A8"))
        } else {
            jsonBtn.setBackgroundResource(R.drawable.bg_action_pill)
            jsonBtn.setTextColor(resources.getColor(android.R.color.white, null))
            subBtn.setBackgroundResource(android.R.color.transparent)
            subBtn.setTextColor(android.graphics.Color.parseColor("#8E92A8"))
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        activity?.finish()
    }
}
