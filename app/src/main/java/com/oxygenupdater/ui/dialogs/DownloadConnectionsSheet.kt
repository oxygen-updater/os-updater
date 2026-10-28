package com.oxygenupdater.ui.dialogs

import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.oxygenupdater.R
import com.oxygenupdater.icons.Check
import com.oxygenupdater.icons.Symbols
import com.oxygenupdater.ui.common.animatedClickable
import com.oxygenupdater.ui.common.modifierDefaultPadding
import com.oxygenupdater.ui.common.modifierMaxWidth
import com.oxygenupdater.ui.common.modifierSemanticsNotSelected
import com.oxygenupdater.ui.common.modifierSemanticsSelected
import com.oxygenupdater.ui.theme.PreviewThemes
import com.oxygenupdater.workers.DownloadConnectionsAuto

/**
 * Lets testers force the number of parallel download connections (see [com.oxygenupdater.workers.downloadConnectionsFor]).
 *
 * @param selected the current value of the `download_connections` pref
 * @param onClick called with the chosen value ([DownloadConnectionsAuto] or one of [DownloadConnectionOptions])
 */
@Composable
fun ColumnScope.DownloadConnectionsSheet(selected: Int, onClick: (Int) -> Unit) {
    SheetHeader(R.string.settings_download_connections)

    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    // Perf: re-use common modifiers to avoid recreating the same object repeatedly
    val iconSpacerSizeModifier = Modifier.size(40.dp) // 24 + 16
    LazyColumn(
        Modifier
            .weight(1f, false)
            .testTag(DownloadConnectionsSheet_LazyColumnTestTag)
    ) {
        items(items = DownloadConnectionOptions, key = { it }) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = modifierMaxWidth
                    .animatedClickable { onClick(it) }
                    .then(modifierDefaultPadding)
                    .testTag(BottomSheet_ItemRowTestTag)
            ) {
                val primary = colorScheme.primary
                val isSelected = selected == it
                if (isSelected) Icon(
                    imageVector = Symbols.Check,
                    contentDescription = stringResource(R.string.summary_on),
                    tint = primary,
                    modifier = Modifier.padding(end = 16.dp) then modifierSemanticsSelected
                ) else Spacer(iconSpacerSizeModifier then modifierSemanticsNotSelected)

                Column {
                    Text(
                        text = downloadConnectionsTitle(it),
                        color = if (isSelected) primary else Color.Unspecified,
                        style = typography.titleSmall,
                    )
                    if (it == DownloadConnectionsAuto) Text(
                        text = stringResource(R.string.settings_download_connections_auto_subtitle),
                        color = if (isSelected) primary else colorScheme.onSurfaceVariant,
                        style = typography.bodySmall,
                    )
                }
            }
        }
    }

    SheetCaption(R.string.settings_download_connections_caption)
}

/** Title for a `download_connections` pref value, shared by the sheet and the settings item's subtitle */
@Composable
fun downloadConnectionsTitle(value: Int) = if (value == DownloadConnectionsAuto) {
    stringResource(R.string.settings_download_connections_auto)
} else stringResource(R.string.settings_download_connections_forced, value)

/** [DownloadConnectionsAuto], then the counts worth comparing against each other */
@VisibleForTesting
val DownloadConnectionOptions = listOf(DownloadConnectionsAuto, 1, 2, 4, 8)

private const val TAG = "DownloadConnectionsSheet"

@VisibleForTesting
const val DownloadConnectionsSheet_LazyColumnTestTag = TAG + "_LazyColumn"

@PreviewThemes
@Composable
fun PreviewDownloadConnectionsSheet() = PreviewModalBottomSheet {
    DownloadConnectionsSheet(selected = DownloadConnectionsAuto) {}
}
