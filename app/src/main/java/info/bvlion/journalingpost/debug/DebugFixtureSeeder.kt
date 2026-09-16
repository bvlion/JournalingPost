package info.bvlion.journalingpost.debug

import info.bvlion.journalingpost.analysis.AnalysisExecutionRepository
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
 * debugビルドの動作確認用に、今日を含む過去14日分のJournalEntry / AnalysisResultを端末内DBへ直接
 * 投入し、解析入力の対応関係も保存する。本番の記録経路
 * ([info.bvlion.journalingpost.journal.JournalRecorder])や外部解析は経由しない。
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
  private val analysisExecutionRepository: AnalysisExecutionRepository,
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

    val entries = buildEntries(today, zone, moodList).map { entry ->
      entry.copy(id = journalEntryRepository.insert(entry))
    }

    val results = buildAnalysisResults(today, zone, nowInstant)
    for (result in results) {
      val resultId = analysisResultWriter.save(result)
      analysisExecutionRepository.recordSuccess(
        resultId = resultId,
        entryIds = entries
          .filter { !it.timestamp.isBefore(result.periodStart) && it.timestamp.isBefore(result.periodEnd) }
          .mapTo(mutableSetOf()) { it.id },
        hostedSuccessfulDay = null,
      )
    }

    markSeeded()
    DebugFixtureSeedResult.Seeded(
      entryCount = entries.size,
      analysisResultCount = results.size,
    )
  }

  private fun buildEntries(today: LocalDate, zone: ZoneId, moodList: List<Mood>): List<JournalEntry> {
    val entries = mutableListOf<JournalEntry>()
    var index = 0
    for ((daysAgo, fixtureDay) in FIXTURE_DAYS.asReversed().withIndex()) {
      val day = today.minusDays(daysAgo.toLong())
      for (fixtureEntry in fixtureDay.entries) {
        val mood = moodList.firstOrNull {
          it.emoji == fixtureEntry.moodEmoji && it.label == fixtureEntry.moodLabel
        }
        entries += JournalEntry(
          timestamp = day.atTime(fixtureEntry.time).atZone(zone).toInstant(),
          moodId = mood?.id,
          moodEmoji = fixtureEntry.moodEmoji,
          moodLabel = fixtureEntry.moodLabel,
          note = fixtureEntry.note,
          source = if (index % 3 == 0) JournalSource.WIDGET else JournalSource.APP,
        )
        index++
      }
    }
    return entries
  }

  private fun buildAnalysisResults(
    today: LocalDate,
    zone: ZoneId,
    nowInstant: Instant,
  ): List<AnalysisResult> = FIXTURE_DAYS.asReversed().mapIndexed { daysAgo, fixtureDay ->
    val day = today.minusDays(daysAgo.toLong())
    val analyzedCandidate = day.atTime(21, 30).atZone(zone).toInstant()
    AnalysisResult(
      periodStart = day.atStartOfDay(zone).toInstant(),
      periodEnd = day.plusDays(1).atStartOfDay(zone).toInstant(),
      // 今日ぶんは投入時刻より未来にならないようにする。
      analyzedAt = if (analyzedCandidate.isAfter(nowInstant)) nowInstant else analyzedCandidate,
      body = fixtureDay.analysisBody,
    )
  }

  private data class FixtureDay(
    val entries: List<FixtureEntry>,
    val analysisBody: String,
  )

  private data class FixtureEntry(
    val time: LocalTime,
    val moodEmoji: String,
    val moodLabel: String,
    val note: String? = null,
  )

  private companion object {
    val FIXTURE_DAYS: List<FixtureDay> = listOf(
      FixtureDay(
        entries = listOf(
          FixtureEntry(time = LocalTime.of(8, 20), moodEmoji = "😌", moodLabel = "穏やか", note = "少し早起きできた。朝の空気が気持ちよかった。"),
          FixtureEntry(time = LocalTime.of(13, 10), moodEmoji = "😄", moodLabel = "嬉しい", note = "気になっていた用事を片づけられて、気持ちが軽くなった。"),
          FixtureEntry(time = LocalTime.of(19, 40), moodEmoji = "😌", moodLabel = "穏やか", note = "夕方にゆっくり散歩した。今日は余裕を持って過ごせた気がする。"),
        ),
        analysisBody = """
          【要約】
          朝から穏やかな気分で始まり、気になっていた用事を片づけたことで気持ちが軽くなった一日だった。夕方には散歩を楽しみ、全体として余裕を持って過ごせたように見える。

          【良かったこと】
          - 少し早起きでき、朝の空気を気持ちよく感じた。
          - 気になっていた用事を片づけ、気持ちが軽くなった。
          - 夕方にゆっくり散歩し、余裕を持って過ごせたように見える。

          【嫌だったこと】
          なし

          【感情】
          ポジティブ（88 / 100）

          【AI アドバイス】
          用事を片づけた後や散歩をした後に気分がよくなっているため、今後もその前後の気分や過ごし方を記録すると、自分に合う一日の組み立て方を確認できそうです。
        """.trimIndent(),
      ),
      FixtureDay(
        entries = listOf(
          FixtureEntry(time = LocalTime.of(9, 0), moodEmoji = "🤩", moodLabel = "ワクワク", note = "朝から出かける予定があって楽しみ。"),
          FixtureEntry(time = LocalTime.of(14, 30), moodEmoji = "😄", moodLabel = "嬉しい", note = "行ってみたかった店で昼ごはんを食べた。思っていたより良かった。"),
          FixtureEntry(time = LocalTime.of(21, 10), moodEmoji = "😌", moodLabel = "穏やか", note = "よく歩いて少し疲れたけど、満足感のある一日だった。"),
        ),
        analysisBody = """
          【要約】
          朝から外出を楽しみにしており、実際に訪れたかった店で期待以上の昼食を楽しめた一日だった。よく歩いたことで少し疲れは見られるものの、夜には満足感のある一日として振り返っている。

          【良かったこと】
          - 朝から出かける予定を楽しみにしていた。
          - 行ってみたかった店で昼ごはんを食べ、期待以上に良かった。
          - よく歩いた一日の終わりに、満足感を得ていた。

          【嫌だったこと】
          - よく歩いたことで少し疲れた。

          【感情】
          ポジティブ（92 / 100）

          【AI アドバイス】
          今回のように、楽しみにしていた外出や期待以上だった出来事が満足感につながった記録を残しておくと、今後の予定づくりの参考になりそうです。外出後の疲れと満足感のバランスも、次回以降確認してみてください。
        """.trimIndent(),
      ),
      FixtureDay(
        entries = listOf(
          FixtureEntry(time = LocalTime.of(7, 50), moodEmoji = "😐", moodLabel = "普通", note = "少し眠いけれど、予定は整理できている。"),
          FixtureEntry(time = LocalTime.of(12, 20), moodEmoji = "😕", moodLabel = "もやもや", note = "午前中に細かい作業が重なって、思ったより進まなかった。"),
          FixtureEntry(time = LocalTime.of(18, 10), moodEmoji = "😮‍💨", moodLabel = "疲れた", note = "夕方には集中力が落ちた。明日は最初に優先順位を決めたい。"),
        ),
        analysisBody = """
          【要約】
          予定は整理できていたものの、午前中は細かい作業が重なって進捗が伸びず、気分が下がった。夕方には集中力も落ち、朝の中立的な状態から一日の後半にかけてややネガティブに変化した。一方で、明日の優先順位を決めるという振り返りはできていた。

          【良かったこと】
          - 朝の時点で予定を整理できていた。
          - 明日に向けて、最初に優先順位を決めるという具体策を考えていた。

          【嫌だったこと】
          - 午前中に細かい作業が重なり、思ったより進まなかった。
          - 夕方に集中力が落ちた。

          【感情】
          ネガティブ（42 / 100）

          【AI アドバイス】
          細かい作業が重なった午前中と、集中力が落ちた夕方を分けて記録し、明日は開始時に優先順位を1〜3件に絞って、進み方や夕方の集中力がどう変わるか確認してみてください。
        """.trimIndent(),
      ),
      FixtureDay(
        entries = listOf(
          FixtureEntry(time = LocalTime.of(8, 10), moodEmoji = "😐", moodLabel = "普通", note = "朝に今日やることを3つだけ決めた。"),
          FixtureEntry(time = LocalTime.of(11, 45), moodEmoji = "😄", moodLabel = "嬉しい", note = "午前中のうちに一番気になっていた作業が終わった。"),
          FixtureEntry(time = LocalTime.of(17, 50), moodEmoji = "😌", moodLabel = "穏やか", note = "昨日より余裕があった。やることを絞ったのがよかったかもしれない。"),
        ),
        analysisBody = """
          【要約】
          朝は気分が中立的だったが、やることを3つに絞って始め、午前中に最も気になっていた作業を終えた。その後は気分が😄から😌へ移り、昨日より余裕があった一日として記録されている。やることを絞る方法が、この日の進み方や余裕と関連していたように見える。

          【良かったこと】
          - 午前中のうちに、最も気になっていた作業を終えた。
          - 昨日より余裕があり、やることを絞ったことがよかったかもしれないと記録している。

          【嫌だったこと】
          なし

          【感情】
          ポジティブ（82 / 100）

          【AI アドバイス】
          今後も、朝にやることを少数に絞り、最も気になっている作業を早めに進めた日の気分や余裕を記録して、同じ傾向が続くか確認してみてください。
        """.trimIndent(),
      ),
      FixtureDay(
        entries = listOf(
          FixtureEntry(time = LocalTime.of(8, 35), moodEmoji = "😰", moodLabel = "不安", note = "朝から予定が多くて、ちゃんと終わるか少し不安。"),
          FixtureEntry(time = LocalTime.of(13, 0), moodEmoji = "😮‍💨", moodLabel = "疲れた", note = "午前中はずっと集中していて、昼にはかなり疲れた。"),
          FixtureEntry(time = LocalTime.of(16, 40), moodEmoji = "😡", moodLabel = "イライラ", note = "予定外の対応が入って、思うように進まなかった。"),
          FixtureEntry(time = LocalTime.of(20, 15), moodEmoji = "😌", moodLabel = "穏やか", note = "夕食後に少し休んだら落ち着いた。今日は詰め込みすぎたと思う。"),
        ),
        analysisBody = """
          【要約】
          予定の多さへの不安から始まり、午前中の集中後には疲労が見られ、午後は予定外の対応で思うように進まなかった一日だった。夕食後に休むと落ち着いたが、本人は全体として予定を詰め込みすぎたと振り返っている。

          【良かったこと】
          - 夕食後に休むことで、落ち着きを取り戻せた。

          【嫌だったこと】
          - 朝から予定が多く、終えられるか不安を感じていた。
          - 午前中の集中後にかなり疲れ、予定外の対応で思うように進まなかった。

          【感情】
          ネガティブ（40 / 100）

          【AI アドバイス】
          予定外の対応が入った日にも進めやすいよう、今後は予定の間に余白を確保できるか確認してみてください。夕食後の休憩で落ち着けた点も、疲れが強い日の回復方法として記録しておくと役立ちそうです。
        """.trimIndent(),
      ),
      FixtureDay(
        entries = listOf(
          FixtureEntry(time = LocalTime.of(8, 0), moodEmoji = "😐", moodLabel = "普通", note = "昨日の疲れが少し残っている。今日は無理をしないようにしたい。"),
          FixtureEntry(time = LocalTime.of(12, 40), moodEmoji = "😌", moodLabel = "穏やか", note = "昼休みに外へ出た。短い時間でも気分転換になった。"),
          FixtureEntry(time = LocalTime.of(18, 30), moodEmoji = "😄", moodLabel = "嬉しい", note = "予定していたところまで終わった。昨日より落ち着いて進められた。"),
        ),
        analysisBody = """
          【要約】
          朝は前日の疲れが少し残り、無理をしない方針で始まったが、昼休みの外出が気分転換になったように見える。その後は予定していたところまで終え、昨日より落ち着いて進められたと記録されており、気分は😐から😌、😄へ改善した一日だった。

          【良かったこと】
          - 昼休みに外へ出て、短い時間でも気分転換になった。
          - 予定していたところまで終え、昨日より落ち着いて進められた。

          【嫌だったこと】
          - 朝の時点で、昨日の疲れが少し残っていた。

          【感情】
          ポジティブ（78 / 100）

          【AI アドバイス】
          短時間の外出後に気分が上向き、その後も落ち着いて予定を進められているため、今後も同様の休憩を取り入れた日の気分や進み方を記録して比較してみてください。
        """.trimIndent(),
      ),
      FixtureDay(
        entries = listOf(
          FixtureEntry(time = LocalTime.of(7, 45), moodEmoji = "😄", moodLabel = "嬉しい", note = "よく眠れて、朝から頭がすっきりしている。"),
          FixtureEntry(time = LocalTime.of(12, 15), moodEmoji = "😐", moodLabel = "普通"),
          FixtureEntry(time = LocalTime.of(15, 50), moodEmoji = "😮‍💨", moodLabel = "疲れた", note = "午後は少し集中力が落ちた。"),
          FixtureEntry(time = LocalTime.of(19, 10), moodEmoji = "😌", moodLabel = "穏やか", note = "予定していたことが終わってほっとした。夜はのんびり過ごせた。"),
        ),
        analysisBody = """
          【要約】
          朝は睡眠が十分で頭がすっきりしており、良い状態で始まったようです。昼は中立的な気分となり、午後には集中力の低下が記録されましたが、夜は予定していたことを終えた安堵とゆったりした時間により、落ち着いた状態に戻った一日でした。

          【良かったこと】
          - よく眠れて、朝から頭がすっきりしていた
          - 夜はのんびり過ごせ、予定していたことが終わってほっとした

          【嫌だったこと】
          - 午後に少し集中力が落ちた

          【感情】
          ポジティブ（75 / 100）

          【AI アドバイス】
          朝のすっきりした状態と、午後に集中力が落ちた点を今後も記録し、時間帯や睡眠との関係を確認してみると、集中しやすい時間の把握に役立ちそうです。
        """.trimIndent(),
      ),
      FixtureDay(
        entries = listOf(
          FixtureEntry(time = LocalTime.of(8, 30), moodEmoji = "😄", moodLabel = "嬉しい", note = "朝からよく眠れて体が軽い。"),
          FixtureEntry(time = LocalTime.of(12, 15), moodEmoji = "😐", moodLabel = "普通"),
          FixtureEntry(time = LocalTime.of(15, 40), moodEmoji = "😮‍💨", moodLabel = "疲れた", note = "散歩したら気分転換になった。"),
          FixtureEntry(time = LocalTime.of(20, 0), moodEmoji = "😌", moodLabel = "穏やか", note = "夜は少し早めに休むことにした。"),
        ),
        analysisBody = """
          【要約】
          朝は睡眠が取れて体が軽く、良好な状態で始まったようです。昼には気分が😐に変化しましたが、午後の散歩が気分転換になり、夜には😌まで戻っています。全体として、途中に気分の揺れはありつつも、休息や散歩を取り入れながら比較的穏やかに過ごした一日と考えられます。

          【良かったこと】
          - 朝からよく眠れて体が軽かった
          - 散歩が気分転換になった
          - 夜は少し早めに休むことにした

          【嫌だったこと】
          なし

          【感情】
          ポジティブ（78 / 100）

          【AI アドバイス】
          朝の睡眠状態、散歩後の気分、早めに休んだ日の翌朝の体調を引き続き記録すると、自分に合う回復パターンを確認しやすくなります。
        """.trimIndent(),
      ),
      FixtureDay(
        entries = listOf(
          FixtureEntry(time = LocalTime.of(9, 10), moodEmoji = "😌", moodLabel = "穏やか", note = "予定を入れすぎず、ゆっくり朝を過ごした。"),
          FixtureEntry(time = LocalTime.of(14, 0), moodEmoji = "😄", moodLabel = "嬉しい", note = "部屋を片づけたら気分もすっきりした。"),
          FixtureEntry(time = LocalTime.of(19, 30), moodEmoji = "😌", moodLabel = "穏やか", note = "明日の準備を少しだけ済ませた。落ち着いて一日を終えられた。"),
        ),
        analysisBody = """
          【要約】
          一日を通して😌と😄が続き、無理のないペースで過ごせたように見えます。片づけによるすっきり感や、翌日の準備を少し進められたこともあり、落ち着きと前向きさが保たれた一日でした。

          【良かったこと】
          - 予定を詰め込まず、ゆっくり朝を過ごした
          - 部屋を片づけ、気分もすっきりした
          - 明日の準備を少し済ませ、落ち着いて一日を終えた

          【嫌だったこと】
          なし

          【感情】
          ポジティブ（85 / 100）

          【AI アドバイス】
          予定を入れすぎないこと、片づけ、翌日の準備が心地よさとともに記録されています。今後も、どの程度の片づけや準備が負担なく気分の安定につながるかを記録してみると、自分に合うペースを確認できそうです。
        """.trimIndent(),
      ),
      FixtureDay(
        entries = listOf(
          FixtureEntry(time = LocalTime.of(8, 5), moodEmoji = "😐", moodLabel = "普通", note = "前より気持ちに余裕がある。"),
          FixtureEntry(time = LocalTime.of(12, 30), moodEmoji = "😄", moodLabel = "嬉しい", note = "午前中に予定どおり進められた。"),
          FixtureEntry(time = LocalTime.of(17, 20), moodEmoji = "😮‍💨", moodLabel = "疲れた", note = "夕方は少し疲れたが、やることは終えられた。"),
        ),
        analysisBody = """
          【要約】
          朝は前より気持ちに余裕がある状態で始まり、午前中は予定どおり進められて気分も上向いた。夕方には少し疲れが見られたものの、必要なことは終えられており、全体としては順調に進んだ一日だったように見える。

          【良かったこと】
          - 午前中に予定どおり進められた。
          - 夕方までにやることを終えられた。
          - 前より気持ちに余裕があると記録されている。

          【嫌だったこと】
          - 夕方に少し疲れた。

          【感情】
          ポジティブ（76 / 100）

          【AI アドバイス】
          午前中に予定どおり進められ、夕方に疲れつつも完了できた流れを、今後も時間帯ごとに記録してみてください。特に、余裕のある朝にどの程度予定を進められるか、夕方の疲れがどのくらい続くかを比較すると、自分に合うペースを確認しやすくなります。
        """.trimIndent(),
      ),
      FixtureDay(
        entries = listOf(
          FixtureEntry(time = LocalTime.of(8, 25), moodEmoji = "😌", moodLabel = "穏やか", note = "朝に少し散歩してから始めた。"),
          FixtureEntry(time = LocalTime.of(11, 50), moodEmoji = "🤩", moodLabel = "ワクワク", note = "新しいことを試してみたら、思ったよりうまくいった。"),
          FixtureEntry(time = LocalTime.of(16, 10), moodEmoji = "😄", moodLabel = "嬉しい", note = "午後も集中が続いた。手応えのある一日だった。"),
          FixtureEntry(time = LocalTime.of(20, 20), moodEmoji = "😌", moodLabel = "穏やか"),
        ),
        analysisBody = """
          【要約】
          朝の散歩から穏やかに始まり、新しい試みが予想以上にうまくいったことで、午前中に気分が高まっていた。午後も集中が続き、手応えを感じる一日となったように見える。夜には穏やかな気分に戻っている。

          【良かったこと】
          - 朝に少し散歩してから一日を始めた。
          - 新しいことを試し、思ったよりうまくいった。
          - 午後も集中が続き、手応えのある一日だった。

          【嫌だったこと】
          なし

          【感情】
          ポジティブ（88 / 100）

          【AI アドバイス】
          散歩、新しい試み、午後の集中が同じ日に記録されているため、今後もこれらを行った日の気分や手応えを記録し、再現しやすい組み合わせか観察してみてください。
        """.trimIndent(),
      ),
      FixtureDay(
        entries = listOf(
          FixtureEntry(time = LocalTime.of(7, 55), moodEmoji = "😐", moodLabel = "普通", note = "少し寝不足。朝はゆっくり始めた。"),
          FixtureEntry(time = LocalTime.of(12, 10), moodEmoji = "😮‍💨", moodLabel = "疲れた", note = "午前中に考えることが多くて、少し頭が疲れた。"),
          FixtureEntry(time = LocalTime.of(15, 30), moodEmoji = "😕", moodLabel = "もやもや", note = "思ったように進まないところがあって、気持ちが引っかかっている。"),
          FixtureEntry(time = LocalTime.of(19, 0), moodEmoji = "😌", moodLabel = "穏やか", note = "いったん切り上げて休んだら、少し気持ちが戻った。"),
        ),
        analysisBody = """
          【要約】
          寝不足の状態から始まり、午前中は考えることの多さで疲れ、午後には思うように進まない点への引っかかりが記録されている。夜にいったん切り上げて休んだ後は、気分がやや回復したように見える。全体として負担感のある一日だが、休息後の改善も確認できる。

          【良かったこと】
          - いったん作業を切り上げて休むことで、19時には「少し気持ちが戻った」と記録している。

          【嫌だったこと】
          - 少し寝不足で、朝はゆっくり始まった。
          - 午前中に考えることが多く、少し頭が疲れた。
          - 思ったように進まないところがあり、気持ちが引っかかった。

          【感情】
          ネガティブ（42 / 100）

          【AI アドバイス】
          今日は、休憩後に気分が戻った変化を手がかりに、負担感や行き詰まりを感じた時点で早めに区切る方法を試し、休む前後の気分を記録してみてください。
        """.trimIndent(),
      ),
      FixtureDay(
        entries = listOf(
          FixtureEntry(time = LocalTime.of(8, 15), moodEmoji = "😄", moodLabel = "嬉しい", note = "昨日よりよく眠れた。朝から気分が軽い。"),
          FixtureEntry(time = LocalTime.of(11, 40), moodEmoji = "😌", moodLabel = "穏やか", note = "昨日引っかかっていたことを落ち着いて見直せた。"),
          FixtureEntry(time = LocalTime.of(17, 30), moodEmoji = "😄", moodLabel = "嬉しい", note = "少しずつ整理できて、思っていたより前に進めた。"),
          FixtureEntry(time = LocalTime.of(21, 0), moodEmoji = "😌", moodLabel = "穏やか", note = "無理に全部終わらせず、区切りのいいところで終えた。"),
        ),
        analysisBody = """
          【要約】
          睡眠の改善と軽い気分で始まり、気になっていたことを落ち着いて見直せた一日だった。夕方には整理が進み、想定以上に前進できた様子がある。夜はすべてを終わらせようとせず、区切りのよいところで終えており、全体として安定して前向きな一日だったと考えられる。

          【良かったこと】
          - 昨日よりよく眠れ、朝から気分が軽かった
          - 昨日引っかかっていたことを落ち着いて見直せた
          - 少しずつ整理が進み、思っていたより前に進めた

          【嫌だったこと】
          なし

          【感情】
          ポジティブ（86 / 100）

          【AI アドバイス】
          「よく眠れた」「落ち着いて見直せた」「区切りのよいところで終えた」ことと気分の推移を、今後も記録してみてください。前進できた量だけでなく、無理なく終えられたタイミングも振り返ると、調子のよい日の共通点を確認しやすくなります。
        """.trimIndent(),
      ),
      FixtureDay(
        entries = listOf(
          FixtureEntry(time = LocalTime.of(8, 0), moodEmoji = "😌", moodLabel = "穏やか", note = "朝から落ち着いている。前よりペースをつかめている気がする。"),
          FixtureEntry(time = LocalTime.of(12, 25), moodEmoji = "😄", moodLabel = "嬉しい", note = "午前中に集中できて、予定していた作業が片づいた。"),
          FixtureEntry(time = LocalTime.of(16, 45), moodEmoji = "😮‍💨", moodLabel = "疲れた", note = "午後は少し疲れたので、早めに休憩を入れた。"),
          FixtureEntry(time = LocalTime.of(19, 20), moodEmoji = "😌", moodLabel = "穏やか", note = "休憩したら気持ちを切り替えられた。無理しすぎず進められたと思う。"),
        ),
        analysisBody = """
          【要約】
          朝は落ち着いており、午前中は集中して予定の作業を片づけられた。午後には少し疲れが出たものの、早めの休憩後に気持ちを切り替えられている。全体として、前よりペースをつかみ、無理を抑えながら進められた一日だったように見える。

          【良かったこと】
          - 午前中に集中でき、予定していた作業を終えた。
          - 疲れを感じた午後に早めの休憩を入れ、その後気持ちを切り替えられた。
          - 前よりペースをつかみ、無理しすぎず進められたと記録している。

          【嫌だったこと】
          - 午後に少し疲れを感じた。

          【感情】
          ポジティブ（82 / 100）

          【AI アドバイス】
          午後に疲れを感じた際、早めの休憩で切り替えられているため、今後も疲れが強くなる前に休憩を入れた場合の気分や進み具合を記録しておくと、この進め方が自分に合うか確認しやすくなります。
        """.trimIndent(),
      ),
    )
  }
}

sealed interface DebugFixtureSeedResult {
  data class Seeded(val entryCount: Int, val analysisResultCount: Int) : DebugFixtureSeedResult

  data object AlreadySeeded : DebugFixtureSeedResult
}
