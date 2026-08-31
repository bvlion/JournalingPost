package info.bvlion.journalingpost

import info.bvlion.journalingpost.mood.Mood
import info.bvlion.journalingpost.mood.MoodRepository
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MoodSettingsViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val collectorScope = CoroutineScope(dispatcher)

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    collectorScope.cancel()
    Dispatchers.resetMain()
  }

  @Test
  fun `画面を開くと保存済みMoodを同じidと順序で読み込む`() = runTest(dispatcher) {
    val moods = listOf(mood("first", "😄", "嬉しい"), mood("second", "😭", "悲しい"))
    val viewModel = MoodSettingsViewModel(FakeMoodRepository(moods)) {}

    viewModel.onScreenOpened(screenSessionId = 1)
    advanceUntilIdle()

    assertEquals(listOf("first", "second"), viewModel.uiState.value.moods.map { it.id })
  }

  @Test
  fun `表示内容の編集と並び替えではidを変更しない`() = runTest(dispatcher) {
    val viewModel = createLoadedViewModel()

    viewModel.updateEmoji("first", "🤩")
    viewModel.updateLabel("first", "ワクワク")
    viewModel.moveDown("first")

    assertEquals(listOf("second", "first"), viewModel.uiState.value.moods.map { it.id })
    assertEquals("🤩", viewModel.uiState.value.moods.last().emoji)
    assertEquals("ワクワク", viewModel.uiState.value.moods.last().label)
  }

  @Test
  fun `削除後に追加したMoodは新しいidになる`() = runTest(dispatcher) {
    val viewModel = createLoadedViewModel()
    val deletedId = viewModel.uiState.value.moods.last().id

    viewModel.removeMood(deletedId)
    viewModel.addMood()

    assertNotEquals(deletedId, viewModel.uiState.value.moods.last().id)
  }

  @Test
  fun `Moodを0件または10件超へ変更できない`() = runTest(dispatcher) {
    val oneMoodViewModel = createLoadedViewModel(listOf(mood("only", "😄", "")))
    oneMoodViewModel.removeMood("only")
    assertEquals(1, oneMoodViewModel.uiState.value.moods.size)

    val tenMoodViewModel = createLoadedViewModel(
      (1..10).map { mood(it.toString(), "", "Mood $it") },
    )
    tenMoodViewModel.addMood()
    assertEquals(10, tenMoodViewModel.uiState.value.moods.size)
  }

  @Test
  fun `空白Moodと通常文字のemojiがある間は保存できない`() = runTest(dispatcher) {
    val viewModel = createLoadedViewModel()

    viewModel.updateEmoji("first", "")
    viewModel.updateLabel("first", "  ")
    assertFalse(viewModel.uiState.value.canSave)

    viewModel.updateLabel("first", "名称のみ")
    viewModel.updateEmoji("first", "abc")
    assertFalse(viewModel.uiState.value.canSave)

    viewModel.updateEmoji("first", "")
    assertTrue(viewModel.uiState.value.canSave)
  }

  @Test
  fun `保存時に空白を除きWidget更新と成功eventを通知する`() = runTest(dispatcher) {
    val repository = FakeMoodRepository(listOf(mood("first", "😄", "嬉しい")))
    var refreshCount = 0
    val viewModel = MoodSettingsViewModel(repository) { refreshCount++ }
    val events = collectEvents(viewModel)
    viewModel.onScreenOpened(screenSessionId = 1)
    advanceUntilIdle()
    viewModel.updateLabel("first", "  新しい名称  ")

    viewModel.save()
    advanceUntilIdle()

    assertEquals("新しい名称", repository.saved.single().label)
    assertEquals(1, refreshCount)
    assertEquals(listOf(MoodSettingsEvent.Saved), events)
  }

  @Test
  fun `保存失敗時は編集内容を維持して失敗eventを通知する`() = runTest(dispatcher) {
    val repository = FakeMoodRepository(
      listOf(mood("first", "😄", "嬉しい")),
      saveError = IOException("disk error"),
    )
    val viewModel = MoodSettingsViewModel(repository) {}
    val events = collectEvents(viewModel)
    viewModel.onScreenOpened(screenSessionId = 1)
    advanceUntilIdle()
    viewModel.updateLabel("first", "変更中")

    viewModel.save()
    advanceUntilIdle()

    assertEquals("変更中", viewModel.uiState.value.moods.single().label)
    assertFalse(viewModel.uiState.value.isSaving)
    assertEquals(listOf(MoodSettingsEvent.SaveFailed), events)
  }

  @Test
  fun `同じ画面sessionを再初期化しても未保存の編集を維持する`() = runTest(dispatcher) {
    val viewModel = createLoadedViewModel()
    viewModel.updateLabel("first", "編集中")

    viewModel.onScreenOpened(screenSessionId = 1)
    advanceUntilIdle()

    assertEquals("編集中", viewModel.uiState.value.moods.first().label)
  }

  @Test
  fun `新しい画面sessionを開くと保存済みのMoodを読み直す`() = runTest(dispatcher) {
    val viewModel = createLoadedViewModel()
    viewModel.updateLabel("first", "編集中")

    viewModel.onScreenOpened(screenSessionId = 2)
    advanceUntilIdle()

    assertEquals("嬉しい", viewModel.uiState.value.moods.first().label)
  }

  private suspend fun createLoadedViewModel(
    moods: List<Mood> = listOf(mood("first", "😄", "嬉しい"), mood("second", "😭", "悲しい")),
  ): MoodSettingsViewModel {
    val viewModel = MoodSettingsViewModel(FakeMoodRepository(moods)) {}
    viewModel.onScreenOpened(screenSessionId = 1)
    dispatcher.scheduler.advanceUntilIdle()
    return viewModel
  }

  private fun collectEvents(viewModel: MoodSettingsViewModel): List<MoodSettingsEvent> {
    val events = mutableListOf<MoodSettingsEvent>()
    collectorScope.launch { viewModel.events.collect { events += it } }
    return events
  }

  private fun mood(id: String, emoji: String, label: String) = Mood(id, emoji, label)

  private class FakeMoodRepository(
    initial: List<Mood>,
    private val saveError: Throwable? = null,
  ) : MoodRepository {
    private val state = MutableStateFlow(initial)
    override val moods: Flow<List<Mood>> = state
    var saved: List<Mood> = emptyList()

    override suspend fun save(moods: List<Mood>) {
      saveError?.let { throw it }
      saved = moods
      state.value = moods
    }
  }
}
