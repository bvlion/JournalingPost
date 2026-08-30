package info.bvlion.journalingpost.analysis

/** 解析結果の端末保存はこのinterfaceのみへ依存する。 */
fun interface AnalysisResultWriter {
  suspend fun save(result: AnalysisResult): Long
}
