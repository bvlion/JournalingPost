package info.bvlion.journalingpost.mood

import org.junit.Assert.assertEquals
import org.junit.Test

class MoodTest {
  @Test
  fun `表示内容を変更してもidを維持できる`() {
    val original = Mood(id = "persistent-id", emoji = "😄", label = "嬉しい")

    val edited = original.copy(emoji = "🤩", label = "ワクワク")

    assertEquals("persistent-id", edited.id)
  }

  @Test
  fun `絵文字と名称がある場合は両方を表示文字列に含める`() {
    assertEquals("😄 嬉しい", Mood(id = "1", emoji = "😄", label = "嬉しい").displayText)
  }

  @Test
  fun `絵文字または名称だけでも表示文字列を作る`() {
    assertEquals("😄", Mood(id = "1", emoji = "😄", label = "").displayText)
    assertEquals("嬉しい", Mood(id = "2", emoji = "", label = "嬉しい").displayText)
  }
}
