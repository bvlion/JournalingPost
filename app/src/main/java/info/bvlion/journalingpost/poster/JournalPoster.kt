package info.bvlion.journalingpost.poster

/** 日記メッセージを投稿する境界。ViewModelはこのinterfaceのみへ依存する。 */
fun interface JournalPoster {
  suspend fun post(message: String): Boolean
}
