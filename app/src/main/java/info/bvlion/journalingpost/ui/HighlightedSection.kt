package info.bvlion.journalingpost.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 初回オンボーディング(#67)で「対象箇所自体が一目で分かる」ことを要件とした視覚誘導。
 * 小さい補助テキストでは実機上目立たなかったため、枠線と薄い背景色で領域そのものを強調する。
 * [highlighted]がfalseの間はそのまま[this]を返す。
 */
@Composable
fun Modifier.highlightedSection(highlighted: Boolean): Modifier {
  if (!highlighted) return this
  val shape = MaterialTheme.shapes.medium
  return this
    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), shape)
    .border(2.dp, MaterialTheme.colorScheme.primary, shape)
}
