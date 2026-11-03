package com.example.esp32agent.android.agent

import java.util.Locale

/**
 * A JSON value. Agent steps are ordered objects, like the Python dicts they replace, so the history sent to
 * the model reads exactly as it did in the Python and Swift versions (`json.dumps` key order and spacing).
 * Port of agent/JSON.swift.
 */
sealed class JValue {
    data class IntV(val v: Long) : JValue()
    data class NumV(val v: Double) : JValue()
    data class StrV(val v: String) : JValue()
    data class BoolV(val v: Boolean) : JValue()
    data class ArrV(val v: List<JValue>) : JValue()
    data class ObjV(val v: JObject) : JValue()
    data object NullV : JValue()

    val int: Long? get() = (this as? IntV)?.v
    val str: String? get() = (this as? StrV)?.v
    val bool: Boolean? get() = (this as? BoolV)?.v
    val arr: List<JValue>? get() = (this as? ArrV)?.v
    val obj: JObject? get() = (this as? ObjV)?.v
    val double: Double?
        get() = when (this) {
            is IntV -> v.toDouble()
            is NumV -> v
            else -> null
        }
}

fun Long.j(): JValue = JValue.IntV(this)
fun Int.j(): JValue = JValue.IntV(this.toLong())
fun Double.j(): JValue = JValue.NumV(this)
fun String.j(): JValue = JValue.StrV(this)
fun Boolean.j(): JValue = JValue.BoolV(this)
fun List<JValue>.j(): JValue = JValue.ArrV(this)
fun JObject.j(): JValue = JValue.ObjV(this)
fun jObj(vararg pairs: Pair<String, JValue>): JObject = JObject(pairs.toList())

/** An insertion-ordered JSON object; equality ignores order, like Python dicts. */
class JObject(pairs: List<Pair<String, JValue>> = emptyList()) {
    private val list = ArrayList<Pair<String, JValue>>(pairs.size)

    init { pairs.forEach { (k, v) -> set(k, v) } }

    val pairs: List<Pair<String, JValue>> get() = list.toList()
    val keys: List<String> get() = list.map { it.first }
    val size: Int get() = list.size
    val isEmpty: Boolean get() = list.isEmpty()

    operator fun get(key: String): JValue? = list.firstOrNull { it.first == key }?.second

    operator fun set(key: String, value: JValue?) {
        val i = list.indexOfFirst { it.first == key }
        when {
            i >= 0 && value != null -> list[i] = key to value
            i < 0 && value != null -> list.add(key to value)
            i >= 0 -> list.removeAt(i)
        }
    }

    /** `{**a, **b}`: b's keys win, a's order first. */
    fun merging(other: JObject): JObject {
        val out = JObject(this.list)
        other.list.forEach { (k, v) -> out[k] = v }
        return out
    }

    fun without(hidden: Set<String>): JObject = JObject(list.filterNot { hidden.contains(it.first) })

    override fun equals(other: Any?): Boolean {
        val o = other as? JObject ?: return false
        return o.list.size == list.size && list.all { (k, v) -> o[k] == v }
    }

    override fun hashCode(): Int = list.sumOf { it.first.hashCode() * 31 + it.second.hashCode() }

    override fun toString(): String = PyJSON.dumps(this)
}

/** Python's `json.dumps` output format (", " and ": " separators, ensure_ascii). */
object PyJSON {
    fun dumps(v: JValue, ascii: Boolean = true): String = when (v) {
        is JValue.IntV -> v.v.toString()
        is JValue.NumV -> number(v.v)
        is JValue.BoolV -> if (v.v) "true" else "false"
        is JValue.NullV -> "null"
        is JValue.StrV -> quote(v.v, ascii)
        is JValue.ArrV -> v.v.joinToString(", ", "[", "]") { dumps(it, ascii) }
        is JValue.ObjV -> dumps(v.v, ascii)
    }

    fun dumps(o: JObject, ascii: Boolean = true): String =
        o.pairs.joinToString(", ", "{", "}") { (k, v) -> quote(k, ascii) + ": " + dumps(v, ascii) }

    private fun number(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return "null"  // json.dumps would raise; a trace line must not
        return if (d == Math.rint(d) && Math.abs(d) < 1e15) String.format(Locale.US, "%.1f", d) else d.toString()
    }

    private fun quote(s: String, ascii: Boolean): String {
        val out = StringBuilder(s.length + 2)
        out.append('"')
        for (cp in s.codePoints().toArray()) {
            when (cp) {
                '"'.code -> out.append("\\\"")
                '\\'.code -> out.append("\\\\")
                '\n'.code -> out.append("\\n")
                '\r'.code -> out.append("\\r")
                '\t'.code -> out.append("\\t")
                else -> if (cp < 0x20 || (ascii && cp > 0x7e)) {
                    // JSON represents non-BMP characters as a UTF-16 surrogate pair.
                    for (unit in Character.toChars(cp)) {
                        out.append(String.format(Locale.US, "\\u%04x", unit.code))
                    }
                } else {
                    out.appendCodePoint(cp)
                }
            }
        }
        return out.append('"').toString()
    }
}
