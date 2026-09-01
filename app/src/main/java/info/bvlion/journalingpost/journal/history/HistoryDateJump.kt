package info.bvlion.journalingpost.journal.history

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 日付指定で任意の日へ移動するための変換と可否判定。Material3のDatePickerはUTC基準のepoch millisで
 * 日付を扱うため、端末timezoneのカレンダー日との境界をここへ閉じ、UI側へ日付計算を持ち出さない。
 */
internal fun LocalDate.toDatePickerMillis(): Long =
  atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun Long.toDatePickerDate(): LocalDate =
  Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

/** 記録のtimestampは常に記録時点のため未来日には記録が存在し得ない。日付指定でも選べないようにする。 */
internal fun isSelectableHistoryDate(utcTimeMillis: Long, today: LocalDate): Boolean =
  !utcTimeMillis.toDatePickerDate().isAfter(today)

internal fun isSelectableHistoryYear(year: Int, today: LocalDate): Boolean = year <= today.year
