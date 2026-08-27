package info.bvlion.journalingpost.poster

/** ViewModelはこのinterfaceのみへ依存する。 */
fun interface JournalPoster {
  suspend fun post(message: String): Boolean
}
