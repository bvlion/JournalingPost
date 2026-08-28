package info.bvlion.journalingpost.journal

import info.bvlion.journalingpost.mood.MoodSnapshot
import info.bvlion.journalingpost.mood.formatMoodMessage
import info.bvlion.journalingpost.poster.JournalPoster
import info.bvlion.journalingpost.poster.WebhookConfig
import java.time.Instant
import kotlinx.coroutines.CancellationException

/**
 * ローカル保存(insert)が成功した時点で記録は成功として扱う。Webhookの失敗・例外や
 * deliveryStatus更新の失敗はこの関数を例外にせず、戻り値のDeliveryStatusへ反映する
 * (status更新に失敗した場合はPENDINGのまま残る)。insert自体の失敗のみ例外を伝播する。
 */
class LocalWebhookJournalRecorder(
  private val repository: JournalEntryRepository,
  private val journalPoster: JournalPoster,
  private val now: () -> Instant = Instant::now,
  private val isWebhookConfigured: () -> Boolean = { WebhookConfig.isConfigured },
) : JournalRecorder {
  override suspend fun record(note: String, mood: MoodSnapshot?, source: JournalSource): DeliveryStatus {
    val id = repository.insert(
      JournalEntry(
        timestamp = now(),
        moodId = mood?.id,
        moodEmoji = mood?.emoji,
        moodLabel = mood?.label,
        note = note.ifBlank { null },
        source = source,
        deliveryStatus = DeliveryStatus.PENDING,
      ),
    )

    val message = if (mood != null) formatMoodMessage(mood.emoji, note) else note
    // 未設定・CI生成のダミー値のままではWebhookへ実際にネットワーク送信しない。
    val sent = if (!isWebhookConfigured()) {
      false
    } else {
      try {
        journalPoster.post(message)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        false
      }
    }
    val status = if (sent) DeliveryStatus.SENT else DeliveryStatus.FAILED

    try {
      repository.updateDeliveryStatus(id, status)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // insertは既に成功しているため、status更新の失敗はここで飲み込む(PENDINGのまま残る)。
    }

    return status
  }
}
