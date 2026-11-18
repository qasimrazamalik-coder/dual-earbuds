package com.dualearbuds.companion.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A fixture help set in the docs/help format, close to the real content, so ranking is tested end to end. */
object FixtureHelp {
    val files = mapOf(
        "getting-started.md" to """
# Getting started
<!-- keywords: start, setup, first time, shuru -->

## How do I start listening on my phone?
Pair your earbuds with the phone, then join the laptop or phone that is sharing.
1. Pair your earbuds in the phone's Bluetooth settings.
2. In Dual Earbuds, scan the QR code, open the link, or pick the host under Nearby.
3. Run Calibrate once.

## How do I join with a QR code or a link?
Tap Scan QR on Home and point the camera at the code the host shows, or paste the link it shares.
Platform notes: **Windows:** the code is on the Phones page. **Android:** the code is under Share this phone.

## What is Nearby?
Nearby lists hosts on the same Wi-Fi that announce themselves, so you can join with one tap.
""",
        "sync-and-delay.md" to """
# Sync and delay
<!-- keywords: late, delay, behind, lag, der, latency, video -->

## Why is the sound late on my phone?
Sound on the phone is late or behind the video when P, the common playout delay, is high or the earbuds have not been calibrated.
P follows the slowest ear: Bluetooth earbuds often need 150 to 250 ms.
1. Run Calibrate for your earbuds.
2. Ask the host to choose Lip-sync priority if you watch video.

## What is P, the playout delay?
P is the time from capture to sound that every ear aims for. The host sets it from what each listener needs.

## What do Lip-sync and Together do?
Lip-sync keeps sound close to the video on the host; Together keeps every ear in step even if the video is later.

## Why do I hear an echo between two phones?
An echo means two ears are more than 20 ms apart. Calibrate both earbuds and check the sync scope.
""",
        "calibration.md" to """
# Calibration
<!-- keywords: calibrate, calibration, latency, earbuds, measure -->

## How do I calibrate my earbuds?
Open Calibrate from Home, hold the phone near the earbuds and let it play the clicks; it measures the latency.

## Why does the app say my earbuds are slow?
Earbuds with more than 150 ms calibrated latency are marked slow; low-latency or gaming mode helps.
""",
        "share-this-phone.md" to """
# Share this phone
<!-- keywords: share, sharing, host, phone to phone, another phone, friend -->

## How do I share this phone's sound with another phone?
Open Share this phone, tap Start sharing and let the other phone scan the QR code.

## Why is there no sound when sharing this phone?
No sound from a sharing phone usually means Android is not passing the app's sound: the capture has stalled
or the app blocks capture. Play something, check the Capture line, and pick All apps.

## Which apps can be shared?
Most music and video apps can be shared; apps that block capture (some streaming apps) stay silent.
""",
        "connection.md" to """
# Connection
<!-- keywords: connect, connection, find, network, wifi, not working, firewall -->

## Why can't my phone find the laptop?
The phone cannot reach the laptop when they are on different networks or public Wi-Fi blocks devices.
1. Put both on the same Wi-Fi, or use this phone's hotspot.
2. Check that Phone link is on in the Windows app.

## What does Wrong PIN mean?
The room has a PIN and the one typed does not match. Ask the host for it; it is never in the link.

## Why does the audio use WebSocket instead of UDP?
When UDP packets are blocked, often by a firewall, audio falls back to the WebSocket, which adds delay.
""",
        "sound-quality.md" to """
# Sound quality
<!-- keywords: choppy, crackle, crackling, stutter, dropout, cuts, quality -->

## Why is the sound choppy or crackling?
Choppy sound comes from lost Wi-Fi packets; 2.4 GHz Wi-Fi is crowded and shares the air with Bluetooth.
1. Use 5 GHz Wi-Fi or a hotspot.

## How do I make it louder?
Raise the volume slider on Home; it goes up to 150 %.
""",
        "background.md" to """
# Playing in the background
<!-- keywords: background, screen off, lock, battery saver, stops -->

## Why does playback stop when the screen is off?
Android stops apps in the background when battery saver is on or the app is restricted. Allow background use.
"""
    )

    val synonyms = """
# word: alt1, alt2
awaz: sound, audio
der: delay, late
late: delay, behind
awaz nahi: no sound, silent
phone se phone: share this phone, phone to phone
chal nahi raha: not working
lock: screen off
louder: volume
tez: louder, volume
"""

    fun assistant(): Assistant = Assistant.fromFiles(files, synonyms)
}

class AssistantTest {
    private val a = FixtureHelp.assistant()

    private fun top(q: String): String {
        val ans = a.ask(q)
        assertTrue("no answer for '$q'", ans.found)
        return ans.source!!.title
    }

    @Test
    fun parserSplitsSectionsAndReadsKeywordsStepsAndAndroidNote() {
        val chunks = HelpParser.parse("getting-started.md", FixtureHelp.files.getValue("getting-started.md"))
        assertEquals(3, chunks.size)
        val first = chunks[0]
        assertEquals("Getting started", first.topic)
        assertEquals("How do I start listening on my phone?", first.title)
        assertEquals("getting-started.md#0", first.id)
        assertEquals(listOf("start", "setup", "first time", "shuru"), first.keywords)
        assertEquals("Pair your earbuds with the phone, then join the laptop or phone that is sharing.", first.answer)
        assertEquals(3, first.steps.size)
        assertEquals("Run Calibrate once.", first.steps[2])
        assertEquals("the code is under Share this phone.", chunks[1].androidNote)
        assertFalse(chunks[1].answer.contains("Platform"))
        assertNull(chunks[2].androidNote)
    }

    @Test
    fun parserCopesWithEmptyAndHeadingOnlyFiles() {
        assertTrue(HelpParser.parse("x.md", "").isEmpty())
        assertTrue(HelpParser.parse("x.md", "# Only a title\nno sections").isEmpty())
        val one = HelpParser.parse("x.md", "## Lonely?\r\n")
        assertEquals(1, one.size)
        assertEquals("", one[0].answer)
        assertEquals("Lonely?", Assistant(one).show(one[0]).text)
    }

    @Test
    fun platformPartPicksAndroidOnly() {
        val line = "**Windows:** use the tray. **Android:** open the notification. **Browser:** reload."
        assertEquals("open the notification.", HelpParser.platformPart(line, "Android"))
        assertEquals("use the tray.", HelpParser.platformPart(line, "windows"))
        assertNull(HelpParser.platformPart("**Windows:** only", "Android"))
    }

    @Test
    fun synonymsParsePhrasesAndComments() {
        val m = SynonymParser.parse("# c\nawaz: Sound, audio\nchal nahi raha: not working\nbad line\nawaz: volume\n:x\n")
        assertEquals(listOf("sound", "audio", "volume"), m["awaz"])
        assertEquals(listOf("not working"), m["chal nahi raha"])
        assertEquals(2, m.size)
    }

    @Test
    fun tokenizerLowercasesSplitsDropsStopWordsAndStems() {
        assertEquals(listOf("wi", "fi", "2", "4", "ghz"), Tokenizer.words("Wi-Fi 2.4 GHz"))
        assertEquals(listOf("sound", "lat"), Tokenizer.terms("Why is the sound late?"))
        assertEquals(listOf("weather", "2"), Tokenizer.terms("What's the weather 2 x"))
        assertEquals("play", Tokenizer.stem("playing"))
        assertEquals("delay", Tokenizer.stem("delayed"))
        assertEquals("phon", Tokenizer.stem("phones"))
        assertEquals(Tokenizer.stem("phone"), Tokenizer.stem("phones"))
        assertEquals(Tokenizer.stem("device"), Tokenizer.stem("devices"))
        assertEquals("quick", Tokenizer.stem("quickly"))
        assertEquals("sing", Tokenizer.stem("sing"))
        assertEquals("bed", Tokenizer.stem("bed"))
        assertEquals("use", Tokenizer.stem("uses"))
        assertEquals("150", Tokenizer.stem("150"))
    }

    @Test
    fun synonymExpansionMatchesWholeWordPhrases() {
        val s = Synonyms(mapOf("chal nahi raha" to listOf("not working"), "der" to listOf("delay")))
        assertEquals(listOf("app", "chal", "nahi", "raha", "not", "working"), s.expand(listOf("app", "chal", "nahi", "raha")))
        assertEquals(listOf("order"), s.expand(listOf("order")))
        assertEquals(listOf("der", "delay"), s.expand(listOf("der")))
    }

    @Test
    fun bm25WeightsTitleOverBodyAndKeywordsOverBody() {
        val idx = Bm25(
            listOf(
                IndexDoc("calibrate earbuds", "open the screen"),
                IndexDoc("other thing", "calibrate earbuds here"),
                IndexDoc("other words", "open the screen", "calibrate"),
                IndexDoc("fourth", "filler words only")
            )
        )
        val s = idx.scores(Tokenizer.terms("calibrate"))
        assertTrue(s[0] > s[2])
        assertTrue(s[2] > s[1])
        assertEquals(0.0, s[3], 0.0)
    }

    @Test
    fun answersRealisticQuestionsIncludingRomanUrdu() {
        val cases = listOf(
            "awaz late aa rahi hai" to "Why is the sound late on my phone?",
            "phone se phone pe awaz nahi aa rahi" to "Why is there no sound when sharing this phone?",
            "Why is the sound behind the video?" to "Why is the sound late on my phone?",
            "how to calibrate earbuds" to "How do I calibrate my earbuds?",
            "sound keeps crackling and cutting out" to "Why is the sound choppy or crackling?",
            "music stops when I lock the screen" to "Why does playback stop when the screen is off?",
            "my phone can't find the laptop" to "Why can't my phone find the laptop?",
            "it says wrong PIN" to "What does Wrong PIN mean?",
            "how do I make it louder" to "How do I make it louder?",
            "what is lip sync mode" to "What do Lip-sync and Together do?",
            "I hear an echo between the two phones" to "Why do I hear an echo between two phones?",
            "firewall blocks UDP" to "Why does the audio use WebSocket instead of UDP?",
            "scan the QR code to join" to "How do I join with a QR code or a link?",
            "laptop se connect nahi ho raha" to "Why can't my phone find the laptop?",
            "share my phone's sound with a friend" to "How do I share this phone's sound with another phone?",
            "what does P mean playout" to "What is P, the playout delay?",
            "earbuds are slow, 200 ms latency" to "Why does the app say my earbuds are slow?"
        )
        val wrong = cases.mapNotNull { (q, want) ->
            val got = a.ask(q)
            if (got.found && got.source!!.title == want) null else "'$q' -> ${got.source?.title ?: "not found"}"
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun unknownQuestionSaysSoAndOffersThreeClosestTopics() {
        val ans = a.ask("what's the weather in Lahore tomorrow")
        assertFalse(ans.found)
        assertNull(ans.source)
        assertEquals(Assistant.NOT_FOUND, ans.text)
        assertEquals(3, ans.related.size)
        assertTrue(ans.steps.isEmpty())
        val empty = a.ask("   ")
        assertFalse(empty.found)
        assertEquals(3, empty.related.size)
    }

    @Test
    fun answerCarriesStepsNotesAndRelated() {
        val ans = a.ask("scan QR code")
        assertTrue(ans.found)
        assertEquals("Tap Scan QR on Home and point the camera at the code the host shows, or paste the link it shares.", ans.text)
        assertEquals(listOf("On Android: the code is under Share this phone."), ans.notes)
        assertTrue(ans.related.size <= 2)
        val late = a.ask("sound late")
        assertEquals(2, late.steps.size)
        assertTrue(late.text.endsWith("Bluetooth earbuds often need 150 to 250 ms."))
        assertTrue(late.notes.isEmpty())
        val shown = a.show(a.chunk("calibration.md#1")!!)
        assertEquals("Why does the app say my earbuds are slow?", shown.source!!.title)
        assertEquals(listOf("calibration.md#0"), shown.related.map { it.id })
    }

    @Test
    fun suggestionsStartWithEachTopicsFirstQuestion() {
        val s = a.suggestions(4)
        assertEquals(4, s.size)
        assertEquals("How do I start listening on my phone?", s[0])
        assertEquals(s.distinct(), s)
    }

    @Test
    fun emptyHelpSetNeverCrashes() {
        val none = Assistant(emptyList())
        val ans = none.ask("anything")
        assertFalse(ans.found)
        assertTrue(ans.related.isEmpty())
        assertTrue(none.suggestions().isEmpty())
    }

    @Test
    fun helpFilesStartingWithUnderscoreAreNotTopics() {
        val x = Assistant.fromFiles(mapOf("_synonyms.txt" to "a: b", "_draft.md" to "## Hidden?\nx", "t.md" to "## Shown?\ny"), null)
        assertEquals(listOf("Shown?"), x.chunks.map { it.title })
    }
}

class LiveFactsTest {
    private val listen = ListenInputs(
        status = "Playing", connected = true, hostName = "Office-PC", hostNoun = "laptop", priority = "lipsync",
        playoutMs = 238, needMs = 238, synced = true, syncErrMs = 12.4, transport = "UDP",
        earbudsName = "Galaxy Buds2", calibratedMs = 182.0, powerSave = true, wifiMhz = 2437, onWifi = true,
        lastError = null, underruns = 3, lost = 12, bufferMs = 40
    )

    @Test
    fun listenerFactsCoverTheSession() {
        val f = LiveFactsBuilder.build(listen, null).map { it.text }
        assertTrue(f.size in 4..10)
        assertTrue(f.any { it == "Playing from Office-PC (laptop) over UDP." })
        assertTrue(f.any { it == "Sync priority on the host: Lip-sync (video first)." })
        assertTrue(f.any { it == "P is 238 ms because this phone needs 238 ms (earbuds 182 ms)." })
        assertTrue(f.any { it == "This phone is 12 ms late against the common deadline." })
        assertTrue(f.any { it == "Earbuds: Galaxy Buds2, calibrated at 182 ms." })
        assertTrue(f.any { it == "Battery saver is on." })
        assertTrue(f.any { it.startsWith("Wi-Fi band: 2.4 GHz") })
        assertTrue(f.any { it.startsWith("Since connecting: 12 packets lost, 3 buffer underruns") })
    }

    @Test
    fun webSocketUncalibratedAndErrorFacts() {
        val f = LiveFactsBuilder.build(
            listen.copy(transport = "WS", calibratedMs = null, needMs = 120, playoutMs = 238, synced = false, lastError = "Wrong PIN."),
            null
        ).map { it.text }
        assertTrue(f.any { it.contains("over WebSocket") })
        assertTrue(f.any { it == "Earbuds: Galaxy Buds2, not calibrated (run Calibrate)." })
        assertTrue(f.any { it == "P is 238 ms; this phone needs 120 ms." })
        assertTrue(f.any { it.startsWith("Clock sync is not running") })
        assertTrue(f.any { it == "Last error: Wrong PIN." })
    }

    @Test
    fun notConnectedStillGivesUsefulFacts() {
        val f = LiveFactsBuilder.build(ListenInputs(status = "Not connected", connected = false, onWifi = false), null).map { it.text }
        assertEquals("Not connected: not connected to a host.", f[0])
        assertTrue(f.contains("No Bluetooth earbuds are connected to this phone."))
        assertTrue(f.contains("This phone is not on Wi-Fi."))
    }

    @Test
    fun sharingFactsNameTheSlowestEarAndCaptureStats() {
        val share = ShareInputs(
            sharing = true, playoutMs = 238, priority = "together",
            listeners = listOf(
                ShareListener("Redmi", 230, 12, "UDP", 64),
                ShareListener("Pixel", 120, -3, "WS")
            ),
            capturedPerSec = 100, peakDb = -14, stalled = true, sentPerSec = 200,
            localOutputName = "Buds", localNeedMs = 180
        )
        val f = LiveFactsBuilder.build(ListenInputs(status = "Not connected", connected = false), share).map { it.text }
        assertTrue(f.size <= LiveFactsBuilder.MAX_FACTS)
        assertEquals("Sharing this phone to 2 listeners.", f[0])
        assertTrue(f.contains("P is 238 ms because Redmi needs 230 ms."))
        assertTrue(f.contains("Listeners: Redmi (needs 230 ms, +12 ms, UDP, battery 64 %); Pixel (needs 120 ms, −3 ms, WS)."))
        assertTrue(f.contains("Capture: 100 packets/s, peak -14 dB, sent 200/s."))
        assertTrue(f.any { it.startsWith("Capture has stalled") })
        assertFalse("sharing phone shouldn't nag about its own earbuds", f.contains("No Bluetooth earbuds are connected to this phone."))
        val local = LiveFactsBuilder.build(null, share.copy(listeners = emptyList())).map { it.text }
        assertTrue(local.contains("P is 238 ms because this phone's own Buds needs 180 ms."))
        assertTrue(local.contains("Sharing this phone; no listeners yet."))
    }

    @Test
    fun factsAreCappedAtTen() {
        val many = ShareInputs(sharing = true, playoutMs = 100, priority = "lipsync", listeners = listOf(ShareListener("A", 90, 1, "UDP")),
            stalled = true, error = "x")
        val f = LiveFactsBuilder.build(listen.copy(backgroundRestricted = true, lastError = "boom"), many)
        assertEquals(LiveFactsBuilder.MAX_FACTS, f.size)
    }

    @Test
    fun rightNowShowsTheMatchingFacts() {
        val a = FixtureHelp.assistant()
        val facts = LiveFactsBuilder.build(listen, null)
        val late = a.ask("awaz late aa rahi hai", facts)
        assertTrue(late.found)
        assertTrue(late.rightNow.toString(), late.rightNow.any { it.startsWith("P is 238 ms") })
        assertTrue(late.rightNow.size <= Assistant.MAX_FACTS_SHOWN)
        val stop = a.ask("music stops when I lock the screen", facts)
        assertTrue(stop.rightNow.toString(), stop.rightNow.contains("Battery saver is on."))
        val choppy = a.ask("why is it choppy", facts)
        assertTrue(choppy.rightNow.toString(), choppy.rightNow.any { it.startsWith("Wi-Fi band: 2.4 GHz") })
        val louder = a.ask("how do I make it louder", facts)
        assertFalse(louder.rightNow.any { it.startsWith("Battery") })
    }

    @Test
    fun sharingQuestionGetsCaptureFacts() {
        val a = FixtureHelp.assistant()
        val share = ShareInputs(sharing = true, capturedPerSec = 0, peakDb = null, stalled = true, sentPerSec = 100)
        val ans = a.ask("phone se phone pe awaz nahi aa rahi", LiveFactsBuilder.build(null, share))
        assertNotNull(ans.source)
        assertTrue(ans.rightNow.toString(), ans.rightNow.any { it.startsWith("Capture has stalled") })
    }
}
