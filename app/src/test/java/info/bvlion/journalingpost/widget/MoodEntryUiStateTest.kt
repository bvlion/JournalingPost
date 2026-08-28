package info.bvlion.journalingpost.widget

import info.bvlion.journalingpost.MainViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodEntryUiStateTest {
  @Test
  fun `INITとFAILUREではWidgetからの新しいMoodを受け付ける`() {
    assertTrue(MainViewModel.UiState.INIT.acceptsNewMoodEntry())
    assertTrue(MainViewModel.UiState.FAILURE.acceptsNewMoodEntry())
  }

  @Test
  fun `記録処理中と記録成功後はWidgetからの新しいMoodを受け付けない`() {
    assertFalse(MainViewModel.UiState.LOADING.acceptsNewMoodEntry())
    assertFalse(MainViewModel.UiState.SUCCESS.acceptsNewMoodEntry())
    assertFalse(MainViewModel.UiState.SUCCESS_DELIVERY_FAILED.acceptsNewMoodEntry())
  }
}
