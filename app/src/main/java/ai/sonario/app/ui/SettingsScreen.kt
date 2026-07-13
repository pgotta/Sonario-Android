package ai.sonario.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ai.sonario.app.data.EngineChoice

/**
 * Settings: choose the engine and, for the Groq cloud engine, paste an API key
 * and set the model string. The key is stored locally on the device only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SummaryViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val scroll = rememberScrollState()

    var keyInput by remember { mutableStateOf("") }
    var modelInput by remember { mutableStateOf(ui.groqModel) }

    Scaffold(
        containerColor = SonarioColors.Deep,
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = SonarioColors.Ink) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back",
                            tint = SonarioColors.InkSoft)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SonarioColors.Deep),
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(scroll).padding(16.dp)
        ) {
            // Engine choice
            Text("Where the AI runs", color = SonarioColors.InkSoft,
                style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(10.dp))
            EngineOption(
                title = "On-device",
                subtitle = "Runs the model on your phone. Private, but slow " +
                        "(CPU only). Needs a downloaded model.",
                selected = ui.engineChoice == EngineChoice.ON_DEVICE,
                onClick = { vm.setEngine(EngineChoice.ON_DEVICE) },
            )
            EngineOption(
                title = "Groq (cloud)",
                subtitle = "Fast. Sends your text to Groq's servers to summarize. " +
                        "Needs a free API key below.",
                selected = ui.engineChoice == EngineChoice.GROQ,
                onClick = { vm.setEngine(EngineChoice.GROQ) },
            )

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = SonarioColors.RuleSoft)
            Spacer(Modifier.height(20.dp))

            // Groq settings
            Text("Groq cloud", color = SonarioColors.InkSoft,
                style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "Get a free API key at console.groq.com (no credit card). Create a " +
                "key, then paste it here. Your key is stored only on this device " +
                "and is sent solely to Groq when you summarize.",
                color = SonarioColors.Muted,
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))

            if (ui.groqKeySet) {
                Text("Key saved: ${vm.currentGroqKeyMasked()}",
                    color = SonarioColors.Green,
                    style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                val (used, limit) = vm.groqDailyUsage()
                val remaining = (limit - used).coerceAtLeast(0)
                val pct = if (limit > 0) (remaining * 100 / limit).toInt() else 0
                Text(
                    "Daily budget: $pct% remaining " +
                    "(~${fmtK(remaining)} of ${fmtK(limit)} tokens left today)",
                    color = if (pct < 15) SonarioColors.Teal else SonarioColors.Muted,
                    style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Counts usage through this app only; resets daily. Groq's free " +
                    "tier is about ${fmtK(limit)} tokens/day.",
                    color = SonarioColors.Muted,
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { vm.resetDailyBudget() }) {
                    Text("Reset counter", color = SonarioColors.Green)
                }
                Spacer(Modifier.height(8.dp))
            }

            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                placeholder = { Text("gsk_...") },
                label = { Text(if (ui.groqKeySet) "Replace API key" else "API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
                colors = sonarioFieldColors(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (keyInput.isNotBlank()) {
                        vm.setGroqKey(keyInput.trim())
                        keyInput = ""
                    }
                },
                enabled = keyInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SonarioColors.Green,
                    contentColor = SonarioColors.Abyss),
            ) { Text("Save key") }

            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = modelInput,
                onValueChange = { modelInput = it },
                label = { Text("Groq model") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
                colors = sonarioFieldColors(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Model names change over time. Default is Llama 4 Scout. If Groq " +
                "retires it, set another from console.groq.com/docs/models.",
                color = SonarioColors.Muted,
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { vm.setGroqModel(modelInput) },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = SonarioColors.InkSoft),
            ) { Text("Save model") }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = SonarioColors.RuleSoft)
            Spacer(Modifier.height(12.dp))
            Text(
                "Sonario 1.3.3 • keyboard-safe Ask field",
                color = SonarioColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun EngineOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) SonarioColors.Panel2 else SonarioColors.Panel,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        onClick = onClick,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(
                    selectedColor = SonarioColors.Green,
                    unselectedColor = SonarioColors.Muted),
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(title, color = SonarioColors.Ink, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = SonarioColors.Muted,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}


/** Compact token count: 480000 -> "480K", 1200000 -> "1.2M". */
private fun fmtK(n: Long): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000 -> "${n / 1000}K"
    else -> n.toString()
}
