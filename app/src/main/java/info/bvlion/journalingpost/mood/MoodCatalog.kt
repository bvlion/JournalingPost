package info.bvlion.journalingpost.mood

import android.content.Context
import info.bvlion.journalingpost.R

/** 初回利用時のMoodセット。固定IDにより、表示内容とは独立した同一性を維持する。 */
fun createInitialMoodCatalog(context: Context): List<Mood> = listOf(
  Mood(
    id = "8b9707cc-0750-44a7-b4ad-375c5baf8ddd",
    emoji = context.getString(R.string.mood_emoji_excited),
    label = context.getString(R.string.mood_label_excited),
  ),
  Mood(
    id = "c3fbd946-68b6-4d85-82b7-cd8d2291d716",
    emoji = context.getString(R.string.mood_emoji_happy),
    label = context.getString(R.string.mood_label_happy),
  ),
  Mood(
    id = "1969bc8d-31b6-407b-a9c0-1c82cd191f63",
    emoji = context.getString(R.string.mood_emoji_calm),
    label = context.getString(R.string.mood_label_calm),
  ),
  Mood(
    id = "c6ec7b76-0db0-417b-9788-380d4ff3dd09",
    emoji = context.getString(R.string.mood_emoji_neutral),
    label = context.getString(R.string.mood_label_neutral),
  ),
  Mood(
    id = "92627908-a545-47a4-9dfd-5e1f2747b261",
    emoji = context.getString(R.string.mood_emoji_tired),
    label = context.getString(R.string.mood_label_tired),
  ),
  Mood(
    id = "d48fb545-b3b7-4d63-8f20-51ce1e9399e4",
    emoji = context.getString(R.string.mood_emoji_uncertain),
    label = context.getString(R.string.mood_label_uncertain),
  ),
  Mood(
    id = "1422aba2-5ccb-47bd-a712-1f6e8c26fa3f",
    emoji = context.getString(R.string.mood_emoji_anxious),
    label = context.getString(R.string.mood_label_anxious),
  ),
  Mood(
    id = "4cc26d74-313c-4fff-a6b3-f376c8eb88a4",
    emoji = context.getString(R.string.mood_emoji_irritated),
    label = context.getString(R.string.mood_label_irritated),
  ),
  Mood(
    id = "c88d112f-7cc8-4ce5-b29a-a96806588452",
    emoji = context.getString(R.string.mood_emoji_sad),
    label = context.getString(R.string.mood_label_sad),
  ),
  Mood(
    id = "7e83185b-de11-4ba2-a448-d93c0c3416a4",
    emoji = context.getString(R.string.mood_emoji_distressed),
    label = context.getString(R.string.mood_label_distressed),
  ),
)
