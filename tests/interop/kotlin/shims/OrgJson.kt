// Test shim: the subset of Android's org.json that the listener's net code uses, with Android's semantics
// (numbers parse to Integer/Long/Double, optInt truncates, whole doubles print without ".0").
package org.json

class JSONException(message: String) : RuntimeException(message)

class JSONObject() {
    private val map = LinkedHashMap<String, Any?>()

    constructor(text: String) : this() {
        val p = Parser(text)
        p.ws()
        p.expect('{')
        p.ws()
        if (p.peek() == '}') {
            p.pos++
        } else {
            while (true) {
                p.ws()
                val k = p.string()
                p.ws()
                p.expect(':')
                p.ws()
                map[k] = p.value()
                p.ws()
                val c = p.next()
                if (c == ',') continue
                if (c == '}') break
                throw JSONException("expected , or }")
            }
        }
        p.ws()
        if (p.pos != text.length) throw JSONException("trailing characters")
    }

    fun has(name: String): Boolean = map.containsKey(name)
    fun opt(name: String): Any? = map[name].let { if (it === NULL) NULL else it }

    fun optString(name: String): String = optString(name, "")
    fun optString(name: String, fallback: String): String {
        val v = map[name] ?: return fallback
        return if (v is String) v else toStr(v)
    }

    fun optInt(name: String): Int = optInt(name, 0)
    fun optInt(name: String, fallback: Int): Int = when (val v = map[name]) {
        is Number -> v.toInt()
        is String -> v.toDoubleOrNull()?.toInt() ?: fallback
        else -> fallback
    }

    fun optLong(name: String, fallback: Long): Long = when (val v = map[name]) {
        is Number -> v.toLong()
        is String -> v.toDoubleOrNull()?.toLong() ?: fallback
        else -> fallback
    }

    fun optDouble(name: String, fallback: Double): Double = when (val v = map[name]) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: fallback
        else -> fallback
    }

    fun optBoolean(name: String, fallback: Boolean): Boolean = when (val v = map[name]) {
        is Boolean -> v
        is String -> if (v.equals("true", true)) true else if (v.equals("false", true)) false else fallback
        else -> fallback
    }

    fun put(name: String, value: Any?): JSONObject {
        if (value == null) map.remove(name) else map[name] = value
        return this
    }

    override fun toString(): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(',')
            first = false
            quote(sb, k)
            sb.append(':')
            when (v) {
                is String -> quote(sb, v)
                else -> sb.append(toStr(v))
            }
        }
        return sb.append('}').toString()
    }

    companion object {
        @JvmField
        val NULL: Any = object {
            override fun toString() = "null"
        }

        fun toStr(v: Any?): String = when (v) {
            null -> "null"
            is Double -> if (v == Math.rint(v) && !v.isInfinite() && Math.abs(v) < 9.0E15) v.toLong().toString() else v.toString()
            is Float -> toStr(v.toDouble())
            else -> v.toString()
        }

        fun quote(sb: StringBuilder, s: String) {
            sb.append('"')
            for (c in s) {
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c == '\n' -> sb.append("\\n")
                    c == '\r' -> sb.append("\\r")
                    c == '\t' -> sb.append("\\t")
                    c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                    else -> sb.append(c)
                }
            }
            sb.append('"')
        }
    }

    private class Parser(val s: String) {
        var pos = 0
        fun peek(): Char = if (pos < s.length) s[pos] else '\u0000'
        fun next(): Char = if (pos < s.length) s[pos++] else throw JSONException("unexpected end")
        fun ws() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }
        fun expect(c: Char) {
            if (next() != c) throw JSONException("expected $c")
        }
        fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = next()
                if (c == '"') return sb.toString()
                if (c == '\\') {
                    when (val e = next()) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                        'u' -> { sb.append(s.substring(pos, pos + 4).toInt(16).toChar()); pos += 4 }
                        else -> sb.append(e)
                    }
                } else sb.append(c)
            }
        }
        fun value(): Any? {
            val c = peek()
            if (c == '"') return string()
            if (c == '{' || c == '[') {
                // nested values are not used by the protocol: skip them
                var depth = 0
                var inStr = false
                while (true) {
                    val x = next()
                    if (inStr) {
                        if (x == '\\') next() else if (x == '"') inStr = false
                        continue
                    }
                    if (x == '"') inStr = true
                    else if (x == '{' || x == '[') depth++
                    else if (x == '}' || x == ']') { depth--; if (depth == 0) return NULL }
                }
            }
            val start = pos
            while (pos < s.length && s[pos] !in ",}] \t\r\n") pos++
            val lit = s.substring(start, pos)
            if (lit == "true") return true
            if (lit == "false") return false
            if (lit == "null") return NULL
            if (lit.isEmpty()) throw JSONException("missing value")
            // Android's JSONTokener.readLiteral: integers become Integer or Long, everything else Double
            if (lit.indexOf('.') < 0 && lit.indexOf('e') < 0 && lit.indexOf('E') < 0) {
                val l = lit.toLongOrNull()
                if (l != null) return if (l in Int.MIN_VALUE..Int.MAX_VALUE) l.toInt() else l
            }
            return lit.toDoubleOrNull() ?: lit
        }
    }
}
