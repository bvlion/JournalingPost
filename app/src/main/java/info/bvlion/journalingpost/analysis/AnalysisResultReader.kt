package info.bvlion.journalingpost.analysis

import kotlinx.coroutines.flow.Flow

/** 解析履歴表示はこのinterfaceのみへ依存する。 */
fun interface AnalysisResultReader {
  fun observeAll(): Flow<List<AnalysisResult>>
}
