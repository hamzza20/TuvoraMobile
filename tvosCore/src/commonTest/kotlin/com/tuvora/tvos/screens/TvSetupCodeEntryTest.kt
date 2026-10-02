package com.tuvora.tvos.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Apple TV code screen's compact keypad and its twelve boxes (build plan 3.6). */
class TvSetupCodeEntryTest {

    @Test
    fun `the keypad offers exactly the 31 code characters in four short rows`() {
        val keys = TvSetupCodeEntry.rows().flatten()
        assertEquals("ABCDEFGHJKMNPQRSTUVWXYZ23456789".toList().map { it.toString() }, keys)
        assertEquals(31, keys.size)
        assertEquals(listOf(8, 8, 8, 7), TvSetupCodeEntry.rows().map { it.size })
        // no look-alikes: the letters and digits the alphabet leaves out are not on the pad
        assertTrue(keys.none { it in listOf("I", "L", "O", "0", "1") })
    }

    @Test
    fun `typing a key appends it and groups the code with dashes`() {
        var typed = ""
        for (k in "ABCDEFGH".map { it.toString() }) typed = TvSetupCodeEntry.append(typed, k)
        assertEquals("TUV-ABCD-EFGH", typed)
    }

    @Test
    fun `the twelfth character completes the code and a thirteenth is ignored`() {
        var typed = ""
        for (k in "ABCDEFGHJKMN".map { it.toString() }) typed = TvSetupCodeEntry.append(typed, k)
        assertEquals("TUV-ABCD-EFGH-JKMN", typed)
        assertTrue(TvSetupCodeEntry.canContinue(typed))
        assertEquals(typed, TvSetupCodeEntry.append(typed, "P"))
    }

    @Test
    fun `a code that starts with TUV is not mistaken for the prefix`() {
        var typed = ""
        for (k in "TUVWXYZ23456".map { it.toString() }) typed = TvSetupCodeEntry.append(typed, k)
        assertEquals("TUV-TUVW-XYZ2-3456", typed)
        assertEquals("TUVWXYZ23456", TvSetupCodeEntry.codeCharacters(typed))
        assertTrue(TvSetupCodeEntry.canContinue(typed))
    }

    @Test
    fun `backspace removes one character and the dash goes with its group`() {
        var typed = "TUV-ABCD-E"
        typed = TvSetupCodeEntry.backspace(typed)
        assertEquals("TUV-ABCD", typed)
        typed = TvSetupCodeEntry.backspace(typed)
        assertEquals("TUV-ABC", typed)
        assertEquals("", TvSetupCodeEntry.backspace("TUV-A"))
        assertEquals("", TvSetupCodeEntry.backspace(""))
    }

    @Test
    fun `boxes always give twelve slots filled from the left`() {
        assertEquals(List(12) { "" }, TvSetupCodeEntry.boxes(""))
        val boxes = TvSetupCodeEntry.boxes("TUV-ABCD-EF")
        assertEquals(12, boxes.size)
        assertEquals(listOf("A", "B", "C", "D", "E", "F"), boxes.take(6))
        assertTrue(boxes.drop(6).all { it == "" })
    }

    @Test
    fun `continue needs a complete valid code`() {
        assertFalse(TvSetupCodeEntry.canContinue(""))
        assertFalse(TvSetupCodeEntry.canContinue("TUV-ABCD-EFGH"))
        assertTrue(TvSetupCodeEntry.canContinue("tuv-abcd-efgh-jkmn"))
    }

    @Test
    fun `text from the iPhone keyboard is cleaned up the same way`() {
        assertEquals("TUV-ABCD-EFGH-JKMN", TvSetupCodeEntry.fromKeyboard("tuv abcd efgh jkmn"))
        assertEquals("TUV-ABCD-EFGH-JKMN", TvSetupCodeEntry.fromKeyboard("abcdefghjkmnpq"))
        assertEquals("", TvSetupCodeEntry.fromKeyboard("   "))
    }

    @Test
    fun `characters outside the alphabet from the keyboard stay visible so the problem can be named`() {
        assertEquals("TUV-AB0D", TvSetupCodeEntry.fromKeyboard("ab0d"))
        assertEquals(listOf("A", "B", "0", "D"), TvSetupCodeEntry.boxes("TUV-AB0D").take(4))
    }
}
