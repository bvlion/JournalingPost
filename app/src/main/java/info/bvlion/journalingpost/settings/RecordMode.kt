package info.bvlion.journalingpost.settings

/**
 * 記録の保存・送信方法。将来Local + Service等の追加余地は残すが、
 * 現時点ではLOCAL_AND_WEBHOOKとLOCAL_ONLYのみを扱う。
 */
enum class RecordMode {
  LOCAL_AND_WEBHOOK,
  LOCAL_ONLY,
}
