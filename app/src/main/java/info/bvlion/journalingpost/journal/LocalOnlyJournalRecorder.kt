package info.bvlion.journalingpost.journal

import info.bvlion.journalingpost.mood.MoodSnapshot
import java.time.Instant

class LocalOnlyJournalRecorder(
  private val repository: JournalEntryRepository,
  private val now: () -> Instant = Instant::now,
) : JournalRecorder {
  override suspend fun record(note: String, mood: MoodSnapshot?, source: JournalSource) {
    require(mood != null || note.isNotBlank())
    require(mood == null || mood.id.isNotBlank())
    require(mood == null || mood.emoji.isNotBlank() || mood.label.isNotBlank())
    repository.insert(
      JournalEntry(
        timestamp = now(),
        moodId = mood?.id,
        moodEmoji = mood?.emoji,
        moodLabel = mood?.label,
        note = note.ifBlank { null },
        source = source,
      ),
    )
  }
}
