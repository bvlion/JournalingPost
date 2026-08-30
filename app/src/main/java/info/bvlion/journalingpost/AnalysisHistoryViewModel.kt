package info.bvlion.journalingpost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.bvlion.journalingpost.analysis.AnalysisHistoryUiState
import info.bvlion.journalingpost.analysis.AnalysisResult
import info.bvlion.journalingpost.analysis.AnalysisResultReader
import info.bvlion.journalingpost.analysis.AnalysisResultWriter
import info.bvlion.journalingpost.analysis.PeriodAnalysisOutcome
import info.bvlion.journalingpost.analysis.PeriodAnalyzer
import info.bvlion.journalingpost.analysis.toAnalysisHistoryItems
import info.bvlion.journalingpost.journal.PeriodJournalEntryReader
import info.bvlion.journalingpost.settings.AnalysisIntegration
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AnalysisHistoryViewModel(
  reader: AnalysisResultReader,
  analysisIntegrationRepository: AnalysisIntegrationRepository,
  private val periodJournalEntryReader: PeriodJournalEntryReader,
  private val periodAnalyzer: PeriodAnalyzer,
  private val analysisResultWriter: AnalysisResultWriter,
  // 端末timezoneは解析開始・一覧生成のたびに解決する。ViewModel生成時に固定すると、移動などで
  // timezoneが変わったあと選択日の境界が古いオフセットで計算されてしまうため。
  private val currentZoneId: () -> ZoneId = { ZoneId.systemDefault() },
) : ViewModel() {
  val uiState: StateFlow<AnalysisHistoryUiState> = reader.observeAll()
    .map { results ->
      val items = results.toAnalysisHistoryItems(currentZoneId())
      if (items.isEmpty()) AnalysisHistoryUiState.Empty else AnalysisHistoryUiState.Content(items)
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalysisHistoryUiState.Loading)

  /** Custom Webhookが解析先として有効なときだけ、手動解析の導線を出す。 */
  val canRunAnalysis: StateFlow<Boolean> = analysisIntegrationRepository.analysisIntegration
    .map { it == AnalysisIntegration.CUSTOM_WEBHOOK }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

  private val _analysisRunState = MutableStateFlow<AnalysisRunState>(AnalysisRunState.Idle)
  val analysisRunState: StateFlow<AnalysisRunState> = _analysisRunState.asStateFlow()

  /**
   * 実行結果(Succeeded/Failed)を画面がSnackbarで見せたら呼ぶ。実行中(Running)は消さない。
   *
   * 実行中にタブを離れて戻ったケースで、完了した結果を画面へ出す前に消してしまわないため、
   * ここ以外で結果を消さない。Failedは次の[analyze]まで、画面が消費するまで保持する。
   */
  fun consumeRunResult() {
    if (_analysisRunState.value != AnalysisRunState.Running) _analysisRunState.value = AnalysisRunState.Idle
  }

  private val _candidateDay = MutableStateFlow<CandidateDayState>(CandidateDayState.None)

  /** 解析する日を選んでいる途中の状態。0件の日は実行を確定できないようにするためのもの。 */
  val candidateDay: StateFlow<CandidateDayState> = _candidateDay.asStateFlow()

  private var candidateDayJob: Job? = null

  /** 日付選択のたびに、その日に解析対象の記録があるかを調べる。 */
  fun checkCandidateDay(day: LocalDate) {
    if ((_candidateDay.value as? CandidateDayState.Checked)?.day == day) return
    candidateDayJob?.cancel()
    _candidateDay.value = CandidateDayState.Checking
    candidateDayJob = viewModelScope.launch {
      val zoneId = currentZoneId()
      val periodStart = day.atStartOfDay(zoneId).toInstant()
      val periodEnd = day.plusDays(1).atStartOfDay(zoneId).toInstant()
      val hasEntries = try {
        periodJournalEntryReader.entriesInPeriod(periodStart, periodEnd).isNotEmpty()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // 読めないときは確定を止めない(実行時にLOCAL_READ扱いになる)。
        true
      }
      _candidateDay.value = CandidateDayState.Checked(day, hasEntries)
    }
  }

  fun clearCandidateDay() {
    candidateDayJob?.cancel()
    _candidateDay.value = CandidateDayState.None
  }

  /**
   * [day]を、解析開始時点の現在の端末timezoneでの1日として `[00:00, 翌日00:00)` のInstant区間へ
   * 変換して解析する。対象期間のJournalEntryが0件なら[PeriodAnalysisOutcome.Failure.NO_ENTRIES]として
   * 扱い、HTTP requestは送らない。実行中の再呼び出しは無視する。失敗してもJournalEntryは変更しない。
   */
  fun analyze(day: LocalDate) {
    if (_analysisRunState.value == AnalysisRunState.Running) return
    _analysisRunState.value = AnalysisRunState.Running
    val zoneId = currentZoneId()
    val periodStart = day.atStartOfDay(zoneId).toInstant()
    val periodEnd = day.plusDays(1).atStartOfDay(zoneId).toInstant()
    viewModelScope.launch {
      val entries = try {
        periodJournalEntryReader.entriesInPeriod(periodStart, periodEnd)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _analysisRunState.value = AnalysisRunState.Failed(PeriodAnalysisOutcome.Failure.LOCAL_READ)
        return@launch
      }
      if (entries.isEmpty()) {
        _analysisRunState.value = AnalysisRunState.Failed(PeriodAnalysisOutcome.Failure.NO_ENTRIES)
        return@launch
      }

      _analysisRunState.value = try {
        when (val outcome = periodAnalyzer.analyze(periodStart, periodEnd, entries)) {
          is PeriodAnalysisOutcome.Success -> {
            // 対象期間・解析日時・本文はいずれもresponseの値を保存元にする(Custom Webhook契約)。
            analysisResultWriter.save(
              AnalysisResult(
                periodStart = outcome.periodStart,
                periodEnd = outcome.periodEnd,
                analyzedAt = outcome.analyzedAt,
                body = outcome.body,
              ),
            )
            AnalysisRunState.Succeeded
          }

          is PeriodAnalysisOutcome.Failure -> AnalysisRunState.Failed(outcome)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // AnalysisResult保存の失敗。JournalEntryには触れていないため、同じ日を再実行できる。
        AnalysisRunState.Failed(null)
      }
    }
  }
}

sealed interface AnalysisRunState {
  data object Idle : AnalysisRunState
  data object Running : AnalysisRunState
  data object Succeeded : AnalysisRunState

  /** [failure]がnullなのは、解析自体は成功したがAnalysisResultの端末保存に失敗した場合。 */
  data class Failed(val failure: PeriodAnalysisOutcome.Failure?) : AnalysisRunState
}

/** 解析する日の候補と、その日に解析対象の記録があるか。 */
sealed interface CandidateDayState {
  data object None : CandidateDayState
  data object Checking : CandidateDayState
  data class Checked(val day: LocalDate, val hasEntries: Boolean) : CandidateDayState
}
