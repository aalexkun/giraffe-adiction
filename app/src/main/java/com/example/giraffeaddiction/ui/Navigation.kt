package com.example.giraffeaddiction.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.giraffeaddiction.ui.components.Tab

/** The screens that sit on top of the three tabs. */
sealed interface Screen {
    data object Checklist : Screen
    data object Picker : Screen
    data class Editor(val packageName: String) : Screen
}

/** Three tabs and a tiny stack: all the navigation this app needs. */
class NavState {
    var tab by mutableStateOf(Tab.TODAY)
    val stack = mutableStateListOf<Screen>()

    val top: Screen? get() = stack.lastOrNull()

    fun push(screen: Screen) {
        stack.add(screen)
    }

    fun replace(screen: Screen) {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        stack.add(screen)
    }

    fun pop(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun select(tab: Tab) {
        stack.clear()
        this.tab = tab
    }
}

@Composable
fun rememberNavState(): NavState = remember { NavState() }
