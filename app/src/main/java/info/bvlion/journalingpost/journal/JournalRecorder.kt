package info.bvlion.journalingpost.journal

import info.bvlion.journalingpost.mood.MoodSnapshot

/** 記録操作の境界。ローカル保存とWebhook配送を1回の記録としてまとめる。ViewModelはこのinterfaceのみへ依存する。 */
fun interface JournalRecorder {
  /** @return Webhook配送が成功したか。ローカル保存はこの結果によらず確定済み。 */
  suspend fun record(note: String, mood: MoodSnapshot?, source: JournalSource): Boolean
}
