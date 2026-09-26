# Pi Mobile: piano per l'efficienza energetica

**Stato (2026-09-26): fasi 1–4 e 5.1, 5.3 implementate e misurate sull'emulatore**, nella forma
decisa dall'admin:
- tutti gli indicatori statici (pallino, "Thinking", spinner di tool e subagent);
- correzioni della fase 2 (timer dello streaming, notifica, servizio vocale, parse fuori dal
  main thread, `/subagents` doppio);
- background guidato dagli eventi con il nuovo `GET /api/agent/running/events`, senza fallback:
  con un pi-web che non lo ha l'app mostra "Update pi-web";
- streaming a 10 aggiornamenti al secondo che rielabora solo la coda del messaggio, con lo
  scroll al fondo nello stesso frame (4.4); anche l'output dei tool va a 10 aggiornamenti al
  secondo, e le sue ultime righe si cercano dalla fine;
- lato server, due parametri opzionali che solo l'app usa (il web non cambia, un pi-web vecchio
  li ignora): `GET /api/agent/[id]?lite=1` senza system prompt (5.3) e
  `GET /api/agent/[id]/events?toolUpdates=tail` con le ultime 16 righe negli update dei tool
  (5.1, dal piano `PLAN-android-bash-tail-updates.md`).

Restano: 3.3 (reconcile raggruppati, ora poco utile: ogni reconcile pesa ~0,7 KB), 5.2 (non
serve più: il watcher non apre stream per sessione) e la fase 6. Il 4.5 è stato misurato e non
serve (nessun fan-out).

**Verifica sul telefono, 2026-09-26: completata**, vedi `phone-verification-2026-09-26.md`.
Le ottimizzazioni reggono sul telefono. Ha trovato quattro punti nuovi:
- la voce non si ferma con un TTS che manda WAV in streaming (corretto, verificato sul
  telefono);
- su OxygenOS il freezer del sistema congela l'app in background e la notifica di fine run non
  arriva, a meno che l'utente non accenda "Allow background activity";
- la lista delle sessioni pesa 1,5 MB e il servizio delle notifiche la riscaricava a ogni
  prompt (ora paginata per progetto, e il servizio chiede solo i propri id: da verificare sul
  telefono);
- il cursore che lampeggia costa CPU per tutta la run.

Misure sull'emulatore (prima = baseline del report, dopo = build di `main` con le modifiche):

| Scenario | Prima | Dopo |
|---|---|---|
| Lista con una run attiva | 3 500 frame/min, CPU 24 s/min | 0 frame/min, CPU 0,06 s/min |
| Chat ferma, primo piano o background | ~1 200 risvegli/min | 7–9 risvegli/min |
| Run in background, a chat chiusa | 38 richieste/min, 226 KB/min | 0 richieste, solo il battito SSE |
| Schermo spento con run: notifiche pubblicate | 42 | 1 |
| Servizio vocale dopo la fine dell'audio | ~10 min | ~1 s |
| Chat, run agentica: frame | 3 335/min | 324/min |
| Chat, run agentica: CPU dell'app | 22,2 s/min | 3,1 s/min |
| Chat, run agentica: rete | 171 KB/min | 119 KB/min |
| Stato della sessione, su questo repository | 43 434 B a risposta, ~18/min | 708 B a risposta |
| Chat, streaming 21 KB: frame | 3 168/min | 736/min (~12,8 fps, di cui ~2–3 del cursore) |
| Chat, streaming 21 KB: costo per aggiornamento | 6 → 29 ms, lineare | 4,3 → 6,6 ms, quasi costante |
| Chat, bash verboso: frame | 2 746/min | 542/min |
| Chat, bash verboso: CPU dell'app | 20,3 s/min | 5,3 s/min |
| Chat, bash verboso: rete | 7 101 KB/min (3,7 MB per comando) | 1 018 KB/min |

Note sulle misure:
- Il cursore lampeggiante del campo di testo, che resta a fuoco dopo l'invio, aggiunge ~2–3
  frame al secondo durante tutta la run.
- Il bash verboso di prova produce solo 33 KB di output; con output più lunghi (fino ai 50 KB
  che il bash conserva) il guadagno della coda cresce.
- La CPU sull'emulatore è indicativa (host condiviso); frame, byte e richieste no.

Verifica visiva sull'emulatore fatta: "Thinking" statico, pallino del tool, markdown in
streaming, scroll che segue il fondo, si stacca quando l'utente scorre e riprende con il
pulsante, output bash con le ultime 8 righe (16 aperto) e risultato completo a fine comando.

Le misure dietro ogni punto sono in `battery-profiling-report.md` (qui citate come F1…F11). Il
piano integra, senza riscriverli, i piani già decisi in `docs/doc-temp-perf/docs/`, in
particolare `PLAN-android-bash-tail-updates.md`.

---

## 1. Obiettivi misurabili

Gli obiettivi si misurano con lo stesso harness del report, sugli stessi scenari, e con
conferma sul telefono reale a 120 Hz.

| Scenario | Oggi (misurato) | Obiettivo |
|---|---|---|
| Lista sessioni, 1 sessione in esecuzione | 3 500 frame/min, CPU 24 s/min | **≤ 240 frame/min** (≤ 4 fps); CPU < 1 s/min |
| Chat, run agentica | 3 335 frame/min | **≤ 450 frame/min**: frame solo quando cambia il contenuto |
| Chat, streaming 16 KB: main thread per aggiornamento | cresce fino a 28 ms a 14 KB | **costante, ≤ 8 ms** qualunque sia la lunghezza |
| Chat ferma, app in background | 1 200 risvegli/min del main thread | **≤ 30/min** |
| Background con run: stream SSE per sessione | 2 | **1** |
| Background con run: richieste | 38/min | **≤ 8/min** |
| Schermo spento con run: pubblicazioni della notifica | 42 in 2,2 min | **1 per cambio di stato** |
| Servizio TTS in foreground dopo la fine dell'audio | ~10 min | **≤ 5 s** |
| Comando bash verboso (33 KB di output) | 3,8 MB di SSE | **≤ 150 KB** |

---

## 2. Principi

1. **Un frame solo quando cambia qualcosa.** Niente animazioni infinite a frame rate pieno;
   quelle necessarie vanno a scatti (pochi aggiornamenti al secondo) o sono brevi.
2. **Lavoro proporzionale al cambiamento**, non alla dimensione del contenuto: lo streaming
   rielabora solo la coda del messaggio.
3. **Un solo consumatore per ogni flusso di dati; eventi invece di polling.**
4. **Quando la UI non è visibile, l'app fa solo ciò che serve alle notifiche.**
5. **Collaudo su un caso, poi replica.**
   - Ogni fase parte da un caso pilota completo, verificato dall'admin sul telefono.
   - Solo dopo si estende agli altri punti della fase.
   - «Funziona» lo dichiara l'admin guardando il risultato.

---

## 3. Fasi

| Fase | Contenuto | Impatto | Rischio | Stima |
|---|---|---|---|---|
| 0 | Preparazione: archivio dell'harness, baseline sul telefono | — | nessuno | ½ giorno |
| 1 | **Pilota: indicatore "in esecuzione"** (`StatusDot`) | molto alto (lista, header della chat) | basso, ma serve una decisione visiva | 1 giorno |
| 2 | Replica alle altre animazioni + correzioni rapide | alto | basso | 1–2 giorni |
| 3 | Background: un solo consumatore, polling solo dove serve | alto a schermo spento | medio | 2–3 giorni |
| 4 | Streaming Markdown a costo costante | alto sulle risposte lunghe | medio (spike prima) | 3–5 giorni |
| 5 | Lato server pi-web (opt-in, il web resta invariato) | alto sulla rete | medio (merge con upstream) | 2–3 giorni |
| 6 | Validazione finale e guardrail | — | nessuno | 1 giorno |

Le fasi 1–2 da sole coprono la maggior parte del consumo in primo piano durante le run e dei
risvegli da fermo, con modifiche piccole e già misurate con A/B.

---

### Fase 0: preparazione

- **0.1 Archiviare l'harness.**
  - Oggi finto LLM, finto TTS, proxy contatore, probe, runner e build profilabile stanno nello
    scratchpad della sessione, che è temporaneo.
  - Vanno copiati fuori dal repository (come il mock backend), per esempio in
    `~/pi-perf-harness/`.
  - Sono file `.mjs`, `.py` e `.sh`, quindi serve la tua conferma prima di salvarli.
- **0.2 Baseline sul telefono reale** (Realme, 120 Hz, collegato via USB).
  - Tre scenari chiave: lista con run attiva, chat con run agentica, schermo spento con run.
  - Metriche: `dumpsys gfxinfo` (frame), `dumpsys batterystats` e stima in mAh dal power
    profile reale del telefono.
  - È il metro con cui l'admin giudicherà ogni fase.

### Fase 1: caso pilota, l'indicatore "in esecuzione"

**Perché questo come pilota.** È il risparmio più grande misurato (F1: lista sessioni −98 % di
CPU, 3 500 → 0 frame/min). È un solo componente (`ui/theme/Components.kt:75`), usato in 5 punti.
Obbliga però a una decisione visiva, che è bene prendere una volta sola e poi replicare.

**Decisione richiesta all'admin**, fra tre stili:

| Stile | Frame al secondo mentre è visibile (stima) |
|---|---|
| a) statico, colore pieno | 0 (misurato) |
| b) pulsazione a scatti, 2–3 cambi al secondo | 2–3 |
| c) impulso breve (300 ms) ogni 3 s | ~6 a 60 Hz, ~12 a 120 Hz, in media |

**Implementazione.**
- Un solo orologio condiviso (`RunPulse`, fornito dal tema tramite `CompositionLocal`) che
  avanza a scatti. Tutti i pallini visibili cambiano nello stesso frame: 4 pallini costano
  come 1.
- L'alpha si legge nella fase di disegno (`Modifier.graphicsLayer { alpha = … }`), non in
  composizione. Ogni scatto costa così solo un ridisegno del layer, senza ricomposizione né
  layout.
- L'orologio si ferma da solo quando nessun pallino è visibile o l'app non è in STARTED.

**Verifica.**
- Emulatore: scenari C ed EAG, frame al minuto.
- Telefono: frame con `gfxinfo` e confronto visivo dell'admin.

**Accettazione.** Lista con run attiva ≤ 240 frame/min e OK visivo dell'admin.

### Fase 2: replica e correzioni rapide

Si replica lo stile scelto:

| # | Intervento | Dove | Finding | Evidenza |
|---|---|---|---|---|
| 2.1 | Shimmer "Thinking" animato solo mentre il thinking è il blocco attivo, con lo stesso stile a scatti | `ChatItems.kt:314`, `:428`; flag dall'assembler | F1 | A/B: streaming −38 % frame |
| 2.2 | Spinner dei tool e dei subagent a scatti, oppure icona fissa più secondi trascorsi (1 aggiornamento al secondo) | `ChatItems.kt:536`, `ChatScreen.kt:1050` | F1 | A/B: agent run −87 % frame |

Correzioni rapide, piccole e indipendenti:

| # | Intervento | Dove | Finding | Evidenza |
|---|---|---|---|---|
| 2.3 | Flush one-shot: parte al primo delta e si ferma quando non ce ne sono più | `ChatViewModel.kt:248`, `:1304` | F7 | A/B: 1 198 → 2 risvegli/min |
| 2.4 | Notifica di servizio pubblicata solo quando titolo, testo o stato cambiano; `PendingIntent` stabile (senza token `nanoTime`) | `RunWatcherService.kt:136-142`, `Notifications.kt:55` | F6 | A/B: 42 → 1 wakelock di sistema |
| 2.5 | Servizio TTS: `setForegroundServiceTimeoutMs` di pochi secondi, e controller rilasciato a fine audio ⁽¹⁾ | `TtsPlaybackService.kt`, `TtsPlayer.kt:111`, `:198` | F10 | verificato nel bytecode di Media3 |
| 2.6 | Parse degli eventi SSE fuori dal main thread (`flowOn(Dispatchers.Default)` dopo il `mapNotNull`) | `PiApi.kt:91-94` | F4 | parse sul main thread nel profilo |
| 2.7 | **Bug del watcher**: uno stream SSE caduto non viene mai riaperto. Il job completato resta nella mappa e `id !in sseJobs` è falso; va controllato `sseJobs[id]?.isActive != true` | `RunWatcherService.kt:117-118` | correttezza, prerequisito della fase 3 | codice |
| 2.8 | `/subagents` richiesto due volte a ogni ritorno in primo piano | `ChatViewModel.kt:465-472` | F11 | misurato (R) |

⁽¹⁾ Ipotesi da verificare nel punto 2.5.
- Il listener del servizio ferma il player su `STATE_ENDED` prima che il controller lo
  riceva, quindi `TtsPlayer.stop()` non verrebbe mai chiamato.
- A +11 minuti il controller dell'app era ancora collegato al servizio.

**Verifica.** Scenari D, F, H, I, EBF sull'emulatore; I (TTS) anche sul telefono.

### Fase 3: background, un solo consumatore

**Pilota.** Una sessione in esecuzione con l'app in background e a schermo spento (scenari
G/H).

**3.1 La chat smette di consumare quando non è visibile.**
- Nuovo `onBackground()` nel ViewModel, chiamato dal `finally` del `repeatOnLifecycle` già
  presente in `ChatScreen.kt:277`. Chiude lo stream eventi, ferma poll, lease e reconcile in
  attesa.
- Il ritorno è già coperto: `onForeground()` fa refresh e reconcile, e il server rimanda lo
  snapshot del messaggio in streaming alla riconnessione (`message_start`), quindi la bolla
  riappare.
- Risultato atteso: da 2 stream a 1, e −16,6 richieste/min (F3).

**3.2 Watcher guidato dagli eventi** (design A4 dell'audit, fattibilità verificata).
- La fine della run arriva da `prompt_done`/`agent_settled` sul suo stream, che dopo la 3.1 è
  l'unico.
- `/api/agent/running` resta solo come scoperta delle sessioni avviate altrove, ogni 15–30 s
  invece di ogni 3 s.
- Serve la riconnessione con backoff (prima il bug 2.7).

**3.3 Reconcile in primo piano con parsimonia.**
- Reconcile di fine turno raggruppati: al massimo uno ogni 3 s, con quello in coda eseguito
  alla fine.
- Il poll a 15 s si salta mentre l'SSE è `Live` e resta solo in `Reconnecting`.
- Misurato oggi: 18,4 req/min in una run agentica.

**3.4 Lista sessioni.**
- Con il watcher attivo, la lista usa il suo insieme di sessioni in esecuzione (uno
  `StateFlow` in `PiApp`) invece di fare un secondo poll ogni 3 s (F8).

**Rischi.**
- Eventi persi durante il background: coperti dal refresh al ritorno, come oggi dopo una
  disconnessione.
- Latenza della notifica di fine run: da ≤ 3 s a ~1 RTT.

**Accettazione.** Scenario G: 1 stream per sessione, ≤ 8 richieste/min, notifiche di fine
run e "in attesa di input" invariate. Da verificare sul telefono.

### Fase 4: streaming Markdown a costo costante

**Spike (1 giorno), da fare prima di impegnarsi.** Prototipo di 4.1 misurato sullo scenario
EXL. Criterio: costo per aggiornamento indipendente dalla lunghezza.

**4.1 Prefisso stabile + coda.**
- Il testo in streaming si divide all'ultimo confine sicuro fra blocchi: una riga vuota fuori
  da code fence, blocchi `$$` e HTML aperti.
- I blocchi del prefisso vengono elaborati una volta sola, quando il confine avanza, e resi
  come componenti a parametri stabili, ciascuno con il suo `graphicsLayer`. Compose così li
  salta in composizione, riusa la loro misura e la loro display list.
- A ogni aggiornamento si rielaborano e ridisegnano solo la coda (tipicamente < 1 KB) e il suo
  parse.
- Oggi parse e normalizzazione valgono ~25 % del main thread; il resto (≈ 75 %) è ricomporre,
  rimisurare e ridisegnare i blocchi chiusi. È questa la parte che 4.1 elimina.

**4.2 Messaggio finale identico a oggi.** A fine streaming il messaggio viene reso una volta
per intero con il percorso attuale.
- Durante lo streaming possono differire solo costrutti a cavallo di blocchi, cioè liste
  "larghe" separate da righe vuote e definizioni di link di riferimento.
- Serve la decisione dell'admin se è accettabile.

**4.3 Flush adattivo come rete di sicurezza.**
- 50 ms sotto ~4 KB, 100 ms oltre.
- A/B: main thread −35 %, GC −40 % a 16 KB.
- Utile anche prima che 4.1 sia pronto.

**4.4 Un frame per aggiornamento invece di due** (F9).
- Lo scroll al fondo va applicato nello stesso passaggio di misura con
  `LazyListState.requestScrollToItem(…)` (presente nella foundation 1.9.4 usata).
- Oggi lo fa lo `snapshotFlow` dopo il layout (`ChatAutoScroll.kt:99-102`).
- Obiettivo: 1,0 frame per aggiornamento, verificato con un A/B.

**4.5 Stabilità dei parametri degli item** (P5 dell'audit): callback con `remember`, così i
messaggi non in streaming non si ricompongono a ogni flush.
- Prima si misura con il composition tracing quanti item si ricompongono davvero; si fa solo
  se il guadagno è significativo.
- **Misurato il 2026-09-26: non serve.** Con un contatore per item, durante lo streaming si
  ricompongono solo il messaggio in streaming e la radice della chat; gli altri item 0 volte
  (strong skipping del compilatore Compose). Dettagli in `phone-verification-2026-09-26.md`, T4.

**Accettazione.** EXL con costo per aggiornamento ≤ 8 ms costanti e GC −70 %; messaggio
finale identico a oggi.

### Fase 5: lato server pi-web (opt-in, il web client resta invariato)

Tutte le modifiche sono parametri opzionali che solo l'app usa. Vanno documentate in
`AGENTS.md` come deviazione del fork, per non perderle nei merge con upstream.

| # | Intervento | Stato | Impatto |
|---|---|---|---|
| 5.1 | **Coda degli update bash** (`?toolUpdates=tail`) | piano già deciso: `PLAN-android-bash-tail-updates.md`, decisioni prese il 2026-09-21, non implementato | 3,8 MB → decine di KB per comando verboso (F5) |
| 5.2 | **Filtro eventi sull'SSE** (`?events=extension_ui_request,extension_ui_closed,prompt_done,agent_settled`), nello stesso punto di trasformazione di 5.1 (`lib/agent-event-wire.ts`) | nuovo | il watcher non riceve più i delta: −95 KB/min per sessione osservata (F4) |
| 5.3 | **Reconcile snello e compresso**: `GET /api/agent/[id]?lite=1` senza `systemPrompt`, passato dall'helper gzip `lib/json-response.ts` | nuovo | secondo l'audit, ~22 KB per risposta nel deployment reale, per lo più non usati; 18 risposte/min durante le run |
| 5.4 | (opzionale) SSE globale dell'insieme delle sessioni in esecuzione | nuovo | elimina anche il poll di scoperta della fase 3.2 |

**Verifica.** Test in `lib/agent-event-wire.test.mjs`, `tsc --noEmit`, `npm run lint`, più lo
scenario EBF e il conteggio dei byte dal proxy.

### Fase 6: validazione finale e guardrail

- Si rilancia l'intera matrice (16 scenari) sull'emulatore, più i 3 scenari chiave sul
  telefono. Si confronta con la baseline della fase 0 e si aggiorna il report.
- **Guardrail leggeri**:
  - un controllo in review (o uno script) che segnali ogni nuovo `rememberInfiniteTransition` o
    `CircularProgressIndicator` indeterminato fuori dal componente condiviso;
  - l'harness archiviato diventa il test di regressione energetica da lanciare prima delle
    release.

---

## 4. Impatto atteso

| Scenario | Oggi | Dopo le fasi 1–2 | Dopo le fasi 3–5 |
|---|---|---|---|
| Lista sessioni con run attiva | 3 500 frame/min | ≤ 240 (misurato: 0 se statico) | invariato; 1 solo poll |
| Chat, run agentica | 3 335 frame/min | ~450 (misurato con indicatori statici) | invariato |
| Chat, streaming 16 KB (main thread) | 20,5 s/min | ~−10 % (meno frame da animazioni) | costante, circa −70 % (stima dallo spike) |
| Chat ferma, background | 1 200 risvegli/min | ~10–20 (misurato) | invariato |
| Background con run: rete | 38 req/min, 226 KB/min, 2 SSE | invariato | ≤ 8 req/min, 1 SSE, ~−50 % KB; con 5.2 molto meno |
| Schermo spento con run: notifiche | 42 wakelock / 2,2 min | 1 per cambio di stato (misurato) | invariato |
| Dopo un "Listen" | processo non congelabile per ~10 min | ≤ 5 s | invariato |
| Bash verboso | 3,8 MB | invariato | decine di KB |

I valori delle fasi 1–2 vengono dagli A/B del report. Quelli delle fasi 3–5 sono stime da
confermare con i piloti.

---

## 5. Decisioni richieste all'admin

1. **Stile degli indicatori** (fase 1): statico, a scatti o impulso breve.
2. **Comportamento in background** (fase 3): OK che la chat chiuda il suo stream quando non è
   visibile e si risincronizzi al ritorno?
3. **Modifiche lato server** (fase 5): OK a parametri opt-in in pi-web, documentati come
   deviazione del fork?
4. **Streaming** (fase 4): accettabile che, solo durante lo streaming, liste "larghe" e link di
   riferimento possano apparire diversi fino alla fine del messaggio?
5. **Archivio dell'harness** (fase 0): dove salvarlo (file non `.md`, serve la tua conferma).

---

## 6. Rischi e contromisure

| Rischio | Contromisura |
|---|---|
| Compose non salta i blocchi chiusi come previsto (fase 4) | lo spike misura prima di impegnarsi; il flush adattivo (4.3) resta come ripiego già misurato |
| Il watcher come unico consumatore perde eventi | prima il bug 2.7 e la riconnessione con backoff; refresh al ritorno come rete di sicurezza |
| Conflitti nei merge con upstream per le modifiche server | modifiche piccole, opt-in, concentrate in `agent-event-wire.ts`, documentate in `AGENTS.md` |
| L'emulatore non rappresenta il telefono (GPU software, niente suspend, host a batteria) | ogni fase si chiude con una verifica sul telefono; per la CPU sull'emulatore si confrontano solo coppie A↔B alternate |
| Regressioni visive | ogni pilota passa dal controllo visivo dell'admin prima della replica |
