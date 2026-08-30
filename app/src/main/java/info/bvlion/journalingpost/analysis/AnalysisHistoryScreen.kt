package info.bvlion.journalingpost.analysis

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import info.bvlion.journalingpost.ui.theme.JournalingPostTheme

/**
 * 解析履歴の遷移先。解析結果(AnalysisResult)の保存と一覧表示は #37 で実装する。
 * それまでは存在しない解析結果を表示せず、空状態だけを示す最小画面とする。
 */
@Composable
fun AnalysisHistoryScreen() {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(
      text = "解析履歴はまだありません",
      style = MaterialTheme.typography.bodyMedium,
    )
  }
}

@Preview(showBackground = true)
@Composable
fun AnalysisHistoryScreenPreview() {
  JournalingPostTheme {
    AnalysisHistoryScreen()
  }
}
