package info.bvlion.journalingpost.time

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CalendarDate(val today: LocalDate, val zoneId: ZoneId)

class CurrentDateProvider(
  private val now: () -> Instant = Instant::now,
  private val zoneId: () -> ZoneId = ZoneId::systemDefault,
) {
  private val _state = MutableStateFlow(readDate())
  val state: StateFlow<CalendarDate> = _state.asStateFlow()

  fun refresh(): CalendarDate = readDate().also { _state.value = it }

  // Activityが前景にある期間だけ登録する。背面で受け取れなかった変更は登録時に補う。
  fun observeChanges(context: Context): AutoCloseable {
    val applicationContext = context.applicationContext
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        refresh()
      }
    }
    ContextCompat.registerReceiver(
      applicationContext,
      receiver,
      IntentFilter().apply {
        addAction(Intent.ACTION_DATE_CHANGED)
        addAction(Intent.ACTION_TIME_CHANGED)
        addAction(Intent.ACTION_TIMEZONE_CHANGED)
      },
      ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    refresh()
    return AutoCloseable { applicationContext.unregisterReceiver(receiver) }
  }

  private fun readDate(): CalendarDate {
    val currentZoneId = zoneId()
    return CalendarDate(now().atZone(currentZoneId).toLocalDate(), currentZoneId)
  }
}
