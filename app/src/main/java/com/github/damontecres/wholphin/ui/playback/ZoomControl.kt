package com.github.damontecres.wholphin.ui.playback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.ui.tryRequestFocus
import kotlin.math.roundToInt

/** NoBars: zoom steps in percent offered in the Zoom panel. */
private val zoomSteps = listOf(1, 5)

/**
 * NoBars: live zoom panel. Each button changes the zoom immediately while the video keeps playing.
 * "Auto" returns to the automatic Full screen fill.
 */
@Composable
fun ZoomControl(
    zoom: Float,
    onChangeZoom: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.tryRequestFocus() }
    val percent = (zoom * 100).roundToInt()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        Text(
            text = "Zoom: $percent%",
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            zoomSteps.reversed().forEach { step ->
                SubtitleDelayButton(
                    text = "-$step",
                    onClick = { onChangeZoom.invoke((percent - step) / 100f) },
                )
            }
            SubtitleDelayButton(
                text = "Auto",
                onClick = { onChangeZoom.invoke(1f) },
            )
            zoomSteps.forEachIndexed { index, step ->
                SubtitleDelayButton(
                    text = "+$step",
                    onClick = { onChangeZoom.invoke((percent + step) / 100f) },
                    modifier = if (index == 0) Modifier.focusRequester(focusRequester) else Modifier,
                )
            }
        }
    }
}
