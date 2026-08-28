package info.bvlion.journalingpost.webhook

interface WebhookSettingsCipher {
  fun encrypt(plaintext: ByteArray): EncryptedWebhookSettings
  fun decrypt(encrypted: EncryptedWebhookSettings): ByteArray
}

/** ByteArrayを保持するため、内容比較のためにequals/hashCodeを明示的にoverrideしている(参照比較にしない)。 */
data class EncryptedWebhookSettings(
  val ciphertext: ByteArray,
  val iv: ByteArray,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is EncryptedWebhookSettings) return false
    return ciphertext.contentEquals(other.ciphertext) && iv.contentEquals(other.iv)
  }

  override fun hashCode(): Int = 31 * ciphertext.contentHashCode() + iv.contentHashCode()
}
