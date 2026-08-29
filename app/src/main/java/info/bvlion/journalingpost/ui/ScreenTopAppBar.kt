package info.bvlion.journalingpost.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import info.bvlion.journalingpost.R

/**
 * 履歴・設定で同じnavigation + titleの階層を使うためのTop App Bar。
 *
 * insetsを0にしているのは、呼び出し側のScaffoldがtopBarを持たず、content paddingとして
 * すでにstatus barぶんを渡しているため。TopAppBarの既定insetsのままだと上余白が二重になる。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenTopAppBar(
  title: String,
  onBack: () -> Unit,
) {
  TopAppBar(
    title = { Text(title) },
    navigationIcon = {
      IconButton(onClick = onBack) {
        Icon(
          painter = painterResource(R.drawable.ic_arrow_back),
          contentDescription = "戻る",
        )
      }
    },
    windowInsets = WindowInsets(0, 0, 0, 0),
  )
}
