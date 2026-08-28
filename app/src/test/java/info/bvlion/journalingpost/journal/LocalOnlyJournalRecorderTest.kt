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
  fun `記録はNOT_REQUIREDで保存され戻り値もNOT_REQUIREDになる`() = runTest {
    val repository = FakeJournalEntryRepository()
    val recorder = LocalOnlyJournalRecorder(repository, now = { fixedNow })

    val result = recorder.record("today was good", mood = null, source = JournalSource.APP)

    assertEquals(DeliveryStatus.NOT_REQUIRED, result)
    val entry = repository.entries.values.single()
    assertEquals("today was good", entry.note)
    assertEquals(DeliveryStatus.NOT_REQUIRED, entry.deliveryStatus)
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
    val repository = object : JournalEntryRepository {
      override suspend fun insert(entry: JournalEntry): Long = throw RuntimeException("db boom")

      override suspend fun updateDeliveryStatus(id: Long, status: DeliveryStatus) {
        error("must not be called")
      }
    }
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

    override suspend fun updateDeliveryStatus(id: Long, status: DeliveryStatus) {
      error("Local onlyではdeliveryStatus更新は行われない")
    }
  }
}
