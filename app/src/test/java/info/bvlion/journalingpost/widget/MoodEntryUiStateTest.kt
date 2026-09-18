package info.bvlion.journalingpost.widget

import info.bvlion.journalingpost.JournalRecordViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodEntryUiStateTest {
  @Test
  fun `記録処理中以外はWidgetからの新しいMoodを受け付ける`() {
    assertTrue(JournalRecordViewModel.UiState.INIT.acceptsNewMoodEntry())
    assertTrue(JournalRecordViewModel.UiState.FAILURE.acceptsNewMoodEntry())
    assertTrue(JournalRecordViewModel.UiState.SUCCESS.acceptsNewMoodEntry())
  }

  @Test
  fun `記録処理中はWidgetからの新しいMoodを受け付けない`() {
    assertFalse(JournalRecordViewModel.UiState.LOADING.acceptsNewMoodEntry())
  }
}
