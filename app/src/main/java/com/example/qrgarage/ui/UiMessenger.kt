package com.example.qrgarage.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class UiMessage(
    val text: String,
    val actionLabel: String? = null,
    val action: (suspend () -> Unit)? = null,
)

/**
 * App-wide snackbar queue. Undo actions (ITEM-13/14) run in an app-lifetime scope so they
 * still work after the screen that triggered them has been popped.
 */
@Singleton
class UiMessenger @Inject constructor() {
    private val channel = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = channel.receiveAsFlow()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun show(text: String) {
        channel.trySend(UiMessage(text))
    }

    fun showUndo(text: String, undo: suspend () -> Unit) {
        channel.trySend(UiMessage(text, "Undo", undo))
    }

    fun runAction(message: UiMessage) {
        val action = message.action ?: return
        scope.launch { action() }
    }
}
