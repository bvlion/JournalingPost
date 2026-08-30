package info.bvlion.journalingpost.analysis.db

import info.bvlion.journalingpost.analysis.AnalysisResult
import info.bvlion.journalingpost.analysis.AnalysisResultReader
import info.bvlion.journalingpost.analysis.AnalysisResultWriter
import kotlinx.coroutines.flow.Flow

internal class RoomAnalysisResultRepository(
  private val dao: AnalysisResultDao,
) : AnalysisResultReader, AnalysisResultWriter {
  override fun observeAll(): Flow<List<AnalysisResult>> = dao.observeAll()

  override suspend fun save(result: AnalysisResult): Long = dao.insert(result)
}
