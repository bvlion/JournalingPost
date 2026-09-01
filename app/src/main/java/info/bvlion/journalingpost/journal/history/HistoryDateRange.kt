package info.bvlion.journalingpost.journal.history

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * 履歴で移動できる範囲は`[earliestDate, today]`。下限はJournalEntryが実際に存在する最古の日で決まり
 * このファイルは持たない。呼び出し側([info.bvlion.journalingpost.JournalHistoryViewModel])が現在の
 * 記録から算出した値を、そのつど引数として渡す。
 */

/** 左右スワイプはHorizontalPagerで扱っており、[earliestDate]をページ番号0とみなす。 */
internal fun historyPageOf(date: LocalDate, earliestDate: LocalDate): Int =
  ChronoUnit.DAYS.between(earliestDate, date).toInt()

internal fun historyDateOfPage(page: Int, earliestDate: LocalDate): LocalDate =
  earliestDate.plusDays(page.toLong())

/** 記録が1件も無く`earliestDate == today`の場合は、今日だけの1ページになる。 */
internal fun historyPageCount(earliestDate: LocalDate, today: LocalDate): Int =
  (historyPageOf(today, earliestDate) + 1).coerceAtLeast(1)

/**
 * 未来日には記録が存在し得ないため上限は常に[today]。下限は現在存在する記録から決まる[earliestDate]で、
 * それより前の日へは移動できない。
 */
internal fun coerceToHistoryRange(date: LocalDate, earliestDate: LocalDate, today: LocalDate): LocalDate = when {
  date.isBefore(earliestDate) -> earliestDate
  date.isAfter(today) -> today
  else -> date
}

/**
 * Material3のDatePickerはUTC基準のepoch millisで日付を扱うため、端末timezoneのカレンダー日との
 * 相互変換をここへ閉じ、UI側へ日付計算を持ち出さない。
 */
internal fun LocalDate.toDatePickerMillis(): Long =
  atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun Long.toDatePickerDate(): LocalDate =
  Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

/** 日付指定でもスワイプ・前日翌日ボタンと同じ`[earliestDate, today]`だけを選べるようにする。 */
internal fun isSelectableHistoryDate(utcTimeMillis: Long, earliestDate: LocalDate, today: LocalDate): Boolean {
  val date = utcTimeMillis.toDatePickerDate()
  return !date.isBefore(earliestDate) && !date.isAfter(today)
}

internal fun isSelectableHistoryYear(year: Int, earliestDate: LocalDate, today: LocalDate): Boolean =
  year in earliestDate.year..today.year
