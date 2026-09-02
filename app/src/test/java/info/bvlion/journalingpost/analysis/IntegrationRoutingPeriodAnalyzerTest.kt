package info.bvlion.journalingpost.analysis

import info.bvlion.journalingpost.journal.JournalEntry
import info.bvlion.journalingpost.journal.JournalSource
import info.bvlion.journalingpost.settings.AnalysisIntegration
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepository
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrationRoutingPeriodAnalyzerTest {
  private val start = Instant.parse("2026-08-30T00:00:00Z")
  private val end = Instant.parse("2026-08-31T00:00:00Z")
  private val entries = listOf(
    JournalEntry(timestamp = start, note = "メモ", source = JournalSource.APP),
  )

  @Test
  fun `CUSTOM_WEBHOOKならwebhook analyzerへ委譲する`() = runTest {
    val webhook = RecordingAnalyzer(PeriodAnalysisOutcome.Failure.SERVER_ERROR)
    val hosted = RecordingAnalyzer(PeriodAnalysisOutcome.Failure.NETWORK)

    router(AnalysisIntegration.CUSTOM_WEBHOOK, webhook, hosted).analyze(start, end, entries)

    assertEquals(1, webhook.callCount)
    assertEquals(0, hosted.callCount)
  }

  @Test
  fun `HOSTEDならhosted analyzerへ委譲する`() = runTest {
    val webhook = RecordingAnalyzer(PeriodAnalysisOutcome.Failure.SERVER_ERROR)
    val hosted = RecordingAnalyzer(PeriodAnalysisOutcome.Failure.NETWORK)

    router(AnalysisIntegration.HOSTED, webhook, hosted).analyze(start, end, entries)

    assertEquals(0, webhook.callCount)
    assertEquals(1, hosted.callCount)
  }

  @Test
  fun `NONEはどちらも呼ばずINTEGRATION_UNAVAILABLE`() = runTest {
    val webhook = RecordingAnalyzer(PeriodAnalysisOutcome.Failure.SERVER_ERROR)
    val hosted = RecordingAnalyzer(PeriodAnalysisOutcome.Failure.NETWORK)

    val outcome = router(AnalysisIntegration.NONE, webhook, hosted).analyze(start, end, entries)

    assertEquals(PeriodAnalysisOutcome.Failure.INTEGRATION_UNAVAILABLE, outcome)
    assertEquals(0, webhook.callCount)
    assertEquals(0, hosted.callCount)
  }

  @Test
  fun `onAnalysisResultPersistedは直近のanalyzeの委譲先へ転送する`() = runTest {
    val webhook = RecordingAnalyzer(PeriodAnalysisOutcome.Failure.SERVER_ERROR)
    val hosted = RecordingPersistenceAnalyzer()
    val router = router(AnalysisIntegration.HOSTED, webhook, hosted)

    router.analyze(start, end, entries)
    router.onAnalysisResultPersisted(start, end)

    assertEquals(listOf(start to end), hosted.persistedPeriods)
  }

  @Test
  fun `直近のanalyzeがwebhookなら別解析先のhostedへは通知しない`() = runTest {
    val webhook = RecordingAnalyzer(PeriodAnalysisOutcome.Failure.SERVER_ERROR)
    val hosted = RecordingPersistenceAnalyzer()
    val router = router(AnalysisIntegration.CUSTOM_WEBHOOK, webhook, hosted)

    router.analyze(start, end, entries)
    router.onAnalysisResultPersisted(start, end)

    assertTrue(hosted.persistedPeriods.isEmpty())
  }

  private fun router(
    integration: AnalysisIntegration,
    webhook: PeriodAnalyzer,
    hosted: PeriodAnalyzer,
  ) = IntegrationRoutingPeriodAnalyzer(
    analysisIntegrationRepository = object : AnalysisIntegrationRepository {
      override val analysisIntegration: Flow<AnalysisIntegration> = MutableStateFlow(integration)
      override suspend fun setAnalysisIntegration(integration: AnalysisIntegration) = error("unused")
    },
    webhookAnalyzer = webhook,
    hostedAnalyzer = hosted,
  )

  private class RecordingAnalyzer(private val outcome: PeriodAnalysisOutcome) : PeriodAnalyzer {
    var callCount = 0
      private set

    override suspend fun analyze(
      periodStart: Instant,
      periodEnd: Instant,
      entries: List<JournalEntry>,
    ): PeriodAnalysisOutcome {
      callCount++
      return outcome
    }
  }

  private class RecordingPersistenceAnalyzer :
    PeriodAnalyzer, AnalysisResultPersistenceListener {
    val persistedPeriods = mutableListOf<Pair<Instant, Instant>>()

    override suspend fun analyze(
      periodStart: Instant,
      periodEnd: Instant,
      entries: List<JournalEntry>,
    ): PeriodAnalysisOutcome = PeriodAnalysisOutcome.Failure.NETWORK

    override suspend fun onAnalysisResultPersisted(periodStart: Instant, periodEnd: Instant) {
      persistedPeriods += periodStart to periodEnd
    }
  }
}
