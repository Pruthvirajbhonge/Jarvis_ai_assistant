# J.A.R.V.I.S. Android (Kotlin + XML)

Phone mode is the default. PC mode is optional (top-right "PC" switch, or say "PC mode" / "phone mode").

## Setup
1. Open in Android Studio (JDK 17), sync, run.
2. Settings (menu icon): add at least one AI key (Gemini, Groq, Grok/xAI or OpenRouter), tap **Test AI connection**.
   Optional build-time defaults: put `GEMINI_API_KEY=...`, `GROQ_API_KEY=...`, `XAI_API_KEY=...`,
   `OPENROUTER_API_KEY=...` in `local.properties` (do not commit it). Keys typed in the app override them.
3. Enable Accessibility, "display over other apps", notification access (buttons in Settings).
4. Say "Hey Jarvis ..." or tap the mic.

## AI failover
Gemini -> Groq -> Grok -> OpenRouter (only providers with a key). A failing provider is skipped for a while.
Gemini also retries newer model names automatically.

## PC mode backend contract (edit `data/api/ApiConfig.kt` if your routes differ)
- `POST /api/command`, `GET /api/status`, `WS /ws/phone`; `Authorization: Bearer <token>` when a token is set.
