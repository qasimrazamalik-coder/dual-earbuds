package com.dualearbuds.companion.diag

/** Fixed-size ring of the last [capacity] log lines. Thread-safe. Lines are [Redact]ed on the way in. */
class LogRing(private val capacity: Int = 200) {
    private val lines = ArrayDeque<String>(capacity)

    @Synchronized
    fun add(line: String) {
        if (lines.size >= capacity) lines.removeFirst()
        lines.addLast(Redact.secrets(line).replace('\n', ' ').take(500))
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() = lines.clear()
}

/** Removes link keys and PINs from text that may leave the device (diagnostics). */
object Redact {
    private val KEY_PARAM = Regex("([?&](?:k|p)=)[^&\\s#\"']*", RegexOption.IGNORE_CASE)
    private val JSON_PIN = Regex("(\"(?:k|p|pin|key)\"\\s*:\\s*\")[^\"]*(\")", RegexOption.IGNORE_CASE)

    fun secrets(text: String): String =
        JSON_PIN.replace(KEY_PARAM.replace(text) { it.groupValues[1] + "•••" }) { it.groupValues[1] + "•••" + it.groupValues[2] }
}
