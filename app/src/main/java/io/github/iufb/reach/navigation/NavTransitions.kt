package io.github.iufb.reach.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.toRoute

/**
 * Переходы как в Telegram iOS (#245): новый экран въезжает справа целиком, предыдущий уезжает влево
 * на треть; «назад» — наоборот, и системный жест «назад» ведёт этот переход за пальцем (predictive back).
 * Вкладки «Чаты / Контакты / Настройки» переключаются без анимации, вход в аккаунт — растворением.
 */
internal typealias NavScope = AnimatedContentTransitionScope<NavBackStackEntry>

internal val navEnter: NavScope.() -> EnterTransition = {
    when (kind()) {
        Kind.Tab -> EnterTransition.None
        Kind.Auth -> fadeIn(tween(FADE_MS))
        Kind.Push -> slideInHorizontally(tween(SLIDE_MS, easing = IOS_EASING)) { it }
    }
}

internal val navExit: NavScope.() -> ExitTransition = {
    when (kind()) {
        Kind.Tab -> ExitTransition.None
        Kind.Auth -> fadeOut(tween(FADE_MS))
        Kind.Push -> slideOutHorizontally(tween(SLIDE_MS, easing = IOS_EASING)) { -(it * PARALLAX).toInt() }
    }
}

internal val navPopEnter: NavScope.() -> EnterTransition = {
    when (kind()) {
        Kind.Tab -> EnterTransition.None
        Kind.Auth -> fadeIn(tween(FADE_MS))
        Kind.Push -> slideInHorizontally(tween(SLIDE_MS, easing = IOS_EASING)) { -(it * PARALLAX).toInt() }
    }
}

internal val navPopExit: NavScope.() -> ExitTransition = {
    when (kind()) {
        Kind.Tab -> ExitTransition.None
        Kind.Auth -> fadeOut(tween(FADE_MS))
        Kind.Push -> slideOutHorizontally(tween(SLIDE_MS, easing = IOS_EASING)) { it }
    }
}

private enum class Kind { Push, Tab, Auth }

private fun NavScope.kind(): Kind = when {
    initialState.isAuth() || targetState.isAuth() -> Kind.Auth
    initialState.isTab() && targetState.isTab() -> Kind.Tab
    else -> Kind.Push
}

private fun NavBackStackEntry.isAuth(): Boolean = destination.hierarchy.any { it.hasRoute<AuthGraph>() }

/** Корни вкладок нижней панели; архив — тоже ChatsRoute, но он открывается слайдом. */
private fun NavBackStackEntry.isTab(): Boolean = when {
    destination.hasRoute<ContactsRoute>() || destination.hasRoute<SettingsRoute>() -> true
    destination.hasRoute<ChatsRoute>() -> !toRoute<ChatsRoute>().archive
    else -> false
}

/** Насколько сдвигается нижний экран: треть ширины, как в UINavigationController. */
private const val PARALLAX = 0.3f
private const val SLIDE_MS = 380
private const val FADE_MS = 220
/** Плавное торможение, близкое к переходу UINavigationController: экран заметно едет, а не прыгает. */
private val IOS_EASING = CubicBezierEasing(0.25f, 0.46f, 0.45f, 0.94f)
