package info.bvlion.journalingpost

import android.app.Application
import info.bvlion.journalingpost.di.AppContainer

/**
 * process内で共有する依存関係の所有者。ActivityはCreationExtrasのApplication経由で[container]へ
 * 到達できるため、ViewModel取得前の初期化呼び出しは不要。
 */
class JournalingPostApplication : Application() {
  internal val container: AppContainer by lazy { AppContainer(this) }
}
