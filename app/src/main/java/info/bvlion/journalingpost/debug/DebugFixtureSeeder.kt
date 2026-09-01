package info.bvlion.journalingpost.debug

import info.bvlion.journalingpost.analysis.AnalysisResult
import info.bvlion.journalingpost.analysis.AnalysisResultWriter
import info.bvlion.journalingpost.journal.JournalEntry
import info.bvlion.journalingpost.journal.JournalEntryRepository
import info.bvlion.journalingpost.journal.JournalSource
import info.bvlion.journalingpost.mood.Mood
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * debugビルドの動作確認用に、今日を含む過去7日分のJournalEntry / AnalysisResultを端末内DBへ直接
 * 投入する。本番の記録経路([info.bvlion.journalingpost.journal.JournalRecorder])や外部解析は
 * 経由しない。
 *
 * 重複投入は[isAlreadySeeded] / [markSeeded](Room外のDataStoreフラグ)で防ぐ。設定項目の
 * ダブルタップ等で[seed]が並行しても、[seedMutex]で全体を直列化し、後続の呼び出しは先行処理の
 * 完了後に投入済み判定をやり直すため、fixtureは1セットしか入らない。この用途のために
 * Room schemaへdebug専用の列は追加しない。releaseビルドでは
 * [info.bvlion.journalingpost.di.AppContainer]がこのクラスを生成せず、設定画面の導線も出ないため、
 * 投入導線・処理ともに動作しない。
 */
class DebugFixtureSeeder(
  private val journalEntryRepository: JournalEntryRepository,
  private val analysisResultWriter: AnalysisResultWriter,
  private val isAlreadySeeded: suspend () -> Boolean,
  private val markSeeded: suspend () -> Unit,
  private val moods: suspend () -> List<Mood>,
  private val zoneId: () -> ZoneId,
  private val now: () -> Instant,
) {
  private val seedMutex = Mutex()

  suspend fun seed(): DebugFixtureSeedResult = seedMutex.withLock {
    if (isAlreadySeeded()) return@withLock DebugFixtureSeedResult.AlreadySeeded

    val zone = zoneId()
    val nowInstant = now()
    val today = nowInstant.atZone(zone).toLocalDate()
    val moodList = moods()

    val entries = buildEntries(today, zone, moodList)
    for (entry in entries) journalEntryRepository.insert(entry)

    val results = buildAnalysisResults(today, zone, nowInstant)
    for (result in results) analysisResultWriter.save(result)

    markSeeded()
    DebugFixtureSeedResult.Seeded(
      entryCount = entries.size,
      analysisResultCount = results.size,
    )
  }

  private fun buildEntries(today: LocalDate, zone: ZoneId, moodList: List<Mood>): List<JournalEntry> {
    val entries = mutableListOf<JournalEntry>()
    var index = 0
    // 今日ぶんは履歴画面の縦スクロールと日付内の横スワイプ競合を確認できる件数にする。
    for (time in TODAY_TIMES) {
      entries += entryAt(today, time, zone, moodList, index)
      index++
    }
    // 過去6日ぶんは日付移動・空日との違いを確認できる程度に複数件ずつ置く。
    for (daysAgo in 1..PAST_DAYS) {
      repeat(PAST_DAY_COUNTS[daysAgo - 1]) { slot ->
        entries += entryAt(today.minusDays(daysAgo.toLong()), PAST_DAY_TIMES[slot], zone, moodList, index)
        index++
      }
    }
    return entries
  }

  private fun entryAt(
    date: LocalDate,
    time: LocalTime,
    zone: ZoneId,
    moodList: List<Mood>,
    index: Int,
  ): JournalEntry {
    val shape = EntryShape.entries[index % EntryShape.entries.size]
    val mood = moodList.getOrNull(index % moodList.size.coerceAtLeast(1))
    val withMood = shape != EntryShape.NOTE_ONLY && mood != null
    val withNote = shape != EntryShape.MOOD_ONLY || mood == null
    return JournalEntry(
      timestamp = date.atTime(time).atZone(zone).toInstant(),
      moodId = mood?.id.takeIf { withMood },
      moodEmoji = mood?.emoji.takeIf { withMood },
      moodLabel = mood?.label.takeIf { withMood },
      note = NOTES[index % NOTES.size].takeIf { withNote },
      source = if (index % 3 == 0) JournalSource.WIDGET else JournalSource.APP,
    )
  }

  private fun buildAnalysisResults(
    today: LocalDate,
    zone: ZoneId,
    nowInstant: Instant,
  ): List<AnalysisResult> = (0..PAST_DAYS).map { daysAgo ->
    val day = today.minusDays(daysAgo.toLong())
    val analyzedCandidate = day.atTime(21, 30).atZone(zone).toInstant()
    AnalysisResult(
      periodStart = day.atStartOfDay(zone).toInstant(),
      periodEnd = day.plusDays(1).atStartOfDay(zone).toInstant(),
      // 今日ぶんは投入時刻より未来にならないようにする。
      analyzedAt = if (analyzedCandidate.isAfter(nowInstant)) nowInstant else analyzedCandidate,
      body = analysisBody(day),
    )
  }

  private fun analysisBody(day: LocalDate): String = buildString {
    appendLine("$day の記録をもとにした動作確認用の解析結果です。")
    appendLine()
    appendLine("・全体的な気分の傾向をここに表示します。")
    appendLine("・特徴的だった出来事の要約をここに表示します。")
    append("・翌日に向けた小さな気づきをここに表示します。")
  }

  private enum class EntryShape { MOOD_ONLY, MOOD_AND_NOTE, NOTE_ONLY }

  private companion object {
    const val PAST_DAYS = 6

    val TODAY_TIMES: List<LocalTime> = listOf(
      LocalTime.of(7, 15),
      LocalTime.of(8, 40),
      LocalTime.of(9, 55),
      LocalTime.of(11, 20),
      LocalTime.of(12, 30),
      LocalTime.of(13, 45),
      LocalTime.of(15, 5),
      LocalTime.of(16, 25),
      LocalTime.of(17, 40),
      LocalTime.of(18, 50),
      LocalTime.of(20, 0),
      LocalTime.of(21, 15),
      LocalTime.of(22, 25),
      LocalTime.of(23, 30),
    )

    val PAST_DAY_COUNTS: List<Int> = listOf(4, 5, 3, 4, 2, 3)

    val PAST_DAY_TIMES: List<LocalTime> = listOf(
      LocalTime.of(8, 30),
      LocalTime.of(12, 15),
      LocalTime.of(15, 40),
      LocalTime.of(19, 20),
      LocalTime.of(22, 10),
    )

    val NOTES: List<String> = listOf(
      "朝からよく眠れて体が軽い。",
      "会議が長引いて少し疲れた。",
      "散歩したら気分転換になった。",
      "夕方に集中できて作業がはかどった。",
      "なんとなく落ち着かない一日だった。",
      "友人と話してリフレッシュできた。",
      "天気が良くて気持ちよかった。",
      "夜ふかししてしまった。",
    )
  }
}

sealed interface DebugFixtureSeedResult {
  data class Seeded(val entryCount: Int, val analysisResultCount: Int) : DebugFixtureSeedResult

  data object AlreadySeeded : DebugFixtureSeedResult
}
