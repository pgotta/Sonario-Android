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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ai.sonario.app.data.EngineChoice
import ai.sonario.app.llm.LlmProvider
import ai.sonario.app.llm.SecureStorage

/**
 * Settings: choose the engine (on-device vs cloud), pick a cloud provider,
 * and configure each provider's API key, model, base URL, and temperature.
 *
 * API keys are stored in hardware-backed encrypted storage and never
 * displayed in full — only a masked preview is shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SummaryViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val scroll = rememberScrollState()

    var keyInput by remember(ui.cloudProvider) { mutableStateOf("") }

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
            // ── Engine choice ──────────────────────────────────────────────────
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
                title = "Cloud",
                subtitle = "Fast. Sends text to the provider you choose below. " +
                    "Needs an API key.",
                selected = ui.engineChoice == EngineChoice.CLOUD,
                onClick = { vm.setEngine(EngineChoice.CLOUD) },
            )

            if (ui.engineChoice == EngineChoice.CLOUD) {
                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = SonarioColors.RuleSoft)
                Spacer(Modifier.height(20.dp))

                CloudProviderSection(vm, ui, keyInput, onKeyChange = { keyInput = it })
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = SonarioColors.RuleSoft)
            Spacer(Modifier.height(12.dp))
            Text(
                "Sonario 1.4.0 • multi-provider BYOK",
                color = SonarioColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun CloudProviderSection(
    vm: SummaryViewModel,
    ui: UiState,
    keyInput: String,
    onKeyChange: (String) -> Unit,
) {
    val providers = LlmProvider.entries
    var expanded by remember { mutableStateOf(false) }

    Text("Cloud provider", color = SonarioColors.InkSoft,
        style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(6.dp))

    // Provider dropdown
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = ui.cloudProvider.displayName,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            colors = sonarioFieldColors(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            providers.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider.displayName) },
                    onClick = {
                        vm.setCloudProvider(provider)
                        onKeyChange("")
                        expanded = false
                    },
                )
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    HorizontalDivider(color = SonarioColors.RuleSoft)
    Spacer(Modifier.height(16.dp))

    // ── API key ─────────────────────────────────────────────────────────────
    val provider = ui.cloudProvider
    Text("${provider.displayName} API key", color = SonarioColors.InkSoft,
        style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(6.dp))
    Text(
        keyHelpText(provider),
        color = SonarioColors.Muted,
        style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(12.dp))

    if (ui.providerKeySet) {
        Text("Key saved: ${vm.maskedKey(provider)}",
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
            "Counts usage through this app only; resets daily.",
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
        onValueChange = onKeyChange,
        placeholder = { Text(keyPlaceholder(provider)) },
        label = { Text(if (ui.providerKeySet) "Replace API key" else "API key") },
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
                vm.setProviderKey(keyInput.trim(), provider)
                onKeyChange("")
            }
        },
        enabled = keyInput.isNotBlank(),
        colors = ButtonDefaults.buttonColors(
            containerColor = SonarioColors.Green,
            contentColor = SonarioColors.Abyss),
    ) { Text("Save key") }

    Spacer(Modifier.height(20.dp))
    HorizontalDivider(color = SonarioColors.RuleSoft)
    Spacer(Modifier.height(16.dp))

    // ── Model ───────────────────────────────────────────────────────────────
    Text("${provider.displayName} model", color = SonarioColors.InkSoft,
        style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(6.dp))

    var modelInput by remember(provider) { mutableStateOf(ui.providerModel) }
    var modelExpanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = modelExpanded,
        onExpandedChange = { modelExpanded = !modelExpanded },
    ) {
        OutlinedTextField(
            value = modelInput,
            onValueChange = {
                modelInput = it
                modelExpanded = true
            },
            label = { Text("Model") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            colors = sonarioFieldColors(),
        )
        ExposedDropdownMenu(
            expanded = modelExpanded,
            onDismissRequest = { modelExpanded = false },
        ) {
            provider.suggestedModels.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model) },
                    onClick = {
                        modelInput = model
                        vm.setProviderModel(model, provider)
                        modelExpanded = false
                    },
                )
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "Model names change over time. Pick one the provider currently offers.",
        color = SonarioColors.Muted,
        style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { vm.setProviderModel(modelInput, provider) },
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = SonarioColors.InkSoft),
    ) { Text("Save model") }

    if (provider == LlmProvider.CUSTOM) {
        Spacer(Modifier.height(16.dp))
        var urlInput by remember(provider) {
            mutableStateOf(ai.sonario.app.data.Settings::class.java.let { "" })
        }
        Text("Custom base URL", color = SonarioColors.InkSoft,
            style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = urlInput,
            onValueChange = {
                urlInput = it
                vm.setCustomBaseUrl(it, provider)
            },
            placeholder = { Text("https://my-proxy.example.com/v1") },
            label = { Text("Base URL") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
            colors = sonarioFieldColors(),
        )
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

private fun keyHelpText(provider: LlmProvider): String = when (provider) {
    LlmProvider.GROQ -> "Get a free key at console.groq.com (no credit card). Stored only on this device."
    LlmProvider.OPENAI -> "Get your key at platform.openai.com/api-keys. Stored only on this device."
    LlmProvider.ANTHROPIC -> "Get your key at console.anthropic.com. Stored only on this device."
    LlmProvider.OLLAMA -> "Ollama runs locally — no API key needed. Just install and start the server."
    LlmProvider.CUSTOM -> "Paste the API key for your OpenAI-compatible proxy. Stored only on this device."
}

private fun keyPlaceholder(provider: LlmProvider): String = when (provider) {
    LlmProvider.GROQ -> "gsk_..."
    LlmProvider.OPENAI -> "sk-..."
    LlmProvider.ANTHROPIC -> "sk-ant-..."
    LlmProvider.OLLAMA -> "(no key needed)"
    LlmProvider.CUSTOM -> "..."
}

private fun fmtK(n: Long): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000 -> "${n / 1000}K"
    else -> n.toString()
}
