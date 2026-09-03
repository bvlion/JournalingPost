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
 * fresh install後の初回起動時に一度だけ表示する、AI解析機能の案内(#67)。
 *
 * 閉じただけ(バックジェスチャーや外側タップを含む)ではAI解析を有効化しない。[onDismiss]は
 * 「今はしない」と同じ「使用しない」のまま利用開始する扱いとし、以後この案内を出さないことにも使う。
 */
@Composable
fun AnalysisIntroductionDialog(
  onSetup: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.analysis_introduction_title)) },
    text = { Text(stringResource(R.string.analysis_introduction_body)) },
    confirmButton = {
      TextButton(onClick = onSetup) { Text(stringResource(R.string.analysis_introduction_setup)) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.analysis_introduction_skip)) }
    },
  )
}

@Preview(showBackground = true)
@Composable
private fun AnalysisIntroductionDialogPreview() {
  JournalingPostTheme {
    AnalysisIntroductionDialog(onSetup = {}, onDismiss = {})
  }
}
