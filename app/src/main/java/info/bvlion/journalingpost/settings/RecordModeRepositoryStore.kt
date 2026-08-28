package info.bvlion.journalingpost.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences

/**
 * RecordModeRepositoryをprocess内で1インスタンスだけ生成し、MainViewModelFactory/
 * SettingsViewModelFactoryの両方から再利用する(RecordModeSettingsStoreと同様のパターン)。
 *
 * DataStoreRecordModeRepository.pendingModeはインスタンスごとの状態のため、設定画面側と
 * 記録処理側が別々にDataStoreRecordModeRepositoryを生成すると、設定変更直後の同期反映が
 * 記録処理側へ伝わらない(旧モードのまま記録される)競合が起きる。この2箇所が必ず同じ
 * RecordModeRepositoryインスタンスを参照するようにするためのstore。
 */
internal object RecordModeRepositoryStore {
  @Volatile
  private var instance: RecordModeRepository? = null

  fun getInstance(context: Context): RecordModeRepository =
    getInstance(RecordModeSettingsStore.getInstance(context))

  /** DataStore生成済みのContextなしで単体テストできるようにするための内部entry point。 */
  internal fun getInstance(dataStore: DataStore<Preferences>): RecordModeRepository =
    instance ?: synchronized(this) {
      instance ?: DataStoreRecordModeRepository(dataStore).also { instance = it }
    }

  /** unit test専用。productionコードから呼び出さないこと。 */
  internal fun resetForTesting() {
    instance = null
  }
}
