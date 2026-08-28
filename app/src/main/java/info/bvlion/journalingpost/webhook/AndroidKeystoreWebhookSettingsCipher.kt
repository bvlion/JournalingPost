package info.bvlion.journalingpost.webhook

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystoreの鍵は端末単位でハードウェア/OSに紐づき、暗号化データを暗号化されたまま
 * 別端末へ移しても復号できない。鍵をKeystoreの外へ取り出すAPIを一切使わないことで、
 * 鍵素材自体をアプリファイルへ書き出さない制約を守る。Keystoreはlocal JVM unit testでは
 * 利用できないため、このクラス自体のテストは追加しない(instrumented testは今回の対象外)。
 */
class AndroidKeystoreWebhookSettingsCipher(
  private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : WebhookSettingsCipher {
  private val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

  override fun encrypt(plaintext: ByteArray): EncryptedWebhookSettings {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    // IVを明示指定しないことで、AndroidKeyStoreプロバイダが暗号化のたびに新しいIVを生成する。
    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
    val ciphertext = cipher.doFinal(plaintext)
    return EncryptedWebhookSettings(ciphertext, cipher.iv)
  }

  override fun decrypt(encrypted: EncryptedWebhookSettings): ByteArray {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, encrypted.iv))
    return cipher.doFinal(encrypted.ciphertext)
  }

  private fun getOrCreateKey(): SecretKey {
    (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
    val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
    keyGenerator.init(
      KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(KEY_SIZE_BITS)
        .build(),
    )
    return keyGenerator.generateKey()
  }

  private companion object {
    const val ANDROID_KEY_STORE = "AndroidKeyStore"
    const val DEFAULT_KEY_ALIAS = "journaling_post_webhook_settings_key"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val GCM_TAG_LENGTH_BITS = 128
    const val KEY_SIZE_BITS = 256
  }
}
