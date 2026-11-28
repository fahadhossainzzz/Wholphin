package com.nobars.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.nobars.jellyfin.NbJellyfin
import java.util.UUID

/** Where the app is. Tabs are the four bottom-bar pages; the others open on top of them. */
sealed interface Screen {
    data object ServerPick : Screen

    data class SignIn(
        val server: NbJellyfin.Server,
    ) : Screen

    data class Title(
        val id: UUID,
    ) : Screen
}

enum class Tab(
    val label: String,
    val glyph: String,
) {
    HOME("Home", "⌂"),
    LIBRARY("Library", "▦"),
    SEARCH("Search", "⌕"),
    SETTINGS("Settings", "⚙"),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NbTheme { PhoneRoot() } }
    }
}

@Composable
fun PhoneRoot() {
    val nb = NbJellyfin.current
    val session by nb.session.collectAsState()
    val stack = remember { mutableStateListOf<Screen>() }
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val nav =
        remember {
            object : Nav {
                override fun open(s: Screen) {
                    stack.add(s)
                }

                override fun back() {
                    if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                }

                override fun signedOut() {
                    stack.clear()
                }
            }
        }

    if (session == null) {
        // Not signed in: server, then sign-in
        val top = stack.lastOrNull()
        BackHandler(enabled = top is Screen.SignIn) { nav.back() }
        Scaffold { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                when (top) {
                    is Screen.SignIn -> SignInScreen(top.server, nav)
                    else -> ServerScreen(nav)
                }
            }
        }
        return
    }

    val top = stack.lastOrNull { it is Screen.Title }
    BackHandler(enabled = stack.isNotEmpty() || tab != Tab.HOME) {
        if (stack.isNotEmpty()) nav.back() else tab = Tab.HOME
    }
    Scaffold(
        bottomBar = {
            if (top == null) {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = t == tab,
                            onClick = { tab = t },
                            icon = { Text(t.glyph, fontSize = 20.sp) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (top is Screen.Title) {
                TitleScreen(top.id, nav)
            } else {
                when (tab) {
                    Tab.HOME -> HomeScreen(nav)
                    Tab.LIBRARY -> LibraryScreen(nav)
                    Tab.SEARCH -> SearchScreen(nav)
                    Tab.SETTINGS -> SettingsScreen(nav)
                }
            }
        }
    }
}

interface Nav {
    fun open(s: Screen)

    fun back()

    fun signedOut()
}
