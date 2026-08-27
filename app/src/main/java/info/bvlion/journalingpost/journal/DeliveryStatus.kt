package info.bvlion.journalingpost.journal

/** JournalEntryのWebhook配送状態。自動再送は行わず、失敗状態の保持までを扱う。 */
enum class DeliveryStatus {
  NOT_REQUIRED,
  PENDING,
  SENT,
  FAILED,
}
