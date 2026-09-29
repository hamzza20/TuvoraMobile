package com.tuvora.tvos.screens

import com.nuvio.app.features.profiles.MAX_PROFILES
import com.nuvio.app.features.profiles.PRIMARY_PROFILE_INDEX
import com.nuvio.app.features.profiles.PinVerifyResult

/** What the PIN overlay is for (NuvioTV ProfilePinOverlayState). */
enum class TvPinMode { Unlock, Set, VerifyForChange, VerifyForRemove, VerifyForDelete }

/**
 * Where a PIN overlay is: its mode, whether a new PIN is being confirmed, the first entry of a new
 * PIN, the verified current PIN (change flow), and an error the flow raised itself (mismatch).
 */
data class TvPinFlowState(
    val mode: TvPinMode,
    val confirming: Boolean = false,
    val draft: String? = null,
    val currentPin: String? = null,
    val localError: String? = null,
)

enum class TvPinActionKind {
    /** Call verify_profile_pin with [TvPinAction.pin]. */
    Verify,
    /** Call set_profile_pin with [TvPinAction.pin] and [TvPinAction.currentPin]. */
    SetPin,
    /** Call clear_profile_pin with [TvPinAction.pin] as the current PIN. */
    ClearPin,
    /** No call: move the overlay to [TvPinAction.next]. */
    Advance,
}

data class TvPinAction(val kind: TvPinActionKind, val pin: String, val currentPin: String?, val next: TvPinFlowState)

/** What follows a PIN the server accepted. */
enum class TvPinVerifiedKind { OpenProfile, StartNewPin, ConfirmDelete }

enum class TvPinSaveKind { Saved, CurrentPinRequired, Failed }

data class TvPinSaveOutcome(val kind: TvPinSaveKind, val message: String? = null, val next: TvPinFlowState? = null)

/** One entry of the profile options dialog (NuvioTV "Profile Options"). */
enum class TvProfileOption { Edit, SetPin, ChangePin, RemovePin, Delete }

/**
 * The manage-profiles decisions of NuvioTV's ProfileSelectionScreen (options dialog, PIN set / change /
 * remove / delete-verify flows, the Add Profile card), pure so they are tested without the RPCs or UI.
 * Copy is NuvioTV's English (res/values/strings.xml profile_*).
 */
object TvProfileManagePolicy {
    const val NAME_MAX_LENGTH = 20
    const val DEFAULT_COLOR_HEX = "#1E88E5"

    fun canAddProfile(profileCount: Int): Boolean = profileCount < MAX_PROFILES

    fun isPrimary(profileIndex: Int): Boolean = profileIndex == PRIMARY_PROFILE_INDEX

    /** Edit; Set PIN or Change PIN; Remove PIN when locked; Delete unless primary. */
    fun options(profileIndex: Int, pinEnabled: Boolean): List<TvProfileOption> = buildList {
        add(TvProfileOption.Edit)
        add(if (pinEnabled) TvProfileOption.ChangePin else TvProfileOption.SetPin)
        if (pinEnabled) add(TvProfileOption.RemovePin)
        if (!isPrimary(profileIndex)) add(TvProfileOption.Delete)
    }

    fun optionTitle(option: TvProfileOption): String = when (option) {
        TvProfileOption.Edit -> "Edit"
        TvProfileOption.SetPin -> "Set PIN"
        TvProfileOption.ChangePin -> "Change PIN"
        TvProfileOption.RemovePin -> "Remove PIN"
        TvProfileOption.Delete -> "Delete"
    }

    /** The overlay an option opens; null for options that don't need a PIN first. */
    fun pinModeFor(option: TvProfileOption, pinEnabled: Boolean): TvPinMode? = when (option) {
        TvProfileOption.SetPin -> TvPinMode.Set
        TvProfileOption.ChangePin -> TvPinMode.VerifyForChange
        TvProfileOption.RemovePin -> TvPinMode.VerifyForRemove
        TvProfileOption.Delete -> if (pinEnabled) TvPinMode.VerifyForDelete else null
        TvProfileOption.Edit -> null
    }

    /** Profile names are capped at NuvioTV's 20 characters. */
    fun clampName(name: String): String = name.take(NAME_MAX_LENGTH)

    fun canSave(name: String, isSaving: Boolean): Boolean = name.isNotBlank() && !isSaving

    /**
     * Picking an avatar takes its background colour (NuvioTV onAvatarSelected); picking the selected
     * one again clears it and restores [fallbackColorHex].
     */
    fun toggleAvatar(selectedId: String?, tappedId: String, tappedBgColor: String?, currentColorHex: String, fallbackColorHex: String): Pair<String?, String> =
        if (selectedId == tappedId) null to fallbackColorHex
        else tappedId to (tappedBgColor?.takeIf { it.isNotBlank() } ?: currentColorHex)

    // --- Avatar picker (components/AvatarPickerGrid.kt) --------------------------------------

    private val PINNED_AVATAR_CATEGORIES = listOf("anime", "animation", "tv", "movie", "gaming")

    /** "all", then NuvioTV's pinned categories that exist, then the rest alphabetically. */
    fun avatarCategories(avatarCategories: List<String>): List<String> {
        val present = avatarCategories.mapNotNull { it.trim().takeIf { c -> c.isNotEmpty() } }.distinct()
        return buildList {
            add("all")
            PINNED_AVATAR_CATEGORIES.forEach { pinned -> if (present.any { it.equals(pinned, ignoreCase = true) }) add(pinned) }
            present.filterNot { c -> PINNED_AVATAR_CATEGORIES.any { it.equals(c, ignoreCase = true) } }
                .sortedBy { it.lowercase() }
                .forEach(::add)
        }
    }

    fun inCategory(avatarCategory: String, selected: String): Boolean =
        selected == "all" || avatarCategory.trim().equals(selected, ignoreCase = true)

    /** profile_avatar_category_* labels; unknown categories are title-cased. */
    fun categoryLabel(category: String): String = when (category.lowercase()) {
        "all" -> "All"
        "supporter" -> "Supporter"
        "anime" -> "Anime"
        "animation" -> "Animation"
        "movie" -> "Movie"
        "tv" -> "TV"
        "gaming" -> "Gaming"
        else -> category.replaceFirstChar { it.uppercase() }
    }

    // --- PIN flow ---------------------------------------------------------------------------

    /** The fourth digit was entered. */
    fun submit(state: TvPinFlowState, pin: String): TvPinAction = when (state.mode) {
        TvPinMode.Set -> when {
            !state.confirming -> TvPinAction(TvPinActionKind.Advance, pin, state.currentPin, state.copy(confirming = true, draft = pin, localError = null))
            pin == state.draft -> TvPinAction(TvPinActionKind.SetPin, pin, state.currentPin, state)
            else -> TvPinAction(
                TvPinActionKind.Advance, pin, state.currentPin,
                state.copy(confirming = false, draft = null, localError = "PINs did not match. Enter a new PIN again."),
            )
        }
        TvPinMode.VerifyForRemove -> TvPinAction(TvPinActionKind.ClearPin, pin, pin, state)
        else -> TvPinAction(TvPinActionKind.Verify, pin, null, state)
    }

    /** verify_profile_pin said yes: open the profile, go on to the new PIN, or confirm the delete. */
    fun verified(mode: TvPinMode): TvPinVerifiedKind = when (mode) {
        TvPinMode.VerifyForChange -> TvPinVerifiedKind.StartNewPin
        TvPinMode.VerifyForDelete -> TvPinVerifiedKind.ConfirmDelete
        else -> TvPinVerifiedKind.OpenProfile
    }

    /** The change flow, once the current PIN checked out: enter the new one, carrying the old. */
    fun startNewPin(currentPin: String): TvPinFlowState = TvPinFlowState(TvPinMode.Set, currentPin = currentPin)

    /** set_profile_pin's result ([result] null when the call threw). */
    fun afterSet(state: TvPinFlowState, result: PinVerifyResult?): TvPinSaveOutcome = when {
        result?.unlocked == true -> TvPinSaveOutcome(TvPinSaveKind.Saved)
        // The server knows a PIN this device didn't (stale pinEnabled): collect the current one.
        result?.currentPinRequired == true -> TvPinSaveOutcome(
            TvPinSaveKind.CurrentPinRequired,
            "This profile already has a PIN. Enter the current PIN to change it.",
            TvPinFlowState(TvPinMode.VerifyForChange),
        )
        else -> TvPinSaveOutcome(TvPinSaveKind.Failed, result?.message?.takeIf { it.isNotBlank() } ?: "Could not save PIN. Try again.", state.copy(confirming = false, draft = null))
    }

    /** clear_profile_pin's result: removed, or the repository's reason (offline) / NuvioTV's "incorrect". */
    fun afterClear(result: PinVerifyResult?): TvPinSaveOutcome =
        if (result?.unlocked == true) TvPinSaveOutcome(TvPinSaveKind.Saved)
        else TvPinSaveOutcome(TvPinSaveKind.Failed, result?.message?.takeIf { it.isNotBlank() } ?: "Current PIN is incorrect.")

    fun heading(state: TvPinFlowState, name: String): String = when (state.mode) {
        TvPinMode.Unlock -> "Enter your PIN to access $name."
        TvPinMode.VerifyForChange -> "Enter current PIN to change PIN for $name."
        TvPinMode.VerifyForRemove -> "Enter current PIN to remove lock for $name."
        TvPinMode.VerifyForDelete -> "Enter current PIN to delete $name."
        TvPinMode.Set -> if (state.confirming) "Confirm your new PIN." else "Create a 4-digit PIN for $name."
    }

    fun support(state: TvPinFlowState): String = when (state.mode) {
        TvPinMode.Unlock -> "Use your remote or keyboard to enter 4 digits."
        TvPinMode.VerifyForChange -> "Enter the current 4-digit PIN before setting a new one."
        TvPinMode.VerifyForRemove -> "Enter the current 4-digit PIN to remove this lock."
        TvPinMode.VerifyForDelete -> "Enter the current 4-digit PIN before deleting this profile."
        TvPinMode.Set -> if (state.confirming) "Re-enter the same 4 digits to finish setup." else "This PIN will be required before opening this profile."
    }

    /** NuvioTV shows the forgot-PIN hint whenever an existing PIN is asked for. */
    fun showsForgotHint(mode: TvPinMode): Boolean = mode != TvPinMode.Set

    /** "Verifying…" for checks, "Saving…" while a new PIN is stored. */
    fun workingText(mode: TvPinMode): String = if (mode == TvPinMode.Set) "Saving…" else "Verifying…"
}
