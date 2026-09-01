package info.bvlion.journalingpost.webhook

import info.bvlion.journalingpost.security.AndroidKeystoreCipher
import info.bvlion.journalingpost.security.EncryptedPayload
import info.bvlion.journalingpost.security.KeystoreCipher

/**
 * Webhook設定の暗号化は共通の[KeystoreCipher](既定は[AndroidKeystoreCipher])へ委譲する。
 * Webhook専用の鍵aliasを固定し、[WebhookSettingsCipher]の型はそのまま維持する。
 */
class AndroidKeystoreWebhookSettingsCipher(
  private val delegate: KeystoreCipher = AndroidKeystoreCipher(WEBHOOK_KEY_ALIAS),
) : WebhookSettingsCipher {
  override fun encrypt(plaintext: ByteArray): EncryptedWebhookSettings =
    delegate.encrypt(plaintext).let { EncryptedWebhookSettings(it.ciphertext, it.iv) }

  override fun decrypt(encrypted: EncryptedWebhookSettings): ByteArray =
    delegate.decrypt(EncryptedPayload(encrypted.ciphertext, encrypted.iv))

  private companion object {
    // 変更すると既存端末の保存済みWebhook設定を復号できなくなるため固定する。
    const val WEBHOOK_KEY_ALIAS = "journaling_post_webhook_settings_key"
  }
}
