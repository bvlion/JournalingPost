package info.bvlion.journalingpost.mood

import kotlinx.coroutines.flow.Flow

interface MoodRepository {
  val moods: Flow<List<Mood>>

  suspend fun save(moods: List<Mood>)
}
