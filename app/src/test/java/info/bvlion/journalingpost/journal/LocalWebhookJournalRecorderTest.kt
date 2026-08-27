package info.bvlion.journalingpost.journal

import info.bvlion.journalingpost.mood.MoodSnapshot
import info.bvlion.journalingpost.poster.JournalPoster
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWebhookJournalRecorderTest {
  private val fixedNow = Instant.ofEpochSecond(1_700_000_000L)

  private fun createRecorder(
    repository: FakeJournalEntryRepository = FakeJournalEntryRepository(),
    poster: (String) -> Boolean = { true },
  ) = LocalWebhookJournalRecorder(repository, JournalPoster { poster(it) }, now = { fixedNow })

  @Test
  fun `record saves the entry locally before updating delivery status`() = runTest {
    val repository = FakeJournalEntryRepository()
    val recorder = createRecorder(repository)

    recorder.record("today was good", mood = null, source = JournalSource.APP)

    assertEquals(listOf("insert", "updateDeliveryStatus:SENT"), repository.calls)
  }

  @Test
  fun `record marks delivery as SENT and returns true when the webhook succeeds`() = runTest {
    val repository = FakeJournalEntryRepository()
    val recorder = createRecorder(repository, poster = { true })

    val result = recorder.record("today was good", mood = null, source = JournalSource.APP)

    assertTrue(result)
    val entry = repository.entries.values.single()
    assertEquals(DeliveryStatus.SENT, entry.deliveryStatus)
  }

  @Test
  fun `record keeps the JournalEntry and marks FAILED when the webhook returns false`() = runTest {
    val repository = FakeJournalEntryRepository()
    val recorder = createRecorder(repository, poster = { false })

    val result = recorder.record("today was good", mood = null, source = JournalSource.APP)

    assertEquals(false, result)
    val entry = repository.entries.values.single()
    assertEquals("today was good", entry.note)
    assertEquals(DeliveryStatus.FAILED, entry.deliveryStatus)
  }

  @Test
  fun `record keeps the JournalEntry and marks FAILED when the webhook throws`() = runTest {
    val repository = FakeJournalEntryRepository()
    val recorder = LocalWebhookJournalRecorder(
      repository,
      JournalPoster { throw RuntimeException("boom") },
      now = { fixedNow },
    )

    val result = recorder.record("today was good", mood = null, source = JournalSource.APP)

    assertEquals(false, result)
    val entry = repository.entries.values.single()
    assertEquals("today was good", entry.note)
    assertEquals(DeliveryStatus.FAILED, entry.deliveryStatus)
  }

  @Test
  fun `record stores the note-only entry with null mood fields`() = runTest {
    val repository = FakeJournalEntryRepository()
    val recorder = createRecorder(repository)

    recorder.record("today was good", mood = null, source = JournalSource.APP)

    val entry = repository.entries.values.single()
    assertEquals("today was good", entry.note)
    assertNull(entry.moodId)
    assertNull(entry.moodEmoji)
    assertNull(entry.moodLabel)
    assertEquals(JournalSource.APP, entry.source)
    assertEquals(fixedNow, entry.timestamp)
  }

  @Test
  fun `record stores the mood snapshot fields and blank note as null`() = runTest {
    val repository = FakeJournalEntryRepository()
    val recorder = createRecorder(repository)
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
  fun `record formats the webhook message from the mood snapshot and note`() = runTest {
    var sentMessage: String? = null
    val recorder = createRecorder(poster = { message -> sentMessage = message; true })
    val mood = MoodSnapshot(id = "HAPPY", emoji = "🙂", label = "嬉しい")

    recorder.record("今日は仕事が進んだ", mood = mood, source = JournalSource.WIDGET)

    assertEquals("気分は🙂とのこと。今日は仕事が進んだ", sentMessage)
  }

  @Test
  fun `record sends the raw note as the webhook message when there is no mood`() = runTest {
    var sentMessage: String? = null
    val recorder = createRecorder(poster = { message -> sentMessage = message; true })

    recorder.record("today was good", mood = null, source = JournalSource.APP)

    assertEquals("today was good", sentMessage)
  }

  private class FakeJournalEntryRepository : JournalEntryRepository {
    val calls = mutableListOf<String>()
    val entries = mutableMapOf<Long, JournalEntry>()
    private var nextId = 1L

    override suspend fun insert(entry: JournalEntry): Long {
      val id = nextId++
      entries[id] = entry.copy(id = id)
      calls += "insert"
      return id
    }

    override suspend fun updateDeliveryStatus(id: Long, status: DeliveryStatus) {
      entries[id] = requireNotNull(entries[id]).copy(deliveryStatus = status)
      calls += "updateDeliveryStatus:$status"
    }
  }
}
