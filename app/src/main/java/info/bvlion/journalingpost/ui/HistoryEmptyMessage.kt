package info.bvlion.journalingpost.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * 記録履歴・解析履歴で、履歴が1件も無いときに出す全体空状態の案内。
 *
 * 両画面でタブを切り替えても案内の縦位置が動いて見えないよう、どちらも画面領域全体を基準に
 * 中央へ置く。status barぶんの上余白は加えない。案内は中央にあり、上端保護やstatus barの
 * システム表示とは干渉しないため。
 */
@Composable
fun HistoryEmptyMessage(text: String, modifier: Modifier = Modifier) {
  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge)
  }
}
