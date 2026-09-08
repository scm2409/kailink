package at.d71.kailink.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val KaiTeal = Color(0xFF1B5E5A)
private val KaiTealDark = Color(0xFF0E3B38)

private val LightColors = lightColorScheme(
    primary = KaiTeal,
    secondary = Color(0xFF3A6EA5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD6CE),
    secondary = Color(0xFF9BBCE0),
    background = KaiTealDark,
)

@Composable
fun KaiLinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}