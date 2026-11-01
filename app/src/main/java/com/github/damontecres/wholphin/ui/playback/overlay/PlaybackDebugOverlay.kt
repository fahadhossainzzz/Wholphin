package com.github.damontecres.wholphin.ui.playback.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.services.stream.Diag
import com.github.damontecres.wholphin.services.stream.DiagSection
import com.github.damontecres.wholphin.services.stream.DiagSnapshot
import com.github.damontecres.wholphin.services.stream.Level
import com.github.damontecres.wholphin.ui.playback.AnalyticsState
import com.github.damontecres.wholphin.ui.playback.CurrentPlayback
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

private val Ok = Color(0xFF5BD46B)
private val Warn = Color(0xFFFFC23D)
private val Bad = Color(0xFFFF5A5A)
private val Value = Color(0xFFEDEDED)

private fun Level.color() =
    when (this) {
        Level.OK -> Ok
        Level.WARN -> Warn
        Level.BAD -> Bad
    }

@Composable
private fun rememberSnapshot(): DiagSnapshot? {
    val snapshot by produceState(Diag.snapshot) {
        while (true) {
            value = Diag.snapshot
            delay(1.seconds)
        }
    }
    return snapshot
}

/**
 * NoBars: the full diagnostics panel, shown with the player controls. Eight sections in two rows
 * of four plus the episode line, colour-coded, sized so one phone photo of the TV captures all of it.
 */
@Composable
fun PlaybackDebugOverlay(
    analyticsState: AnalyticsState,
    currentPlayback: CurrentPlayback?,
    modifier: Modifier = Modifier,
) {
    val snapshot = rememberSnapshot()
    val valueStyle = TextStyle(fontSize = 11.sp, lineHeight = 13.sp, color = Value, fontFamily = FontFamily.SansSerif)
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.78f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (snapshot == null) {
            Text("Collecting playback details…", style = valueStyle)
            return@Column
        }
        snapshot.sections.chunked(4).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                row.forEach { DiagBlock(it, valueStyle, Modifier.weight(1f)) }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            snapshot.footer.forEach {
                Text(it, style = valueStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "Bitrate now ${analyticsState.bitrate} · est ${analyticsState.bitrateEstimate} · " +
                    "backend ${currentPlayback?.backend ?: "?"}",
                style = valueStyle.copy(color = Value.copy(alpha = 0.7f)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DiagBlock(
    section: DiagSection,
    valueStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp), modifier = modifier) {
        Text(
            "● ${section.title}",
            style = valueStyle.copy(color = section.level.color(), fontWeight = FontWeight.Bold, fontSize = 12.sp),
        )
        section.lines.forEach {
            val alert = it.contains("JUDDER") || it.contains("SW!")
            Text(
                it,
                style = if (alert) valueStyle.copy(color = Warn) else valueStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** NoBars: one line along the top while watching, when debug info is on and the controls are hidden. */
@Composable
fun PlaybackDebugCompact(modifier: Modifier = Modifier) {
    val snapshot = rememberSnapshot() ?: return
    Text(
        "● " + snapshot.compact,
        style = TextStyle(fontSize = 11.sp, color = snapshot.compactLevel.color()),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            modifier
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
