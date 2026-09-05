package io.github.saeeddev94.xray.activity

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.saeeddev94.xray.BuildConfig
import io.github.saeeddev94.xray.R
import io.github.saeeddev94.xray.Settings
import io.github.saeeddev94.xray.databinding.ActivityLogsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class LogsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogsBinding
    private val settings by lazy { Settings(applicationContext) }

    companion object {
        private const val MAX_BUFFERED_LINES = (1 shl 14) - 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.logs)
        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        lifecycleScope.launch(Dispatchers.IO) { streamingLog() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_logs, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.deleteLogs -> flush()
            R.id.copyLogs -> copyToClipboard(binding.logsTextView.text.toString())
            else -> finish()
        }
        return true
    }

    private fun flush() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val logFile = settings.xrayCoreLogs()
                if (logFile.exists()) {
                    logFile.writeText("")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            withContext(Dispatchers.Main) {
                binding.logsTextView.text = ""
            }
        }
    }

    private fun copyToClipboard(text: String) {
        if (text.isBlank()) return
        try {
            val clipData = ClipData.newPlainText(null, text)
            val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboardManager.setPrimaryClip(clipData)
            Toast.makeText(applicationContext, "Logs copied", Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            error.printStackTrace()
        }
    }

    @SuppressLint("SetTextI18n")
    private suspend fun streamingLog() = withContext(Dispatchers.IO) {
        val logFile = settings.xrayCoreLogs()
        if (!logFile.exists()) {
            runCatching { logFile.createNewFile() }
        }

        try {
            java.io.RandomAccessFile(logFile, "r").use { reader ->
                var lastPointer = 0L
                if (reader.length() > 100000) {
                    lastPointer = reader.length() - 100000
                }
                reader.seek(lastPointer)

                val bufferedLogLines = arrayListOf<String>()

                while (currentCoroutineContext().isActive) {
                    val line = reader.readLine()
                    if (line != null) {
                        val utf8Line = String(line.toByteArray(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8)
                        bufferedLogLines.add(utf8Line)
                        if (bufferedLogLines.size >= 100) {
                            appendLinesToUi(bufferedLogLines)
                        }
                    } else {
                        if (bufferedLogLines.isNotEmpty()) {
                            appendLinesToUi(bufferedLogLines)
                        }
                        kotlinx.coroutines.delay(300)
                        if (reader.length() < lastPointer) {
                            lastPointer = 0L
                            reader.seek(0)
                        }
                    }
                    lastPointer = reader.filePointer
                }
            }
        } catch (e: Exception) {
            Log.e("LogsActivity", "Error reading log file", e)
        }
    }

    private suspend fun appendLinesToUi(lines: ArrayList<String>) = withContext(Dispatchers.Main) {
        val contentHeight = binding.logsTextView.height
        val scrollViewHeight = binding.logsScrollView.height
        val isScrolledToBottomAlready = (binding.logsScrollView.scrollY + scrollViewHeight) >= contentHeight * 0.95

        val newText = lines.joinToString("\n") + "\n"
        binding.logsTextView.append(newText)
        lines.clear()

        if (isScrolledToBottomAlready) {
            binding.logsScrollView.post {
                binding.logsScrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
    }
}
