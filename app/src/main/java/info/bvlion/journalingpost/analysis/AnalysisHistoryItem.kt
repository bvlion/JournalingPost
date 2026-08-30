package info.bvlion.journalingpost.analysis

import java.time.LocalDateTime

data class AnalysisHistoryItem(
  val id: Long,
  val periodStart: LocalDateTime,
  val periodEnd: LocalDateTime,
  val analyzedAt: LocalDateTime,
  val body: String,
)
