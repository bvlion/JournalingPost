package info.bvlion.journalingpost.journal.db

import androidx.room.TypeConverter
import java.time.Instant

internal class Converters {
  @TypeConverter
  fun toInstant(epochMilli: Long?): Instant? = epochMilli?.let(Instant::ofEpochMilli)

  @TypeConverter
  fun fromInstant(instant: Instant?): Long? = instant?.toEpochMilli()
}
