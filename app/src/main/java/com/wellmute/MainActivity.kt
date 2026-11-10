package com.wellmute

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.wellmute.ui.navigation.Routes
import com.wellmute.ui.navigation.WellmuteNavHost
import com.wellmute.ui.theme.WellmuteTheme

class MainActivity : ComponentActivity() {

    /** A screen to open, from a notification ("Bedtime mode is on…" opens Bedtime). */
    private val routeToOpen = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) routeToOpen.value = routeFrom(intent)
        setContent {
            WellmuteTheme {
                WellmuteNavHost(routeToOpen = routeToOpen.value, onRouteOpened = { routeToOpen.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeFrom(intent)?.let { routeToOpen.value = it }
    }

    /** This activity is exported (it's the launcher's), so any app can send a route: accept only ours. */
    private fun routeFrom(intent: Intent): String? = intent.getStringExtra(EXTRA_ROUTE)?.takeIf { it in OPENABLE_ROUTES }

    companion object {
        private const val EXTRA_ROUTE = "com.wellmute.ROUTE"

        /** The screens Wellmute's notifications open. */
        private val OPENABLE_ROUTES = setOf(Routes.BEDTIME, Routes.FOCUS, Routes.DASHBOARD)

        /** Opens Wellmute on [route] (one of the navigation routes), above Home. */
        fun open(context: Context, route: String): Intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
