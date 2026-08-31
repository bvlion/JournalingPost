package info.bvlion.journalingpost.mood

object MoodValidator {
  const val MIN_MOOD_COUNT = 1
  const val MAX_MOOD_COUNT = 10

  fun isValid(moods: List<Mood>): Boolean =
    moods.size in MIN_MOOD_COUNT..MAX_MOOD_COUNT &&
      moods.map { it.id }.distinct().size == moods.size &&
      moods.all { mood ->
        mood.id.isNotBlank() &&
          (!mood.emoji.isBlank() || !mood.label.isBlank()) &&
          (mood.emoji.isBlank() || isSingleEmoji(mood.emoji.trim()))
      }

  /**
   * emoji base 1つと、そのvariation selector・skin tone・ZWJ結合等だけで構成された
   * 1つのemoji sequenceを許可する。通常文字や複数emojiの連結は許可しない。
   */
  fun isSingleEmoji(value: String): Boolean {
    if (value.isBlank()) return false
    val codePoints = value.codePoints().toArray()

    if (codePoints.size == 2 && codePoints.all { it in REGIONAL_INDICATOR_RANGE }) return true
    if (codePoints.last() == COMBINING_KEYCAP) {
      val base = codePoints.firstOrNull() ?: return false
      val middleIsValid = codePoints.size == 2 ||
        (codePoints.size == 3 && codePoints[1] == VARIATION_SELECTOR_EMOJI)
      return base in KEYCAP_BASES && middleIsValid
    }
    if (codePoints.first() == BLACK_FLAG && codePoints.last() == CANCEL_TAG) {
      var tagStart = 1
      if (codePoints.getOrNull(tagStart) == VARIATION_SELECTOR_EMOJI) tagStart++
      return tagStart < codePoints.lastIndex &&
        codePoints.sliceArray(tagStart until codePoints.lastIndex).all { it in TAG_CHARACTER_RANGE }
    }

    var index = 0
    var componentCount = 0
    while (index < codePoints.size) {
      if (!isEmojiBase(codePoints[index])) return false
      componentCount++
      index++
      if (index < codePoints.size && codePoints[index] == VARIATION_SELECTOR_EMOJI) index++
      if (index < codePoints.size && codePoints[index] in SKIN_TONE_RANGE) index++

      if (index == codePoints.size) return componentCount >= 1
      if (codePoints[index] != ZERO_WIDTH_JOINER) return false
      index++
      if (index == codePoints.size) return false
    }
    return false
  }

  private fun isEmojiBase(codePoint: Int): Boolean =
    codePoint == 0x1F004 ||
      codePoint == 0x1F0CF ||
      codePoint in 0x1F170..0x1F171 ||
      codePoint in 0x1F17E..0x1F17F ||
      codePoint == 0x1F18E ||
      codePoint in 0x1F191..0x1F19A ||
      codePoint in 0x1F201..0x1F202 ||
      codePoint == 0x1F21A ||
      codePoint == 0x1F22F ||
      codePoint in 0x1F232..0x1F23A ||
      codePoint in 0x1F250..0x1F251 ||
      codePoint in 0x1F300..0x1F321 ||
      codePoint in 0x1F324..0x1F393 ||
      codePoint in 0x1F396..0x1F397 ||
      codePoint in 0x1F399..0x1F39B ||
      codePoint in 0x1F39E..0x1F3F0 ||
      codePoint in 0x1F3F3..0x1F3F5 ||
      codePoint in 0x1F3F7..0x1F4FD ||
      codePoint in 0x1F4FF..0x1F53D ||
      codePoint in 0x1F549..0x1F54E ||
      codePoint in 0x1F550..0x1F567 ||
      codePoint in 0x1F56F..0x1F570 ||
      codePoint in 0x1F573..0x1F57A ||
      codePoint == 0x1F587 ||
      codePoint in 0x1F58A..0x1F58D ||
      codePoint == 0x1F590 ||
      codePoint in 0x1F595..0x1F596 ||
      codePoint in 0x1F5A4..0x1F5A5 ||
      codePoint == 0x1F5A8 ||
      codePoint in 0x1F5B1..0x1F5B2 ||
      codePoint == 0x1F5BC ||
      codePoint in 0x1F5C2..0x1F5C4 ||
      codePoint in 0x1F5D1..0x1F5D3 ||
      codePoint in 0x1F5DC..0x1F5DE ||
      codePoint == 0x1F5E1 ||
      codePoint == 0x1F5E3 ||
      codePoint == 0x1F5E8 ||
      codePoint == 0x1F5EF ||
      codePoint == 0x1F5F3 ||
      codePoint in 0x1F5FA..0x1F64F ||
      codePoint in 0x1F680..0x1F6C5 ||
      codePoint in 0x1F6CB..0x1F6D2 ||
      codePoint in 0x1F6D5..0x1F6D7 ||
      codePoint in 0x1F6DC..0x1F6E5 ||
      codePoint == 0x1F6E9 ||
      codePoint in 0x1F6EB..0x1F6EC ||
      codePoint == 0x1F6F0 ||
      codePoint in 0x1F6F3..0x1F6FC ||
      codePoint in 0x1F7E0..0x1F7EB ||
      codePoint == 0x1F7F0 ||
      codePoint in 0x1F90C..0x1F93A ||
      codePoint in 0x1F93C..0x1F945 ||
      codePoint in 0x1F947..0x1F9FF ||
      codePoint in 0x1FA70..0x1FA7C ||
      codePoint in 0x1FA80..0x1FA89 ||
      codePoint in 0x1FA8F..0x1FAC6 ||
      codePoint in 0x1FACE..0x1FADC ||
      codePoint in 0x1FADF..0x1FAE9 ||
      codePoint in 0x1FAF0..0x1FAF8 ||
      codePoint in 0x2194..0x2199 ||
      codePoint in 0x21A9..0x21AA ||
      codePoint in 0x231A..0x231B ||
      codePoint == 0x2328 ||
      codePoint == 0x23CF ||
      codePoint in 0x23E9..0x23F3 ||
      codePoint in 0x23F8..0x23FA ||
      codePoint == 0x24C2 ||
      codePoint in 0x25AA..0x25AB ||
      codePoint == 0x25B6 ||
      codePoint == 0x25C0 ||
      codePoint in 0x25FB..0x25FE ||
      codePoint in 0x2600..0x2604 ||
      codePoint == 0x260E ||
      codePoint == 0x2611 ||
      codePoint in 0x2614..0x2615 ||
      codePoint == 0x2618 ||
      codePoint == 0x261D ||
      codePoint == 0x2620 ||
      codePoint in 0x2622..0x2623 ||
      codePoint == 0x2626 ||
      codePoint == 0x262A ||
      codePoint in 0x262E..0x262F ||
      codePoint in 0x2638..0x263A ||
      codePoint == 0x2640 ||
      codePoint == 0x2642 ||
      codePoint in 0x2648..0x2653 ||
      codePoint in 0x265F..0x2660 ||
      codePoint == 0x2663 ||
      codePoint in 0x2665..0x2666 ||
      codePoint == 0x2668 ||
      codePoint == 0x267B ||
      codePoint in 0x267E..0x267F ||
      codePoint in 0x2692..0x2697 ||
      codePoint == 0x2699 ||
      codePoint in 0x269B..0x269C ||
      codePoint in 0x26A0..0x26A1 ||
      codePoint == 0x26A7 ||
      codePoint in 0x26AA..0x26AB ||
      codePoint in 0x26B0..0x26B1 ||
      codePoint in 0x26BD..0x26BE ||
      codePoint in 0x26C4..0x26C5 ||
      codePoint == 0x26C8 ||
      codePoint in 0x26CE..0x26CF ||
      codePoint == 0x26D1 ||
      codePoint in 0x26D3..0x26D4 ||
      codePoint in 0x26E9..0x26EA ||
      codePoint in 0x26F0..0x26F5 ||
      codePoint in 0x26F7..0x26FA ||
      codePoint == 0x26FD ||
      codePoint == 0x2702 ||
      codePoint == 0x2705 ||
      codePoint in 0x2708..0x270D ||
      codePoint == 0x270F ||
      codePoint == 0x2712 ||
      codePoint == 0x2714 ||
      codePoint == 0x2716 ||
      codePoint == 0x271D ||
      codePoint == 0x2721 ||
      codePoint == 0x2728 ||
      codePoint in 0x2733..0x2734 ||
      codePoint == 0x2744 ||
      codePoint == 0x2747 ||
      codePoint == 0x274C ||
      codePoint == 0x274E ||
      codePoint in 0x2753..0x2755 ||
      codePoint == 0x2757 ||
      codePoint in 0x2763..0x2764 ||
      codePoint in 0x2795..0x2797 ||
      codePoint == 0x27A1 ||
      codePoint == 0x27B0 ||
      codePoint == 0x27BF ||
      codePoint in 0x2934..0x2935 ||
      codePoint in 0x2B05..0x2B07 ||
      codePoint in 0x2B1B..0x2B1C ||
      codePoint == 0x2B50 ||
      codePoint == 0x2B55 ||
      codePoint == 0x3030 ||
      codePoint == 0x303D ||
      codePoint == 0x3297 ||
      codePoint == 0x3299 ||
      codePoint == 0x00A9 ||
      codePoint == 0x00AE ||
      codePoint == 0x203C ||
      codePoint == 0x2049 ||
      codePoint == 0x2122 ||
      codePoint == 0x2139

  private val REGIONAL_INDICATOR_RANGE = 0x1F1E6..0x1F1FF
  private val SKIN_TONE_RANGE = 0x1F3FB..0x1F3FF
  private val TAG_CHARACTER_RANGE = 0xE0020..0xE007E
  private val KEYCAP_BASES = setOf(
    '#'.code,
    '*'.code,
    '0'.code,
    '1'.code,
    '2'.code,
    '3'.code,
    '4'.code,
    '5'.code,
    '6'.code,
    '7'.code,
    '8'.code,
    '9'.code,
  )
  private const val ZERO_WIDTH_JOINER = 0x200D
  private const val VARIATION_SELECTOR_EMOJI = 0xFE0F
  private const val COMBINING_KEYCAP = 0x20E3
  private const val BLACK_FLAG = 0x1F3F4
  private const val CANCEL_TAG = 0xE007F
}
