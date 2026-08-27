package info.bvlion.journalingpost.journal

import info.bvlion.journalingpost.mood.MoodSnapshot
import info.bvlion.journalingpost.mood.formatMoodMessage
import info.bvlion.journalingpost.poster.JournalPoster
import java.time.Instant
import kotlinx.coroutines.CancellationException

/**
 * まずJournalEntryをローカル保存し(local)、その後既存Webhookへ送信する(webhook)。
 *
 * ローカル保存が成功した時点で記録は成功として扱う。Webhookが失敗・例外を投げても
 * JournalEntryは残し、deliveryStatusをFAILEDにするのみで、この関数自体は例外を
 * 投げずに正常終了する。ローカル保存(insert)自体が失敗した場合はその例外をそのまま
 * 呼び出し元へ伝える。
 */
class LocalWebhookJournalRecorder(
  private val repository: JournalEntryRepository,
  private val journalPoster: JournalPoster,
  private val now: () -> Instant = Instant::now,
) : JournalRecorder {
  override suspend fun record(note: String, mood: MoodSnapshot?, source: JournalSource) {
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

    repository.updateDeliveryStatus(id, if (sent) DeliveryStatus.SENT else DeliveryStatus.FAILED)
  }
}
