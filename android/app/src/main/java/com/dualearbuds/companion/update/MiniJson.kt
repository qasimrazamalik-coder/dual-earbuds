package com.dualearbuds.companion.update

/**
 * Minimal, strict JSON reader (RFC 8259) used for the GitHub release response.
 *
 * Kept in pure Kotlin on purpose: Android's `org.json` is only a stub in JVM unit tests, so parsing the release
 * with this class keeps the parser and its tests identical on the phone and on the build machine.
 *
 * Objects become `Map<String, Any?>`, arrays `List<Any?>`, numbers `Long` (when integral and in range) or
 * `Double`, plus `String`, `Boolean` and `null`.
 */
object MiniJson {
    class ParseException(message: String) : Exception(message)

    private const val MAX_DEPTH = 64

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.readValue(0)
        p.skipWs()
        if (!p.atEnd()) throw ParseException("Trailing characters at ${p.pos}")
        return v
    }

    private class Parser(val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length) {
                val c = s[pos]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++ else break
            }
        }

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw ParseException("Nested too deeply")
            if (atEnd()) throw ParseException("Unexpected end")
            return when (val c = s[pos]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') readNumber() else throw ParseException("Unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) throw ParseException("Bad literal at $pos")
            pos += word.length
            return value
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            pos++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') {
                pos++
                return map
            }
            while (true) {
                skipWs()
                if (peek() != '"') throw ParseException("Expected a key at $pos")
                val key = readString()
                skipWs()
                expect(':')
                skipWs()
                map[key] = readValue(depth + 1)
                skipWs()
                when (next()) {
                    ',' -> continue
                    '}' -> return map
                    else -> throw ParseException("Expected ',' or '}' at ${pos - 1}")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            pos++ // [
            val list = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') {
                pos++
                return list
            }
            while (true) {
                skipWs()
                list.add(readValue(depth + 1))
                skipWs()
                when (next()) {
                    ',' -> continue
                    ']' -> return list
                    else -> throw ParseException("Expected ',' or ']' at ${pos - 1}")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw ParseException("Unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (atEnd()) throw ParseException("Bad escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw ParseException("Bad \\u escape")
                                val code = s.substring(pos, pos + 4).toIntOrNull(16)
                                    ?: throw ParseException("Bad \\u escape")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw ParseException("Bad escape \\$e")
                        }
                    }
                    c < ' ' -> throw ParseException("Control character in string at ${pos - 1}")
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            if (peek() == '-') pos++
            if (peek() == '0') {
                pos++
            } else if (peek() in '1'..'9') {
                while (peek() in '0'..'9') pos++
            } else {
                throw ParseException("Bad number at $start")
            }
            var integral = true
            if (peek() == '.') {
                integral = false
                pos++
                if (peek() !in '0'..'9') throw ParseException("Bad number at $start")
                while (peek() in '0'..'9') pos++
            }
            if (peek() == 'e' || peek() == 'E') {
                integral = false
                pos++
                if (peek() == '+' || peek() == '-') pos++
                if (peek() !in '0'..'9') throw ParseException("Bad number at $start")
                while (peek() in '0'..'9') pos++
            }
            val text = s.substring(start, pos)
            if (integral) text.toLongOrNull()?.let { return it }
            return text.toDouble()
        }

        private fun peek(): Char = if (pos < s.length) s[pos] else '\u0000'

        private fun next(): Char {
            if (atEnd()) throw ParseException("Unexpected end")
            return s[pos++]
        }

        private fun expect(c: Char) {
            if (next() != c) throw ParseException("Expected '$c' at ${pos - 1}")
        }
    }
}
