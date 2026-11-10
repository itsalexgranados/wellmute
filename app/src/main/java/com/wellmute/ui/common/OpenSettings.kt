package com.wellmute.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.core.net.toUri
import com.wellmute.R

/**
 * Opens the first of [intents] the device's Settings app supports. Phones implement different
 * Settings pages (a moto g54 has no page for a single mode), so fall back to the main Settings
 * screen rather than crash.
 */
fun Context.openSettings(vararg intents: Intent) {
    for (intent in intents.toList() + Intent(Settings.ACTION_SETTINGS)) {
        try {
            startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Not on this device: try the next, more general page.
        }
    }
}

/** Opens [url] in the browser (Wellmute itself never goes online), or says so if there's none. */
fun Context.openLink(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, getString(R.string.link_no_browser, url), Toast.LENGTH_LONG).show()
    }
}
