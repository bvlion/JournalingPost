package info.bvlion.journalingpost.hosted

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import info.bvlion.journalingpost.security.EncryptedPayload
import info.bvlion.journalingpost.security.KeystoreCipher
import java.io.File
import java.security.GeneralSecurityException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreHostedCredentialsRepositoryTest {
  @get:Rule
  val tempFolder = TemporaryFolder()

  private fun createRepository(cipher: KeystoreCipher = FakeKeystoreCipher()) =
    DataStoreHostedCredentialsRepository(
      PreferenceDataStoreFactory.create(produceFile = { File(tempFolder.root, "hosted_credentials.preferences_pb") }),
      cipher,
    )

  @Test
  fun `未登録なら null を返す`() = runTest {
    assertNull(createRepository().apiKey())
  }

  @Test
  fun `保存した API key を再取得できる`() = runTest {
    val repository = createRepository()

    repository.store("jpk_example_key")

    assertEquals("jpk_example_key", repository.apiKey())
  }

  @Test
  fun `保存内容は cipher で暗号化される`() = runTest {
    val cipher = FakeKeystoreCipher()
    val repository = createRepository(cipher)

    repository.store("jpk_example_key")

    assertTrue(requireNotNull(cipher.lastEncryptedPlaintext).decodeToString().contains("jpk_example_key"))
  }

  @Test
  fun `復号できない保存値は未登録として扱う`() = runTest {
    val cipher = FakeKeystoreCipher()
    val repository = createRepository(cipher)
    repository.store("jpk_example_key")
    cipher.failDecrypt = true

    assertNull(repository.apiKey())
  }

  @Test
  fun `clear すると未登録に戻る`() = runTest {
    val repository = createRepository()
    repository.store("jpk_example_key")

    repository.clear()

    assertNull(repository.apiKey())
  }

  private class FakeKeystoreCipher : KeystoreCipher {
    var failDecrypt = false
    var lastEncryptedPlaintext: ByteArray? = null

    override fun encrypt(plaintext: ByteArray): EncryptedPayload {
      lastEncryptedPlaintext = plaintext
      return EncryptedPayload(plaintext, byteArrayOf(1, 2, 3))
    }

    override fun decrypt(encrypted: EncryptedPayload): ByteArray {
      if (failDecrypt) throw GeneralSecurityException("decrypt failed")
      return encrypted.ciphertext
    }
  }
}
