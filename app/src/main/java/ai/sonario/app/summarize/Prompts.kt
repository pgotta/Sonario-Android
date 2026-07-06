package ai.sonario.app.summarize

/**
 * Prompts carried over verbatim from Sonario desktop's pipeline.py so the
 * on-device output reads like the desktop app's. Only the chunk sizes differ
 * (see SummarizeEngine), because a small on-device model has a far smaller
 * context window than a desktop Ollama model.
 *
 * The "_NO_STYLE" tail and em-dash discipline match the desktop app, which
 * strips em dashes at the provider chokepoint. Here we both instruct against
 * them and post-process in Cleaner.kt.
 */
object Prompts {

    private const val NO_STYLE =
        "\n\nDo not use em dashes or en dashes anywhere. Do not add a closing " +
        "offer of further help. Output only the requested content."

    /** Single-pass / final synthesis, skimmable one-page notes. (SUMMARY_SYSTEM) */
    val SUMMARY = (
        "You write clear, well-structured study notes from a document, transcript, or " +
        "article. Produce SKIMMABLE notes in Markdown, NOT a wall of prose. Aim for " +
        "roughly one page. Follow this structure:\n\n" +
        "**One bold sentence** at the very top giving the overall gist.\n\n" +
        "## Overview\nTwo or three sentences of context, no more.\n\n" +
        "Then 3 to 6 thematic sections, each with its own '## ' sub-headline named " +
        "for the actual topic. Do NOT use generic headers like 'Summary' or " +
        "'Key Points'. Under each sub-headline, prefer bullet points with a " +
        "**bold lead-in phrase** then a short explanation, and a compact Markdown " +
        "table when the content compares items or lists options/steps/types.\n\n" +
        "## Takeaway\nOne or two sentences on the core 'so what'.\n\n" +
        "Be faithful to the source; never invent facts. If it is a video transcript, " +
        "summarize what is said, not the act of speaking. Keep it tight and scannable."
        + NO_STYLE)

    /** Condense one chunk for later combination. (CHUNK_SYSTEM) */
    val CHUNK = (
        "You are condensing one section of a longer work so it can later be combined " +
        "with other sections into a single summary. Capture the section's key facts, " +
        "arguments, and any notable details in a tight paragraph or two. Be faithful; " +
        "do not invent. Return only the condensed notes, no preamble." + NO_STYLE)

    /** Combine section notes into the final notes. (REDUCE_SUMMARY_SYSTEM) */
    val REDUCE = (
        "You are given condensed section-notes from a single long work (a book, long " +
        "article, or long video), in order. Write ONE unified set of SKIMMABLE study " +
        "notes in Markdown, NOT a wall of prose. Aim for about one page. Structure:\n\n" +
        "**One bold sentence** at the very top giving the overall gist.\n\n" +
        "## Overview\nTwo or three sentences of context.\n\n" +
        "Then 4 to 7 thematic sections, each with its own '## ' sub-headline named for " +
        "the actual topic (not generic labels like 'Summary' or 'Key Points'). Under " +
        "each, prefer bullet points with a **bold lead-in phrase** plus a short " +
        "explanation, and use a compact Markdown table whenever the material compares " +
        "items or lists options/steps/types/examples.\n\n" +
        "## Takeaway\nOne or two sentences on the core message.\n\n" +
        "Synthesize across ALL sections, not just the first. Be faithful; never invent. " +
        "Keep it tight and scannable." + NO_STYLE)

    /** Thorough two-page prose view. (DETAILED_SUMMARY_SYSTEM) */
    val DETAILED = (
        "You write a THOROUGH, in-depth prose summary of a document, long article, or " +
        "long video transcript. This is the 'Detailed' view: the reader wants real " +
        "depth and nuance, not a quick skim. Aim for roughly TWO FULL PAGES or more " +
        "for substantial sources. Do not compress aggressively, and do not omit " +
        "important supporting detail, examples, names, numbers, or step-by-step " +
        "reasoning that appears in the source.\n\n" +
        "Write in clear, well-organized PROSE (flowing paragraphs), not bullet lists. " +
        "Use '## ' sub-headlines named for the actual topics. Start with one bold " +
        "sentence giving the overall thesis, then an '## Overview' paragraph, then the " +
        "detailed sections, and end with a '## Bottom line' paragraph. Be faithful to " +
        "the source; never invent facts. If it is a transcript, summarize what is said, " +
        "not the act of speaking." + NO_STYLE)

    /** Plain bulleted outline derived from a finished summary. (BULLETS_SYSTEM) */
    val BULLETS = (
        "Rewrite the given summary as a simplified, skimmable bulleted outline in " +
        "Markdown, plainer and shorter than the original. Use:\n\n" +
        "**A one-line takeaway** in bold at the top.\n\n" +
        "Then 5-10 top-level bullets of the most important points, each a short, " +
        "plain-language line. Use a couple of indented sub-bullets only where a point " +
        "really needs one detail. No long paragraphs and no fluff. Output ONLY the " +
        "bullets and the takeaway line; do not write any closing sentence." + NO_STYLE)
}
