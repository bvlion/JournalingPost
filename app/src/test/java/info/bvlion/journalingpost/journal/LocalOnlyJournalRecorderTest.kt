package info.bvlion.journalingpost.journal

import info.bvlion.journalingpost.mood.MoodSnapshot
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalOnlyJournalRecorderTest {
  private val fixedNow = Instant.ofEpochSecond(1_700_000_000L)

  @Test
  fun `記録は端末へローカル保存される`() = runTest {
    val repository = FakeJournalEntryRepository()
    val recorder = LocalOnlyJournalRecorder(repository, now = { fixedNow })

    recorder.record("today was good", mood = null, source = JournalSource.APP)

    val entry = repository.entries.values.single()
    assertEquals("today was good", entry.note)
    assertEquals(fixedNow, entry.timestamp)
  }

  @Test
  fun `moodスナップショットが保存され空文字のnoteはnullになる`() = runTest {
    val repository = FakeJournalEntryRepository()
    val recorder = LocalOnlyJournalRecorder(repository, now = { fixedNow })
    val mood = MoodSnapshot(id = "HAPPY", emoji = "🙂", label = "嬉しい")

    recorder.record("", mood = mood, source = JournalSource.WIDGET)

    val entry = repository.entries.values.single()
    assertNull(entry.note)
    assertEquals("HAPPY", entry.moodId)
    assertEquals("🙂", entry.moodEmoji)
    assertEquals("嬉しい", entry.moodLabel)
    assertEquals(JournalSource.WIDGET, entry.source)
  }

  @Test
  fun `ローカル保存自体が失敗した場合は例外がそのまま伝播する`() = runTest {
    val repository = JournalEntryRepository { throw RuntimeException("db boom") }
    val recorder = LocalOnlyJournalRecorder(repository, now = { fixedNow })

    var thrown: Throwable? = null
    try {
      recorder.record("today was good", mood = null, source = JournalSource.APP)
    } catch (e: RuntimeException) {
      thrown = e
    }

    assertEquals("db boom", thrown?.message)
  }

  private class FakeJournalEntryRepository : JournalEntryRepository {
    val entries = mutableMapOf<Long, JournalEntry>()
    private var nextId = 1L

    override suspend fun insert(entry: JournalEntry): Long {
      val id = nextId++
      entries[id] = entry.copy(id = id)
      return id
    }
  }
}
