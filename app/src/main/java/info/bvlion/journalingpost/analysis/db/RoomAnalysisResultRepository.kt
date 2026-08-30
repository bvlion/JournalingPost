package info.bvlion.journalingpost.analysis.db

import info.bvlion.journalingpost.analysis.AnalysisResult
import info.bvlion.journalingpost.analysis.AnalysisResultReader
import kotlinx.coroutines.flow.Flow

internal class RoomAnalysisResultRepository(
  private val dao: AnalysisResultDao,
) : AnalysisResultReader {
  override fun observeAll(): Flow<List<AnalysisResult>> = dao.observeAll()
}
