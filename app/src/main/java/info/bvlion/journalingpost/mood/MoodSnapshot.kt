package info.bvlion.journalingpost.mood

/** 記録時点のMood表示内容のsnapshot。将来Mood定義が変更・削除されても、記録済みの内容として残す。 */
data class MoodSnapshot(
  val id: String,
  val emoji: String,
  val label: String,
)
