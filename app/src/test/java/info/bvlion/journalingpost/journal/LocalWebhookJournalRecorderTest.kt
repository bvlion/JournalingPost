package info.bvlion.journalingpost.journal

import info.bvlion.journalingpost.mood.MoodSnapshot
import info.bvlion.journalingpost.poster.JournalPoster
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LocalWebhookJournalRecorderTest {
  private val fixedNow = Instant.ofEpochSecond(1_700_000_000L)

  private fun createRecorder(
    repository: FakeJournalEntryRepository = FakeJournalEntryRepository(),
    poster: (String) -> Boolean = { true },
    isWebhookConfigured: () -> Boolean = { true },
  ) = LocalWebhookJournalRecorder(
    repository,
    JournalPoster { poster(it) },
    now = { fixedNow },
    isWebhookConfigured = isWebhookConfigured,
  )

  @Test
  fun `recordはdeliveryStatus更新より先にローカル保存する`() = runTest {
    val repository = FakeJournalEntryRepository()
    val recorder = createRecorder(repository)

    recorder.record("today was good", mood = null, source = JournalSource.APP)

    assertEquals(listOf("insert", "updateDeliveryStatus:SENT"), repository.calls)
  }

  @Test
  fun `Webhook送信成功時はSENTになり正常終了する`() = runTest {
    val repository = FakeJournalEntryRepository()
    val recorder = createRecorder(repository, poster = { true })

    val result = recorder.record("today was good", mood = null, source = JournalSource.APP)

    assertEquals(DeliveryStatus.SENT, result)
    val entry = repository.entries.values.single()
    assertEquals(DeliveryStatus.SENT, entry.deliveryStatus)
  }

  @Test
  fun `Webhookがfalseを返してもJournalEntryは保持されFAILEDになり正常終了する`() =
    runTest {
      val repository = FakeJournalEntryRepository()
      val recorder = createRecorder(repository, poster = { false })

      val result = recorder.record("today was good", mood = null, source = JournalSource.APP)

      assertEquals(DeliveryStatus.FAILED, result)
      val entry = repository.entries.values.single()
      assertEquals("today was good", entry.note)
      assertEquals(DeliveryStatus.FAILED, entry.deliveryStatus)
    }

  @Test
  fun `Webhookが例外を投げてもJournalEntryは保持されFAILEDになり正常終了する`() = runTest {
    val repository = FakeJournalEntryRepository()
    val recorder = LocalWebhookJournalRecorder(
      repository,
      JournalPoster { throw RuntimeException("boom") },
      now = { fixedNow },
      isWebhookConfigured = { true },
    )

    val result = recorder.record("today was good", mood = null, source = JournalSource.APP)

    assertEquals(DeliveryStatus.FAILED, result)
    val entry = repository.entries.values.single()
    assertEquals("today was good", entry.note)
    assertEquals(DeliveryStatus.FAILED, entry.deliveryStatus)
  }

  @Test
  fun `Webhook設定が不足している場合はネットワーク送信せずFAILEDになりローカル記録は残る`() = runTest {
    val repository = FakeJournalEntryRepository()
    var postCalled = false
    val recorder = createRecorder(
      repository,
      poster = { postCalled = true; true },
      isWebhookConfigured = { false },
    )

    val result = recorder.record("today was good", mood = null, source = JournalSource.APP)

    assertEquals(DeliveryStatus.FAILED, result)
    assertFalse(postCalled)
    val entry = repository.entries.values.single()
    assertEquals("today was good", entry.note)
    assertEquals(DeliveryStatus.FAILED, entry.deliveryStatus)
  }

  @Test
  fun `ローカル保存自体が失敗した場合は例外がそのまま伝播する`() = runTest {
    val repository = object : JournalEntryRepository {
      override suspend fun insert(entry: JournalEntry): Long = throw RuntimeException("db boom")

      override suspend fun updateDeliveryStatus(id: Long, status: DeliveryStatus) {
        error("must not be called when insert fails")
      }
    }
    val recorder = LocalWebhookJournalRecorder(
      repository,
      JournalPoster { true },
      now = { fixedNow },
      isWebhookConfigured = { true },
    )

    var thrown: Throwable? = null
    try {
      recorder.record("today was good", mood = null, source = JournalSource.APP)
    } catch (e: RuntimeException) {
      thrown = e
    }

    assertEquals("db boom", thrown?.message)
  }

  @Test
  fun `deliveryStatus更新が失敗した場合はPENDINGのまま正常終了する`() = runTest {
    val entries = mutableMapOf<Long, JournalEntry>()
    val repository = object : JournalEntryRepository {
      override suspend fun insert(entry: JournalEntry): Long {
        entries[1L] = entry.copy(id = 1L)
        return 1L
      }

      override suspend fun updateDeliveryStatus(id: Long, status: DeliveryStatus) {
        throw RuntimeException("db boom")
      }
    }
    val recorder = LocalWebhookJournalRecorder(
      repository,
      JournalPoster { true },
      now = { fixedNow },
      isWebhookConfigured = { true },
    )

    val result = recorder.record("today was good", mood = null, source = JournalSource.APP)

    // 戻り値は実際のWebhook配送結果(SENT)を返すが、永続化は更新失敗のためPENDINGのまま残る。
    assertEquals(DeliveryStatus.SENT, result)
    val entry = entries.values.single()
    assertEquals("today was good", entry.note)
    assertEquals(DeliveryStatus.PENDING, entry.deliveryStatus)
  }

  @Test
  fun `deliveryStatus更新のCancellationExceptionはそのまま伝播する`() = runTest {
    val repository = object : JournalEntryRepository {
      override suspend fun insert(entry: JournalEntry): Long = 1L

      override suspend fun updateDeliveryStatus(id: Long, status: DeliveryStatus) {
        throw CancellationException("cancelled")
      }
    }
    val recorder = LocalWebhookJournalRecorder(
      repository,
      JournalPoster { true },
      now = { fixedNow },
      isWebhookConfigured = { true },
    )

    var thrown: Throwable? = null
    try {
      recorder.record("today was good", mood = null, source = JournalSource.APP)
    } catch (e: CancellationException) {
      thrown = e
    }

    assertEquals("cancelled", thrown?.message)
  }

  @Test
  fun `noteのみの記録はmood関連フィールドが全てnullで保存される`() = runTest {
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
  fun `moodスナップショットが保存され空文字のnoteはnullになる`() = runTest {
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
  fun `Webhookメッセージはmoodスナップショットとnoteから整形される`() = runTest {
    var sentMessage: String? = null
    val recorder = createRecorder(poster = { message -> sentMessage = message; true })
    val mood = MoodSnapshot(id = "HAPPY", emoji = "🙂", label = "嬉しい")

    recorder.record("今日は仕事が進んだ", mood = mood, source = JournalSource.WIDGET)

    assertEquals("気分は🙂とのこと。今日は仕事が進んだ", sentMessage)
  }

  @Test
  fun `moodがない場合はnoteがそのままWebhookメッセージになる`() = runTest {
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
