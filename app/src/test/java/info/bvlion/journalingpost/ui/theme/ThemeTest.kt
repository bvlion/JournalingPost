package info.bvlion.journalingpost.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
  private val light = lightColorScheme()
  private val dark = darkColorScheme()

  private fun warmth(color: Color): Float = color.red - color.blue

  private fun contrastRatio(a: Color, b: Color): Double {
    val l1 = a.luminance() + 0.05
    val l2 = b.luminance() + 0.05
    return if (l1 > l2) l1 / l2 else l2 / l1
  }

  @Test
  fun `neutralな面はLight Dark双方で暖色側へ寄る`() {
    val tintedLight = light.withUsukouSurfaces(darkTheme = false)
    assertTrue(warmth(tintedLight.surface) > warmth(light.surface))
    assertTrue(warmth(tintedLight.background) > warmth(light.background))
    assertTrue(warmth(tintedLight.surfaceContainer) > warmth(light.surfaceContainer))

    val tintedDark = dark.withUsukouSurfaces(darkTheme = true)
    assertTrue(warmth(tintedDark.surface) > warmth(dark.surface))
    assertTrue(warmth(tintedDark.background) > warmth(dark.background))
  }

  @Test
  fun `accentとon色とoutlineはDynamic Colorのまま残す`() {
    val tinted = light.withUsukouSurfaces(darkTheme = false)

    assertEquals(light.primary, tinted.primary)
    assertEquals(light.secondary, tinted.secondary)
    assertEquals(light.tertiary, tinted.tertiary)
    assertEquals(light.error, tinted.error)
    assertEquals(light.onSurface, tinted.onSurface)
    assertEquals(light.onBackground, tinted.onBackground)
    assertEquals(light.onSurfaceVariant, tinted.onSurfaceVariant)
    assertEquals(light.outline, tinted.outline)
  }

  @Test
  fun `Darkの面は暖色へ寄せても黒レベルを持ち上げない`() {
    val tinted = dark.withUsukouSurfaces(darkTheme = true)

    assertTrue(tinted.surface.luminance() < 0.05f)
    assertTrue(tinted.background.luminance() < 0.05f)
    assertTrue(tinted.surface.luminance() <= dark.surface.luminance() + 0.02f)
  }

  @Test
  fun `Light Dark双方で本文の可読コントラストを保つ`() {
    val tintedLight = light.withUsukouSurfaces(darkTheme = false)
    val tintedDark = dark.withUsukouSurfaces(darkTheme = true)

    assertTrue(contrastRatio(tintedLight.onSurface, tintedLight.surface) >= 4.5)
    assertTrue(contrastRatio(tintedLight.onBackground, tintedLight.background) >= 4.5)
    assertTrue(contrastRatio(tintedDark.onSurface, tintedDark.surface) >= 4.5)
    assertTrue(contrastRatio(tintedDark.onBackground, tintedDark.background) >= 4.5)
  }

  @Test
  fun `surfaceTintは淡香寄りになる`() {
    val tintedLight = light.withUsukouSurfaces(darkTheme = false)
    val tintedDark = dark.withUsukouSurfaces(darkTheme = true)

    assertTrue(warmth(tintedLight.surfaceTint) > warmth(light.surfaceTint))
    assertTrue(warmth(tintedDark.surfaceTint) > warmth(dark.surfaceTint))
  }
}
