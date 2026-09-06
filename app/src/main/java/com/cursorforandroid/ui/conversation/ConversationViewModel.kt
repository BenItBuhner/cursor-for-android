package com.cursorforandroid.ui.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.domain.Agent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ConversationUiState(
    val agent: Agent? = null,
    val conversation: ConversationState,
    val draft: String = "",
    val isSending: Boolean = false,
    val toast: String? = null,
    val isPinned: Boolean = false,
)

class ConversationViewModel(private val graph: AppGraph, val agentId: String) : ViewModel() {

    private val draft = MutableStateFlow("")
    private val sending = MutableStateFlow(false)
    private val toast = MutableStateFlow<String?>(null)

    val agent: StateFlow<Agent?> = graph.agents.state.map { s -> s.agents.firstOrNull { it.id == agentId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), graph.agents.agent(agentId))

    val conversation: StateFlow<ConversationState> = graph.conversations.state(agentId)
    val draftText: StateFlow<String> = draft.asStateFlow()
    val isSending: StateFlow<Boolean> = sending.asStateFlow()
    val toastMessage: StateFlow<String?> = toast.asStateFlow()
    val isPinned: StateFlow<Boolean> = graph.prefs.localAgentState.map { agentId in it.pinnedIds }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        graph.conversations.attach(agentId)
    }

    override fun onCleared() {
        graph.conversations.detach(agentId)
        super.onCleared()
    }

    fun setDraft(value: String) { draft.value = value }

    fun send() {
        val text = draft.value.trim()
        if (text.isEmpty() || sending.value) return
        viewModelScope.launch {
            sending.value = true
            draft.value = ""
            graph.conversations.sendFollowUp(agentId, text).onFailure {
                draft.value = text
                toast.value = it.userMessage()
            }
            sending.value = false
        }
    }

    fun cancelRun() = viewModelScope.launch {
        graph.conversations.cancelActiveRun(agentId).onFailure { toast.value = it.userMessage() }
    }

    fun reload() = graph.conversations.reload(agentId)

    fun togglePinned() = viewModelScope.launch { graph.prefs.togglePinned(agentId) }

    fun archive(onDone: () -> Unit) = viewModelScope.launch {
        graph.agents.archive(agentId).onSuccess { toast.value = "Agent archived"; onDone() }.onFailure { toast.value = it.userMessage() }
    }

    fun unarchive() = viewModelScope.launch {
        graph.agents.unarchive(agentId).onSuccess { toast.value = "Agent unarchived" }.onFailure { toast.value = it.userMessage() }
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        graph.conversations.forget(agentId)
        graph.agents.delete(agentId).onSuccess { onDone() }.onFailure { toast.value = it.userMessage() }
    }

    fun clearToast() { toast.value = null }

    class Factory(private val graph: AppGraph, private val agentId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ConversationViewModel(graph, agentId) as T
    }
}
