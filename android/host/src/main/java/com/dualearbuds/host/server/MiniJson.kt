package com.dualearbuds.host.server

/**
 * Tiny JSON reader/writer for the protocol's flat messages. Kept free of org.json so the
 * server runs (and is unit-tested) on a plain JVM.
 */
object MiniJson {

    /** Builds `{"a":1,"b":"x"}` from pairs. Values: String, Number, Boolean or null. */
    fun obj(vararg pairs: Pair<String, Any?>): String {
        val sb = StringBuilder(64)
        sb.append('{')
        pairs.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(',')
            quote(sb, k)
            sb.append(':')
            when (v) {
                null -> sb.append("null")
                is String -> quote(sb, v)
                is Boolean -> sb.append(if (v) "true" else "false")
                is Double, is Float -> {
                    val d = (v as Number).toDouble()
                    if (d.isNaN() || d.isInfinite()) sb.append("null")
                    else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append(d.toLong())
                    else sb.append(d)
                }
                is Number -> sb.append(v.toLong())
                else -> quote(sb, v.toString())
            }
        }
        sb.append('}')
        return sb.toString()
    }

    fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ' || c == ' ' || c == ' ') {
                    sb.append("\\u")
                    val hex = Integer.toHexString(c.code)
                    repeat(4 - hex.length) { sb.append('0') }
                    sb.append(hex)
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }

    /**
     * Parses a JSON object. Returns null when [text] is not a valid JSON object.
     * Numbers become Double, nested objects Map, arrays List.
     */
    fun parseObject(text: String): Map<String, Any?>? {
        return try {
            val p = Parser(text)
            p.skipWs()
            val v = p.readValue(0)
            p.skipWs()
            @Suppress("UNCHECKED_CAST")
            if (p.pos != text.length) null else v as? Map<String, Any?>
        } catch (_: RuntimeException) {
            null
        }
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++
        }

        fun fail(): Nothing = throw IllegalArgumentException("bad json at $pos")

        fun readValue(depth: Int): Any? {
            if (depth > 16) fail()
            skipWs()
            if (pos >= s.length) fail()
            return when (val c = s[pos]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') readNumber() else fail()
            }
        }

        fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) fail()
            pos += word.length
            return value
        }

        fun readObject(depth: Int): Map<String, Any?> {
            pos++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') fail()
                val k = readString()
                skipWs()
                if (pos >= s.length || s[pos] != ':') fail()
                pos++
                map[k] = readValue(depth + 1)
                skipWs()
                if (pos >= s.length) fail()
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> fail()
                }
            }
        }

        fun readArray(depth: Int): List<Any?> {
            pos++ // [
            val list = ArrayList<Any?>()
            skipWs()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(readValue(depth + 1))
                skipWs()
                if (pos >= s.length) fail()
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> fail()
                }
            }
        }

        fun readString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) fail()
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= s.length) fail()
                        when (s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail()
                                val code = s.substring(pos, pos + 4).toIntOrNull(16) ?: fail()
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> fail()
                        }
                    }
                    c < ' ' -> fail()
                    else -> sb.append(c)
                }
            }
        }

        fun readNumber(): Double {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos] in '0'..'9' || s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E' || s[pos] == '+' || s[pos] == '-')) pos++
            return s.substring(start, pos).toDoubleOrNull() ?: fail()
        }
    }
}

internal fun Map<String, Any?>.str(key: String): String? = this[key] as? String
internal fun Map<String, Any?>.num(key: String): Double? = (this[key] as? Number)?.toDouble()
internal fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean
