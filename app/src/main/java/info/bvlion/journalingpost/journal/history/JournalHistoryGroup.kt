package info.bvlion.journalingpost.journal.history

import java.time.LocalDate
import java.time.LocalTime

/**
 * 履歴一覧に表示する1件分の表示用データ。
 *
 * moodEmoji/moodLabelは現在のMood enumから再生成せず、JournalEntryに保存された
 * 記録時点のsnapshotをそのまま保持する。
 */
data class JournalHistoryItem(
  val id: Long,
  val date: LocalDate,
  val time: LocalTime,
  val moodEmoji: String?,
  val moodLabel: String?,
  val note: String?,
)

/** 同じ日付の[JournalHistoryItem]を新しい順にまとめたグループ。 */
data class JournalHistoryGroup(
  val date: LocalDate,
  val items: List<JournalHistoryItem>,
)
