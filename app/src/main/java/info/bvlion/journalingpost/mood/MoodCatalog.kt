package info.bvlion.journalingpost.mood

/**
 * 記録画面とWidgetが共有するMoodの表示順。[Mood] enumの宣言順ではなく、気分の強弱が
 * 自然に並ぶよう手で並べた順序を正とする。記録画面・Widgetで別々のリストを持たず、
 * 必ずこの1つを参照する。
 *
 * ユーザーが順序・件数を変更できるようにするのは #42。それまでは固定リストとして扱う。
 */
val moodCatalog: List<Mood> = listOf(
  Mood.ANGRY,
  Mood.VERY_SAD,
  Mood.ANXIOUS,
  Mood.SAD,
  Mood.TEARFUL,
  Mood.NEUTRAL,
  Mood.CALM,
  Mood.HAPPY,
  Mood.VERY_HAPPY,
  Mood.ELATED,
)
