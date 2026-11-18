package com.dualearbuds.companion.assistant

/** A help chunk with its score for one question. */
data class Hit(val chunk: HelpChunk, val score: Double)

/** The extractive answer the Ask screen shows. */
data class Answer(
    val question: String,
    /** True when the best chunk scored at or above the floor. */
    val found: Boolean,
    /** The chunk the answer comes from (the source), null when not found. */
    val source: HelpChunk?,
    /** The direct answer paragraph, or the "I don't know that one yet" line. */
    val text: String,
    val steps: List<String>,
    /** Android platform note and further paragraphs of the source chunk. */
    val notes: List<String>,
    /** Live facts that match the question ("Right now"). */
    val rightNow: List<String>,
    /** Next two chunks when found; the three closest topics when not. */
    val related: List<HelpChunk>
)

/**
 * The offline assistant: BM25 retrieval over the help chunks with synonym expansion, plus live facts matched by
 * the same scorer. No network, no ML runtime (docs/assistant-and-mcp.md §2).
 */
class Assistant(val chunks: List<HelpChunk>, synonyms: Map<String, List<String>> = emptyMap()) {
    private val synonyms = Synonyms(synonyms)
    private val index = Bm25(chunks.map { IndexDoc(it.title, it.body, it.keywords.joinToString(" ")) })

    /** Query terms after synonym expansion, stop words and stemming. */
    fun queryTerms(question: String): List<String> =
        Tokenizer.terms(synonyms.expand(Tokenizer.words(question))).distinct()

    /** [queryTerms] weighted: the user's own words 1, words only added by synonyms [SYNONYM_WEIGHT]. */
    fun weightedTerms(question: String): Map<String, Double> {
        val words = Tokenizer.words(question)
        val own = Tokenizer.terms(words).toSet()
        val out = LinkedHashMap<String, Double>()
        Tokenizer.terms(synonyms.expand(words)).forEach { t -> out[t] = if (t in own) 1.0 else SYNONYM_WEIGHT }
        return out
    }

    /** Every chunk ranked for [question], best first (ties keep file order). */
    fun search(question: String): List<Hit> {
        val terms = weightedTerms(question)
        if (terms.isEmpty() || chunks.isEmpty()) return emptyList()
        val s = index.scores(terms)
        return chunks.indices.sortedWith(compareByDescending<Int> { s[it] }.thenBy { it }).map { Hit(chunks[it], s[it]) }
    }

    /** Facts that match [question], best first, at most [MAX_FACTS_SHOWN]. */
    fun matchFacts(question: String, facts: List<LiveFact>): List<LiveFact> {
        if (facts.isEmpty()) return emptyList()
        val terms = weightedTerms(question)
        if (terms.isEmpty()) return emptyList()
        val s = Bm25(facts.map { IndexDoc("", it.text, it.keywords) }).scores(terms)
        return facts.indices.filter { s[it] >= FACT_FLOOR }
            .sortedWith(compareByDescending<Int> { s[it] }.thenBy { it })
            .take(MAX_FACTS_SHOWN).map { facts[it] }
    }

    fun ask(question: String, facts: List<LiveFact> = emptyList()): Answer {
        val q = question.trim()
        val hits = search(q)
        val rightNow = matchFacts(q, facts).map { it.text }
        val best = hits.firstOrNull()
        if (best == null || best.score < SCORE_FLOOR) {
            val closest = if (hits.isNotEmpty()) hits.take(3).map { it.chunk } else chunks.take(3)
            return Answer(q, false, null, NOT_FOUND, emptyList(), emptyList(), rightNow, closest)
        }
        return show(best.chunk, q, rightNow, hits.drop(1).filter { it.score >= SCORE_FLOOR }.take(2).map { it.chunk })
    }

    /** Shows one chunk as an answer (a tapped Related link); [related] defaults to its neighbours in the topic. */
    fun show(chunk: HelpChunk, question: String = chunk.title, rightNow: List<String> = emptyList(), related: List<HelpChunk>? = null): Answer {
        val notes = ArrayList<String>()
        chunk.androidNote?.let { notes += "On Android: $it" }
        notes += chunk.more
        val rel = related ?: chunks.filter { it.file == chunk.file && it.id != chunk.id }.take(2)
        return Answer(question, true, chunk, chunk.answer.ifEmpty { chunk.title }, chunk.steps, notes, rightNow, rel)
    }

    fun chunk(id: String): HelpChunk? = chunks.firstOrNull { it.id == id }

    /** Up to [n] chunk titles to offer as suggested questions: getting started first, then each topic's first section. */
    fun suggestions(n: Int = 4): List<String> {
        val firsts = chunks.groupBy { it.file }.values.map { it.first() }.sortedBy { if (it.file == START_FILE) 0 else 1 }
        return (firsts + chunks).map { it.title }.distinct().take(n)
    }

    companion object {
        /**
         * Below this BM25 score the best chunk isn't trusted. One distinctive word matched in a title clears it;
         * a lone common word ("phone", "sound") in a body does not.
         */
        const val SCORE_FLOOR = 2.0
        const val FACT_FLOOR = 0.3
        const val SYNONYM_WEIGHT = 0.6
        const val MAX_FACTS_SHOWN = 4
        const val START_FILE = "getting-started.md"
        const val NOT_FOUND = "I don't know that one yet. These topics are the closest:"

        fun fromFiles(files: Map<String, String>, synonymsText: String?): Assistant {
            val chunks = files.entries.sortedBy { it.key }.filter { it.key.endsWith(".md") && !it.key.startsWith("_") }
                .flatMap { (name, text) -> HelpParser.parse(name, text) }
            return Assistant(chunks, synonymsText?.let { SynonymParser.parse(it) } ?: emptyMap())
        }
    }
}
