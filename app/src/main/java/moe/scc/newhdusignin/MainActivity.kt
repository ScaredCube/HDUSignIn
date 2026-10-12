package moe.scc.newhdusignin
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SklTheme { SklApp(viewModel()) } }
    }
}

@Composable
fun SklTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(
            primary = Color(0xFFA5D3B9), onPrimary = Color(0xFF0C3827), primaryContainer = Color(0xFF28503D),
            secondaryContainer = Color(0xFF354B40), surface = Color(0xFF101613), surfaceContainer = Color(0xFF1C231E),
            surfaceContainerLow = Color(0xFF18201A), surfaceContainerHigh = Color(0xFF273029)
        )
        else -> lightColorScheme(
            primary = Color(0xFF336B58), onPrimary = Color.White, primaryContainer = Color(0xFFD3EBDD), onPrimaryContainer = Color(0xFF163C2D),
            secondary = Color(0xFF52665B), secondaryContainer = Color(0xFFE0EAE2), onSecondaryContainer = Color(0xFF25392E),
            surface = Color(0xFFF6F9F4), surfaceContainer = Color(0xFFEBF1E9), surfaceContainerLow = Color(0xFFF0F5ED),
            surfaceContainerHigh = Color(0xFFE5EDE3), outlineVariant = Color(0xFFC6D2C8), onSurface = Color(0xFF1A251F), onSurfaceVariant = Color(0xFF536157)
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}
