package com.github.damontecres.wholphin.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

object HeaderUtils {
    val topPadding = 48.dp
    val bottomPadding = 32.dp
    val startPadding = 8.dp

    val padding = PaddingValues(top = topPadding, bottom = bottomPadding, start = startPadding)

    val height = 180.dp

    val logoHeight = 60.dp

    val modifier =
        Modifier
            .padding(padding)
            .height(height)

    /**
     * NoBars: the browsing header's frame (title line, three fact columns, three lines of story). Half the empty
     * margin on top and that space given to the header instead, so the poster rows stay where they were.
     */
    val browseTopPadding = 24.dp
    val browseHeight = 204.dp
    val browseModifier =
        Modifier
            .padding(top = browseTopPadding, bottom = bottomPadding, start = startPadding)
            .height(browseHeight)
}
