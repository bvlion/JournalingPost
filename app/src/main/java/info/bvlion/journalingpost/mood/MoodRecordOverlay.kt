package info.bvlion.journalingpost.mood

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import info.bvlion.journalingpost.ui.theme.JournalingPostTheme

private const val SCRIM_ALPHA = 0.32f

/**
 * Moodを選んだ後の記録ダイアログ。記録画面(App)とWidgetで同じUI・挙動を使うための共通Composable。
 *
 * scrim + 中央カードを画面全体へ重ねる。閉じる操作はscrimのタップと[onDismiss]呼び出し側に委ね、
 * fade animationやActivityのlifecycle制御(Widget側)は呼び出し元が[modifier]や状態で扱う。
 *
 * @param isInteractionLocked 記録処理中や完了直後など、操作を受け付けない状態。scrimタップも無効化する。
 */
@Composable
fun MoodRecordOverlay(
  moodEmoji: String,
  moodLabel: String,
  isInteractionLocked: Boolean,
  hasFailure: Boolean,
  onRecord: (note: String) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
      // lock中もscrimのtapは消費し、背後(NavigationBar等)へ透過させない。
      .pointerInput(isInteractionLocked) {
        detectTapGestures { if (!isInteractionLocked) onDismiss() }
      },
  ) {
    Box(
      modifier = Modifier.fillMaxSize().safeDrawingPadding(),
      contentAlignment = Alignment.Center,
    ) {
      MoodRecordCard(
        moodEmoji = moodEmoji,
        moodLabel = moodLabel,
        isInteractionLocked = isInteractionLocked,
        hasFailure = hasFailure,
        onRecord = onRecord,
        onDismiss = onDismiss,
      )
    }
  }
}

@Composable
private fun MoodRecordCard(
  moodEmoji: String,
  moodLabel: String,
  isInteractionLocked: Boolean,
  hasFailure: Boolean,
  onRecord: (note: String) -> Unit,
  onDismiss: () -> Unit,
) {
  var note by rememberSaveable { mutableStateOf("") }
  var isNoteVisible by rememberSaveable { mutableStateOf(false) }

  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 20.dp)
      // 閉じる操作はscrim tapだけに限りたいので、カード上のtapはここで止める。
      .pointerInput(Unit) { detectTapGestures {} },
    shape = AlertDialogDefaults.shape,
    color = AlertDialogDefaults.containerColor,
    contentColor = AlertDialogDefaults.titleContentColor,
    tonalElevation = AlertDialogDefaults.TonalElevation,
  ) {
    Column(modifier = Modifier.padding(24.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = moodEmoji, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.width(8.dp))
        Text(text = moodLabel, style = MaterialTheme.typography.titleMedium)
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
        TextButton(onClick = onDismiss, enabled = !isInteractionLocked) {
          Text("記録しない")
        }
        Button(
          onClick = { onRecord(note) },
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

@Preview(showBackground = true)
@Composable
fun MoodRecordOverlayPreview() {
  JournalingPostTheme {
    MoodRecordOverlay(
      moodEmoji = "🙂",
      moodLabel = "嬉しい",
      isInteractionLocked = false,
      hasFailure = false,
      onRecord = {},
      onDismiss = {},
    )
  }
}
