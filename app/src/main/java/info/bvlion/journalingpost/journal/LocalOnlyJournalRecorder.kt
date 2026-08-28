package info.bvlion.journalingpost.journal

import info.bvlion.journalingpost.mood.MoodSnapshot
import java.time.Instant

/** Webhookを一切呼ばず、ローカル保存のみで記録を完結させる。 */
class LocalOnlyJournalRecorder(
  private val repository: JournalEntryRepository,
  private val now: () -> Instant = Instant::now,
) : JournalRecorder {
  override suspend fun record(note: String, mood: MoodSnapshot?, source: JournalSource): DeliveryStatus {
    repository.insert(
      JournalEntry(
        timestamp = now(),
        moodId = mood?.id,
        moodEmoji = mood?.emoji,
        moodLabel = mood?.label,
        note = note.ifBlank { null },
        source = source,
        deliveryStatus = DeliveryStatus.NOT_REQUIRED,
      ),
    )
    return DeliveryStatus.NOT_REQUIRED
  }
}
