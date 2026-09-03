package info.bvlion.journalingpost.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext

@Composable
fun JournalingPostTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit
) {
  val context = LocalContext.current
  val dynamicScheme = if (darkTheme) {
    dynamicDarkColorScheme(context)
  } else {
    dynamicLightColorScheme(context)
  }
  MaterialTheme(
    colorScheme = dynamicScheme.withUsukouSurfaces(darkTheme),
    typography = Typography,
    content = content
  )
}

/**
 * Dynamic Colorが返す配色のうち、neutralな面（background / surface 系）だけへ淡香をわずかに混ぜる。
 * primary等のaccentとon色、outlineはDynamic Colorのまま残すため、壁紙連動の操作感を保ったまま
 * 全画面へ淡香の下地だけが乗る。
 *
 * Darkは黒レベルを持ち上げないよう混ぜる量を控えめにし、代わりにelevation由来の色付け（surfaceTint）を
 * 淡香寄りにして、ダイアログや下部ナビなど一段持ち上がった面へ暖色の識別性を残す。
 */
internal fun ColorScheme.withUsukouSurfaces(darkTheme: Boolean): ColorScheme {
  val tint = if (darkTheme) UsukouShade else Usukou
  val surfaceRatio = if (darkTheme) 0.12f else 0.16f
  val tintRatio = if (darkTheme) 0.40f else 0.35f
  fun blend(base: Color): Color = lerp(base, tint, surfaceRatio)
  return copy(
    background = blend(background),
    surface = blend(surface),
    surfaceVariant = blend(surfaceVariant),
    surfaceBright = blend(surfaceBright),
    surfaceDim = blend(surfaceDim),
    surfaceContainerLowest = blend(surfaceContainerLowest),
    surfaceContainerLow = blend(surfaceContainerLow),
    surfaceContainer = blend(surfaceContainer),
    surfaceContainerHigh = blend(surfaceContainerHigh),
    surfaceContainerHighest = blend(surfaceContainerHighest),
    surfaceTint = lerp(surfaceTint, Usukou, tintRatio),
  )
}
