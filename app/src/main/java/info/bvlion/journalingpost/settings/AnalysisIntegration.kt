package info.bvlion.journalingpost.settings

/**
 * JournalEntryの解析・連携方法。JournalEntryはどの値でも必ず端末へローカル保存するため、
 * これは「保存方法」ではなく「保存した記録を外部でどう扱うか」の選択を表す。
 * 将来はJournalingPost Hosted等が同じ選択軸へ加わる。
 */
enum class AnalysisIntegration {
  /** 端末内にのみ記録し、外部へは送信しない。 */
  NONE,

  /** 記録時に、ユーザーが設定したCustom Webhookへ送信する。 */
  CUSTOM_WEBHOOK,
}
