package com.example.esp32agent.android.agent

/**
 * A small JSON parser into [JValue], so nothing in the agent depends on a platform JSON library: model
 * replies, the board's `status` heartbeat and the trace files all go through it. Object key order is kept,
 * which the history sent to the model relies on.
 */
object JsonParse {
    class Error(message: String) : RuntimeException(message)

    fun parse(text: String): JValue {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (!p.done()) throw Error("trailing content at " + p.pos)
        return v
    }

    fun parseObject(text: String): JObject? = (parse(text) as? JValue.ObjV)?.v

    private class Parser(private val s: String) {
        var pos = 0

        fun done() = pos >= s.length

        fun skipWs() {
            while (pos < s.length) {
                val c = s[pos]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++ else return
            }
        }

        fun value(): JValue {
            skipWs()
            if (done()) throw Error("unexpected end of input")
            val c = s[pos]
            return when {
                c == '{' -> obj()
                c == '[' -> arr()
                c == '"' -> JValue.StrV(str())
                c == 't' -> literal("true", JValue.BoolV(true))
                c == 'f' -> literal("false", JValue.BoolV(false))
                c == 'n' -> literal("null", JValue.NullV)
                c == '-' || c in '0'..'9' -> number()
                else -> throw Error("unexpected character at " + pos)
            }
        }

        private fun literal(word: String, value: JValue): JValue {
            if (!s.startsWith(word, pos)) throw Error("expected " + word + " at " + pos)
            pos += word.length
            return value
        }

        private fun obj(): JValue {
            pos++  // the opening brace
            val pairs = ArrayList<Pair<String, JValue>>()
            skipWs()
            if (!done() && s[pos] == '}') {
                pos++
                return JValue.ObjV(JObject(pairs))
            }
            while (true) {
                skipWs()
                val key = str()
                skipWs()
                if (done() || s[pos] != ':') throw Error("expected a colon at " + pos)
                pos++
                pairs.add(key to value())
                skipWs()
                if (done()) throw Error("unterminated object")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return JValue.ObjV(JObject(pairs))
                    }
                    else -> throw Error("expected a comma at " + pos)
                }
            }
        }

        private fun arr(): JValue {
            pos++  // the opening bracket
            val items = ArrayList<JValue>()
            skipWs()
            if (!done() && s[pos] == ']') {
                pos++
                return JValue.ArrV(items)
            }
            while (true) {
                items.add(value())
                skipWs()
                if (done()) throw Error("unterminated array")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return JValue.ArrV(items)
                    }
                    else -> throw Error("expected a comma at " + pos)
                }
            }
        }

        private fun str(): String {
            if (done() || s[pos] != '"') throw Error("expected a string at " + pos)
            pos++
            val out = StringBuilder()
            while (true) {
                if (done()) throw Error("unterminated string")
                val c = s[pos++]
                if (c == '"') return out.toString()
                if (c != '\\' || done()) {
                    out.append(c)
                    continue
                }
                when (val e = s[pos++]) {
                    '"' -> out.append('"')
                    '/' -> out.append('/')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> {
                        if (pos + 4 > s.length) throw Error("truncated unicode escape")
                        out.append(Integer.parseInt(s.substring(pos, pos + 4), 16).toChar())
                        pos += 4
                    }
                    '\\' -> out.append('\\')
                    else -> throw Error("bad escape at " + pos)
                }
            }
        }

        private fun number(): JValue {
            val start = pos
            if (!done() && s[pos] == '-') pos++
            while (!done() && s[pos] in '0'..'9') pos++
            var floating = false
            if (!done() && s[pos] == '.') {
                floating = true
                pos++
                while (!done() && s[pos] in '0'..'9') pos++
            }
            if (!done() && (s[pos] == 'e' || s[pos] == 'E')) {
                floating = true
                pos++
                if (!done() && (s[pos] == '+' || s[pos] == '-')) pos++
                while (!done() && s[pos] in '0'..'9') pos++
            }
            val text = s.substring(start, pos)
            if (!floating) text.toLongOrNull()?.let { return JValue.IntV(it) }
            return JValue.NumV(text.toDoubleOrNull() ?: throw Error("bad number " + text))
        }
    }
}
