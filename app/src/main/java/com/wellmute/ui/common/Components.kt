package com.wellmute.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wellmute.apps.AppInfoRepository

/** An app's icon, or a neutral circle if the app has been uninstalled. */
@Composable
fun AppIcon(packageName: String, appInfo: AppInfoRepository, size: Dp = 40.dp) {
    val icon = remember(packageName) { appInfo.iconFor(packageName) }
    if (icon != null) {
        Image(icon, contentDescription = null, modifier = Modifier.size(size))
    } else {
        Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest))
    }
}

/** Explains a missing permission and opens the Settings screen to grant it. */
@Composable
fun AccessCard(@StringRes message: Int, @StringRes button: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = onClick, modifier = Modifier.padding(top = 12.dp)) {
                Text(stringResource(button))
            }
        }
    }
}
