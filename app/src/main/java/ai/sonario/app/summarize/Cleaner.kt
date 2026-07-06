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
        s = s.replace('\u2014', ',').replace('\u2013', '-') // em/en dash -> plain
        s = Regex("""\s+,""").replace(s, ",")
        s = Regex(""",\s{2,}""").replace(s, ", ")
        return s.trimEnd()
    }
}
