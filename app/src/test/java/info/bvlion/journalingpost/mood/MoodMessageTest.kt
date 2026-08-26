package info.bvlion.journalingpost.mood

import org.junit.Assert.assertEquals
import org.junit.Test

class MoodMessageTest {
  @Test
  fun `mood only without note`() {
    assertEquals("気分は🙂とのこと", formatMoodMessage(Mood.HAPPY, ""))
  }

  @Test
  fun `mood with note`() {
    assertEquals(
      "気分は🙂とのこと。今日は仕事が進んだ",
      formatMoodMessage(Mood.HAPPY, "今日は仕事が進んだ"),
    )
  }

  @Test
  fun `blank note is treated as no note`() {
    assertEquals("気分は😭とのこと", formatMoodMessage(Mood.VERY_SAD, "   "))
  }

  @Test
  fun `note surrounding whitespace is trimmed`() {
    assertEquals(
      "気分は😆とのこと。やったー",
      formatMoodMessage(Mood.VERY_HAPPY, "  やったー  "),
    )
  }
}
