package ai.sonario.app.source

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.util.zip.ZipInputStream

/**
 * Extracts plain text from a local file the user picks with the system file
 * browser. Supports txt/md, pdf, epub, and docx. Everything runs on-device; the
 * extracted text then flows into the normal summarize pipeline.
 */
class FileTextExtractor(private val appContext: Context) {

    data class Chapter(val title: String, val text: String)

    data class Extracted(
        val text: String,
        val name: String,
        val error: String? = null,
        val chapters: List<Chapter> = emptyList(),
    ) {
        val ok: Boolean get() = error == null && text.isNotBlank()
        val isEpub: Boolean get() = chapters.isNotEmpty()
    }

    suspend fun extract(uri: Uri): Extracted = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val lower = name.lowercase()
        try {
            // EPUB is special: capture per-chapter structure as well as full text.
            if (lower.endsWith(".epub")) {
                val chapters = readEpubChapters(uri)
                val full = chapters.joinToString("\n\n") { it.text }
                return@withContext if (full.isBlank())
                    Extracted("", name, error = "Couldn't find readable text in this EPUB.")
                else Extracted(full.trim(), name, chapters = chapters)
            }
            val text = when {
                lower.endsWith(".txt") || lower.endsWith(".md") ||
                    lower.endsWith(".markdown") || lower.endsWith(".text") -> readPlain(uri)
                lower.endsWith(".pdf") -> readPdf(uri)
                lower.endsWith(".docx") -> readDocx(uri)
                lower.endsWith(".htm") || lower.endsWith(".html") -> readHtml(uri)
                else -> readPlain(uri)  // best-effort: treat unknown as text
            }
            if (text.isBlank())
                Extracted("", name, error = "Couldn't find readable text in this file. " +
                    "If it's a scanned PDF (images only), it has no extractable text.")
            else Extracted(text.trim(), name)
        } catch (e: Exception) {
            Extracted("", name, error = "Couldn't read this file: ${e.message}")
        }
    }

    private fun displayName(uri: Uri): String {
        var name = "document"
        appContext.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
        }
        return name
    }

    private fun readPlain(uri: Uri): String =
        appContext.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: ""

    private fun readHtml(uri: Uri): String {
        val html = readPlain(uri)
        return Jsoup.parse(html).text()
    }

    private fun readPdf(uri: Uri): String {
        PDFBoxResourceLoader.init(appContext)
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input).use { doc ->
                return PDFTextStripper().getText(doc)
            }
        }
        return ""
    }

    /**
     * EPUB = zip of XHTML content documents. Each becomes a chapter. We keep the
     * spine order (zip order is a good-enough proxy) and derive a title from the
     * first heading or the filename. Tiny fragments (nav, copyright) are skipped.
     */
    private fun readEpubChapters(uri: Uri): List<Chapter> {
        val chapters = ArrayList<Chapter>()
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val n = entry.name.lowercase()
                    if (!entry.isDirectory &&
                        (n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm"))
                    ) {
                        val html = zip.readBytes().toString(Charsets.UTF_8)
                        val doc = org.jsoup.Jsoup.parse(html)
                        val body = doc.body().text()
                        if (body.length >= 200) {   // skip nav/cover/copyright stubs
                            val heading = doc.selectFirst("h1, h2, h3")?.text()?.trim()
                            val title = heading?.takeIf { it.isNotBlank() }
                                ?: entry.name.substringAfterLast('/')
                                    .substringBeforeLast('.')
                                    .replace(Regex("""[_-]"""), " ")
                                    .replaceFirstChar { it.uppercase() }
                            chapters.add(Chapter(title, body))
                        }
                    }
                    entry = zip.nextEntry
                }
            }
        }
        // Number chapters for clarity if titles are generic/duplicated.
        return chapters.mapIndexed { i, c ->
            Chapter("Chapter ${i + 1}: ${c.title}".take(80), c.text)
        }
    }

    /** DOCX = zip; the body text lives in word/document.xml as <w:t> runs. */
    private fun readDocx(uri: Uri): String {
        var xml = ""
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        xml = zip.readBytes().toString(Charsets.UTF_8)
                        break
                    }
                    entry = zip.nextEntry
                }
            }
        }
        if (xml.isBlank()) return ""
        // Insert paragraph breaks, then pull the text of each <w:t> run.
        val withBreaks = xml
            .replace(Regex("</w:p>"), "\n")
            .replace(Regex("<w:br/?>"), "\n")
        val out = StringBuilder()
        Regex("""<w:t[^>]*>(.*?)</w:t>|\n""", RegexOption.DOT_MATCHES_ALL)
            .findAll(withBreaks)
            .forEach { m ->
                if (m.value == "\n") out.append('\n')
                else out.append(unescapeXml(m.groupValues[1]))
            }
        return out.toString()
    }

    private fun unescapeXml(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")
}
