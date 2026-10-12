# Plan: formato `wav_stream` per tts-server + swap app Android

Obiettivo: far girare l'app `pi-web/android` (Media3 1.1.1, extractor MP3
hardcoded) contro `tts-server` di omnivoice.cpp **mantenendo lo streaming**
(primo audio in ~1-2s invece di aspettare la generazione intera).

Strategia (opzione B concordata): il server emette un WAV "streaming" —
header RIFF di 44 byte con dimensioni placeholder `0xFFFFFFFF` + chunk
s16le 24 kHz mono via chunked transfer. Verificato su `WavExtractor`
Media3 1.1.1: con data-chunk size `0xFFFFFFFF` e lunghezza input
sconosciuta, legge fino a EOF (no rf64, no clamp su inputLength).

## 1. Server — omnivoice.cpp

### `src/audio-io.h`
- Nuova funzione `wav_write_header_streaming(char *&p, int sr, int n_channels, int bits)`:
  identica ai 44 byte di `wav_write_header_basic` (RIFF/WAVE/fmt PCM/data)
  ma con `file_size = 0xFFFFFFFF` e `data_size = 0xFFFFFFFF`.
- Funzioni esistenti invariate.

### `src/tts-server.h` — `tts_handle_speech`
- Terzo formato accettato: `"wav_stream"` (aggiornato il messaggio di
  validazione: `response_format must be 'pcm', 'wav' or 'wav_stream'`).
- Nuova branch che riusa il chunked provider del percorso `pcm`:
  - Content-Type `audio/wav` (anziché `audio/pcm`)
  - prima write: header streaming di 44 byte, poi chunk s16le identici a `pcm`
  - disconnessione client → abort + free GPU: invariato
- Il provider lambda di `pcm` e `wav_stream` viene condiviso (differenze:
  content type + eventuale header prelude).

## 2. App — pi-web/android

### `app/src/main/java/app/pimobile/data/TtsClient.kt`
- `response_format`: `"mp3"` → `"wav_stream"`.
- `stream: true` resta (il server ignora i campi sconosciuti).

### `app/src/main/java/app/pimobile/service/TtsPlaybackService.kt`
- `MimeTypes.AUDIO_MPEG` → `MimeTypes.AUDIO_WAV` (WavExtractor presente in
  Media3 1.1.1; sample rate/canali/bits letti dal fmt chunk).

## 3. Verifica

1. Rebuild omnivoice su questa macchina (build CPU esistente).
2. `tts-server` locale + `curl -N` POST con `response_format: "wav_stream"`:
   - header: primi 44 byte = RIFF, size 0xFFFFFFFF, fmt PCM 16-bit mono 24000
   - `ffprobe` sul file salvato: codec pcm_s16le, 24000 Hz, mono, audio valido
3. `./gradlew assembleDebug` in `pi-web/android` → APK nuovo (installazione
   sul telefono: passo di Daniele).
4. Laptop: sync dei 2 file server + rebuild, avvio `tts-server` su 0.0.0.0
   per il test end-to-end con l'app.

## Aperture (da decidere)

1. **Compatibilità Kokoro**: con `wav_stream` hardcoded l'app non funziona
   più contro Kokoro (mp3). Alternativa: setting "TTS format"
   (mp3 / wav_stream) in SettingsScreen (~15 righe, preserva la scelta).
2. **Sync laptop**: scp diretto dei 2 file (veloce, working tree diverge da
   git) oppure commit + push + pull (pulito).
