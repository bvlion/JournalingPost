package info.bvlion.journalingpost.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import info.bvlion.journalingpost.mood.Mood
import info.bvlion.journalingpost.mood.moodCatalog

/**
 * SizeMode.Exact + LocalSize.currentで実際のWidgetサイズを取得し、縦方向に
 * 十分な高さがあるときだけ絵文字+ラベルのexpanded表示へ切り替える。それ以外
 * (compact)では絵文字のみを横一列に表示する。横方向へ広げた場合は、compact/
 * expandedいずれもdefaultWeight()により各Mood領域がそのまま広がる。
 *
 * 表示するMoodと並びは記録画面と共有する[moodCatalog]を正とする。
 */
class MoodWidget : GlanceAppWidget() {
  override val sizeMode: SizeMode = SizeMode.Exact

  override suspend fun provideGlance(context: Context, id: GlanceId) {
    provideContent {
      GlanceTheme {
        MoodWidgetContent()
      }
    }
  }

  /**
   * Widget picker用のgenerated preview(Android 15+)。previewSizeModeは既定の
   * SizeMode.Singleのままにしている。widget_mood_info.xmlのminWidth/minHeight
   * (110dp x 40dp)はLABELED_MIN_HEIGHT_DP(180dp)未満なので、この既定値のまま
   * MoodWidgetContent()を呼ぶだけでcompact表示がpreviewとして描画される。
   * preview専用のUIは持たず、実Widgetと同じcomposableをそのまま再利用する。
   */
  override suspend fun providePreview(context: Context, widgetCategory: Int) {
    provideContent {
      GlanceTheme {
        MoodWidgetContent()
      }
    }
  }
}

@Composable
private fun MoodWidgetContent() {
  val size = LocalSize.current
  val isExpanded = size.height >= LABELED_MIN_HEIGHT_DP.dp

  Scaffold(
    modifier = GlanceModifier.fillMaxSize(),
    backgroundColor = GlanceTheme.colors.widgetBackground,
    horizontalPadding = 0.dp,
  ) {
    if (isExpanded) {
      ExpandedMoodList()
    } else {
      CompactMoodRow()
    }
  }
}

/**
 * compactでは、狭い幅でGlanceの[Text]がemojiを"…"へ省略してしまい、
 * どのMoodか判別できなくなる問題があったため、emoji文字列をBitmapへ
 * レンダリングし[Image] + [ContentScale.Fit]で表示する。Textレイアウトの
 * 省略挙動を受けないため、セルがどれだけ狭くても縮小されるだけで
 * emoji自体は常に識別できる。
 */
@Composable
private fun CompactMoodRow() {
  val context = LocalContext.current
  Row(modifier = GlanceModifier.fillMaxSize().padding(2.dp)) {
    moodCatalog.forEach { mood ->
      val emojiBitmap = remember(mood) { renderEmojiBitmap(mood.emoji) }
      Box(
        modifier = GlanceModifier
          .defaultWeight()
          .fillMaxHeight()
          .clickable(moodAction(mood))
          .semantics { contentDescription = context.getString(mood.descriptionRes) },
        contentAlignment = Alignment.Center,
      ) {
        Image(
          provider = ImageProvider(emojiBitmap),
          contentDescription = null,
          modifier = GlanceModifier.fillMaxSize(),
          contentScale = ContentScale.Fit,
        )
      }
    }
  }
}

/**
 * emoji 1文字をAndroid標準のcolor emojiフォントのまま、透明背景の正方形Bitmapへ
 * 中央揃えで描画する。固定PNG/Vectorを用意するのではなく、Mood毎のemoji文字列
 * (ユーザーが将来任意のemojiを選べるようになっても通る経路)から都度生成する。
 * 十分縮小しても荒れないよう実際の表示サイズより高い解像度で描画しておく。
 */
private fun renderEmojiBitmap(emoji: String): Bitmap {
  val bitmap = Bitmap.createBitmap(EMOJI_BITMAP_SIZE_PX, EMOJI_BITMAP_SIZE_PX, Bitmap.Config.ARGB_8888)
  val canvas = Canvas(bitmap)
  val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = EMOJI_BITMAP_SIZE_PX * 0.75f
    textAlign = Paint.Align.CENTER
  }
  val centerX = EMOJI_BITMAP_SIZE_PX / 2f
  val centerY = EMOJI_BITMAP_SIZE_PX / 2f - (paint.ascent() + paint.descent()) / 2f
  canvas.drawText(emoji, centerX, centerY, paint)
  return bitmap
}

private const val EMOJI_BITMAP_SIZE_PX = 128

@Composable
private fun ExpandedMoodList() {
  val context = LocalContext.current
  Column(modifier = GlanceModifier.fillMaxSize().padding(4.dp)) {
    moodCatalog.forEach { mood ->
      Row(
        modifier = GlanceModifier
          .defaultWeight()
          .fillMaxWidth()
          .padding(horizontal = 8.dp)
          .clickable(moodAction(mood))
          .semantics { contentDescription = context.getString(mood.descriptionRes) },
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = mood.emoji,
          style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 18.sp),
        )
        Text(
          text = context.getString(mood.labelRes),
          maxLines = 1,
          modifier = GlanceModifier.defaultWeight().padding(start = 8.dp),
          style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp),
        )
      }
    }
  }
}

private fun moodAction(mood: Mood) =
  actionStartActivity<MoodEntryActivity>(actionParametersOf(MOOD_KEY to mood.name))

private val MOOD_KEY = ActionParameters.Key<String>(MoodEntryActivity.EXTRA_MOOD)

// Androidのセルサイズ計算式(70dp×セル数-30dp)で3セル分の高さ。
// compactの横一列だけでは手狭なラベルを、縦3セル以上に広げたときに表示する。
private const val LABELED_MIN_HEIGHT_DP = 180
