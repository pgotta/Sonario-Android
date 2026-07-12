package ai.sonario.app.summarize

/**
 * Output cleanup, ported from pipeline._clean_summary in Sonario desktop.
 * Strips model-invented citation links, em/en dashes, and trailing
 * "If you want, I can..." offers so the summary ends cleanly on its content.
 */
object Cleaner {
    private val caLink = Regex("""\[([^\]]+)\]\((?:ca|sandbox|attachment)://[^)]*\)""")
    private val bareCa = Regex("""\(?(?:ca|sandbox|attachment)://[^\s)]+\)?""")
    private val offer = Regex(
        """\n+\s*(?:if you(?:'d| would)? (?:want|like)|i can (?:also )?(?:turn|make|""" +
        """break|create|provide)|let me know|would you like|want me to)\b.*$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun clean(md: String?): String {
        if (md.isNullOrBlank()) return ""
        var s = caLink.replace(md) { it.groupValues[1] }
        s = bareCa.replace(s, "")
        s = offer.replace(s, "")
        s = tablesToBullets(s)   // tables render poorly on mobile -> bullet lists
        s = s.replace('\u2014', ',').replace('\u2013', '-') // em/en dash -> plain
        s = Regex("""\s+,""").replace(s, ",")
        s = Regex(""",\s{2,}""").replace(s, ", ")
        return s.trimEnd()
    }

    /**
     * Convert GFM Markdown tables into nested bullet lists so they render cleanly
     * on a phone (the Markdown renderer mangles tables into a flat column of
     * cells). Each data row becomes a bullet with the header labels bolded:
     *   - **Header A:** value  ·  Header B: value
     * Non-table text is passed through untouched.
     */
    private fun tablesToBullets(md: String): String {
        val lines = md.split("\n")
        val out = StringBuilder()
        var i = 0
        fun isRow(l: String) = l.trim().startsWith("|") && l.contains("|")
        fun isSep(l: String) =
            l.trim().matches(Regex("""\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)+\|?"""))
        fun cells(l: String) = l.trim().trim('|').split("|").map { it.trim() }

        while (i < lines.size) {
            val line = lines[i]
            // A table = a header row, a separator row, then >=1 data rows.
            if (i + 1 < lines.size && isRow(line) && isSep(lines[i + 1])) {
                val headers = cells(line)
                i += 2 // skip header + separator
                while (i < lines.size && isRow(lines[i]) && !isSep(lines[i])) {
                    val vals = cells(lines[i])
                    val parts = ArrayList<String>()
                    for (c in vals.indices) {
                        val h = headers.getOrNull(c)?.takeIf { it.isNotBlank() }
                        val v = vals[c]
                        if (v.isBlank()) continue
                        parts.add(if (h != null) "**$h:** $v" else v)
                    }
                    if (parts.isNotEmpty()) out.append("- ").append(parts.joinToString(" · ")).append("\n")
                    i++
                }
                out.append("\n")
            } else {
                out.append(line).append("\n")
                i++
            }
        }
        return out.toString()
    }
}
