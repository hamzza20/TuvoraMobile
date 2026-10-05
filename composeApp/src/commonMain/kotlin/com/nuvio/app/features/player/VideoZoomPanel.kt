package com.nuvio.app.features.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.compose_player_reset
import nuvio.composeapp.generated.resources.player_zoom_both
import nuvio.composeapp.generated.resources.player_zoom_height
import nuvio.composeapp.generated.resources.player_zoom_help
import nuvio.composeapp.generated.resources.player_zoom_position_horizontal
import nuvio.composeapp.generated.resources.player_zoom_position_vertical
import nuvio.composeapp.generated.resources.player_zoom_title
import nuvio.composeapp.generated.resources.player_zoom_width
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * Manual zoom (F36): width, height and position on top of Fit / Fill / Zoom. Pure UI — the runtime
 * owns the [VideoZoom] and decides whether it is remembered for the series (F37).
 * Narrow panel so the picture being adjusted stays visible beside it.
 */
@Composable
internal fun VideoZoomPanel(
    visible: Boolean,
    zoom: VideoZoom,
    onZoomChanged: (VideoZoom) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlayerSidePanel(
        visible = visible,
        onDismiss = onDismiss,
        width = 360.dp,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
        ) {
            PlayerPanelHeader(title = stringResource(Res.string.player_zoom_title)) {
                PlayerDialogButton(
                    label = stringResource(Res.string.compose_player_reset),
                    onClick = { onZoomChanged(VideoZoom.IDENTITY) },
                )
                PlayerDialogButton(
                    label = stringResource(Res.string.action_close),
                    onClick = onDismiss,
                )
            }
            Spacer(Modifier.height(8.dp))
            SubtitleHelperText(stringResource(Res.string.player_zoom_help))
            Spacer(Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ZoomStepperRow(
                    title = stringResource(Res.string.player_zoom_both),
                    value = VideoZoomPolicy.scaleLabel(zoom),
                    axis = VideoZoomAxis.Both,
                    zoom = zoom,
                    onZoomChanged = onZoomChanged,
                )
                ZoomStepperRow(
                    title = stringResource(Res.string.player_zoom_width),
                    value = percent(zoom.scaleX),
                    axis = VideoZoomAxis.Width,
                    zoom = zoom,
                    onZoomChanged = onZoomChanged,
                )
                ZoomStepperRow(
                    title = stringResource(Res.string.player_zoom_height),
                    value = percent(zoom.scaleY),
                    axis = VideoZoomAxis.Height,
                    zoom = zoom,
                    onZoomChanged = onZoomChanged,
                )
                ZoomStepperRow(
                    title = stringResource(Res.string.player_zoom_position_horizontal),
                    value = signedPercent(zoom.panX),
                    axis = VideoZoomAxis.PanX,
                    zoom = zoom,
                    onZoomChanged = onZoomChanged,
                )
                ZoomStepperRow(
                    title = stringResource(Res.string.player_zoom_position_vertical),
                    value = signedPercent(zoom.panY),
                    axis = VideoZoomAxis.PanY,
                    zoom = zoom,
                    onZoomChanged = onZoomChanged,
                )
            }
        }
    }
}

@Composable
private fun ZoomStepperRow(
    title: String,
    value: String,
    axis: VideoZoomAxis,
    zoom: VideoZoom,
    onZoomChanged: (VideoZoom) -> Unit,
) {
    SubtitleStyleSection(title = title) {
        SubtitleStyleStepper(
            value = value,
            onDecrease = { onZoomChanged(VideoZoomPolicy.adjust(zoom, axis, -1)) },
            onIncrease = { onZoomChanged(VideoZoomPolicy.adjust(zoom, axis, +1)) },
            valueWidth = 120.dp,
        )
    }
}

private fun percent(value: Float): String = "${(value * 100).roundToInt()}%"

private fun signedPercent(value: Float): String {
    val p = (value * 100).roundToInt()
    return if (p > 0) "+$p%" else "$p%"
}
