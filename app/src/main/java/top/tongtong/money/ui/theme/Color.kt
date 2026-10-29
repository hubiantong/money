package top.tongtong.money.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Coral = Color(0xFFF06B6B)
val Mint = Color(0xFF39B98A)
val MintDark = Color(0xFF1F8F68)
val Ink = Color(0xFF23201F)
val Muted = Color(0xFF7B7370)
val WarmBackground = Color(0xFFFFF8F4)
val WarmSurface = Color(0xFFFFFFFF)
val Danger = Color(0xFFD64545)
val DangerBackground = Color(0xFFFFEBEB)
val SavingsBlue = Color(0xFF4A7DDB)

private val LightColors = lightColorScheme(
    primary = Coral,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF4A1210),
    secondary = Mint,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC7F2E1),
    onSecondaryContainer = Color(0xFF073B2B),
    background = WarmBackground,
    onBackground = Ink,
    surface = WarmSurface,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF4E9E4),
    onSurfaceVariant = Muted,
    error = Danger,
    onError = Color.White,
    errorContainer = DangerBackground,
    onErrorContainer = Color(0xFF5B1616),
    outline = Color(0xFFDCCFC9),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB4AB),
    onPrimary = Color(0xFF561E1B),
    primaryContainer = Color(0xFF7B2C28),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFF6FDCB8),
    onSecondary = Color(0xFF003825),
    secondaryContainer = Color(0xFF00513A),
    onSecondaryContainer = Color(0xFFC7F2E1),
    background = Color(0xFF171312),
    onBackground = Color(0xFFEDE0DB),
    surface = Color(0xFF211B1A),
    onSurface = Color(0xFFEDE0DB),
    surfaceVariant = Color(0xFF3A302D),
    onSurfaceVariant = Color(0xFFD3C3BD),
    // error 使用更饱和的红色，避免与 primary 在暗色下难以区分
    error = Color(0xFFF26D66),
    onError = Color(0xFF410002),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF9A8B85),
)

@Composable
fun MoneyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
): Unit = MaterialTheme(
    colorScheme = if (darkTheme) DarkColors else LightColors,
    typography = MoneyTypography,
    content = content,
)
