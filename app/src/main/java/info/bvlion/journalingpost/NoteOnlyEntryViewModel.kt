package info.bvlion.journalingpost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.bvlion.journalingpost.settings.NoteOnlyEntryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 記録画面が「メモだけ記録」を表示するかどうかだけを持つ。設定画面の[SettingsViewModel]と分けているのは、
 * 記録画面がWebhook設定(Android Keystoreの初期化を伴う)の読み込みまで巻き込まないため。
 *
 * 読み込み確定前はnullで、その間は導線の有無を確定させない。
 */
class NoteOnlyEntryViewModel(repository: NoteOnlyEntryRepository) : ViewModel() {
  val isEnabled: StateFlow<Boolean?> = repository.isNoteOnlyEntryEnabled
    .map<Boolean, Boolean?> { it }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
