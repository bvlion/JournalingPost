package info.bvlion.journalingpost.mood

import info.bvlion.journalingpost.R

/** Widgetから選択できる気分。 */
enum class Mood(val emoji: String, val labelRes: Int) {
  VERY_SAD("😭", R.string.mood_label_very_sad),
  SAD("😞", R.string.mood_label_sad),
  NEUTRAL("😐", R.string.mood_label_neutral),
  HAPPY("🙂", R.string.mood_label_happy),
  VERY_HAPPY("😆", R.string.mood_label_very_happy),

  // ここから下はWidgetレイアウトの継続的なストレステスト用に追加した仮Mood。
  // 「気分は10種類」という最終仕様ではなく、compactの高密度表示・横方向resize・
  // 縦方向expanded・長いMood名称、および将来Mood数をユーザーが増減できるように
  // なった場合の挙動を確認し続けるためのデータとして、評価後も残す方針。
  // Mood/JournalEntryは近くRoom等へ永続化する予定で、この enum やstring resource
  // 自体を最終的なMoodデータモデルとは考えていない。将来のユーザー定義Moodへ
  // 移行するまでの暫定値。
  ANGRY("😡", R.string.mood_label_angry),
  ANXIOUS("😰", R.string.mood_label_anxious),
  TEARFUL("😢", R.string.mood_label_tearful),
  CALM("😌", R.string.mood_label_calm),
  ELATED("🤩", R.string.mood_label_elated),
  ;

  companion object {
    fun fromExtraValue(value: String?): Mood? = entries.firstOrNull { it.name == value }
  }
}
