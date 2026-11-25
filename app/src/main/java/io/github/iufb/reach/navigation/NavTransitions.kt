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
 * Вкладки «Чаты / Контакты / Настройки» переключаются без анимации, вход в аккаунт — растворением,
 * шаги входа (телефон → код → пароль) — сдвигом с растворением (#375).
 */
internal typealias NavScope = AnimatedContentTransitionScope<NavBackStackEntry>

internal val navEnter: NavScope.() -> EnterTransition = {
    when (kind()) {
        Kind.Tab -> EnterTransition.None
        Kind.Auth -> fadeIn(tween(FADE_MS))
        Kind.AuthStep -> authStepEnter(forward = true)
        Kind.Push -> slideInHorizontally(tween(SLIDE_MS, easing = IOS_EASING)) { it }
    }
}

internal val navExit: NavScope.() -> ExitTransition = {
    when (kind()) {
        Kind.Tab -> ExitTransition.None
        Kind.Auth -> fadeOut(tween(FADE_MS))
        Kind.AuthStep -> authStepExit(forward = true)
        Kind.Push -> slideOutHorizontally(tween(SLIDE_MS, easing = IOS_EASING)) { -(it * PARALLAX).toInt() }
    }
}

internal val navPopEnter: NavScope.() -> EnterTransition = {
    when (kind()) {
        Kind.Tab -> EnterTransition.None
        Kind.Auth -> fadeIn(tween(FADE_MS))
        Kind.AuthStep -> authStepEnter(forward = false)
        Kind.Push -> slideInHorizontally(tween(SLIDE_MS, easing = IOS_EASING)) { -(it * PARALLAX).toInt() }
    }
}

internal val navPopExit: NavScope.() -> ExitTransition = {
    when (kind()) {
        Kind.Tab -> ExitTransition.None
        Kind.Auth -> fadeOut(tween(FADE_MS))
        Kind.AuthStep -> authStepExit(forward = false)
        Kind.Push -> slideOutHorizontally(tween(SLIDE_MS, easing = IOS_EASING)) { it }
    }
}

private enum class Kind { Push, Tab, Auth, AuthStep }

private fun NavScope.kind(): Kind = when {
    initialState.isAuth() && targetState.isAuth() -> Kind.AuthStep
    initialState.isAuth() || targetState.isAuth() -> Kind.Auth
    initialState.isTab() && targetState.isTab() -> Kind.Tab
    else -> Kind.Push
}

private fun NavBackStackEntry.isAuth(): Boolean = destination.hierarchy.any { it.hasRoute<AuthGraph>() }

/**
 * Корень вкладок нижней панели: все три вкладки теперь в одном хосте (#395), отдельных экранов
 * навигации у них нет. Архив — тоже ChatsRoute, но он открывается слайдом.
 */
private fun NavBackStackEntry.isTab(): Boolean =
    destination.hasRoute<ChatsRoute>() && !toRoute<ChatsRoute>().archive

/**
 * Шаги входа (#375): нижний шаг всегда стоит на месте и непрозрачен, двигается только верхний.
 * Вперёд новый шаг въезжает справа на четверть ширины и проявляется поверх старого; назад верхний
 * (NavHost при «назад» кладёт уходящий сверху) уезжает вправо и гаснет, открывая предыдущий.
 * Так на середине перехода сквозь шаги не видно того, что под навигацией.
 * Общие с каталогом компонентов (AuthPreview), чтобы переход проверялся без выхода из аккаунта.
 */
internal fun authStepEnter(forward: Boolean): EnterTransition =
    if (forward) {
        slideInHorizontally(tween(AUTH_STEP_MS, easing = IOS_EASING)) { (it * AUTH_STEP_SHIFT).toInt() } + fadeIn(tween(AUTH_STEP_MS))
    } else {
        EnterTransition.None
    }

internal fun authStepExit(forward: Boolean): ExitTransition =
    if (forward) {
        // Остаётся видимым, пока новый шаг въезжает поверх. Затухание до 1 Compose считает пустым
        // и убирает шаг сразу — поэтому до незаметных 0,99
        fadeOut(tween(AUTH_STEP_MS), targetAlpha = KEEP_ALPHA)
    } else {
        slideOutHorizontally(tween(AUTH_STEP_MS, easing = IOS_EASING)) { (it * AUTH_STEP_SHIFT).toInt() } + fadeOut(tween(AUTH_STEP_MS))
    }

private const val AUTH_STEP_MS = 320
private const val KEEP_ALPHA = 0.99f
private const val AUTH_STEP_SHIFT = 0.25f

/** Насколько сдвигается нижний экран: треть ширины, как в UINavigationController. */
private const val PARALLAX = 0.3f
private const val SLIDE_MS = 380
private const val FADE_MS = 220
/** Плавное торможение, близкое к переходу UINavigationController: экран заметно едет, а не прыгает. */
private val IOS_EASING = CubicBezierEasing(0.25f, 0.46f, 0.45f, 0.94f)
