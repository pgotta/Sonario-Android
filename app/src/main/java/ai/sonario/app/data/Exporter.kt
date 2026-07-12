package ai.sonario.app.data

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import java.io.OutputStream

/**
 * Writes summary text to a user-chosen file via the Storage Access Framework.
 * The caller obtains a destination Uri from ACTION_CREATE_DOCUMENT (the system
 * file picker) and hands it here along with the format.
 */
object Exporter {

    enum class Format(val mime: String, val ext: String) {
        TXT("text/plain", "txt"),
        MD("text/markdown", "md"),
        PDF("application/pdf", "pdf"),
    }

    fun write(context: Context, uri: Uri, format: Format, title: String, body: String) {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            when (format) {
                Format.TXT -> writeText(out, stripMarkdown(title, body))
                Format.MD -> writeText(out, "# $title\n\n$body")
                Format.PDF -> writePdf(out, title, stripMarkdown("", body))
            }
        }
    }

    private fun writeText(out: OutputStream, text: String) {
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** Very light Markdown -> plain text for TXT and PDF output. */
    private fun stripMarkdown(title: String, body: String): String {
        var s = body
        s = Regex("""(?m)^#{1,6}\s*""").replace(s, "")   // headers
        s = Regex("""\*\*(.*?)\*\*""").replace(s, "$1")   // bold
        s = Regex("""\*(.*?)\*""").replace(s, "$1")       // italic
        s = Regex("""`(.*?)`""").replace(s, "$1")         // inline code
        s = Regex("""(?m)^\s*[-*]\s+""").replace(s, "• ") // bullets
        s = Regex("""\[(.*?)\]\((.*?)\)""").replace(s, "$1") // links
        return if (title.isBlank()) s.trim() else "$title\n\n${s.trim()}"
    }

    private fun writePdf(out: OutputStream, title: String, text: String) {
        val pageWidth = 595   // A4 @ 72dpi
        val pageHeight = 842
        val margin = 40f
        val doc = PdfDocument()

        val titlePaint = Paint().apply { textSize = 16f; isFakeBoldText = true }
        val bodyPaint = Paint().apply { textSize = 11f }
        val lineHeight = 16f
        val maxWidth = pageWidth - margin * 2

        // Wrap the whole document (title + body) into lines that fit the width.
        val lines = ArrayList<Pair<String, Paint>>()
        wrap(title, titlePaint, maxWidth).forEach { lines.add(it to titlePaint) }
        lines.add("" to bodyPaint) // blank line after title
        for (para in text.split("\n")) {
            if (para.isBlank()) { lines.add("" to bodyPaint); continue }
            wrap(para, bodyPaint, maxWidth).forEach { lines.add(it to bodyPaint) }
        }

        var pageNum = 1
        var page = doc.startPage(
            PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
        var canvas = page.canvas
        var y = margin + lineHeight

        for ((line, paint) in lines) {
            if (y > pageHeight - margin) {
                doc.finishPage(page)
                pageNum++
                page = doc.startPage(
                    PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
                canvas = page.canvas
                y = margin + lineHeight
            }
            if (line.isNotEmpty()) canvas.drawText(line, margin, y, paint)
            y += lineHeight
        }
        doc.finishPage(page)
        doc.writeTo(out)
        doc.close()
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(" ")
        val lines = ArrayList<String>()
        var cur = StringBuilder()
        for (w in words) {
            val trial = if (cur.isEmpty()) w else "$cur $w"
            if (paint.measureText(trial) > maxWidth && cur.isNotEmpty()) {
                lines.add(cur.toString()); cur = StringBuilder(w)
            } else {
                cur = StringBuilder(trial)
            }
        }
        if (cur.isNotEmpty()) lines.add(cur.toString())
        return lines
    }

    /** A safe default filename for a summary export. */
    fun suggestedName(title: String, format: Format): String {
        val base = title.ifBlank { "summary" }
            .replace(Regex("""[^A-Za-z0-9 _-]"""), "")
            .trim().replace(Regex("""\s+"""), "_")
            .take(60)
        return "$base.${format.ext}"
    }
}
