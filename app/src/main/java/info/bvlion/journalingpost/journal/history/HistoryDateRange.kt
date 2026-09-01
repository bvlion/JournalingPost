package info.bvlion.journalingpost.journal.history

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * 履歴で表示できる最も古い日。記録の有無で決まる下限ではない。
 *
 * 左右スワイプはHorizontalPagerで扱っており有限のページ数を必要とするため、下限そのものは避けられない。
 * 値はMaterial3のDatePickerが既定で選択できる下限(1900年)へ揃えてあり、スワイプで辿れる範囲と
 * 日付指定で選べる範囲が食い違わないようにしている。
 */
internal val HISTORY_EARLIEST_DATE: LocalDate = LocalDate.of(1900, 1, 1)

/**
 * 最後のページを[today]にすることで、Pagerの終端がそのまま「未来へは進めない」制約になる。
 * 端末時刻が[HISTORY_EARLIEST_DATE]より前になっている場合でもPagerを構成できるよう1ページは残す。
 */
internal fun historyPageCount(today: LocalDate): Int = (historyPageOf(today) + 1).coerceAtLeast(1)

internal fun historyPageOf(date: LocalDate): Int =
  ChronoUnit.DAYS.between(HISTORY_EARLIEST_DATE, date).toInt()

internal fun historyDateOfPage(page: Int): LocalDate = HISTORY_EARLIEST_DATE.plusDays(page.toLong())

/** 記録のtimestampは常に記録時点のため、未来日には記録が存在し得ない。上限は[today]。 */
internal fun coerceToHistoryRange(date: LocalDate, today: LocalDate): LocalDate =
  minOf(maxOf(date, HISTORY_EARLIEST_DATE), today)

/**
 * Material3のDatePickerはUTC基準のepoch millisで日付を扱うため、端末timezoneのカレンダー日との
 * 相互変換をここへ閉じ、UI側へ日付計算を持ち出さない。
 */
internal fun LocalDate.toDatePickerMillis(): Long =
  atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun Long.toDatePickerDate(): LocalDate =
  Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

internal fun isSelectableHistoryDate(utcTimeMillis: Long, today: LocalDate): Boolean {
  val date = utcTimeMillis.toDatePickerDate()
  return !date.isBefore(HISTORY_EARLIEST_DATE) && !date.isAfter(today)
}

internal fun isSelectableHistoryYear(year: Int, today: LocalDate): Boolean =
  year in HISTORY_EARLIEST_DATE.year..today.year
