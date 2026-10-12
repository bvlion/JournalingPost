package info.bvlion.journalingpost

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

// 解析用の依存関係を生成せず、購読開始だけをActivityの再生成をまたいで保持する。
internal class AnalysisObservationViewModel : ViewModel() {
  var shouldObserveAnalysis by mutableStateOf(false)
}
