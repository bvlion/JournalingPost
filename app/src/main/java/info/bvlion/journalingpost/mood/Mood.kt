package info.bvlion.journalingpost.mood

/** Widgetから選択できる固定の気分。段階・絵文字ともにカスタマイズ対象外。 */
enum class Mood(val emoji: String) {
  VERY_SAD("😭"),
  SAD("😞"),
  NEUTRAL("😐"),
  HAPPY("🙂"),
  VERY_HAPPY("😆"),
  ;

  companion object {
    /** Intent extra等、外部から渡された文字列をMoodへ変換する。不正な値はnullを返す。 */
    fun fromExtraValue(value: String?): Mood? = entries.firstOrNull { it.name == value }
  }
}
