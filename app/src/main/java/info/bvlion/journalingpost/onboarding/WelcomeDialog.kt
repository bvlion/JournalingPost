package info.bvlion.journalingpost.onboarding

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import info.bvlion.journalingpost.R
import info.bvlion.journalingpost.ui.theme.JournalingPostTheme

/**
 * fresh install後の初回起動時に一度だけ表示する、記録を促す案内(#67)。
 * 閉じると[info.bvlion.journalingpost.mood.MoodRecordScreen]側で気分を選ぶ場所への
 * 一時的な視覚誘導へ進む。
 */
@Composable
fun WelcomeDialog(onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.record_welcome_title)) },
    text = { Text(stringResource(R.string.record_welcome_body)) },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.record_welcome_confirm)) }
    },
  )
}

@Preview(showBackground = true)
@Composable
private fun WelcomeDialogPreview() {
  JournalingPostTheme {
    WelcomeDialog(onDismiss = {})
  }
}
