package info.bvlion.journalingpost.journal

/** 履歴からの個別削除はこのinterfaceのみへ依存する。 */
fun interface JournalEntryDeleter {
  /** 削除に失敗した場合は例外を投げる(CancellationExceptionはそのまま再送出する)。 */
  suspend fun delete(id: Long)
}
