package com.nuvio.app.features.iptv

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.layout.fillMaxWidth
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.provider_confirm_field_label
import nuvio.composeapp.generated.resources.provider_setup_cancel
import org.jetbrains.compose.resources.stringResource

/**
 * Type-to-confirm for Detach and Remove playlist (every playlist; [DestructiveConfirmPolicy]). The
 * destructive button is inert until the typed word matches, Return does nothing unless it already does,
 * Cancel comes first, and the keyboard opens on the field so the word can be typed at once. Nothing a
 * stray tap does can reach [onConfirm].
 */
@Composable
internal fun DestructiveConfirmDialog(
    action: DestructiveAction,
    copy: DestructiveConfirmCopy,
    confirmLabel: String,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    var typed by rememberSaveable(action) { mutableStateOf("") }
    val confirmed = DestructiveConfirmPolicy.isConfirmed(action, typed)
    val focus = remember { FocusRequester() }
    LaunchedEffect(action) { runCatching { focus.requestFocus() } }

    DialogSurface(
        onDismissRequest = { if (!busy) onDismiss() },
        title = copy.title,
        message = copy.message,
    ) {
        copy.extra?.let {
            Text(text = it, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
        }
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            singleLine = true,
            enabled = !busy,
            label = { Text(stringResource(Res.string.provider_confirm_field_label, DestructiveConfirmPolicy.requiredWord(action))) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                // Return confirms only a matching word; on anything else it does nothing at all.
                if (DestructiveConfirmPolicy.returnConfirms(action, typed) && !busy) onConfirm()
            }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = tokens.colors.danger.copy(alpha = tokens.opacity.strong),
                unfocusedBorderColor = tokens.colors.borderDefault.copy(alpha = tokens.opacity.medium),
                focusedContainerColor = tokens.colors.surface,
                unfocusedContainerColor = tokens.colors.surface,
                focusedLabelColor = tokens.colors.textSecondary,
                unfocusedLabelColor = tokens.colors.textMuted,
                focusedTextColor = tokens.colors.textPrimary,
                unfocusedTextColor = tokens.colors.textPrimary,
                cursorColor = tokens.colors.danger,
            ),
        )
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.provider_setup_cancel),
                onClick = onDismiss,
                style = DialogButtonStyle.Secondary,
                enabled = !busy,
            )
            DialogButton(
                text = confirmLabel,
                onClick = onConfirm,
                style = DialogButtonStyle.Destructive,
                enabled = confirmed,
                loading = busy,
            )
        }
    }
}
