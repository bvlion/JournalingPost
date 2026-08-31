package info.bvlion.journalingpost.mood

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreMoodRepositoryTest {
  @get:Rule
  val tempFolder = TemporaryFolder()

  private val initialMoods = listOf(
    Mood(id = "initial-1", emoji = "🤩", label = "ワクワク"),
    Mood(id = "initial-2", emoji = "😄", label = "嬉しい"),
  )

  private fun createRepository(): DataStoreMoodRepository {
    val dataStore = PreferenceDataStoreFactory.create(
      produceFile = { File(tempFolder.root, "mood_settings.preferences_pb") },
    )
    return DataStoreMoodRepository(dataStore, initialMoods)
  }

  @Test
  fun `初回利用時は初期Moodセットを順序どおり返す`() = runTest {
    assertEquals(initialMoods, createRepository().moods.first())
  }

  @Test
  fun `編集した表示内容と順序と件数を保存する`() = runTest {
    val repository = createRepository()
    val customized = listOf(
      initialMoods[1].copy(emoji = "", label = "名称のみ"),
      Mood(id = "new-id", emoji = "🥳", label = ""),
    )

    repository.save(customized)

    assertEquals(customized, repository.moods.first())
  }

  @Test
  fun `不正な件数や表示内容は保存しない`() = runTest {
    val repository = createRepository()

    var emptyListFailed = false
    try {
      repository.save(emptyList())
    } catch (e: IllegalArgumentException) {
      emptyListFailed = true
    }
    var emptyContentFailed = false
    try {
      repository.save(listOf(Mood(id = "1", emoji = "", label = "")))
    } catch (e: IllegalArgumentException) {
      emptyContentFailed = true
    }

    assertTrue(emptyListFailed)
    assertTrue(emptyContentFailed)
    assertEquals(initialMoods, repository.moods.first())
  }

  @Test
  fun `保存値が壊れている場合は初期Moodセットを返す`() = runTest {
    val repository = DataStoreMoodRepository(
      StaticDataStore(preferencesOf(stringPreferencesKey("moods") to "not-json")),
      initialMoods,
    )

    assertEquals(initialMoods, repository.moods.first())
  }

  @Test
  fun `保存値が仕様外の場合は初期Moodセットを返す`() = runTest {
    val invalid = Json.encodeToString(listOf(Mood(id = "1", emoji = "", label = "")))
    val repository = DataStoreMoodRepository(
      StaticDataStore(preferencesOf(stringPreferencesKey("moods") to invalid)),
      initialMoods,
    )

    assertEquals(initialMoods, repository.moods.first())
  }

  @Test
  fun `DataStore読み込みがIOExceptionでも初期値へ置換せず復旧後の保存値を返す`() = runTest {
    val customized = listOf(Mood(id = "custom", emoji = "🥳", label = "楽しい"))
    val recovered = preferencesOf(stringPreferencesKey("moods") to Json.encodeToString(customized))
    val repository = DataStoreMoodRepository(RecoveringDataStore(recovered), initialMoods)

    assertEquals(customized, repository.moods.first())
  }

  private class StaticDataStore(initial: Preferences) : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { emit(initial) }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
      error("not used in this test")
  }

  private class RecoveringDataStore(private val recovered: Preferences) : DataStore<Preferences> {
    private var attempt = 0

    override val data: Flow<Preferences> = flow {
      attempt++
      if (attempt == 1) throw IOException("disk error")
      emitAll(flowOf(recovered))
    }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
      error("not used in this test")
  }
}
