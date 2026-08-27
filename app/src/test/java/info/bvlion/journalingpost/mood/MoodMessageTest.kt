package info.bvlion.journalingpost.mood

import org.junit.Assert.assertEquals
import org.junit.Test

class MoodMessageTest {
  @Test
  fun `noteなしはmoodのみのメッセージになる`() {
    assertEquals("気分は🙂とのこと", formatMoodMessage("🙂", ""))
  }

  @Test
  fun `noteありはmoodとnoteを含むメッセージになる`() {
    assertEquals(
      "気分は🙂とのこと。今日は仕事が進んだ",
      formatMoodMessage("🙂", "今日は仕事が進んだ"),
    )
  }

  @Test
  fun `空白のみのnoteはnoteなし扱いになる`() {
    assertEquals("気分は😭とのこと", formatMoodMessage("😭", "   "))
  }

  @Test
  fun `noteの前後の空白は除去される`() {
    assertEquals(
      "気分は😆とのこと。やったー",
      formatMoodMessage("😆", "  やったー  "),
    )
  }
}
