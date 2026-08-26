package info.bvlion.journalingpost.mood

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoodTest {
  @Test
  fun `fromExtraValue resolves a known mood name`() {
    assertEquals(Mood.HAPPY, Mood.fromExtraValue("HAPPY"))
  }

  @Test
  fun `fromExtraValue returns null when extra is missing`() {
    assertNull(Mood.fromExtraValue(null))
  }

  @Test
  fun `fromExtraValue returns null for an unexpected value`() {
    assertNull(Mood.fromExtraValue("EXCITED"))
  }
}
