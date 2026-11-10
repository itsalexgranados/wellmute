package com.wellmute.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.wellmute.R
import kotlinx.coroutines.launch
import java.text.Normalizer

private const val SEARCH_KEY = "app-search"

/**
 * A search box for an app list: type part of an app's name to narrow the list. When it's tapped,
 * it scrolls to the top of [listState], so the matching apps get the space above the keyboard.
 */
fun LazyListScope.appSearchItem(query: String, onQueryChange: (String) -> Unit, listState: LazyListState) {
    item(key = SEARCH_KEY) {
        val scope = rememberCoroutineScope()
        val focusManager = LocalFocusManager.current
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text(stringResource(R.string.search_apps)) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = if (query.isEmpty()) {
                null
            } else {
                {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.search_clear))
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            // "Search" on the keyboard closes it, so the whole filtered list is visible.
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .onFocusChanged { focus ->
                    if (!focus.isFocused) return@onFocusChanged
                    val index = listState.layoutInfo.visibleItemsInfo.find { it.key == SEARCH_KEY }?.index ?: return@onFocusChanged
                    scope.launch { listState.animateScrollToItem(index) }
                },
        )
    }
}

/** Whether [label] matches what was typed, ignoring case and accents ("cafe" finds "Café"). */
fun matchesSearch(label: String, query: String): Boolean {
    if (query.isBlank()) return true
    return label.foldForSearch().contains(query.trim().foldForSearch())
}

private fun String.foldForSearch(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
