package info.bvlion.journalingpost.analysis

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * 端末内に保存する解析結果の原本。JournalEntryとは独立して保持し、外部参照キーやcascadeは
 * 持たない。解析に使ったJournalEntryが後から削除・変更されても、生成済みの解析結果はそのまま
 * 残す。同じ対象期間に対する複数回の解析結果も、それぞれ独立した行として保持する。
 *
 * periodStart/periodEndは解析対象とした期間そのもの(UTC基準のInstant)を保存し、表示時に
 * 端末のタイムゾーンへ変換する。期間をどう決めて渡すかは #38 / #40 で実装する。
 */
@Entity(tableName = "analysis_results")
data class AnalysisResult(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val periodStart: Instant,
  val periodEnd: Instant,
  val analyzedAt: Instant,
  val body: String,
)
