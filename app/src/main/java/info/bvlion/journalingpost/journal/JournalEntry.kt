package info.bvlion.journalingpost.journal

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * 端末内に永続化する1件の記録。
 *
 * Mood付き記録では、moodIdに加えて記録時点のmoodEmoji/moodLabelをsnapshotとして
 * 保存する。将来Mood定義(名称・絵文字・個数等)が変更・削除されても、保存済みの
 * moodEmoji/moodLabelはそのまま残るため、過去の記録の表示内容は変わらない。
 * moodIdは現時点ではMood enumの名称(安定した識別子)を保存するが、Mood定義が
 * 別途永続化された場合もそちらを参照できる形を想定している。
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
  val deliveryStatus: DeliveryStatus,
)
