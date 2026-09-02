package info.bvlion.journalingpost.analysis

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 手動解析の日付選択は、JournalEntryが1件以上ある日([recordedDays])だけを選べるようにする。
 * Hosted / Custom Webhookのどちらでも同じ選択として扱うため、判定はこの1か所へ閉じる。
 *
 * Material3のDatePickerはUTC基準のepoch millisで日付を渡してくるため、端末timezoneのカレンダー日
 * との相互変換もここへ閉じ、UI側へ日付計算を持ち出さない。
 */
@OptIn(ExperimentalMaterial3Api::class)
internal fun recordedDaySelectableDates(recordedDays: Set<LocalDate>): SelectableDates {
  val years = recordedDays.map { it.year }
  val selectableYears = if (years.isEmpty()) IntRange.EMPTY else years.min()..years.max()
  return object : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
      utcTimeMillis.toLocalDateFromUtc() in recordedDays

    override fun isSelectableYear(year: Int): Boolean = year in selectableYears
  }
}

internal fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun Long.toLocalDateFromUtc(): LocalDate =
  Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
