package info.bvlion.journalingpost.journal

import info.bvlion.journalingpost.mood.MoodSnapshot

/**
 * 記録操作の境界。ローカル保存とWebhook配送を1回の記録としてまとめる。ViewModelはこのinterfaceのみへ依存する。
 *
 * 「記録」の成否はJournalEntryのローカル保存が成功したかどうかで決まる。Webhook配送の成否は
 * JournalEntry.deliveryStatusとしてのみ表現し、この関数の成功/失敗には影響しない。ローカル保存
 * 自体に失敗した場合のみ例外を投げる(CancellationExceptionはそのまま再送出する)。
 */
fun interface JournalRecorder {
  suspend fun record(note: String, mood: MoodSnapshot?, source: JournalSource)
}
