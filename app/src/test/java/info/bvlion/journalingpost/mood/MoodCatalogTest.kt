package info.bvlion.journalingpost.mood

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodValidatorTest {
  @Test
  fun `単体emojiと複数code pointのemoji sequenceを許可する`() {
    assertTrue(MoodValidator.isSingleEmoji("😄"))
    assertTrue(MoodValidator.isSingleEmoji("😮‍💨"))
    assertTrue(MoodValidator.isSingleEmoji("👨‍👩‍👧‍👦"))
    assertTrue(MoodValidator.isSingleEmoji("👍🏽"))
    assertTrue(MoodValidator.isSingleEmoji("🇯🇵"))
    assertTrue(MoodValidator.isSingleEmoji("1️⃣"))
  }

  @Test
  fun `通常文字と複数emojiはemojiとして許可しない`() {
    assertFalse(MoodValidator.isSingleEmoji("A"))
    assertFalse(MoodValidator.isSingleEmoji("あ"))
    assertFalse(MoodValidator.isSingleEmoji("✓"))
    assertFalse(MoodValidator.isSingleEmoji("😄😭"))
    assertFalse(MoodValidator.isSingleEmoji("🇯"))
  }

  @Test
  fun `絵文字のみ名称のみ両方のMoodを許可する`() {
    assertTrue(MoodValidator.isValid(listOf(Mood("1", "😄", ""))))
    assertTrue(MoodValidator.isValid(listOf(Mood("1", "", "嬉しい"))))
    assertTrue(MoodValidator.isValid(listOf(Mood("1", "😄", "嬉しい"))))
  }

  @Test
  fun `絵文字と名称の両方が空白相当なら許可しない`() {
    assertFalse(MoodValidator.isValid(listOf(Mood("1", "  ", "\n"))))
  }

  @Test
  fun `Moodは1件以上10件以下かつidが一意の場合だけ許可する`() {
    val tenMoods = (1..10).map { Mood(it.toString(), "", "Mood $it") }

    assertFalse(MoodValidator.isValid(emptyList()))
    assertTrue(MoodValidator.isValid(tenMoods))
    assertFalse(MoodValidator.isValid(tenMoods + Mood("11", "", "Mood 11")))
    assertFalse(MoodValidator.isValid(listOf(Mood("same", "😄", ""), Mood("same", "😭", ""))))
  }

  @Test
  fun `名称や絵文字の重複は許可する`() {
    val moods = listOf(
      Mood(id = "1", emoji = "😄", label = "同じ"),
      Mood(id = "2", emoji = "😄", label = "同じ"),
    )

    assertTrue(MoodValidator.isValid(moods))
    assertEquals(2, moods.size)
  }
}
