# Bring Your Own Key (BYOK) — Setup Guide

Sonario lets you choose which AI provider powers your summaries. To use a
cloud provider, you need an API key. This guide walks through getting one
and adding it to the app securely.

## Supported providers

| Provider  | Type | Free tier | Key needed |
|-----------|------|-----------|------------|
| Groq      | Cloud | ✅ ~500k tok/day | Yes |
| OpenAI    | Cloud | — | Yes |
| Anthropic | Cloud | — | Yes |
| Ollama    | Local | ✅ (your hardware) | No |
| Custom    | Cloud | — | Maybe |

## Getting an API key

### Groq (recommended for free tier)

1. Go to [console.groq.com](https://console.groq.com) and sign in.
2. Navigate to **API Keys** → **Create API Key**.
3. Copy the key (starts with `gsk_`).
4. In Sonario: **Settings → Providers → Groq → API key → paste → Save key**.

### OpenAI

1. Go to [platform.openai.com/api-keys](https://platform.openai.com/api-keys).
2. Click **Create new secret key**.
3. Copy the key (starts with `sk-`).
4. In Sonario: **Settings → Providers → OpenAI → API key → paste → Save key**.

### Anthropic

1. Go to [console.anthropic.com](https://console.anthropic.com).
2. Navigate to **API Keys** → **Create Key**.
3. Copy the key (starts with `sk-ant-`).
4. In Sonario: **Settings → Providers → Anthropic → API key → paste → Save key**.

### Ollama (local, no key)

1. Install Ollama on your computer: [ollama.com](https://ollama.com).
2. Pull a model: `ollama pull llama3.2`.
3. Start the server: `ollama serve` (listens on `localhost:11434`).
4. In Sonario: **Settings → Providers → Ollama → set base URL** to
   `http://<your-computer-ip>:11434/v1`.
5. Pick a model and save.

### Custom (OpenAI-compatible proxy)

1. Get the base URL and API key from your proxy provider.
2. In Sonario: **Settings → Providers → Custom → set base URL**.
3. Paste the key if the proxy requires one.
4. Pick a model name (must match what the proxy expects).

## How your key is stored

Sonario encrypts your API key with **AES-256-GCM** using a key that lives in
the **Android Keystore** (hardware-backed on most devices). The encrypted
blob is stored in SharedPreferences; the plaintext key is never written to
disk. When you summarize, the key is decrypted in memory and sent only in
the `Authorization` (or `x-api-key` for Anthropic) header of requests you
initiate.

If the device has no hardware-backed keystore, Sonario falls back to
Base64 obfuscation and logs a warning.

## Security notes

- Your key is **never** sent to Sonario's developers or any third party.
- Your key is **never** included in crash reports.
- Removing the app or clearing its data **permanently deletes** all stored keys.
- You can clear a key at any time in **Settings → Providers**.

## Troubleshooting

| Problem | Fix |
|---------|-----|
| "rejected the API key" | Re-copy the key; make sure it's complete. |
| "model was not found" | Pick a model the provider currently offers. |
| "connection timed out" | Check Wi-Fi / mobile data. |
| "rate limit is still active" | Wait a minute; the free tier resets. |
| Ollama not reachable | Verify the server is running and the IP is correct. |
