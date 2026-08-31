package info.bvlion.journalingpost.journal

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * Mood付き記録では、moodIdに加えて記録時点のmoodEmoji/moodLabelをsnapshotとして
 * 保存する。将来Mood定義(名称・絵文字・個数等)が変更・削除されても、保存済みの
 * moodEmoji/moodLabelはそのまま残るため、過去の記録の表示内容は変わらない。
 * moodIdにはユーザー設定のMoodが持つ永続IDを保存し、名称や絵文字の一致では
 * Moodの同一性を判定しない。
 */
@Entity(tableName = "journal_entries")
data class JournalEntry(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val timestamp: Instant,
  val moodId: String? = null,
  val moodEmoji: String? = null,
  val moodLabel: String? = null,
  val note: String? = null,
  val source: JournalSource,
)
