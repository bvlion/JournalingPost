package info.bvlion.journalingpost.security

/**
 * 端末内の秘密値(Webhook設定・Hosted API key)をローカル保存する前に暗号化するための共通の口。
 * 実体は[AndroidKeystoreCipher]で、テストではFakeへ差し替える。
 */
interface KeystoreCipher {
  fun encrypt(plaintext: ByteArray): EncryptedPayload
  fun decrypt(encrypted: EncryptedPayload): ByteArray
}

/** ByteArrayを保持するため、内容比較のためにequals/hashCodeを明示的にoverrideしている(参照比較にしない)。 */
data class EncryptedPayload(
  val ciphertext: ByteArray,
  val iv: ByteArray,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is EncryptedPayload) return false
    return ciphertext.contentEquals(other.ciphertext) && iv.contentEquals(other.iv)
  }

  override fun hashCode(): Int = 31 * ciphertext.contentHashCode() + iv.contentHashCode()
}
