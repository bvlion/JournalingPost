package info.bvlion.journalingpost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.bvlion.journalingpost.mood.Mood
import info.bvlion.journalingpost.mood.MoodRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class MoodViewModel(repository: MoodRepository) : ViewModel() {
  val moods: StateFlow<List<Mood>?> = repository.moods
    .map<List<Mood>, List<Mood>?> { it }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
