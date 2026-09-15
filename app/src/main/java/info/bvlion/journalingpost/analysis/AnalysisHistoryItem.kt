package info.bvlion.journalingpost.analysis

import java.time.LocalDateTime

data class AnalysisHistoryItem(
  val id: Long,
  val periodStart: LocalDateTime,
  val periodEnd: LocalDateTime,
  val analyzedAt: LocalDateTime,
  val body: String,
) {
  val displayBody: String get() = if (body.startsWith("【要約】")) {
    body.removePrefix("【要約】").trimStart('\r', '\n')
  } else {
    body
  }
}
