package info.bvlion.journalingpost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.bvlion.journalingpost.settings.MoodNoteInputRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class MoodNoteInputViewModel(repository: MoodNoteInputRepository) : ViewModel() {
  val isInitiallyOpen: StateFlow<Boolean?> = repository.isMoodNoteInputInitiallyOpen
    .map<Boolean, Boolean?> { it }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
