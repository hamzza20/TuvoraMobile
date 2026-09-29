package com.tuvora.tvos.screens

/** NuvioTV's Move Up / Move Down for personal lists. Pure. */
object TvListOrder {
    /** The keys with [key] swapped one step [up] or down, or null when it cannot move (edge or absent). */
    fun move(keys: List<String>, key: String, up: Boolean): List<String>? {
        val index = keys.indexOf(key)
        if (index < 0) return null
        val target = if (up) index - 1 else index + 1
        if (target !in keys.indices) return null
        return keys.toMutableList().apply { this[index] = this[target]; this[target] = key }
    }
}
