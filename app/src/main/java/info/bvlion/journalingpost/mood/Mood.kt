package info.bvlion.journalingpost.mood

import kotlinx.serialization.Serializable

/**
 * 利用者が設定するMood。idは表示内容から独立しており、emojiやlabelを編集しても変更しない。
 */
@Serializable
data class Mood(
  val id: String,
  val emoji: String,
  val label: String,
) {
  val displayText: String get() = listOf(emoji, label).filter { it.isNotBlank() }.joinToString(" ")
}
