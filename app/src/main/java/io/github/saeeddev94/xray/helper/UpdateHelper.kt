package io.github.saeeddev94.xray.helper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.saeeddev94.xray.BuildConfig
import io.github.saeeddev94.xray.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class UpdateHelper(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    data class ReleaseAsset(
        val name: String,
        val downloadUrl: String,
        val size: Long,
    )

    data class ReleaseInfo(
        val tagName: String,
        val title: String,
        val body: String,
        val apkAsset: ReleaseAsset,
    )

    fun checkUpdate(manual: Boolean = true, repository: String = "Fazer000/Xray") {
        if (manual) {
            Toast.makeText(context, context.getString(R.string.checkingUpdate), Toast.LENGTH_SHORT).show()
        }

        scope.launch(Dispatchers.IO) {
            val url = "https://api.github.com/repos/$repository/releases/latest"
            try {
                val jsonStr = HttpHelper.get(url)
                val json = JSONObject(jsonStr)
                val tagName = json.optString("tag_name", "").trim()
                val title = json.optString("name", tagName).ifBlank { tagName }
                val body = json.optString("body", "")
                val assetsArray = json.optJSONArray("assets")

                var apkAsset: ReleaseAsset? = null
                if (assetsArray != null) {
                    val apkAssets = mutableListOf<ReleaseAsset>()
                    for (i in 0 until assetsArray.length()) {
                        val assetObj = assetsArray.getJSONObject(i)
                        val name = assetObj.optString("name", "")
                        val downloadUrl = assetObj.optString("browser_download_url", "")
                        val size = assetObj.optLong("size", 0L)
                        if (name.lowercase().endsWith(".apk") && downloadUrl.isNotBlank()) {
                            apkAssets.add(ReleaseAsset(name, downloadUrl, size))
                        }
                    }
                    apkAsset = findBestApk(apkAssets)
                }

                val isNewer = isNewerVersion(tagName, BuildConfig.VERSION_NAME)

                withContext(Dispatchers.Main) {
                    if (isNewer && apkAsset != null) {
                        val releaseInfo = ReleaseInfo(tagName, title, body, apkAsset)
                        showUpdateDialog(releaseInfo)
                    } else if (manual) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.noUpdateAvailable),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (manual) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.downloadFailed, e.message ?: "Unknown error"),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private fun showUpdateDialog(releaseInfo: ReleaseInfo) {
        val titleText = context.getString(R.string.updateAvailable, releaseInfo.tagName)
        val messageText = if (releaseInfo.body.isNotBlank()) {
            releaseInfo.body
        } else {
            releaseInfo.title
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(titleText)
            .setMessage(messageText)
            .setPositiveButton(R.string.btnUpdate) { _, _ ->
                downloadAndInstall(releaseInfo.apkAsset)
            }
            .setNegativeButton(R.string.btnCancel, null)
            .show()
    }

    private fun downloadAndInstall(asset: ReleaseAsset) {
        val downloadDir = context.getExternalFilesDir(null) ?: context.cacheDir
        val apkFile = File(downloadDir, "update.apk")
        if (apkFile.exists()) {
            apkFile.delete()
        }

        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_update_progress, null)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.updateProgressBar)
        val percentView = dialogView.findViewById<TextView>(R.id.updateProgressPercent)

        val progressDialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.downloadingUpdate)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        progressDialog.show()

        DownloadHelper(scope, asset.downloadUrl, apkFile, object : DownloadHelper.DownloadListener {
            override fun onProgress(progress: Int) {
                progressBar.progress = progress
                percentView.text = "$progress%"
            }

            override fun onError(exception: Exception) {
                progressDialog.dismiss()
                Toast.makeText(
                    context,
                    context.getString(R.string.downloadFailed, exception.message ?: "Unknown error"),
                    Toast.LENGTH_SHORT
                ).show()
            }

            override fun onComplete() {
                progressDialog.dismiss()
                installApk(apkFile)
            }
        }).start()
    }

    fun installApk(apkFile: File) {
        if (!apkFile.exists()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.checkUpdate)
                    .setMessage(R.string.installPermissionRequired)
                    .setPositiveButton(R.string.settings) { _, _ ->
                        val intent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    }
                    .setNegativeButton(R.string.btnCancel, null)
                    .show()
                return
            }
        }

        try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                context,
                "Error launching installer: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    companion object {
        fun isNewerVersion(latestVersion: String, currentVersion: String): Boolean {
            val cleanLatest = latestVersion.trim().removePrefix("v").removePrefix("V")
            val cleanCurrent = currentVersion.trim().removePrefix("v").removePrefix("V")

            val latestParts = cleanLatest.split("-")[0].split(".").mapNotNull { it.toIntOrNull() }
            val currentParts = cleanCurrent.split("-")[0].split(".").mapNotNull { it.toIntOrNull() }

            val maxLength = maxOf(latestParts.size, currentParts.size)
            for (i in 0 until maxLength) {
                val latestNum = latestParts.getOrElse(i) { 0 }
                val currentNum = currentParts.getOrElse(i) { 0 }
                if (latestNum > currentNum) return true
                if (latestNum < currentNum) return false
            }
            return false
        }
    }
}
