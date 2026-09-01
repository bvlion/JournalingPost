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
  private val fingerprint = "fp-1"

  private fun createStore() = DataStoreHostedIdempotencyKeyStore(
    PreferenceDataStoreFactory.create(produceFile = { File(tempFolder.root, "hosted_idempotency.preferences_pb") }),
    now = { now },
  )

  @Test
  fun `同じ期間_同じfingerprintの未確定retryは同じkeyを返す`() = runTest {
    val store = createStore()

    val first = store.currentKey(periodA, fingerprint)
    now = now.plusSeconds(60)
    val second = store.currentKey(periodA, fingerprint)

    assertEquals(first, second)
  }

  @Test
  fun `期間が違えば別のkeyになる`() = runTest {
    val store = createStore()

    assertNotEquals(store.currentKey(periodA, fingerprint), store.currentKey(periodB, fingerprint))
  }

  @Test
  fun `fingerprintが変わると新しいkeyになる`() = runTest {
    val store = createStore()

    val first = store.currentKey(periodA, fingerprint)
    now = now.plusSeconds(60)
    val second = store.currentKey(periodA, "fp-2")

    assertNotEquals(first, second)
  }

  @Test
  fun `clear すると次は新しいkeyになる`() = runTest {
    val store = createStore()

    val first = store.currentKey(periodA, fingerprint)
    store.clear(periodA)
    val second = store.currentKey(periodA, fingerprint)

    assertNotEquals(first, second)
  }

  @Test
  fun `保持期間を過ぎたkeyは作り直す`() = runTest {
    val store = createStore()

    val first = store.currentKey(periodA, fingerprint)
    now = now.plusSeconds(31 * 60)
    val second = store.currentKey(periodA, fingerprint)

    assertNotEquals(first, second)
  }
}
