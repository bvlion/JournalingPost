package info.bvlion.journalingpost.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 下部NavigationBarが現在地を示すトップレベルdestination(記録 / 記録履歴 / 解析履歴 / 設定)の共通枠。
 * 画面名の固定タイトルやAppBarは持たず、スクロールコンテンツをedge-to-edgeでsystem status barの
 * 下まで流し、status bar領域だけを[StatusBarProtection]で保護する。
 *
 * 記録履歴のようにstatus bar直下へ独自の固定要素(日付ナビゲーション)を重ねる画面は、その要素側の
 * 背景で上端保護を兼ねるため、この枠ではなくBoxを直接組む。
 */
@Composable
fun TopLevelScreen(
  modifier: Modifier = Modifier,
  content: @Composable BoxScope.() -> Unit,
) {
  Box(modifier = modifier.fillMaxSize()) {
    content()
    StatusBarProtection(modifier = Modifier.align(Alignment.TopCenter))
  }
}

/**
 * status bar領域へ重ねる半透明の上端保護。スクロールコンテンツが背後を流れても時刻・電池等の
 * システムアイコンの可読性を保つ。淡香の下地色をそのまま使い、下端はコンテンツへ向けて透明へ
 * フェードさせて保護の終端が線として出ないようにする。必要以上に主張させない。
 */
@Composable
fun StatusBarProtection(modifier: Modifier = Modifier) {
  val color = MaterialTheme.colorScheme.background
  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
      .background(
        Brush.verticalGradient(
          0.0f to color.copy(alpha = 0.9f),
          0.7f to color.copy(alpha = 0.72f),
          1.0f to color.copy(alpha = 0.0f),
        ),
      ),
  )
}

/**
 * 記録履歴の固定日付ナビゲーションなど、status bar直下へ固定する要素の背景色。
 * [StatusBarProtection]と同じ淡香の下地色を、やや強めの一様な半透明で使い、上端保護と地続きに
 * 見せつつ、その要素自体がわずかに識別できる程度の存在感に留める。
 */
@Composable
fun fixedTopRegionBackgroundColor(): Color =
  MaterialTheme.colorScheme.background.copy(alpha = 0.88f)

/**
 * トップレベルのLazyColumn向けcontentPadding。既定の余白に加えて、先頭コンテンツがstatus barへ
 * 潜り込んで始まらないよう上端へstatus bar領域ぶんを足す。コンテンツ自体はスクロールで
 * status barの下を通過する。
 */
@Composable
fun topLevelListContentPadding(
  horizontal: Dp = 16.dp,
  vertical: Dp = 16.dp,
): PaddingValues {
  val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
  return PaddingValues(start = horizontal, top = vertical + statusBarTop, end = horizontal, bottom = vertical)
}

/** status bar領域の高さ。edge-to-edgeのトップレベル画面で、スクロールコンテナの上パディングへ加える。 */
@Composable
fun statusBarSpacing(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
