package ai.sonario.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shown on the launch AFTER a crash. Displays the recorded stack trace so the
 * problem is visible instead of the app silently closing, with a button to copy
 * it. The report also lives on disk at
 * Android/data/ai.sonario.app/files/last_crash.txt.
 */
@Composable
fun CrashScreen(text: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Surface(color = SonarioColors.Deep, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Sonario hit an error",
                color = SonarioColors.Ink,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.headlineMedium)
            Text(
                "The last run crashed. Details below (also saved to " +
                "Android/data/ai.sonario.app/files/last_crash.txt). Copy this and " +
                "send it so it can be fixed.",
                color = SonarioColors.Muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))

            Row {
                Button(
                    onClick = { clipboard.setText(AnnotatedString(text)) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SonarioColors.Green,
                        contentColor = SonarioColors.Abyss),
                ) { Text("Copy report") }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SonarioColors.InkSoft),
                ) { Text("Continue") }
            }

            Spacer(Modifier.height(14.dp))
            Surface(
                color = SonarioColors.Panel,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                Text(
                    text = text,
                    color = SonarioColors.InkSoft,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                )
            }
        }
    }
}
