package ai.sonario.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import ai.sonario.app.data.Settings
import ai.sonario.app.llm.EngineChoice
import ai.sonario.app.llm.LlmProvider
import ai.sonario.app.llm.SecureStorage
import kotlinx.coroutines.launch

/**
 * Settings screen — now provider-aware. The user picks a cloud provider,
 * enters their BYOK API key (stored encrypted), selects a model, and can
 * optionally override the base URL for proxies / self-hosted endpoints.
 *
 * The on-device path is unchanged: pick a GGUF model and go.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    onEngineChoice: (EngineChoice) -> Unit,
    onProviderChanged: (LlmProvider) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var selectedProvider by remember { mutableStateOf(settings.cloudProvider) }
    var apiKey by remember { mutableStateOf(settings.keyFor(selectedProvider) ?: "") }
    var showKey by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf(settings.modelFor(selectedProvider)) }
    var customUrl by remember { mutableStateOf(settings.customBaseUrlFor(selectedProvider)) }
    var temperature by remember { mutableFloatStateOf(settings.temperatureFor(selectedProvider)) }
    var maxOutput by remember { mutableIntStateOf(settings.maxOutputTokens) }
    var engineChoice by remember { mutableStateOf(settings.engine) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    // Reset per-provider fields when the user switches provider
    fun switchProvider(p: LlmProvider) {
        selectedProvider = p
        apiKey = settings.keyFor(p) ?: ""
        showKey = false
        selectedModel = settings.modelFor(p)
        customUrl = settings.customBaseUrlFor(p)
        temperature = settings.temperatureFor(p)
        onProviderChanged(p)
    }

    // Persist current provider's settings whenever they change
    fun persist() {
        settings.setKeyFor(selectedProvider, apiKey.ifBlank { null })
        settings.setModelFor(selectedProvider, selectedModel)
        settings.setCustomBaseUrlFor(selectedProvider, customUrl)
        settings.setTemperatureFor(selectedProvider, temperature)
        settings.maxOutputTokens = maxOutput
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                actions = {
                    TextButton(onClick = {
                        persist()
                        onDismiss()
                    }) { Text("Done") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Engine toggle ─────────────────────────────────────────────────────
            Text("Engine", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = engineChoice == EngineChoice.ON_DEVICE,
                    onClick = {
                        engineChoice = EngineChoice.ON_DEVICE
                        settings.engine = EngineChoice.ON_DEVICE
                        onEngineChoice(EngineChoice.ON_DEVICE)
                    },
                    label = { Text("On-device") },
                )
                FilterChip(
                    selected = engineChoice == EngineChoice.CLOUD,
                    onClick = {
                        engineChoice = EngineChoice.CLOUD
                        settings.engine = EngineChoice.CLOUD
                        onEngineChoice(EngineChoice.CLOUD)
                    },
                    label = { Text("Cloud") },
                )
            }

            HorizontalDivider()

            if (engineChoice == EngineChoice.CLOUD) {
                // ── Provider selector ────────────────────────────────────────────
                Text("Cloud provider", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LlmProvider.entries.forEach { p ->
                        FilterChip(
                            selected = selectedProvider == p,
                            onClick = { switchProvider(p) },
                            label = { Text(p.displayName) },
                        )
                    }
                }

                // ── API key ──────────────────────────────────────────────────────
                Text("API key", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        settings.setKeyFor(selectedProvider, it.ifBlank { null })
                    },
                    label = {
                        Text(
                            if (selectedProvider.needsKey) "${selectedProvider.displayName} API key (BYOK)"
                            else "API key (optional)"
                        )
                    },
                    placeholder = {
                        Text(
                            when (selectedProvider) {
                                LlmProvider.OPENAI -> "sk-…"
                                LlmProvider.ANTHROPIC -> "sk-ant-…"
                                LlmProvider.GROQ -> "gsk_…"
                                else -> ""
                            }
                        )
                    },
                    visualTransformation = if (showKey) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        Row {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showKey) "Hide key" else "Show key",
                                )
                            }
                            IconButton(onClick = {
                                clipboard.setText(AnnotatedString(apiKey))
                                toast = "Copied"
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Stored encrypted with Android Keystore. Never leaves your device except in the Authorization header of requests you initiate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // ── Model selector ───────────────────────────────────────────────
                Text("Model", style = MaterialTheme.typography.titleMedium)
                ExposedDropdownMenuBox(
                    expanded = modelMenuOpen,
                    onExpandedChange = { modelMenuOpen = it },
                ) {
                    OutlinedTextField(
                        value = selectedModel,
                        onValueChange = {
                            selectedModel = it
                            settings.setModelFor(selectedProvider, it)
                        },
                        label = { Text("Model") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuOpen) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = modelMenuOpen,
                        onDismissRequest = { modelMenuOpen = false },
                    ) {
                        selectedProvider.suggestedModels.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m) },
                                onClick = {
                                    selectedModel = m
                                    settings.setModelFor(selectedProvider, m)
                                    modelMenuOpen = false
                                },
                                trailingIcon = if (m == selectedModel) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null,
                            )
                        }
                    }
                }

                // ── Custom base URL ──────────────────────────────────────────────
                Text("Custom base URL", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = customUrl,
                    onValueChange = {
                        customUrl = it
                        settings.setCustomBaseUrlFor(selectedProvider, it)
                    },
                    label = { Text("Override (optional)") },
                    placeholder = { Text(selectedProvider.baseUrl) },
                    supportingText = {
                        Text(
                            if (customUrl.isBlank()) "Default: ${selectedProvider.baseUrl}"
                            else "Using custom endpoint",
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // ── Temperature ──────────────────────────────────────────────────
                Text("Temperature: ${"%.2f".format(temperature)}", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = temperature,
                    onValueChange = {
                        temperature = it
                        settings.setTemperatureFor(selectedProvider, it)
                    },
                    valueRange = 0f..2f,
                    steps = 19,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Precise", style = MaterialTheme.typography.bodySmall)
                    Text("Creative", style = MaterialTheme.typography.bodySmall)
                }

                // ── Max output tokens ────────────────────────────────────────────
                Text("Max output tokens: $maxOutput", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = maxOutput.toFloat(),
                    onValueChange = {
                        maxOutput = it.toInt()
                        settings.maxOutputTokens = maxOutput
                    },
                    valueRange = 256f..8192f,
                    steps = 31,
                )

                // ── Status ───────────────────────────────────────────────────────
                val hasKey = settings.hasKeyFor(selectedProvider)
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (hasKey)
                            MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            if (hasKey) "✓ Key stored securely"
                            else if (selectedProvider.needsKey) "⚠ Key required"
                            else "No key needed",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                // ── On-device info ───────────────────────────────────────────────
                Text("On-device engine", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Summarises using a GGUF model running locally via llama.cpp. " +
                        "No data ever leaves your device, but large models are CPU-slow.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { /* navigate to ModelsScreen */ }) {
                    Text("Manage models →")
                }
            }
        }
    }

    // Toast feedback
    LaunchedEffect(toast) {
        if (toast != null) {
            kotlinx.coroutines.delay(1500)
            toast = null
        }
    }
    toast?.let {
        Snackbar(modifier = Modifier.padding(16.dp)) { Text(it) }
    }
}
