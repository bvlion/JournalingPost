package info.bvlion.journalingpost.analysis

import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [zoneId]でのローカル日時へ変換し、解析日時の新しい順に並べる。analyzedAtが同一の結果が
 * 複数あってもidの降順で二次的に順序を確定させるため、並び順は呼び出しごとに揺れない。
 */
fun List<AnalysisResult>.toAnalysisHistoryItems(zoneId: ZoneId): List<AnalysisHistoryItem> =
  sortedWith(compareByDescending<AnalysisResult> { it.analyzedAt }.thenByDescending { it.id })
    .map { it.toAnalysisHistoryItem(zoneId) }

private fun AnalysisResult.toAnalysisHistoryItem(zoneId: ZoneId) = AnalysisHistoryItem(
  id = id,
  periodStart = LocalDateTime.ofInstant(periodStart, zoneId),
  periodEnd = LocalDateTime.ofInstant(periodEnd, zoneId),
  analyzedAt = LocalDateTime.ofInstant(analyzedAt, zoneId),
  body = body,
)
