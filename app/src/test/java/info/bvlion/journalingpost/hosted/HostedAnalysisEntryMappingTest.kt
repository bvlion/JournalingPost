package info.bvlion.journalingpost.hosted

import info.bvlion.journalingpost.journal.JournalEntry
import info.bvlion.journalingpost.journal.JournalSource
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostedAnalysisEntryMappingTest {
  private fun entry(
    moodEmoji: String? = null,
    moodLabel: String? = null,
    note: String? = null,
  ) = JournalEntry(
    timestamp = Instant.parse("2026-08-30T01:15:00Z"),
    moodId = if (moodEmoji != null || moodLabel != null) "MOOD" else null,
    moodEmoji = moodEmoji,
    moodLabel = moodLabel,
    note = note,
    source = JournalSource.APP,
  )

  @Test
  fun `recordedAtはInstanceのRFC3339表記になる`() {
    assertEquals("2026-08-30T01:15:00Z", entry(note = "メモ").toHostedAnalysisEntry().recordedAt)
  }

  @Test
  fun `絵文字と名称の両方があるMoodはそのまま送る`() {
    val mood = entry(moodEmoji = "🙂", moodLabel = "嬉しい").toHostedAnalysisEntry().mood
    assertEquals(HostedAnalysisRequest.Entry.Mood("🙂", "嬉しい"), mood)
  }

  @Test
  fun `絵文字だけのMoodはlabelを空文字で送る`() {
    val mood = entry(moodEmoji = "🙂", moodLabel = "").toHostedAnalysisEntry().mood
    assertEquals(HostedAnalysisRequest.Entry.Mood("🙂", ""), mood)
  }

  @Test
  fun `名称だけのMoodはemojiを空文字で送る`() {
    val mood = entry(moodEmoji = "", moodLabel = "穏やか").toHostedAnalysisEntry().mood
    assertEquals(HostedAnalysisRequest.Entry.Mood("", "穏やか"), mood)
  }

  @Test
  fun `Moodが無いentryはmoodを載せない`() {
    assertNull(entry(note = "メモだけ").toHostedAnalysisEntry().mood)
  }

  @Test
  fun `emoji_labelが両方空白ならmoodを載せない`() {
    assertNull(entry(moodEmoji = " ", moodLabel = "").toHostedAnalysisEntry().mood)
  }

  @Test
  fun `noteはそのまま渡す`() {
    assertEquals("メモ本文", entry(moodEmoji = "🙂", moodLabel = "嬉しい", note = "メモ本文").toHostedAnalysisEntry().note)
    assertNull(entry(moodEmoji = "🙂", moodLabel = "嬉しい").toHostedAnalysisEntry().note)
  }
}
