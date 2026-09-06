package io.github.saeeddev94.xray.helper

import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FormatHelper {

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        val tb = gb * 1024

        val df = DecimalFormat("#.##")
        return when {
            bytes >= tb -> "${df.format(bytes / tb)} TB"
            bytes >= gb -> "${df.format(bytes / gb)} GB"
            bytes >= mb -> "${df.format(bytes / mb)} MB"
            bytes >= kb -> "${df.format(bytes / kb)} KB"
            else -> "$bytes B"
        }
    }

    fun formatTrafficUsage(upload: Long, download: Long, total: Long): String {
        val used = upload + download
        val usedStr = formatBytes(used)
        val totalStr = if (total > 0L) formatBytes(total) else "∞"
        return "$usedStr / $totalStr"
    }

    fun calculateTrafficProgress(upload: Long, download: Long, total: Long): Int {
        if (total <= 0L) return 0
        val used = upload + download
        return ((used.toDouble() / total.toDouble()) * 100).toInt().coerceIn(0, 100)
    }

    fun formatExpiration(expireTimestampSec: Long): String {
        if (expireTimestampSec <= 0L) return "Бессрочно"
        val nowSec = System.currentTimeMillis() / 1000L
        val diffSec = expireTimestampSec - nowSec
        if (diffSec <= 0) return "Истекла"

        val days = diffSec / (24 * 3600)
        val hours = (diffSec % (24 * 3600)) / 3600

        return when {
            days > 0 -> "Осталось $days дн."
            hours > 0 -> "Осталось $hours ч."
            else -> "Меньше часа"
        }
    }

    fun formatDate(timestampMs: Long): String {
        if (timestampMs <= 0L) return ""
        val sdf = SimpleDateFormat("dd.MM.yy, HH:mm", Locale.getDefault())
        return sdf.format(Date(timestampMs))
    }
}
