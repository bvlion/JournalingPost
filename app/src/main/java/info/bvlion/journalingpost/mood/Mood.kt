package info.bvlion.journalingpost.mood

/** Widgetから選択できる気分。 */
enum class Mood(val emoji: String) {
  VERY_SAD("😭"),
  SAD("😞"),
  NEUTRAL("😐"),
  HAPPY("🙂"),
  VERY_HAPPY("😆"),

  // ここから下はWidgetレイアウトの継続的なストレステスト用に追加した仮Mood。
  // 「気分は10種類」という最終仕様ではなく、compactの高密度表示・横方向resize・
  // 縦方向expanded・長いMood名称、および将来Mood数をユーザーが増減できるように
  // なった場合の挙動を確認し続けるためのデータとして、評価後も残す方針。
  // Mood/JournalEntryは近くRoom等へ永続化する予定で、この enum やstring resource
  // 自体を最終的なMoodデータモデルとは考えていない。将来のユーザー定義Moodへ
  // 移行するまでの暫定値。
  ANGRY("😡"),
  ANXIOUS("😰"),
  TEARFUL("😢"),
  CALM("😌"),
  ELATED("🤩"),
  ;

  companion object {
    /** Intent extra等、外部から渡された文字列をMoodへ変換する。不正な値はnullを返す。 */
    fun fromExtraValue(value: String?): Mood? = entries.firstOrNull { it.name == value }
  }
}
