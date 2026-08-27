package info.bvlion.journalingpost.mood

/** 既存Webhook payloadのtext形式に合わせて、気分の絵文字と任意メモをメッセージへ整形する。 */
fun formatMoodMessage(emoji: String, note: String): String {
  val trimmedNote = note.trim()
  return if (trimmedNote.isEmpty()) {
    "気分は${emoji}とのこと"
  } else {
    "気分は${emoji}とのこと。$trimmedNote"
  }
}
