package info.bvlion.journalingpost.widget

import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.glance.appwidget.GlanceAppWidgetManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MoodWidgetPreviewTest {
  @Test
  fun `Mood保存後のpreview再登録は30分後に再試行する`() = runTest {
    val preferences = FakeSharedPreferences()
    var registrationCount = 0
    val registerPreviews: suspend () -> Int = {
      registrationCount++
      GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS
    }

    registerMoodWidgetPreviewOnce(
      context = ContextWrapper(null),
      preferences = preferences,
      currentTimeMillis = 0L,
      platformVersion = 35,
      registerPreviews = registerPreviews,
    )
    registerMoodWidgetPreviewOnce(
      context = ContextWrapper(null),
      shouldRefresh = true,
      preferences = preferences,
      currentTimeMillis = 1L,
      platformVersion = 35,
      registerPreviews = registerPreviews,
    )
    registerMoodWidgetPreviewOnce(
      context = ContextWrapper(null),
      preferences = preferences,
      currentTimeMillis = 30 * 60 * 1_000L,
      platformVersion = 35,
      registerPreviews = registerPreviews,
    )

    assertEquals(2, registrationCount)
  }

  private class FakeSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any>()

    override fun contains(key: String) = key in values

    override fun edit(): SharedPreferences.Editor = Editor(values)

    override fun getAll(): Map<String, *> = values

    override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue

    override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue

    override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue

    override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue

    override fun getStringSet(key: String, defValues: Set<String>?) =
      (values[key] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: defValues

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
  }

  private class Editor(private val values: MutableMap<String, Any>) : SharedPreferences.Editor {
    private val updated = mutableMapOf<String, Any>()
    private val removed = mutableSetOf<String>()
    private var shouldClear = false

    override fun apply() {
      commit()
    }

    override fun clear(): SharedPreferences.Editor = apply { shouldClear = true }

    override fun commit(): Boolean {
      if (shouldClear) values.clear()
      removed.forEach(values::remove)
      values.putAll(updated)
      return true
    }

    override fun putBoolean(key: String, value: Boolean) = apply { updated[key] = value }

    override fun putFloat(key: String, value: Float) = apply { updated[key] = value }

    override fun putInt(key: String, value: Int) = apply { updated[key] = value }

    override fun putLong(key: String, value: Long) = apply { updated[key] = value }

    override fun putString(key: String, value: String?) = applyValue(key, value)

    override fun putStringSet(key: String, values: Set<String>?) = applyValue(key, values)

    override fun remove(key: String) = apply { removed += key }

    private fun applyValue(key: String, value: Any?): SharedPreferences.Editor = apply {
      if (value == null) removed += key else updated[key] = value
    }
  }
}
