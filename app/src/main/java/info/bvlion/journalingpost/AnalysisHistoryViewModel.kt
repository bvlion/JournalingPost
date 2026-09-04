package info.bvlion.journalingpost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.bvlion.journalingpost.analysis.AnalysisHistoryUiState
import info.bvlion.journalingpost.analysis.AnalysisResultReader
import info.bvlion.journalingpost.analysis.AnalysisResultWriter
import info.bvlion.journalingpost.analysis.PeriodAnalysisOutcome
import info.bvlion.journalingpost.analysis.PeriodAnalysisRunner
import info.bvlion.journalingpost.analysis.PeriodAnalyzer
import info.bvlion.journalingpost.analysis.manualAnalysisSelectableDays
import info.bvlion.journalingpost.analysis.toAnalysisHistoryItems
import info.bvlion.journalingpost.journal.JournalEntryReader
import info.bvlion.journalingpost.journal.PeriodJournalEntryReader
import info.bvlion.journalingpost.settings.AnalysisIntegration
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AnalysisHistoryViewModel(
  reader: AnalysisResultReader,
  analysisIntegrationRepository: AnalysisIntegrationRepository,
  journalEntryReader: JournalEntryReader,
  private val periodJournalEntryReader: PeriodJournalEntryReader,
  periodAnalyzer: PeriodAnalyzer,
  analysisResultWriter: AnalysisResultWriter,
  // 端末timezoneは解析開始・一覧生成のたびに解決する。ViewModel生成時に固定すると、移動などで
  // timezoneが変わったあと選択日の境界が古いオフセットで計算されてしまうため。
  private val currentZoneId: () -> ZoneId = { ZoneId.systemDefault() },
  private val currentDate: () -> LocalDate = { LocalDate.now(currentZoneId()) },
) : ViewModel() {
  private val periodAnalysisRunner = PeriodAnalysisRunner(periodAnalyzer, analysisResultWriter)
  val uiState: StateFlow<AnalysisHistoryUiState> = reader.observeAll()
    .map { results ->
      val items = results.toAnalysisHistoryItems(currentZoneId())
      if (items.isEmpty()) AnalysisHistoryUiState.Empty else AnalysisHistoryUiState.Content(items)
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalysisHistoryUiState.Loading)

  val canRunAnalysis: StateFlow<Boolean> = analysisIntegrationRepository.analysisIntegration
    .map { it == AnalysisIntegration.CUSTOM_WEBHOOK || it == AnalysisIntegration.HOSTED }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

  /**
   * 手動解析の日付選択で選べる日([currentZoneId]でのカレンダー日)。Custom Webhookは記録のある日すべて、
   * Hostedは当日と解析済みの日を除いた前日以前の記録日だけ。境界は選択日と同じく端末timezoneで解決する。
   */
  val selectableDays: StateFlow<Set<LocalDate>> = combine(
    analysisIntegrationRepository.analysisIntegration,
    journalEntryReader.observeAll(),
    reader.observeAll(),
  ) { integration, entries, results ->
    val zoneId = currentZoneId()
    manualAnalysisSelectableDays(
      integration = integration,
      recordedDays = entries.mapTo(mutableSetOf()) { it.timestamp.atZone(zoneId).toLocalDate() },
      analyzedDays = results.mapTo(mutableSetOf()) { it.periodStart.atZone(zoneId).toLocalDate() },
      today = currentDate(),
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

  /** 実行中の表示と二重実行の抑止に使う。 */
  private val _isAnalysisRunning = MutableStateFlow(false)
  val isAnalysisRunning: StateFlow<Boolean> = _isAnalysisRunning.asStateFlow()

  /**
   * 解析実行の結果。1件ずつ画面が受け取って消費する。実行中にタブを離れて戻ったケースでも結果を
   * 落とさないよう、画面が受け取るまでは保持する。
   */
  private val _runResults = Channel<AnalysisRunResult>(Channel.BUFFERED)
  val runResults: Flow<AnalysisRunResult> = _runResults.receiveAsFlow()

  /**
   * [day]を、解析開始時点の現在の端末timezoneでの1日として `[00:00, 翌日00:00)` のInstant区間へ
   * 変換して解析する。対象期間のJournalEntryが0件なら[PeriodAnalysisOutcome.Failure.NO_ENTRIES]として
   * 扱い、HTTP requestは送らない。実行中の再呼び出しは無視する。失敗してもJournalEntryは変更しない。
   */
  fun analyze(day: LocalDate) {
    if (_isAnalysisRunning.value) return
    _isAnalysisRunning.value = true
    val zoneId = currentZoneId()
    val periodStart = day.atStartOfDay(zoneId).toInstant()
    val periodEnd = day.plusDays(1).atStartOfDay(zoneId).toInstant()
    viewModelScope.launch {
      try {
        _runResults.send(runAnalysis(day, periodStart, periodEnd))
      } finally {
        _isAnalysisRunning.value = false
      }
    }
  }

  private suspend fun runAnalysis(
    day: LocalDate,
    periodStart: Instant,
    periodEnd: Instant,
  ): AnalysisRunResult {
    val entries = try {
      periodJournalEntryReader.entriesInPeriod(periodStart, periodEnd)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return AnalysisRunResult.Failed(PeriodAnalysisOutcome.Failure.LOCAL_READ, day)
    }
    if (entries.isEmpty()) return AnalysisRunResult.Failed(PeriodAnalysisOutcome.Failure.NO_ENTRIES, day)

    // 対象期間・解析日時・本文はいずれもresponseの値を保存元にする(Custom Webhook契約)。保存と、
    // 端末保存確定のretry stateを持つanalyzerへの通知は[PeriodAnalysisRunner]へ閉じている。
    return when (val outcome = periodAnalysisRunner.run(periodStart, periodEnd, entries)) {
      is PeriodAnalysisRunner.Outcome.Saved -> AnalysisRunResult.Succeeded(outcome.savedResultId)
      is PeriodAnalysisRunner.Outcome.Failed -> AnalysisRunResult.Failed(outcome.failure, day)
      PeriodAnalysisRunner.Outcome.SaveFailed -> AnalysisRunResult.Failed(null, day)
    }
  }
}

sealed interface AnalysisRunResult {
  /**
   * 解析が成功しAnalysisResultを保存した。[savedResultId]は保存した行のidで、一覧へ反映された
   * この結果が先頭に来たとき画面を先頭へ寄せ、生成された結果をそのまま見せるために使う。
   */
  data class Succeeded(val savedResultId: Long) : AnalysisRunResult

  /**
   * 失敗した解析実行。[day]は実行対象に選んだ日で、対象日を含むメッセージ(NO_ENTRIES等)を
   * 失敗理由と同じ結果から組み立てられるように持つ。[failure]がnullなのは、解析自体は成功したが
   * AnalysisResultの端末保存に失敗した場合。
   */
  data class Failed(val failure: PeriodAnalysisOutcome.Failure?, val day: LocalDate) : AnalysisRunResult
}
