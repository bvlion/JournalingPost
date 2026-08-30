package info.bvlion.journalingpost.mood

import info.bvlion.journalingpost.R

/**
 * 記録画面・Widgetで選択できる気分。表示順は[moodCatalog]で管理する。
 *
 * ユーザーによるMoodの編集・増減・並び替えは #42 で扱う。それまでは、この enum と
 * 対応するstring resourceを固定のMoodセットとして共通利用する。Mood/JournalEntryの
 * 永続化済みデータ(moodId等)はこの名前を参照するため、既存の name は安易に変更しない。
 */
enum class Mood(val emoji: String, val labelRes: Int, val descriptionRes: Int) {
  VERY_SAD("😭", R.string.mood_label_very_sad, R.string.mood_description_very_sad),
  SAD("😞", R.string.mood_label_sad, R.string.mood_description_sad),
  NEUTRAL("😐", R.string.mood_label_neutral, R.string.mood_description_neutral),
  HAPPY("🙂", R.string.mood_label_happy, R.string.mood_description_happy),
  VERY_HAPPY("😆", R.string.mood_label_very_happy, R.string.mood_description_very_happy),
  ANGRY("😡", R.string.mood_label_angry, R.string.mood_description_angry),
  ANXIOUS("😰", R.string.mood_label_anxious, R.string.mood_description_anxious),
  TEARFUL("😢", R.string.mood_label_tearful, R.string.mood_description_tearful),
  CALM("😌", R.string.mood_label_calm, R.string.mood_description_calm),
  ELATED("🤩", R.string.mood_label_elated, R.string.mood_description_elated),
  ;

  companion object {
    fun fromExtraValue(value: String?): Mood? = entries.firstOrNull { it.name == value }
  }
}
