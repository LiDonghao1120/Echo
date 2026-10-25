package com.novcloud.echo.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

object SystemBars {
    private var status by mutableIntStateOf(0)
    private var nav by mutableIntStateOf(0)
    val lightStatusIcons: Boolean get() = status > 0
    val lightNavIcons: Boolean get() = nav > 0

    internal fun push(n: Boolean) { status++; if (n) nav++ }
    internal fun pop(n: Boolean) { status--; if (n) nav-- }
}

@Composable
fun LightSystemIcons(navigation: Boolean = false) {
    DisposableEffect(navigation) {
        SystemBars.push(navigation)
        onDispose { SystemBars.pop(navigation) }
    }
}
