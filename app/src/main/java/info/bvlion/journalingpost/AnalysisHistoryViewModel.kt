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
import info.bvlion.journalingpost.settings.AnalysisIntegration
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
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
  private val periodAnalyzer: PeriodAnalyzer,
  private val analysisResultWriter: AnalysisResultWriter,
  // 端末timezoneは解析開始・一覧生成のたびに解決する。ViewModel生成時に固定すると、移動などで
  // timezoneが変わったあと選択日の境界が古いオフセットで計算されてしまうため。
  private val currentZoneId: () -> ZoneId = { ZoneId.systemDefault() },
  private val now: () -> Instant = Instant::now,
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
   * 実行結果の表示を画面側が消費したら呼ぶ。実行中は消さない。
   *
   * ここ以外で結果表示を消さないのは、解析中にタブを離れて戻ったケースで、完了した結果
   * (特にFailed)を画面へ出す前に消してしまわないため。Failedは次の[analyze]まで表示を維持し、
   * Succeededは画面側がToastを出したうえでこれを呼んで消す。
   */
  fun consumeRunResult() {
    if (_analysisRunState.value != AnalysisRunState.Running) _analysisRunState.value = AnalysisRunState.Idle
  }

  /**
   * [day]を、解析開始時点の現在の端末timezoneでの1日として `[00:00, 翌日00:00)` のInstant区間へ
   * 変換して解析する。実行中の再呼び出しは無視する。失敗してもJournalEntryは変更しないため、
   * 同じ日を再実行できる。
   */
  fun analyze(day: LocalDate) {
    if (_analysisRunState.value == AnalysisRunState.Running) return
    _analysisRunState.value = AnalysisRunState.Running
    val zoneId = currentZoneId()
    val periodStart = day.atStartOfDay(zoneId).toInstant()
    val periodEnd = day.plusDays(1).atStartOfDay(zoneId).toInstant()
    viewModelScope.launch {
      _analysisRunState.value = try {
        when (val outcome = periodAnalyzer.analyze(periodStart, periodEnd)) {
          is PeriodAnalysisOutcome.Success -> {
            analysisResultWriter.save(
              AnalysisResult(
                periodStart = periodStart,
                periodEnd = periodEnd,
                analyzedAt = now(),
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
