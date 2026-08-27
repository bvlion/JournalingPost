package info.bvlion.journalingpost.mood

/** Widgetから選択できる気分。 */
enum class Mood(val emoji: String) {
  VERY_SAD("😭"),
  SAD("😞"),
  NEUTRAL("😐"),
  HAPPY("🙂"),
  VERY_HAPPY("😆"),

  // ここから下はIssue #21のWidgetレイアウト評価用に一時追加した仮Mood。
  // 「気分は10種類」という仕様ではなく、Mood数が増えた場合にcompact/横方向resize/
  // 縦方向expandedがそれぞれどう振る舞うかを実機確認するための一時データ。
  // 評価が終わり次第、削除または本来の仕様に置き換える想定。
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
