package com.wellmute.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.ConcurrentHashMap

/** An app in a list to pick from. */
data class AppChoice(val packageName: String, val label: String)

/** Names and icons of installed apps, cached for the lifetime of the process. */
class AppInfoRepository(context: Context) {

    private val packageManager = context.packageManager
    private val labels = ConcurrentHashMap<String, String>()
    private val icons = ConcurrentHashMap<String, ImageBitmap>()

    fun labelFor(packageName: String): String = labels.getOrPut(packageName) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    /** Apps with a launcher icon, as package names. */
    fun launchableApps(): List<String> =
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }
            .distinct()

    /** Launchable apps the user may pick in a list, by name. Slow: call off the main thread. */
    fun choosableApps(isProtected: (String) -> Boolean): List<AppChoice> =
        launchableApps()
            .filterNot(isProtected)
            .map { AppChoice(it, labelFor(it)) }
            .sortedBy { it.label.lowercase() }

    /** Null for apps that have been uninstalled since they were used. */
    fun iconFor(packageName: String): ImageBitmap? = icons[packageName] ?: try {
        packageManager.getApplicationIcon(packageName)
            .toBitmap(ICON_SIZE_PX, ICON_SIZE_PX)
            .asImageBitmap()
            .also { icons[packageName] = it }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private companion object {
        const val ICON_SIZE_PX = 96
    }
}
