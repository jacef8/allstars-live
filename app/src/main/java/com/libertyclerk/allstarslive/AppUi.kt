package com.libertyclerk.allstarslive

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tiny shared UI state. [inGame] is set by the web scorer (via the JS bridge) when an
 * actual game/console is on screen — MainActivity uses it to hide the bottom tab bar
 * during a game (the tabs are seldom used mid-game) behind a small floating toggle.
 */
object AppUi {
    private val _inGame = MutableStateFlow(false)
    val inGame: StateFlow<Boolean> = _inGame
    fun setInGame(v: Boolean) { _inGame.value = v }
}
