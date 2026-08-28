package info.bvlion.journalingpost.journal

import info.bvlion.journalingpost.mood.MoodSnapshot
import info.bvlion.journalingpost.settings.RecordMode
import info.bvlion.journalingpost.settings.RecordModeRepository
import kotlinx.coroutines.flow.first

/**
 * 記録開始時点のモードを1回だけ取得し、以降の分岐(ローカルのみ/ローカル+Webhook)は
 * その値に固定する。record()実行中にモードが変更されても、その回の記録には影響しない。
 */
class ModeRoutingJournalRecorder(
  private val recordModeRepository: RecordModeRepository,
  private val localOnlyRecorder: JournalRecorder,
  private val localWebhookRecorder: JournalRecorder,
) : JournalRecorder {
  override suspend fun record(note: String, mood: MoodSnapshot?, source: JournalSource): DeliveryStatus =
    when (recordModeRepository.recordMode.first()) {
      RecordMode.LOCAL_ONLY -> localOnlyRecorder.record(note, mood, source)
      RecordMode.LOCAL_AND_WEBHOOK -> localWebhookRecorder.record(note, mood, source)
    }
}
