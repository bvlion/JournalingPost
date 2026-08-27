package info.bvlion.journalingpost.journal

import info.bvlion.journalingpost.mood.MoodSnapshot
import info.bvlion.journalingpost.mood.formatMoodMessage
import info.bvlion.journalingpost.poster.JournalPoster
import java.time.Instant
import kotlinx.coroutines.CancellationException

/**
 * まずJournalEntryをローカル保存し(local)、その後既存Webhookへ送信する(webhook)。
 * Webhookが失敗してもローカル保存済みのJournalEntryは残し、deliveryStatusのみ更新する。
 */
class LocalWebhookJournalRecorder(
  private val repository: JournalEntryRepository,
  private val journalPoster: JournalPoster,
  private val now: () -> Instant = Instant::now,
) : JournalRecorder {
  override suspend fun record(note: String, mood: MoodSnapshot?, source: JournalSource): Boolean {
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
    return sent
  }
}
