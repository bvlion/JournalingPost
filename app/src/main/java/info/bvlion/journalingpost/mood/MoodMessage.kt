package info.bvlion.journalingpost.mood

/** 既存Webhook payloadのtext形式に合わせて、気分と任意メモをメッセージへ整形する。 */
fun formatMoodMessage(mood: Mood, note: String): String {
  val trimmedNote = note.trim()
  return if (trimmedNote.isEmpty()) {
    "気分は${mood.emoji}とのこと"
  } else {
    "気分は${mood.emoji}とのこと。$trimmedNote"
  }
}
