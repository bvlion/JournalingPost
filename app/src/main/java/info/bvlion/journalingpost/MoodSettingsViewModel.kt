package info.bvlion.journalingpost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.bvlion.journalingpost.mood.Mood
import info.bvlion.journalingpost.mood.MoodRepository
import info.bvlion.journalingpost.mood.MoodValidator
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class MoodSettingsViewModel(
  private val repository: MoodRepository,
  private val refreshWidgets: suspend () -> Unit,
) : ViewModel() {
  private val _uiState = MutableStateFlow(MoodSettingsUiState())
  val uiState = _uiState.asStateFlow()

  private val _events = Channel<MoodSettingsEvent>(Channel.BUFFERED)
  val events: Flow<MoodSettingsEvent> = _events.receiveAsFlow()

  fun onScreenOpened() {
    while (_events.tryReceive().isSuccess) Unit
    _uiState.value = MoodSettingsUiState()
    viewModelScope.launch {
      _uiState.value = MoodSettingsUiState(
        moods = repository.moods.first().map { it.toDraft() },
        isLoading = false,
      )
    }
  }

  fun updateEmoji(id: String, emoji: String) {
    updateMood(id) { copy(emoji = emoji) }
  }

  fun updateLabel(id: String, label: String) {
    updateMood(id) { copy(label = label) }
  }

  fun moveUp(id: String) {
    move(id, -1)
  }

  fun moveDown(id: String) {
    move(id, 1)
  }

  fun addMood() {
    val current = _uiState.value
    if (current.isLoading || current.isSaving || current.moods.size >= MoodValidator.MAX_MOOD_COUNT) return
    _uiState.value = current.copy(
      moods = current.moods + MoodDraft(id = UUID.randomUUID().toString(), emoji = "", label = ""),
    )
  }

  fun removeMood(id: String) {
    val current = _uiState.value
    if (current.isLoading || current.isSaving || current.moods.size <= MoodValidator.MIN_MOOD_COUNT) return
    _uiState.value = current.copy(moods = current.moods.filterNot { it.id == id })
  }

  fun save() {
    val current = _uiState.value
    if (!current.canSave) return
    val normalized = current.moods.map { draft ->
      Mood(id = draft.id, emoji = draft.emoji.trim(), label = draft.label.trim())
    }
    if (!MoodValidator.isValid(normalized)) return

    _uiState.value = current.copy(isSaving = true)
    viewModelScope.launch {
      try {
        repository.save(normalized)
        try {
          refreshWidgets()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // 設定自体は保存済みで、Widgetは次回のsystem updateでも同じRepositoryから再描画される。
        }
        _uiState.value = MoodSettingsUiState(
          moods = normalized.map { it.toDraft() },
          isLoading = false,
        )
        _events.send(MoodSettingsEvent.Saved)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _uiState.value = current
        _events.send(MoodSettingsEvent.SaveFailed)
      }
    }
  }

  private fun updateMood(id: String, update: MoodDraft.() -> MoodDraft) {
    val current = _uiState.value
    if (current.isLoading || current.isSaving) return
    _uiState.value = current.copy(
      moods = current.moods.map { mood -> if (mood.id == id) mood.update() else mood },
    )
  }

  private fun move(id: String, offset: Int) {
    val current = _uiState.value
    if (current.isLoading || current.isSaving) return
    val from = current.moods.indexOfFirst { it.id == id }
    val to = from + offset
    if (from < 0 || to !in current.moods.indices) return
    val reordered = current.moods.toMutableList()
    val moved = reordered.removeAt(from)
    reordered.add(to, moved)
    _uiState.value = current.copy(moods = reordered)
  }

  private fun Mood.toDraft() = MoodDraft(id = id, emoji = emoji, label = label)
}

data class MoodDraft(
  val id: String,
  val emoji: String,
  val label: String,
) {
  val isContentBlank: Boolean get() = emoji.isBlank() && label.isBlank()
  val isEmojiInvalid: Boolean get() = emoji.isNotBlank() && !MoodValidator.isSingleEmoji(emoji.trim())
}

data class MoodSettingsUiState(
  val moods: List<MoodDraft> = emptyList(),
  val isLoading: Boolean = true,
  val isSaving: Boolean = false,
) {
  val canSave: Boolean get() = !isLoading && !isSaving && moods.isNotEmpty() &&
    moods.none { it.isContentBlank || it.isEmojiInvalid }
}

sealed interface MoodSettingsEvent {
  data object Saved : MoodSettingsEvent
  data object SaveFailed : MoodSettingsEvent
}
