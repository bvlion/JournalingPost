package info.bvlion.journalingpost.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.glance.appwidget.updateAll
import info.bvlion.journalingpost.analysis.AnalysisResultReader
import info.bvlion.journalingpost.analysis.AnalysisResultWriter
import info.bvlion.journalingpost.analysis.PeriodAnalyzer
import info.bvlion.journalingpost.analysis.WebhookPeriodAnalyzer
import info.bvlion.journalingpost.analysis.db.RoomAnalysisResultRepository
import info.bvlion.journalingpost.journal.JournalEntryDeleter
import info.bvlion.journalingpost.journal.JournalEntryReader
import info.bvlion.journalingpost.journal.JournalRecorder
import info.bvlion.journalingpost.journal.LocalOnlyJournalRecorder
import info.bvlion.journalingpost.journal.PeriodJournalEntryReader
import info.bvlion.journalingpost.journal.db.JournalDatabase
import info.bvlion.journalingpost.journal.db.RoomJournalEntryRepository
import info.bvlion.journalingpost.mood.DataStoreMoodRepository
import info.bvlion.journalingpost.mood.MoodRepository
import info.bvlion.journalingpost.mood.createInitialMoodCatalog
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepository
import info.bvlion.journalingpost.settings.DataStoreAnalysisIntegrationRepository
import info.bvlion.journalingpost.settings.DataStoreNoteOnlyEntryRepository
import info.bvlion.journalingpost.settings.NoteOnlyEntryRepository
import info.bvlion.journalingpost.settings.WebhookAwareAnalysisIntegrationRepository
import info.bvlion.journalingpost.webhook.AndroidKeystoreWebhookSettingsCipher
import info.bvlion.journalingpost.webhook.DataStoreWebhookSettingsRepository
import info.bvlion.journalingpost.webhook.WebhookSettingsRepository
import info.bvlion.journalingpost.widget.MoodWidget
import info.bvlion.journalingpost.widget.registerMoodWidgetPreviewOnce
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

/**
 * process内で共有する依存関係の生成場所。JournalingPostApplicationが1つだけ保持する。
 *
 * DataStoreは同一ファイルに対して複数instanceを生成すると例外になり、Repositoryは書き込み完了前の
 * 値を即時反映するためのin-memory状態を持つ。どちらもprocess内で1つに保つ必要があるため、
 * 個別のsingleton holderを置かずここへ集約する。
 *
 * 生成はいずれも遅延させる。特にAndroid Keystoreやdatabase fileの初期化を、実際に使う画面より前へ
 * 前倒ししないため。
 */
internal class AppContainer(context: Context) {
  private val context = context.applicationContext

  private val database: JournalDatabase by lazy { JournalDatabase.create(this.context) }

  private val journalEntryRepository by lazy { RoomJournalEntryRepository(database.journalEntryDao()) }

  private val analysisResultRepository by lazy { RoomAnalysisResultRepository(database.analysisResultDao()) }

  private val httpClient by lazy {
    HttpClient(CIO) {
      install(ContentNegotiation) {
        json()
      }
    }
  }

  val journalRecorder: JournalRecorder by lazy { LocalOnlyJournalRecorder(journalEntryRepository) }

  val moodRepository: MoodRepository by lazy {
    DataStoreMoodRepository(
      dataStore = createPreferenceDataStore(MOOD_SETTINGS_FILE_NAME),
      initialMoods = createInitialMoodCatalog(context),
    )
  }

  val noteOnlyEntryRepository: NoteOnlyEntryRepository by lazy {
    DataStoreNoteOnlyEntryRepository(createPreferenceDataStore(NOTE_ONLY_ENTRY_FILE_NAME))
  }

  val journalEntryReader: JournalEntryReader get() = journalEntryRepository

  val journalEntryDeleter: JournalEntryDeleter get() = journalEntryRepository

  val periodJournalEntryReader: PeriodJournalEntryReader get() = journalEntryRepository

  val analysisResultReader: AnalysisResultReader get() = analysisResultRepository

  val analysisResultWriter: AnalysisResultWriter get() = analysisResultRepository

  val webhookSettingsRepository: WebhookSettingsRepository by lazy {
    DataStoreWebhookSettingsRepository(
      dataStore = createPreferenceDataStore(WEBHOOK_SETTINGS_FILE_NAME),
      cipher = AndroidKeystoreWebhookSettingsCipher(),
    )
  }

  /**
   * 「CUSTOM_WEBHOOKが有効ならWebhook設定が存在する」契約はここでのみ組み立てる。記録側・設定画面側の
   * どちらかが素のDataStore実装を直接使うと、その契約が片側だけ崩れるため。
   */
  val analysisIntegrationRepository: AnalysisIntegrationRepository by lazy {
    WebhookAwareAnalysisIntegrationRepository(
      delegate = DataStoreAnalysisIntegrationRepository(
        createPreferenceDataStore(ANALYSIS_INTEGRATION_FILE_NAME),
      ),
      webhookSettingsRepository = webhookSettingsRepository,
    )
  }

  val periodAnalyzer: PeriodAnalyzer by lazy {
    WebhookPeriodAnalyzer(
      httpClient = httpClient,
      analysisIntegrationRepository = analysisIntegrationRepository,
      webhookSettingsRepository = webhookSettingsRepository,
    )
  }

  suspend fun refreshMoodWidgets() {
    try {
      MoodWidget().updateAll(context)
    } finally {
      registerMoodWidgetPreviewOnce(context, shouldRefresh = true)
    }
  }

  private fun createPreferenceDataStore(fileName: String): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile(fileName) })

  private companion object {
    /**
     * AnalysisIntegration設定とはファイルを分離する(責務が異なるうえ、Webhook設定ファイルは
     * backupから除外する必要があり、他設定と混在させられない)。変更する場合はAndroidManifestの
     * dataExtractionRules側のファイルパスも合わせて更新すること。
     */
    const val WEBHOOK_SETTINGS_FILE_NAME = "webhook_settings"

    const val ANALYSIS_INTEGRATION_FILE_NAME = "analysis_integration_settings"

    const val MOOD_SETTINGS_FILE_NAME = "mood_settings"

    const val NOTE_ONLY_ENTRY_FILE_NAME = "note_only_entry_settings"
  }
}
