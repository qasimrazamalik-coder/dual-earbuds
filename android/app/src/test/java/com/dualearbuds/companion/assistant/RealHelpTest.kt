package com.dualearbuds.companion.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The shipped help (repo-root docs/help, the same files the copyHelp task bundles) answers real questions,
 * English and Roman Urdu. Skipped when the folder can't be found from the working directory.
 */
class RealHelpTest {
    private val dir: File? = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
        .map { File(it, "docs/help") }
        .firstOrNull { File(it, "_synonyms.txt").isFile }

    private fun assistant(): Assistant {
        assumeTrue("docs/help not found", dir != null)
        val files = dir!!.listFiles().orEmpty().filter { it.name.endsWith(".md") }.associate { it.name to it.readText() }
        return Assistant.fromFiles(files, File(dir, "_synonyms.txt").readText())
    }

    @Test
    fun everySectionParsesWithAnAnswer() {
        val a = assistant()
        assertTrue(a.chunks.size >= 100)
        val empty = a.chunks.filter { it.answer.isBlank() }.map { it.id }
        assertTrue("sections without an answer paragraph: $empty", empty.isEmpty())
        assertEquals(a.chunks.size, a.chunks.map { it.id }.distinct().size)
    }

    @Test
    fun topHitsForRealQuestions() {
        val a = assistant()
        val cases = listOf(
            "awaz late aa rahi hai" to "The sound is late or out of sync",
            "phone se phone pe awaz nahi aa rahi" to "Phone to phone sharing is silent",
            "Why is the sound behind the video?" to "Why is the sound behind the picture?",
            "how do I calibrate my earbuds on android" to "How do I calibrate earbuds on the Android phone?",
            "sound keeps cutting out" to "The sound stutters or keeps cutting out",
            "music stops when the screen turns off" to "Why does the sound stop when the phone screen turns off?",
            "phone can't connect to the laptop" to "The phone can't connect to the laptop",
            "laptop se connect nahi ho raha" to "The phone can't connect to the laptop",
            "wrong link key" to "Why does the phone say \"Wrong link key\"?",
            "what is P" to "What is P and why does it change?",
            "lip sync or together" to "What is the difference between Lip-sync and Everyone together?",
            "echo between two earbuds" to "Why is there an echo between two pairs of earbuds?",
            "firewall blocks udp" to "What is the difference between UDP and WebSocket?",
            "can I listen on iphone" to "Can I listen on an iPhone?",
            "is it free" to "Does Dual Earbuds cost anything?",
            "how to share netflix from my phone" to "Why can't I share Netflix from my phone?"
        )
        val wrong = cases.mapNotNull { (q, want) ->
            val got = a.ask(q)
            if (got.found && got.source!!.title == want) null else "'$q' -> ${got.source?.title ?: "not found"}"
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun romanUrduScreenOffLandsInBatteryTopics() {
        val a = assistant()
        val top3 = a.search("screen band hone pe awaz ruk jati hai").take(3).map { it.chunk.file }
        assertTrue(top3.toString(), top3.all { it == "background-and-battery.md" })
    }

    @Test
    fun offTopicQuestionsAreNotAnswered() {
        val a = assistant()
        for (q in listOf("what's the weather in Lahore tomorrow", "how do I bake a cake")) {
            val ans = a.ask(q)
            assertFalse(q, ans.found)
            assertEquals(3, ans.related.size)
        }
    }
}
