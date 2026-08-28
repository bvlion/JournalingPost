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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import info.bvlion.journalingpost.MainViewModel
import info.bvlion.journalingpost.MainViewModelFactory
import info.bvlion.journalingpost.journal.JournalSource
import info.bvlion.journalingpost.mood.Mood
import info.bvlion.journalingpost.mood.MoodSnapshot
import info.bvlion.journalingpost.ui.theme.JournalingPostTheme
import kotlinx.coroutines.launch

/** 既存MainViewModel/JournalRecorderを再利用する。 */
class MoodEntryActivity : ComponentActivity() {
  private val viewModel: MainViewModel by viewModels { MainViewModelFactory }
  private var mood by mutableStateOf<Mood?>(null)

  // Widgetのタップ1回を1つの入力sessionとして識別する。同じMoodを続けてタップした場合でも
  // 前回のnote/メモ展開状態を引き継がないよう、Moodではなくこの値をComposeのkeyにする。
  private var sessionId by mutableIntStateOf(0)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    MainViewModelFactory.initialize(applicationContext)

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
          val uiState by viewModel.uiState.collectAsState()
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
 * 受け付けない。完了済みのSUCCESS系は次のWidgetタップを取りこぼさないよう受け付ける。
 */
internal fun MainViewModel.UiState.acceptsNewMoodEntry(): Boolean =
  this != MainViewModel.UiState.LOADING

private const val CLOSE_FADE_DURATION_MS = 250
private const val SCRIM_ALPHA = 0.32f

@Composable
fun MoodEntryScreen(
  mood: Mood,
  uiState: MainViewModel.UiState,
  onRecord: (String) -> Unit,
  onClose: () -> Unit,
) {
  var note by rememberSaveable { mutableStateOf("") }
  var isNoteVisible by rememberSaveable { mutableStateOf(false) }
  // MainViewModelは前のsessionの結果を保持したままなので、このsessionが記録を始めるまでは
  // その結果へ反応しない。そうしないと、直前の記録が成功したまま開かれたsessionが
  // いきなりfade/finishしてWidgetタップを失う。
  var hasRequestedRecord by rememberSaveable { mutableStateOf(false) }
  var isClosing by remember { mutableStateOf(false) }
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  val contentAlpha = remember { Animatable(1f) }

  val sessionState = if (hasRequestedRecord) uiState else MainViewModel.UiState.INIT
  val isRecording = sessionState == MainViewModel.UiState.LOADING
  val isSuccess =
    sessionState == MainViewModel.UiState.SUCCESS || sessionState == MainViewModel.UiState.SUCCESS_DELIVERY_FAILED
  // SUCCESS到達後もrecomposeで「記録」ボタンが一瞬通常表示へ戻らないよう、LOADING/SUCCESS系/
  // fade中(isClosing)のすべてを「操作不能」として扱う。MainViewModelはINITへ戻さないため、
  // この操作lockはUI側だけで判定する。
  val isInteractionLocked = isRecording || isSuccess || isClosing
  val hasFailure = sessionState == MainViewModel.UiState.FAILURE

  // finish()するとViewModelのcoroutineごと破棄されるため、fade outを完了させてから
  // Activityを閉じる。Toastはfinish後でも安全なapplicationContextを使い、閉じたあとに出す。
  fun requestClose(showSuccessToast: Boolean) {
    if (isClosing) return
    isClosing = true
    coroutineScope.launch {
      contentAlpha.animateTo(targetValue = 0f, animationSpec = tween(durationMillis = CLOSE_FADE_DURATION_MS))
      onClose()
      if (showSuccessToast) {
        Toast.makeText(context.applicationContext, "記録しました", Toast.LENGTH_SHORT).show()
      }
    }
  }

  LaunchedEffect(sessionState) {
    // Webhook配送失敗を記録自体の失敗として扱わず、SUCCESSと同様に画面を閉じる。
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

  Box(
    modifier = Modifier
      .fillMaxSize()
      .alpha(contentAlpha.value)
      .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
      .pointerInput(isInteractionLocked) {
        if (isInteractionLocked) return@pointerInput
        detectTapGestures { requestClose(showSuccessToast = false) }
      },
  ) {
    Box(
      modifier = Modifier.fillMaxSize().safeDrawingPadding(),
      contentAlignment = Alignment.Center,
    ) {
      Surface(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 20.dp)
          // 閉じる操作はscrim tapだけに限りたいので、Surface上のtapはここで止める。
          .pointerInput(Unit) { detectTapGestures {} },
        shape = AlertDialogDefaults.shape,
        color = AlertDialogDefaults.containerColor,
        contentColor = AlertDialogDefaults.titleContentColor,
        tonalElevation = AlertDialogDefaults.TonalElevation,
      ) {
        Column(modifier = Modifier.padding(24.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = mood.emoji, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(8.dp))
            Text(text = stringResource(mood.labelRes), style = MaterialTheme.typography.titleMedium)
          }

          if (isNoteVisible) {
            val focusRequester = remember { FocusRequester() }
            // 「メモを追加」を選んだ直後だけfocusを移し、ソフトキーボードを表示させる。
            // 初期表示から入力欄を出さないのは、文章を書かなくても記録が成立することを
            // UI自体で表現するため。
            LaunchedEffect(Unit) {
              focusRequester.requestFocus()
            }
            TextField(
              value = note,
              onValueChange = { note = it },
              modifier = Modifier.fillMaxWidth().padding(top = 16.dp).focusRequester(focusRequester),
              enabled = !isInteractionLocked,
              maxLines = 4,
              trailingIcon = if (note.isNotEmpty()) {
                {
                  IconButton(
                    onClick = { note = "" },
                    enabled = !isInteractionLocked,
                    modifier = Modifier.semantics { contentDescription = "メモをクリア" },
                  ) {
                    Text("✕")
                  }
                }
              } else {
                null
              },
            )
          }

          if (hasFailure) {
            Text(
              text = "記録に失敗しました。もう一度お試しください",
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodySmall,
              modifier = Modifier.padding(top = 8.dp),
            )
          }

          FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            if (!isNoteVisible) {
              TextButton(onClick = { isNoteVisible = true }, enabled = !isInteractionLocked) {
                Text("メモを追加")
              }
            }
            TextButton(onClick = { requestClose(showSuccessToast = false) }, enabled = !isInteractionLocked) {
              Text("記録しない")
            }
            Button(
              onClick = {
                hasRequestedRecord = true
                onRecord(note)
              },
              enabled = !isInteractionLocked,
            ) {
              if (isInteractionLocked) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
              } else {
                Text("記録")
              }
            }
          }
        }
      }
    }
  }
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
