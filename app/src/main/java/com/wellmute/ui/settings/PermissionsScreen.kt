package com.wellmute.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wellmute.R
import com.wellmute.WellmuteApp
import com.wellmute.ui.common.SubpageScaffold
import com.wellmute.ui.setup.permissionCards
import com.wellmute.ui.setup.rememberPermissionStatus

/** Every permission Wellmute can use: what it's for, what it's never used for, and whether it's allowed. */
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val container = (LocalContext.current.applicationContext as WellmuteApp).container
    val granted = rememberPermissionStatus(container.permissions)

    SubpageScaffold(title = R.string.settings_permissions, onBack = onBack) { padding ->
        LazyColumn(contentPadding = padding) {
            item {
                Text(
                    stringResource(R.string.permissions_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            permissionCards(container.permissions, granted)
        }
    }
}
