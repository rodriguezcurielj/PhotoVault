package com.photovault.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.photovault.app.data.Memory
import com.photovault.app.data.MediaItem
import com.photovault.app.data.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data class Viewer(val items: List<MediaItem>, val start: Int, val albumId: Long? = null, val lockedPassword: String? = null) : Screen
    data class Editor(val item: MediaItem) : Screen
    /** kind: favorites | videos | archive | recent | screenshots | place:<city> | folder:<bucketId> | search:<query> */
    data class Grid(val title: String, val kind: String) : Screen
    data class Album(val id: Long) : Screen
    data object Trash : Screen
    data object Locked : Screen
    data object Map : Screen
    data object Settings : Screen
    data object FreeUp : Screen
    data object Folders : Screen
    data object Import : Screen
    data class Story(val memory: Memory) : Screen
}

/** Back stack + helpers shared by every screen. */
class AppController(val actions: Actions, val snackbar: SnackbarHostState, val scope: CoroutineScope, val onExit: (() -> Unit)?) {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    var tab by androidx.compose.runtime.mutableIntStateOf(0)
    /** True while picking photos on the Library tab (the tab bar turns into the selection bar). */
    var selectingHome by androidx.compose.runtime.mutableStateOf(false)
    val top get() = stack.last()
    fun push(s: Screen) { stack.add(s) }
    fun pop() {
        when {
            stack.size > 1 -> stack.removeAt(stack.lastIndex)
            tab != 0 -> tab = 0 // Back from Search/Library goes to Photos first
            else -> onExit?.invoke()
        }
    }
    fun replaceTop(s: Screen) { stack[stack.lastIndex] = s }

    // Scroll positions survive opening a photo and coming back.
    private val gridStates = HashMap<Any, androidx.compose.foundation.lazy.grid.LazyGridState>()
    fun gridState(key: Any) = gridStates.getOrPut(key) { androidx.compose.foundation.lazy.grid.LazyGridState() }

    fun toast(msg: String) { scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(msg) } }

    /** Runs a photo action in the background and shows its result. */
    fun run(block: suspend () -> String?) {
        scope.launch {
            val msg = try { block() } catch (e: Exception) { e.message ?: "Something went wrong" }
            if (!msg.isNullOrBlank()) toast(msg)
        }
    }
}

val LocalApp = staticCompositionLocalOf<AppController> { error("no app") }

@Composable
fun App(actions: Actions, initial: Screen? = null, onExit: (() -> Unit)? = null) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val app = remember { AppController(actions, snackbar, scope, onExit).also { c -> if (initial != null) c.stack[0] = initial } }
    val loggedIn by Repo.loggedIn.collectAsState()

    CompositionLocalProvider(LocalApp provides app) {
        Box(Modifier.fillMaxSize()) {
            if (!loggedIn && app.stack.firstOrNull() is Screen.Home) {
                LoginScreen()
            } else {
                BackHandler(enabled = app.stack.size > 1 || app.tab != 0 || onExit != null) { app.pop() }
                when (val s = app.top) {
                    Screen.Home -> HomeScreen()
                    is Screen.Viewer -> ViewerScreen(s)
                    is Screen.Editor -> EditorScreen(s.item)
                    is Screen.Grid -> GridScreen(s)
                    is Screen.Album -> AlbumScreen(s.id)
                    Screen.Trash -> TrashScreen()
                    Screen.Locked -> LockedScreen()
                    Screen.Map -> MapScreen()
                    Screen.Settings -> SettingsScreen()
                    Screen.FreeUp -> FreeUpScreen()
                    Screen.Folders -> FoldersScreen()
                    Screen.Import -> ImportScreen()
                    is Screen.Story -> StoryScreen(s.memory)
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}
