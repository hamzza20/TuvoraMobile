// tvOS build only: shared data models carry a Compose `Color` (theme accents, badge colours).
// Compose UI has no tvOS artifact, so this provides that value type and the handful of helpers
// shared logic calls, with the same packing as Compose's sRGB colours (ARGB in the high 32 bits).
package androidx.compose.ui.graphics

import kotlin.jvm.JvmInline
import kotlin.math.abs

@JvmInline
value class Color(val value: ULong) {
    val alpha: Float get() = ((value shr 56) and 0xFFu).toFloat() / 255f
    val red: Float get() = ((value shr 48) and 0xFFu).toFloat() / 255f
    val green: Float get() = ((value shr 40) and 0xFFu).toFloat() / 255f
    val blue: Float get() = ((value shr 32) and 0xFFu).toFloat() / 255f

    fun copy(alpha: Float = this.alpha, red: Float = this.red, green: Float = this.green, blue: Float = this.blue): Color =
        Color(red, green, blue, alpha)

    companion object {
        val Black = Color(0xFF000000)
        val White = Color(0xFFFFFFFF)
        val Transparent = Color(0x00000000)
        val Unspecified = Color(0uL)
        val Red = Color(0xFFFF0000)
        val Green = Color(0xFF00FF00)
        val Blue = Color(0xFF0000FF)
        val Yellow = Color(0xFFFFFF00)
        val Cyan = Color(0xFF00FFFF)
        val Magenta = Color(0xFFFF00FF)
        val Gray = Color(0xFF888888)
        val DarkGray = Color(0xFF444444)
        val LightGray = Color(0xFFCCCCCC)

        /** As Compose's `Color.hsv`: hue 0..360, saturation and value 0..1. */
        fun hsv(hue: Float, saturation: Float, value: Float, alpha: Float = 1f): Color {
            val h = ((hue % 360f) + 360f) % 360f / 60f
            val c = value * saturation
            val x = c * (1f - abs(h % 2f - 1f))
            val (r, g, b) = when (h.toInt()) {
                0 -> Triple(c, x, 0f)
                1 -> Triple(x, c, 0f)
                2 -> Triple(0f, c, x)
                3 -> Triple(0f, x, c)
                4 -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
            val m = value - c
            return Color(r + m, g + m, b + m, alpha)
        }
    }
}

fun Color(color: Long): Color = Color((color.toULong() and 0xFFFFFFFFu) shl 32)

fun Color(color: Int): Color = Color(color.toLong() and 0xFFFFFFFFL)

fun Color(red: Int, green: Int, blue: Int, alpha: Int = 0xFF): Color = Color(
    ((alpha.toLong() and 0xFF) shl 24) or ((red.toLong() and 0xFF) shl 16) or
        ((green.toLong() and 0xFF) shl 8) or (blue.toLong() and 0xFF),
)

fun Color(red: Float, green: Float, blue: Float, alpha: Float = 1f): Color {
    fun c(f: Float) = (f.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    return Color(c(red), c(green), c(blue), c(alpha))
}

fun Color.toArgb(): Int = (value shr 32).toInt()

/** Relative luminance with the sRGB transfer curve removed, as Compose's `luminance()`. */
fun Color.luminance(): Float {
    fun linear(c: Float) = if (c <= 0.04045f) c / 12.92f else kotlin.math.exp(2.4f * kotlin.math.ln((c + 0.055f) / 1.055f))
    return 0.2126f * linear(red) + 0.7152f * linear(green) + 0.0722f * linear(blue)
}

/** Linear interpolation between two colours, as Compose's `lerp` for sRGB colours. */
fun lerp(start: Color, stop: Color, fraction: Float): Color {
    fun mix(a: Float, b: Float) = a + (b - a) * fraction
    return Color(mix(start.red, stop.red), mix(start.green, stop.green), mix(start.blue, stop.blue), mix(start.alpha, stop.alpha))
}
