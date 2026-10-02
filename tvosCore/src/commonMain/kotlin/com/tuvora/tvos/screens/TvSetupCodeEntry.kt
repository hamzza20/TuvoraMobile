package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.SetupCode

/**
 * The Apple TV code screen's compact keypad and its twelve boxes (build plan 3.6). Pure, so it tests
 * without UI. The typed text is always the shared [SetupCode.liveFormat] form (`TUV-XXXX-XXXX-XXXX`), so
 * the keypad, the iPhone keyboard and a paste can never disagree about what a code looks like.
 */
object TvSetupCodeEntry {
    private const val COLUMNS = 8

    /** The 31 code characters in alphabet order, eight to a row (the last row has seven). */
    fun rows(): List<List<String>> = SetupCode.ALPHABET.map { it.toString() }.chunked(COLUMNS)

    /** Just the code characters typed so far (no `TUV-` lead, no dashes), uppercase. */
    fun codeCharacters(typed: String): String {
        val live = SetupCode.liveFormat(typed)
        return live.removePrefix("${SetupCode.PREFIX}-").filter { it != '-' }
    }

    /** [typed] with one more key; a thirteenth character is ignored. */
    fun append(typed: String, key: String): String {
        val code = codeCharacters(typed)
        if (code.length >= SetupCode.LENGTH) return SetupCode.liveFormat(typed)
        return format(code + key.uppercase().filter { it != '-' && !it.isWhitespace() })
    }

    /** [typed] without its last character (the dash that led that group goes with it). */
    fun backspace(typed: String): String {
        val code = codeCharacters(typed)
        return if (code.length <= 1) "" else format(code.dropLast(1))
    }

    /** Twelve slots for the boxes, filled from the left; an empty string is an empty box. */
    fun boxes(typed: String): List<String> {
        val code = codeCharacters(typed)
        return List(SetupCode.LENGTH) { i -> code.getOrNull(i)?.toString() ?: "" }
    }

    /** Continue is allowed only for twelve valid characters. */
    fun canContinue(typed: String): Boolean = SetupCode.isComplete(typed)

    /** Text typed or pasted through the system keyboard, cleaned up exactly like typing: [SetupCode.liveFormat]. */
    fun fromKeyboard(raw: String): String = SetupCode.liveFormat(raw)

    private fun format(code: String): String =
        if (code.isEmpty()) "" else (listOf(SetupCode.PREFIX) + code.chunked(4)).joinToString("-")
}
