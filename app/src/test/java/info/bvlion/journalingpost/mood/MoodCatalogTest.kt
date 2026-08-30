package info.bvlion.journalingpost.mood

import org.junit.Assert.assertEquals
import org.junit.Test

class MoodCatalogTest {
  @Test
  fun `moodCatalogはすべてのMoodを重複なく含む`() {
    assertEquals(Mood.entries.toSet(), moodCatalog.toSet())
    assertEquals(Mood.entries.size, moodCatalog.size)
  }

  @Test
  fun `moodCatalogの並びは気分の強弱順で固定されている`() {
    assertEquals(
      listOf(
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
      ),
      moodCatalog,
    )
  }
}
