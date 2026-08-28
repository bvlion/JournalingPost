package info.bvlion.journalingpost.journal

import info.bvlion.journalingpost.mood.MoodSnapshot
import info.bvlion.journalingpost.mood.formatMoodMessage
import info.bvlion.journalingpost.poster.JournalPoster
import java.time.Instant
import kotlinx.coroutines.CancellationException

/**
 * ローカル保存(insert)が成功した時点で記録は成功として扱う。Webhookの失敗・例外や
 * deliveryStatus更新の失敗はこの関数を例外にせず、戻り値のDeliveryStatusへ反映する
 * (status更新に失敗した場合はPENDINGのまま残る)。insert自体の失敗のみ例外を伝播する。
 * Webhook設定未登録・復号不能・template不正な場合もjournalPoster.post()がfalseを返すため、
 * ここでは配送方法固有の判定を持たない。
 */
class LocalWebhookJournalRecorder(
  private val repository: JournalEntryRepository,
  private val journalPoster: JournalPoster,
  private val now: () -> Instant = Instant::now,
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
    val sent = try {
      journalPoster.post(message)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      false
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
