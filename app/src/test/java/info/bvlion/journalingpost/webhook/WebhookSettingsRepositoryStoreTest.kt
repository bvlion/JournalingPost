package info.bvlion.journalingpost.webhook

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebhookSettingsRepositoryStoreTest {
  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `getInstanceは同一DataStoreとcipherに対して常に同じrepositoryインスタンスを返す`() {
    val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
      produceFile = { File(tempFolder.root, "webhook_settings.preferences_pb") },
    )
    val cipher = object : WebhookSettingsCipher {
      override fun encrypt(plaintext: ByteArray) = EncryptedWebhookSettings(plaintext, byteArrayOf())
      override fun decrypt(encrypted: EncryptedWebhookSettings) = encrypted.ciphertext
    }

    val first = WebhookSettingsRepositoryStore.getInstance(dataStore, cipher)
    val second = WebhookSettingsRepositoryStore.getInstance(dataStore, cipher)

    assertSame(first, second)
  }
}
