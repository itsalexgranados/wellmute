package com.wellmute.ui.navigation

import androidx.lifecycle.Lifecycle
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wellmute.WellmuteApp
import com.wellmute.ui.bedtime.BedtimeScreen
import com.wellmute.ui.bedtime.BedtimeSectionScreen
import com.wellmute.ui.bedtime.BedtimeSection
import com.wellmute.ui.dashboard.AppDetailScreen
import com.wellmute.ui.dashboard.AppDetailViewModel
import com.wellmute.ui.dashboard.DashboardScreen
import com.wellmute.ui.focus.FocusScreen
import com.wellmute.ui.focus.FocusInsightsScreen
import com.wellmute.ui.home.HomeScreen
import com.wellmute.ui.lab.NotificationLabScreen
import com.wellmute.ui.lab.UsageLabScreen
import com.wellmute.ui.reminders.RemindersScreen
import com.wellmute.ui.settings.PermissionsScreen
import com.wellmute.data.PeriodType
import com.wellmute.ui.settings.SettingsScreen
import com.wellmute.ui.settings.NotificationSettingsScreen
import com.wellmute.ui.timers.AddTimerScreen
import com.wellmute.ui.timers.AppTimersScreen
import com.wellmute.ui.setup.SetupScreen

object Routes {
    const val HOME = "home"
    const val SETUP = "setup"
    const val DASHBOARD = "dashboard"
    const val APP_DETAIL = "app/{${AppDetailViewModel.ARG_PACKAGE}}/{${AppDetailViewModel.ARG_DAY}}?${AppDetailViewModel.ARG_VIEW}={${AppDetailViewModel.ARG_VIEW}}"
    const val BEDTIME = "bedtime"
    const val BEDTIME_SECTION = "bedtime/{section}"
    const val FOCUS = "focus"
    const val FOCUS_INSIGHTS = "focus/insights"
    const val REMINDERS = "reminders"
    const val TIMERS = "timers"
    const val TIMERS_ADD = "timers/add"
    const val SETTINGS = "settings"
    const val PERMISSIONS = "permissions"
    const val NOTIFICATION_SETTINGS = "settings/notifications"
    const val USAGE_LAB = "usage_lab"
    const val NOTIFICATION_LAB = "notification_lab"

    /** [view] opens the app on the same week, month or year the dashboard shows; null opens the day. */
    fun appDetail(packageName: String, epochDay: Long, view: PeriodType?) =
        "app/$packageName/$epochDay" + (view?.let { "?${AppDetailViewModel.ARG_VIEW}=${it.name}" } ?: "")



    fun bedtimeSection(section: BedtimeSection) = "bedtime/${section.name}"
}

@Composable
fun WellmuteNavHost(routeToOpen: String? = null, onRouteOpened: () -> Unit = {}) {
    val container = (LocalContext.current.applicationContext as WellmuteApp).container
    // Wait until we know whether the first-launch setup was finished, to start in the right place.
    val setupDone = container.setup.done.collectAsStateWithLifecycle().value ?: return
    val navController = rememberNavController()
    // Only while the screen is fully shown: a second tap during the animation used to go back two
    // screens, or past the first one to an empty screen.
    val back: () -> Unit = {
        if (navController.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
            navController.popBackStack()
        }
    }
    // Also ignore taps that open a screen while another is still arriving (a double tap opened two).
    fun open(route: String) {
        if (navController.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) navController.navigate(route)
    }

    NavHost(
        navController = navController,
        startDestination = if (setupDone) Routes.HOME else Routes.SETUP,
        // Short slides, like Android's own settings, instead of the default slow cross-fade where
        // both screens overlap half-visible. Going back slides the other way (and follows the
        // back gesture as you swipe).
        enterTransition = { slideInHorizontally(tween(TRANSITION_MILLIS)) { it / 5 } + fadeIn(tween(TRANSITION_MILLIS)) },
        exitTransition = { slideOutHorizontally(tween(TRANSITION_MILLIS)) { -it / 5 } + fadeOut(tween(TRANSITION_MILLIS)) },
        popEnterTransition = { slideInHorizontally(tween(TRANSITION_MILLIS)) { -it / 5 } + fadeIn(tween(TRANSITION_MILLIS)) },
        popExitTransition = { slideOutHorizontally(tween(TRANSITION_MILLIS)) { it / 5 } + fadeOut(tween(TRANSITION_MILLIS)) },
        // The back gesture would otherwise use the library's own animation, which shrinks the screen
        // into a card and then jumps to the one behind (seen on the Pixel): follow it with the same slide.
        predictivePopEnterTransition = { slideInHorizontally(tween(TRANSITION_MILLIS)) { -it / 5 } + fadeIn(tween(TRANSITION_MILLIS)) },
        predictivePopExitTransition = { slideOutHorizontally(tween(TRANSITION_MILLIS)) { it / 5 } + fadeOut(tween(TRANSITION_MILLIS)) },
    ) {
        composable(Routes.SETUP) {
            SetupScreen(onDone = { navController.navigate(Routes.HOME) { popUpTo(Routes.SETUP) { inclusive = true } } })
        }
        composable(Routes.HOME) { HomeScreen(onNavigate = { open(it) }) }
        composable(Routes.DASHBOARD) {
            DashboardScreen(
                onBack = back,
                onOpenApp = { packageName, day, view -> open(Routes.appDetail(packageName, day.toEpochDay(), view)) },
                onOpenFocusInsights = { open(Routes.FOCUS_INSIGHTS) },
            )
        }
        composable(
            Routes.APP_DETAIL,
            arguments = listOf(
                navArgument(AppDetailViewModel.ARG_PACKAGE) { type = NavType.StringType },
                navArgument(AppDetailViewModel.ARG_DAY) { type = NavType.LongType },
                navArgument(AppDetailViewModel.ARG_VIEW) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { AppDetailScreen(onBack = back) }
        composable(Routes.BEDTIME) { BedtimeScreen(onBack = back, onOpen = { open(Routes.bedtimeSection(it)) }) }
        composable(Routes.BEDTIME_SECTION, arguments = listOf(navArgument("section") { type = NavType.StringType })) { entry ->
            val section = BedtimeSection.valueOf(entry.arguments?.getString("section") ?: BedtimeSection.SCHEDULE.name)
            BedtimeSectionScreen(section = section, onBack = back)
        }
        composable(Routes.FOCUS) { FocusScreen(onBack = back, onOpenInsights = { open(Routes.FOCUS_INSIGHTS) }) }
        composable(Routes.FOCUS_INSIGHTS) { FocusInsightsScreen(onBack = back) }
        composable(Routes.REMINDERS) { RemindersScreen(onBack = back) }
        composable(Routes.TIMERS) { AppTimersScreen(onBack = back, onAdd = { open(Routes.TIMERS_ADD) }) }
        composable(Routes.TIMERS_ADD) { AddTimerScreen(onBack = back, onDone = back) }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = back,
                onOpenPermissions = { open(Routes.PERMISSIONS) },
                onOpenNotifications = { open(Routes.NOTIFICATION_SETTINGS) },
                onOpenUsageLab = { open(Routes.USAGE_LAB) },
                onOpenNotificationLab = { open(Routes.NOTIFICATION_LAB) },
            )
        }
        composable(Routes.PERMISSIONS) { PermissionsScreen(onBack = back) }
        composable(Routes.NOTIFICATION_SETTINGS) { NotificationSettingsScreen(onBack = back) }
        composable(Routes.USAGE_LAB) { UsageLabScreen(onBack = back) }
        composable(Routes.NOTIFICATION_LAB) { NotificationLabScreen(onBack = back) }
    }

    // Opened from a notification: show that screen, with Home behind it for Back.
    LaunchedEffect(routeToOpen) {
        val route = routeToOpen ?: return@LaunchedEffect
        if (setupDone) {
            navController.navigate(route) {
                popUpTo(Routes.HOME)
                launchSingleTop = true
            }
        }
        onRouteOpened()
    }
}

/** Screen transitions: short, so going back and forth feels quick. */
private const val TRANSITION_MILLIS = 250
