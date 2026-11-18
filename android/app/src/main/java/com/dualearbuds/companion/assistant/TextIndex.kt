package com.dualearbuds.companion.assistant

import kotlin.math.ln

/**
 * Tokenising for the Ask screen (docs/assistant-and-mcp.md §2): lowercase, split on anything that isn't a
 * letter or digit, drop stop words, light English stemming.
 */
object Tokenizer {
    /** English and Roman Urdu filler. "no", "not", "nahi" stay: they change the meaning ("no sound"). */
    val STOP_WORDS: Set<String> = setOf(
        "a", "an", "the", "is", "are", "was", "were", "be", "been", "am", "i", "me", "my", "we", "our", "you", "your",
        "it", "its", "to", "of", "in", "on", "at", "for", "and", "or", "but", "do", "does", "did", "how", "what", "why",
        "when", "where", "which", "who", "can", "could", "should", "would", "will", "with", "this", "that", "these",
        "those", "there", "here", "from", "by", "as", "so", "if", "then", "than", "just", "very", "really", "please",
        "get", "got", "have", "has", "had", "any", "some", "about", "into", "up", "out", "too", "also", "much",
        // Roman Urdu
        "hai", "hain", "ho", "ka", "ki", "ke", "ko", "se", "pe", "par", "mein", "main", "me", "aa", "raha", "rahi",
        "rahe", "kya", "kyun", "kyon", "kaise", "kar", "karo", "karein", "mera", "meri", "mere", "mujhe", "ye", "yeh",
        "wo", "woh", "bhi", "to", "tha", "thi", "hota", "hoti", "hy", "h"
    )

    /** Lowercase words, no stop-word removal or stemming (phrase synonyms are matched on these). */
    fun words(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in text) {
            if (ch.isLetterOrDigit()) {
                sb.append(ch.lowercaseChar())
            } else if (sb.isNotEmpty()) {
                out += sb.toString()
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    /** Index terms: words without stop words and single letters ("what's" → what, s), stemmed. */
    fun terms(text: String): List<String> = terms(words(text))

    fun terms(words: List<String>): List<String> =
        words.filter { it !in STOP_WORDS && (it.length > 1 || it[0].isDigit()) }.map { stem(it) }

    /**
     * Strips one of `ing`, `ed`, `es`, `s`, `ly` when at least 3 letters stay, then folds a trailing `e` (again
     * keeping 3 letters) so "phone" and "phones", "device" and "devices" meet. Digits are left alone.
     */
    fun stem(word: String): String {
        if (word.length <= 3 || word.any { it.isDigit() }) return word
        var w = word
        for (suffix in SUFFIXES) {
            if (w.endsWith(suffix) && w.length - suffix.length >= 3) {
                w = w.dropLast(suffix.length)
                break
            }
        }
        if (w.length > 3 && w.endsWith('e')) w = w.dropLast(1)
        return w
    }

    private val SUFFIXES = listOf("ing", "ed", "es", "s", "ly")
}

/** Synonym and Roman Urdu expansion for queries. Keys can be phrases; matching is on whole words. */
class Synonyms(map: Map<String, List<String>>) {
    private val entries: List<Pair<List<String>, List<String>>> = map.entries
        .map { (k, v) -> Tokenizer.words(k) to v.flatMap { Tokenizer.words(it) } }
        .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
        .sortedByDescending { it.first.size }

    /** The query's words plus every alternative whose key (word or phrase) appears in them. */
    fun expand(words: List<String>): List<String> {
        val out = ArrayList(words)
        for ((key, alts) in entries) {
            if (containsPhrase(words, key)) out += alts
        }
        return out
    }

    private fun containsPhrase(words: List<String>, phrase: List<String>): Boolean {
        if (phrase.size > words.size) return false
        for (i in 0..words.size - phrase.size) {
            var ok = true
            for (j in phrase.indices) if (words[i + j] != phrase[j]) { ok = false; break }
            if (ok) return true
        }
        return false
    }

    companion object {
        val NONE = Synonyms(emptyMap())
    }
}

/** A document for [Bm25]: up to three weighted fields. */
data class IndexDoc(val title: String, val body: String, val keywords: String = "")

/**
 * BM25 (k1 1.2, b 0.75) over a weighted bag of fields: title 2×, body 1×, keywords 1.5×
 * (term frequency and length both count the weights).
 */
class Bm25(docs: List<IndexDoc>) {
    private val tf: List<Map<String, Double>>
    private val len: DoubleArray
    private val df = HashMap<String, Int>()
    private val avgLen: Double
    val size: Int = docs.size

    init {
        tf = docs.map { d ->
            val m = HashMap<String, Double>()
            fun add(text: String, w: Double) = Tokenizer.terms(text).forEach { t -> m[t] = (m[t] ?: 0.0) + w }
            add(d.title, TITLE_WEIGHT)
            add(d.body, BODY_WEIGHT)
            add(d.keywords, KEYWORD_WEIGHT)
            m
        }
        len = DoubleArray(docs.size) { i -> tf[i].values.sum() }
        tf.forEach { m -> m.keys.forEach { t -> df[t] = (df[t] ?: 0) + 1 } }
        avgLen = if (docs.isEmpty()) 1.0 else (len.sum() / docs.size).coerceAtLeast(1.0)
    }

    fun idf(term: String): Double {
        val n = df[term] ?: 0
        return ln(1.0 + (size - n + 0.5) / (n + 0.5))
    }

    /** Score of every document for the (already tokenised) query terms; each distinct term counts once. */
    fun scores(queryTerms: Collection<String>): DoubleArray {
        val terms = queryTerms.toSet()
        return DoubleArray(size) { i ->
            var s = 0.0
            val m = tf[i]
            val norm = K1 * (1 - B + B * len[i] / avgLen)
            for (t in terms) {
                val f = m[t] ?: continue
                s += idf(t) * f * (K1 + 1) / (f + norm)
            }
            s
        }
    }

    companion object {
        const val K1 = 1.2
        const val B = 0.75
        const val TITLE_WEIGHT = 2.0
        const val BODY_WEIGHT = 1.0
        const val KEYWORD_WEIGHT = 1.5
    }
}
