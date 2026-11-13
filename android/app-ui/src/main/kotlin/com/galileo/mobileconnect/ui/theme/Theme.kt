package com.galileo.mobileconnect.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.galileo.mobileconnect.design.Layout
import com.galileo.mobileconnect.design.ThemeTokens

/** The user's choice. Night is the factory default: this app is used in the dark (docs/android/SDD.md 3.8). */
enum class ThemeChoice(val label: String) {
    Night("Night (red)"),
    Standard("Standard");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name }
    }
}

/**
 * The tokens as UI colours. This file is the only place a literal colour may be made: every other composable reads one
 * from [LocalTheme], so a component cannot get a colour the theme did not give it (ADR-AND-007).
 */
class Theme(tokens: ThemeTokens) {
    val background = Color(tokens.background)
    val surface = Color(tokens.surface)
    val raised = Color(tokens.raised)
    val border = Color(tokens.border)
    val text = Color(tokens.text)
    val textSecondary = Color(tokens.textSecondary)
    val accent = Color(tokens.accent)
    val onAccent = Color(tokens.onAccent)
    val danger = Color(tokens.danger)
    val onDanger = Color(tokens.onDanger)
    val warning = Color(tokens.warning)
    val good = Color(tokens.good)
    val imageTint = Color(tokens.imageTint)
    val horizonFill = Color(tokens.horizonFill)
    val landNear = Color(tokens.landNear)
    val landFar = Color(tokens.landFar)
    val isNight = tokens == ThemeTokens.night

    /** For a veil over a screen, where the colour is the background's own. */
    fun veil(alpha: Float): Color = background.copy(alpha = alpha)
}

val LocalTheme = compositionLocalOf { Theme(ThemeTokens.night) }

/** For a panel whose space is short: smaller controls, over the gloved-hands size used everywhere else. */
val LocalCompact = compositionLocalOf { false }

val LocalLayout = compositionLocalOf { Layout }

/** Applied at the root, so every composable gets the theme without knowing about it. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ThemeRoot(choice: ThemeChoice, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val theme = remember(choice, systemDark) {
        Theme(
            when (choice) {
                ThemeChoice.Night -> ThemeTokens.night
                ThemeChoice.Standard -> if (systemDark) ThemeTokens.dark else ThemeTokens.light
            },
        )
    }
    CompositionLocalProvider(LocalTheme provides theme) {
        Box(Modifier.fillMaxSize().background(theme.background).windowInsetsPadding(WindowInsets.systemBars).semantics { testTagsAsResourceId = true }) { content() }
    }
}
