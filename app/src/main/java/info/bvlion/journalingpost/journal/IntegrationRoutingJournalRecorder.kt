package info.bvlion.journalingpost.journal

import info.bvlion.journalingpost.mood.MoodSnapshot
import info.bvlion.journalingpost.settings.AnalysisIntegration
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepository
import kotlinx.coroutines.flow.first

/**
 * 記録開始時点の解析・連携方法を1回だけ取得し、以降の分岐はその値に固定する。record()実行中に
 * 設定が変更されても、その回の記録には影響しない。どちらの分岐でもJournalEntryは必ずローカルへ
 * 保存し、違いは外部へ送信するかどうかだけ。
 */
class IntegrationRoutingJournalRecorder(
  private val analysisIntegrationRepository: AnalysisIntegrationRepository,
  private val localOnlyRecorder: JournalRecorder,
  private val localWebhookRecorder: JournalRecorder,
) : JournalRecorder {
  override suspend fun record(note: String, mood: MoodSnapshot?, source: JournalSource): DeliveryStatus =
    when (analysisIntegrationRepository.analysisIntegration.first()) {
      AnalysisIntegration.NONE -> localOnlyRecorder.record(note, mood, source)
      AnalysisIntegration.CUSTOM_WEBHOOK -> localWebhookRecorder.record(note, mood, source)
    }
}
