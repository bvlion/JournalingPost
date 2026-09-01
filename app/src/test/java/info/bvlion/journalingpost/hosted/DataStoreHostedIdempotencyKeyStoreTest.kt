package info.bvlion.journalingpost.hosted

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreHostedIdempotencyKeyStoreTest {
  @get:Rule
  val tempFolder = TemporaryFolder()

  private var now = Instant.parse("2026-08-30T00:00:00Z")

  private val periodA = HostedAnalysisPeriod(
    Instant.parse("2026-08-29T00:00:00Z"),
    Instant.parse("2026-08-30T00:00:00Z"),
  )
  private val periodB = HostedAnalysisPeriod(
    Instant.parse("2026-08-30T00:00:00Z"),
    Instant.parse("2026-08-31T00:00:00Z"),
  )

  private fun createStore() = DataStoreHostedIdempotencyKeyStore(
    PreferenceDataStoreFactory.create(produceFile = { File(tempFolder.root, "hosted_idempotency.preferences_pb") }),
    now = { now },
  )

  @Test
  fun `同じ期間の未確定retryは同じkeyを返す`() = runTest {
    val store = createStore()

    val first = store.currentKey(periodA)
    now = now.plusSeconds(60)
    val second = store.currentKey(periodA)

    assertEquals(first, second)
  }

  @Test
  fun `期間が違えば別のkeyになる`() = runTest {
    val store = createStore()

    assertNotEquals(store.currentKey(periodA), store.currentKey(periodB))
  }

  @Test
  fun `clear すると次は新しいkeyになる`() = runTest {
    val store = createStore()

    val first = store.currentKey(periodA)
    store.clear(periodA)
    val second = store.currentKey(periodA)

    assertNotEquals(first, second)
  }

  @Test
  fun `保持期間を過ぎたkeyは作り直す`() = runTest {
    val store = createStore()

    val first = store.currentKey(periodA)
    now = now.plusSeconds(31 * 60)
    val second = store.currentKey(periodA)

    assertNotEquals(first, second)
  }
}
