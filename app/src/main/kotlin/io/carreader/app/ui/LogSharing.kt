package io.carreader.app.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Opens the Android share sheet with the diagnostics log: inline text when small, a file otherwise. */
object LogSharing {
    private const val INLINE_LIMIT = 60_000

    fun share(context: Context, report: String, subject: String, chooserTitle: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        if (report.length <= INLINE_LIMIT) {
            intent.putExtra(Intent.EXTRA_TEXT, report)
        } else {
            val dir = File(context.cacheDir, "logs").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
            val file = File(dir, "car-reader-log-$stamp.txt").apply { writeText(report) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.putExtra(Intent.EXTRA_TEXT, report.take(2_000))
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }
}
