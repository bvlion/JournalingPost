package info.bvlion.journalingpost.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow

/**
 * 1度きりの結果を運ぶ[events]を、画面のlifecycleがSTARTEDの間だけ購読する。
 *
 * [LaunchedEffect]で直接collectすると、composableがcompositionに残っている限りbackground中でも
 * 結果を消費してしまい、Snackbarを見せられないまま失う。STARTEDの間だけ購読すれば、背面にいる間や
 * 対象画面を離れている間に届いた結果はChannelのbufferへ残り、画面が再開したときに受け取れる。
 */
@Composable
fun <T> EventEffect(events: Flow<T>, onEvent: (T) -> Unit) {
  val lifecycleOwner = LocalLifecycleOwner.current
  val currentOnEvent by rememberUpdatedState(onEvent)
  LaunchedEffect(events, lifecycleOwner) {
    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
      events.collect { currentOnEvent(it) }
    }
  }
}
