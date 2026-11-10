package com.wellmute.ui.common

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import android.provider.Settings
import com.wellmute.R
import com.wellmute.contacts.StarredContact
import com.wellmute.contacts.StarredContacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Who "Starred contacts only" means: the starred contacts, read (never changed) from Contacts.
 * Adding one picks a contact and opens it in the Contacts app to tap the star; tapping a listed
 * one opens it there to unstar it. Without permission to read contacts, a button asks for it.
 */
@Composable
fun StarredContactsList(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Re-read on return from Contacts or from the permission prompt.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    val canRead = remember(resumes) { StarredContacts.canRead(context) }
    val contacts by produceState<List<StarredContact>?>(null, resumes, canRead) {
        value = withContext(Dispatchers.IO) { StarredContacts.read(context) }
    }
    // After a refusal Android may stop showing its prompt, so the button then opens App info instead.
    var refused by rememberSaveable { mutableStateOf(false) }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) refused = true
        resumes++
    }
    // Picking a contact needs no permission; the Contacts app then lets the user star it.
    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri != null) context.openContact(uri)
    }

    Column(modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(stringResource(R.string.starred_title), style = MaterialTheme.typography.titleSmall)
        if (!canRead) {
            // First time: say plainly that access is needed before anyone can be listed.
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(R.string.starred_permission_title),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Text(
                stringResource(if (refused) R.string.starred_permission_refused else R.string.starred_permission_why),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            FilledTonalButton(
                onClick = {
                    if (refused) {
                        context.openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                    } else {
                        requestPermission.launch(Manifest.permission.READ_CONTACTS)
                    }
                },
            ) {
                Text(stringResource(if (refused) R.string.starred_open_settings else R.string.starred_allow))
            }
            return@Column
        }
        val list = contacts ?: return@Column
        if (list.isEmpty()) {
            Text(
                stringResource(R.string.starred_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        list.forEach { contact -> ContactRow(contact) { context.openContact(contact.uri) } }
        Text(
            stringResource(R.string.starred_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        TextButton(onClick = { pickContact.launch(null) }) {
            Icon(Icons.Outlined.PersonAdd, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(stringResource(R.string.starred_add))
        }
    }
}

@Composable
private fun ContactRow(contact: StarredContact, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                contact.name.first().uppercase(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(contact.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

/** Opens a contact in the Contacts app, where its star can be switched on or off. */
private fun Context.openContact(uri: Uri) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (_: ActivityNotFoundException) {
        // No contacts app: nothing to open.
    }
}

