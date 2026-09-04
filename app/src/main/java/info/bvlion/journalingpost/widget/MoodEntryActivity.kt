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
import info.bvlion.journalingpost.MoodNoteInputViewModel
import info.bvlion.journalingpost.MoodViewModel
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
  private val moodViewModel: MoodViewModel by viewModels { appViewModelFactory }
  private val moodNoteInputViewModel: MoodNoteInputViewModel by viewModels { appViewModelFactory }
  private var moodId by mutableStateOf<String?>(null)

  // 「メモだけ記録」はMoodを持たないため、moodIdの有無とは別に保持する。
  private var isNoteOnly by mutableStateOf(false)

  // Widgetのタップ1回を1つの入力sessionとして識別する。同じMoodを続けてタップした場合でも
  // 前回のnote/メモ展開状態を引き継がないよう、Moodではなくこの値をComposeのkeyにする。
  private var sessionId by mutableIntStateOf(0)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    if (!applyEntryIntent(intent)) {
      finish()
      return
    }
    sessionId = savedInstanceState?.getInt(STATE_SESSION_ID) ?: 0
    // scrimをsystem bar領域まで途切れなく描くため、translucent windowでもcontentを全画面へ広げる。
    enableEdgeToEdge()

    setContent {
      JournalingPostTheme {
        val moods by moodViewModel.moods.collectAsStateWithLifecycle()
        val isMoodNoteInputInitiallyOpen by moodNoteInputViewModel.isInitiallyOpen.collectAsStateWithLifecycle()
        val currentMoodId = moodId
        val isNoteOnlyEntry = isNoteOnly
        val currentMood = moods?.firstOrNull { it.id == currentMoodId }

        // 「メモだけ記録」はMood設定に依存しないため、Mood一覧の読み込み結果でfinishしない。
        LaunchedEffect(moods, currentMoodId, isNoteOnlyEntry) {
          if (!isNoteOnlyEntry && moods != null && currentMood == null) finish()
        }

        if (isNoteOnlyEntry || (currentMood != null && isMoodNoteInputInitiallyOpen != null)) {
          val recordingMood = if (isNoteOnlyEntry) null else currentMood
          val currentSessionId = sessionId
          val uiState by viewModel.uiState.collectAsStateWithLifecycle()

          key(currentSessionId) {
            MoodEntryScreen(
              mood = recordingMood,
              isNoteInitiallyVisible = recordingMood != null && isMoodNoteInputInitiallyOpen == true,
              uiState = uiState,
              onRecord = { note ->
                viewModel.record(
                  note = note,
                  mood = recordingMood?.let { MoodSnapshot(id = it.id, emoji = it.emoji, label = it.label) },
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
    if (!applyEntryIntent(intent)) return
    // 構成変更でActivityが作り直されたときも新しい記録対象を読めるようにIntentごと差し替える。
    setIntent(intent)
    sessionId++
    viewModel.resetState()
  }

  /** 記録対象をIntentから読み取る。どちらの記録も指していない場合はfalseを返し、状態を変えない。 */
  private fun applyEntryIntent(intent: Intent): Boolean {
    if (intent.getBooleanExtra(EXTRA_NOTE_ONLY, false)) {
      moodId = null
      isNoteOnly = true
      return true
    }
    val newMoodId = intent.getStringExtra(EXTRA_MOOD)?.takeIf { it.isNotBlank() } ?: return false
    moodId = newMoodId
    isNoteOnly = false
    return true
  }

  /** 古いsessionのfadeが遅れて完了しても、その間に始まった新しいsessionをfinishしない。 */
  private fun closeSession(id: Int) {
    if (id != sessionId) return
    finish()
  }

  companion object {
    const val EXTRA_MOOD = "info.bvlion.journalingpost.extra.MOOD"
    const val EXTRA_NOTE_ONLY = "info.bvlion.journalingpost.extra.NOTE_ONLY"
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
 * Widgetから起動したときの記録画面。ダイアログのUI・挙動は[MoodRecordOverlay]で
 * 記録画面と共通化し、ここではActivityのlifecycle固有の処理(fade out、finish、
 * 完了Toast、前sessionの結果を引き継がないためのgating)だけを持つ。
 *
 * @param mood 記録するMood。nullは「メモだけ記録」を表す。
 */
@Composable
fun MoodEntryScreen(
  mood: Mood?,
  isNoteInitiallyVisible: Boolean,
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
    mood = mood,
    isNoteInitiallyVisible = isNoteInitiallyVisible,
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
      mood = Mood(id = "1", emoji = "😄", label = "嬉しい"),
      isNoteInitiallyVisible = false,
      uiState = MainViewModel.UiState.INIT,
      onRecord = {},
      onClose = {},
    )
  }
}

@Preview(showBackground = true)
@Composable
fun NoteOnlyEntryScreenPreview() {
  JournalingPostTheme {
    MoodEntryScreen(
      mood = null,
      isNoteInitiallyVisible = false,
      uiState = MainViewModel.UiState.INIT,
      onRecord = {},
      onClose = {},
    )
  }
}
