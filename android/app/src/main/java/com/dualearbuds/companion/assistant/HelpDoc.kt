package com.dualearbuds.companion.assistant

/**
 * One retrievable piece of help: a `##` section of a docs/help topic file (docs/assistant-and-mcp.md §1).
 * [answer] is the short direct answer (the first paragraph), [steps] the numbered steps, [androidNote] the
 * Android part of a "Platform notes:" line, [more] any other paragraphs.
 */
data class HelpChunk(
    /** "file.md#index", stable while the help set doesn't change. */
    val id: String,
    val file: String,
    /** The file's `#` topic title. */
    val topic: String,
    /** The section's `##` heading (a question). */
    val title: String,
    /** Everything under the heading, as written. */
    val body: String,
    val keywords: List<String>,
    val answer: String,
    val steps: List<String>,
    val androidNote: String?,
    val more: List<String>
)

/** Parses help Markdown files in the docs/help format. Pure; never throws on odd input. */
object HelpParser {
    private val KEYWORDS = Regex("""<!--\s*keywords\s*:(.*?)-->""", RegexOption.IGNORE_CASE)
    private val STEP = Regex("""^\s*(\d+)[.)]\s+(.*)$""")
    private val BULLET = Regex("""^\s*[-*]\s+(.*)$""")
    private val PLATFORM = Regex("""^\s*Platform notes?\s*:\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val PLATFORM_PART = Regex("""\*\*\s*([A-Za-z ]+?)\s*:\s*\*\*""")

    fun parse(fileName: String, text: String): List<HelpChunk> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var topic = fileName.removeSuffix(".md").replace('-', ' ').replaceFirstChar { it.uppercase() }
        val keywords = ArrayList<String>()
        KEYWORDS.findAll(text).forEach { m ->
            m.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { keywords += it }
        }
        val out = ArrayList<HelpChunk>()
        var title: String? = null
        val body = ArrayList<String>()
        fun flush() {
            val t = title ?: return
            out += chunk(fileName, out.size, topic, t, body.toList(), keywords.toList())
            body.clear()
        }
        for (raw in lines) {
            val line = raw.trimEnd()
            when {
                line.startsWith("## ") -> {
                    flush()
                    title = line.removePrefix("## ").trim()
                }
                line.startsWith("# ") && title == null -> topic = line.removePrefix("# ").trim()
                KEYWORDS.containsMatchIn(line) -> Unit
                title != null -> body += line
            }
        }
        flush()
        return out
    }

    private fun chunk(file: String, index: Int, topic: String, title: String, body: List<String>, keywords: List<String>): HelpChunk {
        val paragraphs = ArrayList<String>()
        val steps = ArrayList<String>()
        var note: String? = null
        val current = StringBuilder()
        fun endParagraph() {
            if (current.isNotBlank()) paragraphs += current.toString().trim()
            current.setLength(0)
        }
        for (line in body) {
            val step = STEP.find(line)
            val bullet = BULLET.find(line)
            val platform = PLATFORM.find(line)
            when {
                line.isBlank() -> endParagraph()
                platform != null -> {
                    endParagraph()
                    note = platformPart(platform.groupValues[1], "Android")
                }
                step != null -> {
                    endParagraph()
                    steps += clean(step.groupValues[2])
                }
                bullet != null && steps.isNotEmpty() -> steps += clean(bullet.groupValues[1])
                else -> {
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(line.trim())
                }
            }
        }
        endParagraph()
        val cleaned = paragraphs.map { clean(it) }.filter { it.isNotEmpty() }
        return HelpChunk(
            id = "$file#$index",
            file = file,
            topic = topic,
            title = title,
            body = body.joinToString("\n").trim(),
            keywords = keywords,
            answer = cleaned.firstOrNull().orEmpty(),
            steps = steps,
            androidNote = note,
            more = cleaned.drop(1)
        )
    }

    /** The text after `**Android:**` up to the next `**Name:**`, or null when the line has no Android part. */
    fun platformPart(line: String, platform: String): String? {
        val parts = PLATFORM_PART.findAll(line).toList()
        val i = parts.indexOfFirst { it.groupValues[1].trim().equals(platform, ignoreCase = true) }
        if (i < 0) return null
        val start = parts[i].range.last + 1
        val end = if (i + 1 < parts.size) parts[i + 1].range.first else line.length
        return clean(line.substring(start, end)).trim().ifEmpty { null }
    }

    /** Drops Markdown emphasis and code marks; the screen shows plain text. */
    fun clean(s: String): String = s.replace("**", "").replace("`", "").replace(Regex("""(?<!\w)_(.+?)_(?!\w)"""), "$1").trim()
}

/** `docs/help/_synonyms.txt`: lines `word: alt1, alt2`; `#` comments. Keys may be phrases ("chal nahi raha"). */
object SynonymParser {
    fun parse(text: String): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        for (raw in text.replace("\r", "").split('\n')) {
            val line = raw.substringBefore('#').trim()
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            val alts = line.substring(colon + 1).split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            if (key.isEmpty() || alts.isEmpty()) continue
            out[key] = (out[key].orEmpty() + alts).distinct()
        }
        return out
    }
}
