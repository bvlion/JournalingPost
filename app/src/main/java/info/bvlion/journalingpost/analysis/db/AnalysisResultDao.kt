package info.bvlion.journalingpost.analysis.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import info.bvlion.journalingpost.analysis.AnalysisResult
import kotlinx.coroutines.flow.Flow

@Dao
internal interface AnalysisResultDao {
  @Insert
  suspend fun insert(result: AnalysisResult): Long

  /** 解析日時の新しい順。analyzedAtが同一の結果もあり得るため、idを二次キーにして順序を安定させる。 */
  @Query("SELECT * FROM analysis_results ORDER BY analyzedAt DESC, id DESC")
  fun observeAll(): Flow<List<AnalysisResult>>
}
