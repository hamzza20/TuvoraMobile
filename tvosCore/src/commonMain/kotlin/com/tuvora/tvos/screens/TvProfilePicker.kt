package com.tuvora.tvos.screens

import com.nuvio.app.features.profiles.PinVerifyResult

/** What the PIN overlay does after a verify attempt (NuvioTV ProfileSelectionScreen.kt:454-470). */
enum class TvPinOutcomeKind { Unlocked, Incorrect, Locked, VerifyFailed }

data class TvPinOutcome(val kind: TvPinOutcomeKind, val retryAfterSeconds: Int = 0)

/**
 * The "Who's watching?" PIN entry, as NuvioTV runs it: four digits, auto-submit on the fourth, the
 * server's lockout reported in seconds. Pure, so the rules are tested without the RPC or the UI.
 */
object TvProfilePinPolicy {
    const val PIN_LENGTH = 4

    /** A digit is taken only while the PIN is short and no verify is in flight. */
    fun append(pin: String, digit: Char, isWorking: Boolean): String =
        if (isWorking || !digit.isDigit() || pin.length >= PIN_LENGTH) pin else pin + digit

    fun backspace(pin: String, isWorking: Boolean): String = if (isWorking) pin else pin.dropLast(1)

    fun isComplete(pin: String): Boolean = pin.length == PIN_LENGTH

    /** [result] is null when the verify call itself failed (NuvioTV's `onFailure`). */
    fun outcome(result: PinVerifyResult?): TvPinOutcome = when {
        result == null -> TvPinOutcome(TvPinOutcomeKind.VerifyFailed)
        result.unlocked -> TvPinOutcome(TvPinOutcomeKind.Unlocked)
        result.retryAfterSeconds > 0 -> TvPinOutcome(TvPinOutcomeKind.Locked, result.retryAfterSeconds)
        else -> TvPinOutcome(TvPinOutcomeKind.Incorrect)
    }
}

/**
 * How the who's-watching row lays out [itemCount] cards in [maxWidth] (NuvioTV ProfileGridLayoutPolicy,
 * B61): compact cards only when full-size ones don't fit even with the tight gap, and a row that still
 * overflows scrolls instead of squeezing the last cards to nothing.
 */
object TvProfileGridLayout {
    data class Layout(val compact: Boolean, val gap: Float, val scrollable: Boolean)

    fun layout(itemCount: Int, maxWidth: Float, cardWidth: Float, compactCardWidth: Float, gap: Float, compactGap: Float): Layout {
        val defaultWidth = rowWidth(itemCount, cardWidth, gap)
        val fullSizeTightWidth = rowWidth(itemCount, cardWidth, compactGap)
        val compact = defaultWidth > maxWidth && fullSizeTightWidth > maxWidth
        val chosenGap = if (defaultWidth > maxWidth) compactGap else gap
        val chosenWidth = rowWidth(itemCount, if (compact) compactCardWidth else cardWidth, chosenGap)
        return Layout(compact = compact, gap = chosenGap, scrollable = chosenWidth > maxWidth)
    }

    fun rowWidth(itemCount: Int, cardWidth: Float, gap: Float): Float =
        if (itemCount <= 0) 0f else cardWidth * itemCount + gap * (itemCount - 1)
}
