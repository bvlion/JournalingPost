package info.bvlion.journalingpost.journal

import info.bvlion.journalingpost.mood.MoodSnapshot
import info.bvlion.journalingpost.poster.JournalPoster
import info.bvlion.journalingpost.settings.AnalysisIntegration
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class IntegrationRoutingJournalRecorderTest {

  @Test
  fun `解析・連携を使用しない場合はJournalPosterが呼ばれずNOT_REQUIREDでローカル保存される`() = runTest {
    val repository = FakeJournalEntryRepository()
    var postCalled = false
    val recorder = createRecorder(
      repository = repository,
      integration = AnalysisIntegration.NONE,
      poster = { postCalled = true; true },
    )

    val result = recorder.record("today was good", mood = null, source = JournalSource.APP)

    assertEquals(DeliveryStatus.NOT_REQUIRED, result)
    assertFalse(postCalled)
    assertEquals(DeliveryStatus.NOT_REQUIRED, repository.entries.values.single().deliveryStatus)
  }

  @Test
  fun `Custom Webhookを使う場合はローカル保存のうえWebhookへ送信されSENTになる`() = runTest {
    val repository = FakeJournalEntryRepository()
    val recorder = createRecorder(repository = repository, integration = AnalysisIntegration.CUSTOM_WEBHOOK, poster = { true })

    val result = recorder.record("today was good", mood = null, source = JournalSource.APP)

    assertEquals(DeliveryStatus.SENT, result)
    assertEquals(DeliveryStatus.SENT, repository.entries.values.single().deliveryStatus)
  }

  @Test
  fun `記録開始時点の解析・連携を1回だけ取得する`() = runTest {
    val repository = FakeJournalEntryRepository()
    val integrationRepository = CountingAnalysisIntegrationRepository(AnalysisIntegration.NONE)
    val recorder = IntegrationRoutingJournalRecorder(
      analysisIntegrationRepository = integrationRepository,
      localOnlyRecorder = LocalOnlyJournalRecorder(repository),
      localWebhookRecorder = LocalWebhookJournalRecorder(repository, JournalPoster { true }),
    )

    recorder.record("today was good", mood = null, source = JournalSource.APP)

    assertEquals(1, integrationRepository.accessCount)
  }

  @Test
  fun `解析・連携を変更しても既存のJournalEntryは保持される`() = runTest {
    val repository = FakeJournalEntryRepository()
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK)
    val recorder = IntegrationRoutingJournalRecorder(
      analysisIntegrationRepository = integrationRepository,
      localOnlyRecorder = LocalOnlyJournalRecorder(repository),
      localWebhookRecorder = LocalWebhookJournalRecorder(repository, JournalPoster { true }),
    )

    recorder.record("first", mood = null, source = JournalSource.APP)
    integrationRepository.setAnalysisIntegration(AnalysisIntegration.NONE)
    recorder.record("second", mood = null, source = JournalSource.APP)

    assertEquals(2, repository.entries.size)
    assertEquals("first", repository.entries.getValue(1L).note)
    assertEquals(DeliveryStatus.SENT, repository.entries.getValue(1L).deliveryStatus)
    assertEquals("second", repository.entries.getValue(2L).note)
    assertEquals(DeliveryStatus.NOT_REQUIRED, repository.entries.getValue(2L).deliveryStatus)
  }

  @Test
  fun `どの解析・連携でもJournalEntryは必ずローカルへ保存される`() = runTest {
    val repository = FakeJournalEntryRepository()
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE)
    val recorder = IntegrationRoutingJournalRecorder(
      analysisIntegrationRepository = integrationRepository,
      localOnlyRecorder = LocalOnlyJournalRecorder(repository),
      localWebhookRecorder = LocalWebhookJournalRecorder(repository, JournalPoster { false }),
    )

    recorder.record("none", mood = null, source = JournalSource.APP)
    integrationRepository.setAnalysisIntegration(AnalysisIntegration.CUSTOM_WEBHOOK)
    recorder.record("webhook", mood = null, source = JournalSource.APP)

    assertEquals(listOf("none", "webhook"), repository.entries.values.map { it.note })
    assertEquals(DeliveryStatus.FAILED, repository.entries.getValue(2L).deliveryStatus)
  }

  private fun createRecorder(
    repository: FakeJournalEntryRepository,
    integration: AnalysisIntegration,
    poster: (String) -> Boolean,
  ) = IntegrationRoutingJournalRecorder(
    analysisIntegrationRepository = FakeAnalysisIntegrationRepository(integration),
    localOnlyRecorder = LocalOnlyJournalRecorder(repository),
    localWebhookRecorder = LocalWebhookJournalRecorder(repository, JournalPoster { poster(it) }),
  )

  private class FakeAnalysisIntegrationRepository(initial: AnalysisIntegration) : AnalysisIntegrationRepository {
    private val state = MutableStateFlow(initial)
    override val analysisIntegration: Flow<AnalysisIntegration> = state

    override suspend fun setAnalysisIntegration(integration: AnalysisIntegration) {
      state.value = integration
    }
  }

  private class CountingAnalysisIntegrationRepository(
    private val current: AnalysisIntegration,
  ) : AnalysisIntegrationRepository {
    var accessCount = 0
      private set

    override val analysisIntegration: Flow<AnalysisIntegration>
      get() {
        accessCount++
        return MutableStateFlow(current)
      }

    override suspend fun setAnalysisIntegration(integration: AnalysisIntegration) = error("not used in this test")
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
      entries[id] = requireNotNull(entries[id]).copy(deliveryStatus = status)
    }
  }
}
