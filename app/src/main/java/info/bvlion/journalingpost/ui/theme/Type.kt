package info.bvlion.journalingpost.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Typography = Typography(
  bodyLarge = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Normal,
    fontSize = 16.sp,
    lineHeight = 24.sp,
    letterSpacing = 0.5.sp
  )
)

// 記録履歴・解析履歴で利用者が読む主要情報・本文(記録行の時刻/気分/メモ、解析結果本文)のサイズ。
// bodyLarge(16sp)は履歴一覧全体が重く見え、bodyMedium(14sp)では補助情報と差がつかないため、
// 可読性優先で15spにする。全体空状態の案内は密度の対象ではないためbodyLarge(16sp)のまま。
val HistoryReadingTextStyle = Typography.bodyLarge.copy(fontSize = 15.sp)
