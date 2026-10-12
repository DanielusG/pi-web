# Plan: `pre_process_prompt` nel body TTS dell'app Android

Obiettivo: far inviare all'app `pi-web/android` il campo `pre_process_prompt`
del server TTS omnivoice.cpp, in modo che il testo (già sanificato da
`TtsText.sanitize`) possa essere riscritto dall'LLM del server prima della
sintesi.

Il server (`~/tmpGit/omnivoice.cpp/src/tts-server.h`, `POST /v1/audio/speech`)
accetta già `pre_process_prompt` (bool): attiva la rewrite LLM solo se è
`true` **e** il server è partito con le credenziali LLM
(`llm_model`/`llm_base_url`/`llm_api_key`); senza credenziali la flag è
ignorata silenziosamente. Nessun cambio sul server.

Scelta concordata: **toggle nelle impostazioni, default off** — nessun
cambio di comportamento finché l'utente non lo accende (con il toggle spento
il body resta identico a oggi).

## 1. `app/src/main/java/app/pimobile/data/Settings.kt`

- `ServerConfig`: nuovo campo `val ttsPreProcess: Boolean = false`
  (KDoc: invia `pre_process_prompt: true`; ignorato dal server senza
  credenziali LLM).
- `SettingsStore`:
  - nuovo `ttsPreProcessKey = booleanPreferencesKey("tts_pre_process")`
    (import di `booleanPreferencesKey`);
  - flow `config`: `ttsPreProcess = it[ttsPreProcessKey] ?: false`;
  - `save()`: `it[ttsPreProcessKey] = config.ttsPreProcess`.

Il campo viaggia da solo fino al player: `PiApp.kt` colleziona
`settings.config` e lo assegna a `api.config` e `tts.config` — nessun altro
mapping manuale da aggiornare.

## 2. `app/src/main/java/app/pimobile/data/TtsClient.kt`

- `requestFor()`: `if (config.ttsPreProcess) put("pre_process_prompt", true)`.
  Inviato solo quando attivo.

## 3. `app/src/main/java/app/pimobile/ui/settings/SettingsScreen.kt`

- `var ttsPreProcess by rememberSaveable { mutableStateOf(current.ttsPreProcess) }`.
- Sezione TTS, dopo la riga Model/Voice: `Row` con label "LLM preprocessing"
  + `Switch` material3 (nuovo import) + nota descrittiva (la rewrite richiede
  che il server sia avviato con le credenziali LLM).
- Bottone Connect: la costruzione `ServerConfig(...)` con argomenti
  posizionali passa a argomenti named, includendo
  `ttsPreProcess = ttsPreProcess`. Persistenza col Connect, come gli altri
  campi TTS.

## Verifica

1. `./gradlew assembleDebug` in `pi-web/android` compila (verifica anche del
   passaggio a argomenti named in SettingsScreen).
2. Installazione sul telefono e test end-to-end: passo di Daniele
   (toggle on → Listen con server omnivoice avviato con LLM).
