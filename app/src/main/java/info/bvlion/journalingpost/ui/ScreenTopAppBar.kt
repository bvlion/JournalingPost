package info.bvlion.journalingpost.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import info.bvlion.journalingpost.R

/**
 * 画面タイトルを示すTop App Bar。
 *
 * [onBack]がある場合のみ戻るナビゲーションを出す。下部NavigationBarで切り替わる上位画面では
 * 戻る操作を持たないためnullで呼び、Webhook設定のような下位画面では戻る操作を渡す。
 *
 * insetsを0にしているのは、呼び出し側のScaffoldがtopBarを持たず、content paddingとして
 * すでにstatus barぶんを渡しているため。TopAppBarの既定insetsのままだと上余白が二重になる。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenTopAppBar(
  title: String,
  onBack: (() -> Unit)? = null,
) {
  TopAppBar(
    title = { Text(title) },
    navigationIcon = {
      if (onBack != null) {
        IconButton(onClick = onBack) {
          Icon(
            painter = painterResource(R.drawable.ic_arrow_back),
            contentDescription = stringResource(R.string.action_back),
          )
        }
      }
    },
    windowInsets = WindowInsets(0, 0, 0, 0),
  )
}
