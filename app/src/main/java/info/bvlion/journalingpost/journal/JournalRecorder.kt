package info.bvlion.journalingpost.journal

import info.bvlion.journalingpost.mood.MoodSnapshot

/**
 * ViewModelはこのinterfaceのみへ依存する。
 *
 * 「記録」の成否はJournalEntryのローカル保存が成功したかどうかで決まる。ローカル保存自体に
 * 失敗した場合のみ例外を投げる(CancellationExceptionはそのまま再送出する)。外部への送信は
 * 記録時には行わず、期間解析(手動/自動)として別operationで実行する。
 */
fun interface JournalRecorder {
  suspend fun record(note: String, mood: MoodSnapshot?, source: JournalSource)
}
