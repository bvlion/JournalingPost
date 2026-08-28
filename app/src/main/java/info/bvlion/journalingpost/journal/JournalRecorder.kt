package info.bvlion.journalingpost.journal

import info.bvlion.journalingpost.mood.MoodSnapshot

/**
 * ViewModelはこのinterfaceのみへ依存する。
 *
 * 「記録」の成否はJournalEntryのローカル保存が成功したかどうかで決まる。戻り値の
 * DeliveryStatusはWebhook等の外部配送結果のみを表し、この関数自体の成功/失敗には
 * 影響しない。ローカル保存自体に失敗した場合のみ例外を投げる(CancellationExceptionは
 * そのまま再送出する)。
 */
fun interface JournalRecorder {
  suspend fun record(note: String, mood: MoodSnapshot?, source: JournalSource): DeliveryStatus
}
