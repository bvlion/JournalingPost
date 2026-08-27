package info.bvlion.journalingpost.mood

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoodTest {
  @Test
  fun `fromExtraValueは既知のmood名を解決する`() {
    assertEquals(Mood.HAPPY, Mood.fromExtraValue("HAPPY"))
  }

  @Test
  fun `fromExtraValueはextraがない場合nullを返す`() {
    assertNull(Mood.fromExtraValue(null))
  }

  @Test
  fun `fromExtraValueは未知の値の場合nullを返す`() {
    assertNull(Mood.fromExtraValue("EXCITED"))
  }
}
