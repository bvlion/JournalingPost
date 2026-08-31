package info.bvlion.journalingpost.widget

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.bvlion.journalingpost.MainViewModel
import info.bvlion.journalingpost.R
import info.bvlion.journalingpost.di.appViewModelFactory
import info.bvlion.journalingpost.journal.JournalSource
import info.bvlion.journalingpost.mood.Mood
import info.bvlion.journalingpost.mood.MoodRecordOverlay
import info.bvlion.journalingpost.mood.MoodSnapshot
import info.bvlion.journalingpost.ui.theme.JournalingPostTheme
import kotlinx.coroutines.launch

/** 既存MainViewModel/JournalRecorderを再利用する。 */
class MoodEntryActivity : ComponentActivity() {
  private val viewModel: MainViewModel by viewModels { appViewModelFactory }
  private var mood by mutableStateOf<Mood?>(null)

  // Widgetのタップ1回を1つの入力sessionとして識別する。同じMoodを続けてタップした場合でも
  // 前回のnote/メモ展開状態を引き継がないよう、Moodではなくこの値をComposeのkeyにする。
  private var sessionId by mutableIntStateOf(0)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val initialMood = Mood.fromExtraValue(intent.getStringExtra(EXTRA_MOOD))
    if (initialMood == null) {
      finish()
      return
    }
    mood = initialMood
    sessionId = savedInstanceState?.getInt(STATE_SESSION_ID) ?: 0
    // scrimをsystem bar領域まで途切れなく描くため、translucent windowでもcontentを全画面へ広げる。
    enableEdgeToEdge()

    setContent {
      JournalingPostTheme {
        mood?.let { currentMood ->
          val currentSessionId = sessionId
          val uiState by viewModel.uiState.collectAsStateWithLifecycle()
          val moodLabel = stringResource(currentMood.labelRes)

          key(currentSessionId) {
            MoodEntryScreen(
              mood = currentMood,
              uiState = uiState,
              onRecord = { note ->
                viewModel.record(
                  note = note,
                  mood = MoodSnapshot(id = currentMood.name, emoji = currentMood.emoji, label = moodLabel),
                  source = JournalSource.WIDGET,
                )
              },
              onClose = { closeSession(currentSessionId) },
            )
          }
        }
      }
    }
  }

  // sessionIdが復元されないと、構成変更後にrememberSaveableのkeyがずれて入力中のnoteを失う。
  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putInt(STATE_SESSION_ID, sessionId)
  }

  /** launchMode=singleTaskのため、Widgetを再度タップしても新しいinstanceは作られずここへ届く。 */
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    if (!viewModel.uiState.value.acceptsNewMoodEntry()) return
    val newMood = Mood.fromExtraValue(intent.getStringExtra(EXTRA_MOOD)) ?: return
    // 構成変更でActivityが作り直されたときも新しいMoodを読めるようにIntentごと差し替える。
    setIntent(intent)
    mood = newMood
    sessionId++
    viewModel.resetState()
  }

  /** 古いsessionのfadeが遅れて完了しても、その間に始まった新しいsessionをfinishしない。 */
  private fun closeSession(id: Int) {
    if (id != sessionId) return
    finish()
  }

  companion object {
    const val EXTRA_MOOD = "info.bvlion.journalingpost.extra.MOOD"
    private const val STATE_SESSION_ID = "info.bvlion.journalingpost.state.SESSION_ID"
  }
}

/**
 * 記録処理中(LOADING)だけは、新しいentryへ置き換えると実行中のrecord()をcancelしてしまうため
 * 受け付けない。完了済みのSUCCESSは次のWidgetタップを取りこぼさないよう受け付ける。
 */
internal fun MainViewModel.UiState.acceptsNewMoodEntry(): Boolean =
  this != MainViewModel.UiState.LOADING

private const val CLOSE_FADE_DURATION_MS = 250

/**
 * Widgetから起動したときのMood記録画面。ダイアログのUI・挙動は[MoodRecordOverlay]で
 * 記録画面と共通化し、ここではActivityのlifecycle固有の処理(fade out、finish、
 * 完了Toast、前sessionの結果を引き継がないためのgating)だけを持つ。
 */
@Composable
fun MoodEntryScreen(
  mood: Mood,
  uiState: MainViewModel.UiState,
  onRecord: (String) -> Unit,
  onClose: () -> Unit,
) {
  // MainViewModelは前のsessionの結果を保持したままなので、このsessionが記録を始めるまでは
  // その結果へ反応しない。そうしないと、直前の記録が成功したまま開かれたsessionが
  // いきなりfade/finishしてWidgetタップを失う。
  var hasRequestedRecord by rememberSaveable { mutableStateOf(false) }
  var isClosing by remember { mutableStateOf(false) }
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  val contentAlpha = remember { Animatable(1f) }
  val successMessage = stringResource(R.string.record_success)

  val sessionState = if (hasRequestedRecord) uiState else MainViewModel.UiState.INIT
  val isRecording = sessionState == MainViewModel.UiState.LOADING
  val isSuccess = sessionState == MainViewModel.UiState.SUCCESS
  // SUCCESS到達後もrecomposeで「記録」ボタンが一瞬通常表示へ戻らないよう、LOADING/SUCCESS/
  // fade中(isClosing)のすべてを「操作不能」として扱う。MainViewModelはINITへ戻さないため、
  // この操作lockはUI側だけで判定する。
  val isInteractionLocked = isRecording || isSuccess || isClosing
  val hasFailure = sessionState == MainViewModel.UiState.FAILURE

  // finish()するとViewModelのcoroutineごと破棄されるため、fade outを完了させてから
  // Activityを閉じる。ここはWidgetタップで開いて記録後すぐ閉じるsurfaceで、閉じたあとに
  // 結果を伝えるためSnackbarではなくToastを使う(閉じても安全なapplicationContext)。
  fun requestClose(showSuccessToast: Boolean) {
    if (isClosing) return
    isClosing = true
    coroutineScope.launch {
      contentAlpha.animateTo(targetValue = 0f, animationSpec = tween(durationMillis = CLOSE_FADE_DURATION_MS))
      onClose()
      if (showSuccessToast) {
        Toast.makeText(context.applicationContext, successMessage, Toast.LENGTH_SHORT).show()
      }
    }
  }

  LaunchedEffect(sessionState) {
    if (isSuccess) {
      requestClose(showSuccessToast = true)
    }
  }

  // lock中もBackを消費する。BackHandlerをdisableするとActivity標準のfinishへフォールスルーし、
  // 記録処理中のrecord() coroutineがViewModelごとcancelされ得るため。
  BackHandler {
    if (!isInteractionLocked) {
      requestClose(showSuccessToast = false)
    }
  }

  MoodRecordOverlay(
    moodEmoji = mood.emoji,
    moodLabel = stringResource(mood.labelRes),
    isInteractionLocked = isInteractionLocked,
    hasFailure = hasFailure,
    onRecord = { note ->
      hasRequestedRecord = true
      onRecord(note)
    },
    onDismiss = { requestClose(showSuccessToast = false) },
    modifier = Modifier.alpha(contentAlpha.value),
  )
}

@Preview(showBackground = true)
@Composable
fun MoodEntryScreenPreview() {
  JournalingPostTheme {
    MoodEntryScreen(
      mood = Mood.HAPPY,
      uiState = MainViewModel.UiState.INIT,
      onRecord = {},
      onClose = {},
    )
  }
}
