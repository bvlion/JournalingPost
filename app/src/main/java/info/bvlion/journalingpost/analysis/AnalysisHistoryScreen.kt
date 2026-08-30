package info.bvlion.journalingpost.analysis

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 解析履歴の遷移先。解析結果(AnalysisResult)の保存・一覧表示・empty stateは #37 で実装する。
 * それまでは仮のmodel/repository/文言を持たず、遷移だけできる空のdestinationとする。
 */
@Composable
fun AnalysisHistoryScreen() {
  Box(modifier = Modifier.fillMaxSize())
}
