package com.enna.lite.ui.menu

import androidx.compose.runtime.Composable
import com.enna.lite.models.MediaMetadata
import androidx.media3.common.Timeline

@Composable
fun SelectionMediaMetadataMenu(
    songSelection: List<MediaMetadata>,
    onDismiss: () -> Unit,
    clearAction: () -> Unit,
    currentItems: List<Timeline.Window>
) {
    // Placeholder UI
}