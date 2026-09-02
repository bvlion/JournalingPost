package info.bvlion.journalingpost.analysis

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import info.bvlion.journalingpost.settings.AnalysisIntegration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 手動解析で選べる日を決める。判定はこの1か所へ閉じ、UI側へ日付計算を持ち出さない。
 *
 * Custom Webhookは記録が1件以上ある日すべて(Issue #38で確定した扱いを維持する)。
 * Hostedは当日を対象にせず、まだ解析されていない前日以前の記録日だけにする(Issue #59)。
 * 自動解析・手動解析を問わず解析済み(その日を対象期間とする[AnalysisResult]が存在する)日も除外する。
 */
internal fun manualAnalysisSelectableDays(
  integration: AnalysisIntegration,
  recordedDays: Set<LocalDate>,
  analyzedDays: Set<LocalDate>,
  today: LocalDate,
): Set<LocalDate> = when (integration) {
  AnalysisIntegration.HOSTED ->
    recordedDays.filterTo(mutableSetOf()) { it.isBefore(today) && it !in analyzedDays }

  AnalysisIntegration.CUSTOM_WEBHOOK, AnalysisIntegration.NONE -> recordedDays
}

/**
 * 手動解析の日付選択で、[selectableDays]に含まれる日だけを選べるようにする。
 *
 * Material3のDatePickerはUTC基準のepoch millisで日付を渡してくるため、端末timezoneのカレンダー日
 * との相互変換もここへ閉じる。
 */
@OptIn(ExperimentalMaterial3Api::class)
internal fun analysisSelectableDates(selectableDays: Set<LocalDate>): SelectableDates {
  val years = selectableDays.map { it.year }
  val selectableYears = if (years.isEmpty()) IntRange.EMPTY else years.min()..years.max()
  return object : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
      utcTimeMillis.toLocalDateFromUtc() in selectableDays

    override fun isSelectableYear(year: Int): Boolean = year in selectableYears
  }
}

internal fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun Long.toLocalDateFromUtc(): LocalDate =
  Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
