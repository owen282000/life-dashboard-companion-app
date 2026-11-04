package com.owen282000.lifedashboard.ui.ld

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** One pushed page. [id] is unique, so the same route twice in a stack keeps two states apart. */
@Serializable
data class LDEntry(val id: String, val route: LDRoute, val tab: LDTab) {
    /** Ids only count up, so a newer page has the higher one: that is how a push differs from a pop. */
    internal val order: Long get() = id.drop(1).toLong()
}

/** The whole navigation state as plain data: a stack per tab and the tab in front. */
@Serializable
data class LDBackStack(
    val tab: LDTab = LDTab.Health,
    val stacks: Map<LDTab, List<LDEntry>> = emptyMap(),
    val nextId: Long = 1
) {
    /** The pushed pages of the tab in front, without its root. */
    val current: List<LDEntry> get() = stacks[tab].orEmpty()

    /** A second tap while the page is sliding in does not open it twice. */
    fun push(route: LDRoute): LDBackStack {
        if (current.lastOrNull()?.route == route) return this
        return copy(stacks = stacks + (tab to current + LDEntry("e$nextId", route, tab)), nextId = nextId + 1)
    }

    fun pop(): LDBackStack = copy(stacks = stacks + (tab to current.dropLast(1)))

    /** The stack of the tab that is left stays as it is, for coming back. */
    fun selectTab(next: LDTab): LDBackStack = copy(tab = next)

    fun popToRoot(on: LDTab = tab): LDBackStack = copy(stacks = stacks + (on to emptyList()))

    /** [route] in place of the page in front, as a running backfill replaces the page that started it. */
    fun replaceTop(route: LDRoute): LDBackStack = pop().let { it.copy(stacks = it.stacks + (tab to it.current + LDEntry("e$nextId", route, tab)), nextId = nextId + 1) }

    fun encode(): String = json.encodeToString(this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Null for what cannot be read, and the app then starts on its first tab. */
        fun decode(text: String): LDBackStack? = runCatching { json.decodeFromString<LDBackStack>(text) }.getOrNull()
    }
}

/**
 * The app's navigation: `LocalLDNavigator.current` on every screen. Saved across rotation and
 * process death. [onRemoved] hears of every page that is gone for good, for what is kept per page.
 */
@Stable
class LDNavigator(initial: LDBackStack = LDBackStack()) {
    var state by mutableStateOf(initial)
        private set

    internal var onRemoved: (LDEntry) -> Unit = {}

    val tab: LDTab get() = state.tab
    val stack: List<LDEntry> get() = state.current

    fun push(route: LDRoute) {
        state = state.push(route)
    }

    /** Back: true when a page went. On a root screen nothing goes, and Android takes it from there. */
    fun pop(): Boolean {
        val top = state.current.lastOrNull() ?: return false
        state = state.pop()
        onRemoved(top)
        return true
    }

    /** Back from the page [entryId], and nothing when that is no longer in front: a second tap while it slides out. */
    fun pop(entryId: String?) {
        if (state.current.lastOrNull()?.id == entryId) pop()
    }

    fun selectTab(tab: LDTab) {
        state = state.selectTab(tab)
    }

    fun popToRoot(tab: LDTab = state.tab) {
        val gone = state.stacks[tab].orEmpty()
        state = state.popToRoot(tab)
        gone.forEach(onRemoved)
    }

    fun replaceTop(route: LDRoute) {
        val top = state.current.lastOrNull()
        state = state.replaceTop(route)
        top?.let(onRemoved)
    }

    /** From outside the stack, such as a pairing link: on the route's own tab, over its root. */
    fun open(route: LDRoute) {
        selectTab(route.homeTab)
        popToRoot(route.homeTab)
        push(route)
    }

    companion object {
        val Saver: Saver<LDNavigator, String> = Saver(
            save = { it.state.encode() },
            restore = { saved -> LDBackStack.decode(saved)?.let(::LDNavigator) ?: LDNavigator() }
        )
    }
}

@Composable
fun rememberLDNavigator(): LDNavigator = rememberSaveable(saver = LDNavigator.Saver) { LDNavigator() }

val LocalLDNavigator = staticCompositionLocalOf<LDNavigator> { error("No LDNavigator: this screen runs outside LDShell") }

/** The id of the page around this composable, null on a root screen. */
val LocalLDEntryId = compositionLocalOf<String?> { null }

/**
 * Keeps what a screen is editing across rotation, in memory only, because it can hold a webhook
 * secret or a broker password: that stays out of the saved-instance Bundle. It goes when the page is
 * popped or the process ends.
 */
class LDDraftStore : ViewModel() {
    private val drafts = mutableMapOf<String, MutableState<Any?>>()

    @Suppress("UNCHECKED_CAST")
    internal fun <T> state(page: String, key: String, initial: () -> T): MutableState<T> =
        drafts.getOrPut("$page/$key") { mutableStateOf(initial()) } as MutableState<T>

    internal fun clear(page: String) {
        drafts.keys.removeAll { it.startsWith("$page/") }
    }
}

/**
 * The screen-local draft of a page: `var draft by rememberLDDraft { actions.mqttDraft() }`. It starts
 * from [initial] once, survives rotation, and is gone with the page, which is how Back discards it.
 */
@Composable
fun <T> rememberLDDraft(initial: () -> T): MutableState<T> {
    val page = LocalLDEntryId.current ?: "root"
    // Two drafts on one page share a composite key hash, so each gets an id of its own.
    val key = rememberSaveable { UUID.randomUUID().toString() }
    val store: LDDraftStore = viewModel()
    return remember(page, key) { store.state(page, key, initial) }
}
