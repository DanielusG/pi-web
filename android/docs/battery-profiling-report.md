# Pi Mobile: profilazione del consumo batteria

**Stato: fase 2 completata.** Lo stesso protocollo del caso di prova (scenario E, approvato
dall'admin) è stato replicato su 16 scenari. Sui finding principali sono stati fatti A/B con build
modificate, e il report verifica sperimentalmente l'audit statico del 2026-09-20
(`PERF-REVIEW-android.md` e le sue verifiche).

Data: 2026-09-24 · Branch `feat/android-client` @ `43ac89b` · App: build release (R8).
Le build A/B esistono solo nello scratchpad; il repository non è stato modificato.

---

## 1. Sintesi

### Dove va la batteria

Il costo si concentra **quando l'utente guarda una sessione in esecuzione**, nella chat o nella
lista sessioni. Il background costa due ordini di grandezza meno in CPU, ma tiene vive rete e
sistema per tutta la durata della run.

| Situazione (una run in corso) | CPU dell'app | Frame | Rete |
|---|---:|---:|---:|
| Lista sessioni aperta, 1 sessione in esecuzione | **24,0 s/min** | 58 fps continui | 40 req/min |
| Chat, run agentica (tool ogni ~7 s) | **22,2 s/min** | 56 fps continui | 44 req/min, 171 KB/min |
| Chat, risposta lunga (16 KB) in streaming | **45,9 s/min** | 53–60 fps | 338 KB/min |
| App in background, run in corso | ~0,6 s/min | 0 | 38 req/min, 226 KB/min, **2 SSE** |
| Schermo spento, run in corso | ~0,6 s/min | 0 | come sopra, più 42 wakelock di sistema ogni 2,2 min |
| *Riferimento: lista sessioni ferma* | *0,05 s/min* | *0* | *20 req/min* |

24 s di CPU al minuto sono circa il 40 % di un core, **per tutta la durata della run**.

### Hotspot, in ordine di impatto

| # | Hotspot | Evidenza dinamica | Risparmio misurato (A/B) | Costo del fix |
|---|---|---|---|---|
| 1 | **Animazioni infinite durante le run** (pallini pulsanti, shimmer "Thinking", spinner indeterminati) | 56–60 fps per tutta la run, in lista e in chat | Lista: **CPU −98 %, frame 3 500 → 0/min**. Agent run: **frame −87 %**. Streaming: CPU −23 %, frame −38 % | piccolo |
| 2 | **Streaming: tutto il messaggio rifatto a ogni aggiornamento** (parse, normalizzazione, ricomposizione, layout, draw) | Main thread per aggiornamento ≈ 5 ms + 1,6 ms/KB: a 14 KB occupa il 56 % del main thread; GC 8 s/min | Solo flush a 100 ms: main thread −35 %, GC −40 %. La soluzione strutturale (blocchi memorizzati) non è misurata | medio |
| 3 | **Background con run: doppio SSE sulla stessa sessione, più reconcile e polling che continuano** | 2 stream paralleli (434 KB invece di ~220), 45 `GET /api/agent/:id`, 48 poll `/running` ogni 2,7 min | ~50 % del traffico in background | medio |
| 4 | **Output bash inviato come snapshot completo** | **3,8 MB** di SSE per un comando da 33 KB di output (24 s) | — (serve un lato server) | medio (server + app) |
| 5 | **Notifica di servizio ripubblicata ogni 3 s** | 42 wakelock di sistema (8,5 s) ogni 2,2 min a schermo spento | **42 → 1**; SystemUI −24 % | minimo |
| 6 | **Timer a 20 Hz perpetuo** nel ViewModel della chat | 1 200 risvegli/min del main thread finché il processo non viene congelato | **1 198 → 2/min** | minimo |
| 7 | **Servizio TTS in foreground fino a 10 min dopo ogni "Listen"** | 4 min dopo la fine dell'audio: ancora foreground, processo non congelabile, 1 200 risvegli/min | — | minimo |
| 8 | Doppio polling nella lista sessioni con una run attiva | 40 req/min di `/api/agent/running` (2 × 20) | — | minimo |
| 9 | Due frame per aggiornamento (auto-scroll) | ≈ 1,9 frame per aggiornamento senza animazioni | — | medio |

Da notare: **l'audit statico classificava come «OK» proprio il primo hotspot (P14)**, mentre dava
per non richiesto il gzip (P1), che invece il client chiede già su ogni richiesta. Il confronto completo è nella sezione 5.

---

## 2. Metodo

### Ambiente

- **Emulatore headless** `pimobile`: API 35, x86_64, profilo Pixel 7 a 1080×2400 e **60 Hz**,
  `-no-window -gpu swiftshader_indirect`, `adb root`. L'emulatore di un altro utente della
  macchina non è stato toccato.
- **App**: la stessa build release del repository (R8 attivo). In una copia fuori dal repository
  sono stati aggiunti solo `<profileable android:shell="true"/>` e `-dontobfuscate`, per avere nomi
  leggibili in simpleperf. Codice compilato AOT (`cmd package compile -m speed`).
- **Backend: pi-web reale** (`npm run dev`, stesso commit), con directory agente isolata
  (`PI_CODING_AGENT_DIR`).
  - **200 sessioni sintetiche**: 120 in 8 progetti, più 80 in un progetto "bench". Ogni scenario
    apre una sessione bench nuova, tramite lo stesso deep link delle notifiche.
- **Modello: finto LLM compatibile OpenAI**, a 50 chunk/s × 5 caratteri (≈ 250 caratteri/s).
  pi-web emette quindi gli **eventi SSE veri**. Modalità:

  | Modalità | Contenuto |
  |---|---|
  | `stream` | 1,2 KB di reasoning, poi 5,6 KB di markdown |
  | `streamxl` | 1,2 KB di reasoning, poi 16,2 KB di markdown |
  | `agent N` | cicli testo + tool `bash` (`sleep 6; ls`) |
  | `bashflood` | un comando che stampa 300 righe in 21 s |

- **Finto server TTS** compatibile OpenAI (`wav_stream`), per misurare "Listen" a schermo spento.
- **Proxy contatore** tra emulatore e pi-web: conta richieste, byte ed eventi SSE per endpoint,
  gli header `Accept-Encoding`/`Content-Encoding` e le connessioni SSE aperte in contemporanea.

### Metriche per finestra

| Metrica | Fonte | Robusta al carico dell'host? |
|---|---|---|
| CPU dell'app per thread | `/proc/<pid>/task/*/stat` | **no** (vedi sotto) |
| Risvegli (context switch volontari) per thread | `/proc/<pid>/task/*/status` | sì |
| Frame disegnati | `dumpsys gfxinfo` | sì |
| CPU di SurfaceFlinger, SystemUI, system_server | `/proc` | no |
| Richieste, byte, eventi SSE per endpoint | proxy | sì |
| Wakelock | `dumpsys batterystats` (batteria "scollegata") e `dumpsys power` ogni 2 s | sì |
| Stato del processo (adj, procState, freezer) | `dumpsys activity`, cgroup | sì |
| Timeline ogni 2 s: fps, CPU di main e RenderThread, eventi SSE | come sopra | — |
| Metodi caldi | `simpleperf record -g -e cpu-clock -f 2000` | — |

Le ricerche nella UI (dump di uiautomator) si fanno prima della finestra; dentro si usano solo
eventi `input`.

### Limiti: cosa si trasferisce al telefono

- **Si trasferiscono**: frame, risvegli, richieste, byte, wakelock e rapporti fra scenari.
- **CPU dell'host.**
  - L'host è un desktop x86: i ms assoluti sul telefono saranno di più.
  - **Durante la sessione il portatile host è passato a batteria** (21:28:19, profilo
    power-saver, CPU limitata a 2 GHz). Da quel momento i tempi di CPU del guest sono
    gonfiati di circa 1,5×.
  - Tutte le misure della matrice (sezione 3) e gli A/B della lista e dello streaming sono
    *prima* di quell'ora.
  - Gli A/B successivi si confrontano solo su metriche robuste, oppure con coppie A↔variante
    alternate nello stesso stato di alimentazione (sezione «Coppie alternate»).
- **GPU.** Il rendering passa dalla GPU emulata: il 49 % dei campioni di RenderThread è nel driver
  `goldfish_pipe`. Sul telefono quel lavoro va alla GPU: contano i frame, non i ms di
  RenderThread.
- **Suspend.** Il kernel dell'emulatore non va mai in suspend (`suspend_stats/success = 0`). A
  schermo spento si misura quindi ciò che l'app *prova* a fare.
  - Su un telefono i timer vengono rinviati durante il suspend.
  - I pacchetti di rete in arrivo invece lo interrompono. Durante una run arrivano eventi SSE
    in continuazione, quindi di fatto il dispositivo resta sveglio.
- **Refresh rate.** L'emulatore va a 60 Hz; sul Realme del test (120 Hz) le animazioni producono
  il doppio dei frame.
- **Rete.** È in loopback: niente stati di potenza della radio. Contano numero e dimensione
  degli scambi.

---

## 3. Risultati per scenario (build attuale, alimentazione collegata)

Medie su 2–3 ripetizioni; lo scarto fra ripetizioni è ±0–15 %. Tutti i valori sono al minuto.

| Scenario | CPU app (ms) | Risvegli main | Frame | SurfaceFlinger (ms) | Richieste | KB |
|---|---:|---:|---:|---:|---:|---:|
| A0 · app chiusa (rumore di fondo) | 0 | 0 | 0 | ~550 ¹ | 0 | 0 |
| B · lista sessioni, niente in esecuzione | 52 | 78 | 0 | 353 | 19,9 | 1,9 |
| **C · lista sessioni, 1 sessione in esecuzione** | **23 985** | **11 148** | **3 500** | **5 146** | 40,3 | 105 |
| D · chat aperta, ferma | 147 | **1 198** | 0 | 241 | 0 | 0 |
| E · chat, streaming 5,6 KB (fase 1) | 27 713 | 4 758 | 2 967 | 4 060 | 34,1 | 314 |
| **EXL · chat, streaming 16 KB** | **45 898** | 8 245 | 3 168 | 4 470 | 29,1 | 338 |
| **EAG · chat, run agentica** | **22 218** | 8 264 | 3 335 | 4 687 | 43,5 | 171 |
| EBF · chat, bash verboso | 20 305 | 7 044 | 2 746 | 3 918 | 39,5 | **7 101** |
| F · background ("previous app"), ferma | 148 | **1 198** | 0 | 249 | 0 | 0 |
| Fb · background, poi un'altra app (processo congelato) | 10 | 102 | 0 | 266 | 0 | 0 |
| G · background, run in corso ² | ~620 | 2 292 | 0 | 756 | 38,3 | 226 |
| H · schermo spento, run in corso ² | ~630 | 2 286 | 0 | 392 | 38,3 | 226 |
| H2 · come H, più doze forzato | ~610 | 2 314 | 0 | 392 | 38,4 | 227 |
| I · TTS "Listen", schermo spento (90 s di audio) | 556 | 1 418 | 20 | 70 | 0,4 | 0 |
| K · visualizzatore file aperto dalla chat | 142 | 1 206 | 1 | 216 | 1,5 | 0,1 |
| R · ritorno in chat dal background | — | — | — | — | 4 richieste | — |

¹ Misurato con l'host a batteria: SurfaceFlinger gonfiato come le altre CPU.
² Solo la parte in background, stimata dalla timeline togliendo i primi 10 s in primo piano.

In C, EAG ed EXL il frame rate resta a 56–60 fps **anche nelle fasi senza eventi**, cioè mentre
un tool è in esecuzione e il testo non cambia (colonna "fps while quiet" della timeline). È il
segno che i frame non vengono dai dati ma dalle animazioni (F1).

---

## 4. Findings

La confidenza è **alta** con misura diretta e A/B, **media** quando il dato è dedotto da misure
coerenti fra loro.

### F1: animazioni infinite, rendering continuo per tutta la run (alta)

**Evidenza e A/B.** Nella timeline di C ed EAG i frame restano a 57–60 fps anche nei tratti
senza eventi. Gli A/B con le animazioni rese statiche:

| Scenario | A (attuale) | Senza pallino/shimmer (V_anim) | + spinner del tool statico (V_anim2) |
|---|---:|---:|---:|
| C · lista + 1 run | 23 985 ms/min CPU, 3 500 frame/min, SF 5 146 | **382 ms/min, 0 frame, SF 316** (−98 %) | — |
| E · streaming 5,6 KB | 1 739 frame, 16,2 s CPU | 1 067 frame (−39 %), 12,6 s CPU (−22 %) | — |
| EAG · run agentica | 3 335 frame/min | 2 987 (−10 %: resta lo spinner) | **448 frame/min (−87 %)** ³ |

³ V_anim2 è stata misurata con l'host a batteria. La CPU scende comunque da 22,2 a 7,1 s/min
nonostante il clock più basso, quindi il risparmio reale è maggiore.

**Sorgenti, tutte a 60 fps (120 sul telefono) finché sono visibili.**

- `StatusDot(pulsing = true)` (`ui/theme/Components.kt:75`):
  - nell'header della chat per tutta la run (`ui/chat/ChatScreen.kt:778`);
  - nella lista sessioni: fino a 4 insieme per una sola sessione attiva, cioè titolo "Sessions",
    box "Active", riga attiva e riga del progetto (`ui/sessions/SessionsScreen.kt:228`, `:284`,
    `:342`, `:475`).
- `ShimmerText("Thinking")` (`ui/theme/Components.kt:49`): resta animato per **tutto** lo
  streaming del messaggio, anche dopo la fine del thinking, perché riceve
  `streaming = item.streaming` (`ui/chat/ChatItems.kt:314`, `:428`).
- `CircularProgressIndicator` indeterminato:
  - nella card del tool mentre il tool gira (`ui/chat/ChatItems.kt:536`);
  - nella barra dei subagent (`ui/chat/ChatScreen.kt:1050`);
  - nel footer mentre "Listen" carica (`ui/chat/ChatItems.kt:188`).

**Perché costa tanto.** Anche se si anima un solo pallino di 6 dp, ogni frame fa girare tutta
la pipeline: vsync, ricomposizione (`StatusDot` legge l'alpha in composizione), RenderThread,
GPU e composizione di SurfaceFlinger. Nella lista con una run attiva RenderThread lavora circa
330 ms/s. Su display LTPO un'animazione continua impedisce anche di scendere a refresh rate bassi.

**Direzione.**
- Indicatori statici, oppure animazioni rare e brevi (un impulso ogni 2–3 s, poi fermo).
- Lo shimmer va legato alla sola fase di thinking.
- Uno spinner lento o statico nella card del tool.

**Costo del fix**: piccolo, ma è una scelta visiva. L'audit notava già che toccare queste
animazioni richiede una decisione esplicita.

### F2: streaming, il costo per aggiornamento cresce con la lunghezza del messaggio (alta)

**Evidenza (EXL).** CPU del main thread nella timeline, a 20 aggiornamenti al secondo:

| Lunghezza del messaggio | Main thread | Per aggiornamento |
|---:|---:|---:|
| ~0 KB (thinking) | 116 ms/s | 5,8 ms |
| 2,8 KB | 226 ms/s | 11,3 ms |
| 5,7 KB | 316 ms/s | 15,8 ms |
| 8,6 KB | 404 ms/s | 20,2 ms |
| 11,4 KB | 512 ms/s | 25,6 ms |
| 14,3 KB | 563 ms/s | 28,2 ms |

- La crescita è lineare, circa 1,6 ms per KB, sulla CPU dell'host.
- Il costo totale per risposta cresce quindi O(n²). A 16 KB il GC arriva a 8 s/min
  (HeapTaskDaemon + ReferenceQueueDaemon).
- Per estrapolazione, oltre ~30 KB il costo per aggiornamento supera i 50 ms del flush: il main
  thread si satura e compare il jank. Nelle coppie a clock ridotto (sezione «Coppie alternate»)
  la saturazione si vede già a 16 KB.

**Dove va il tempo (simpleperf, streaming 5,6 KB).**
- **Ricomposizione ≈ 48 %** del main thread, di cui `MarkdownDocument` e i blocchi Markdown
  ≈ 26 %.
- **Disegno del testo ≈ 34 %**.
- **Misura e layout ≈ 15 %**.
- **Regex ≈ 7 %**: è `prepareMathMarkdown`, chiamato da `MarkdownDocument`.
- La gestione degli eventi SSE nel ViewModel pesa **meno dell'1 %**.

**Dove va il tempo (simpleperf, EXL fra 8 e 16 KB, 84 k campioni).**
- Il main thread fa il 61 % dei campioni dell'app, il GC il 19 %, RenderThread il 18 %.
- Nel main thread:
  - `MarkdownDocument` ≈ 50 %, di cui parse JetBrains ≈ 12 % e regex di
    `prepareMathMarkdown` ≈ 13 %;
  - disegno ≈ 33 %;
  - misura e layout ≈ 15 %.

In sintesi: parse e normalizzazione valgono circa un quarto. Il resto è **tutto il messaggio
ricomposto, rimisurato e ridisegnato a ogni flush**, perciò spostare solo il parse fuori dal
main thread (A2 dell'audit) non basta.

**Codice.**
- `remember(text) { prepareMathMarkdown(text); buildMarkdownTreeFromString(…) }`
  (`ui/markdown/Markdown.kt:186-188`), rieseguito a ogni flush da 50 ms
  (`ui/chat/ChatViewModel.kt:1304-1310`).
- Poi tutti i blocchi del messaggio vengono ricomposti e rimisurati, anche quelli già chiusi.

**Direzione**, in ordine di rapporto costo/beneficio:
1. Memorizzare i blocchi top-level già chiusi (stessa chiave, stesso nodo), così parse,
   ricomposizione e misura riguardano solo l'ultimo blocco.
2. Parse e normalizzazione fuori dal main thread.
3. Frequenza di aggiornamento adattiva sui messaggi lunghi. Il test a 100 ms (sezione «Coppie alternate»)
   toglie il 35 % del main thread e il 40 % del GC, ma lascia la crescita lineare: è un tampone,
   non la soluzione.

### F3: in background con una run attiva la stessa sessione riceve due stream, e il ViewModel continua a pollare (alta)

**Evidenza (G/H).** Dal log del proxy, per la stessa sessione:
- lo **stream della chat** (aperto a 1,8 s, 234 KB);
- **lo stream del watcher**, aperto a 14,5 s, cioè subito dopo HOME (211 KB).

Ogni delta del modello viene quindi scaricato e parsato **due volte**. Inoltre continuano, con
l'app non visibile:

| Richiesta | Frequenza in background |
|---|---|
| reconcile `GET /api/agent/:id` (per ogni turno e ogni 15 s) | 16,6/min, 57 KB/min |
| poll del watcher su `/running` | 17,7/min |
| rinnovo del lease | 1 ogni 30 s |

**Codice.**
- Quando la chat va in STOPPED, `AppVisibility.viewingSessionId` torna a `null`
  (`ui/chat/ChatScreen.kt:286-296`), quindi il watcher apre il suo SSE per quella sessione
  (`notify/RunWatcherService.kt:108-128`).
- L'`eventLoop` del ViewModel (`ui/chat/ChatViewModel.kt:1068`) vive in `viewModelScope` e non
  è legato al lifecycle: resta aperto finché la run non finisce, più 30 s.

**Impatto.** La CPU dell'app in background è bassa (~0,6 s/min). Il costo reale è la rete:
226 KB/min, ~2 200 eventi SSE/min in totale, 38 richieste/min per tutta la run. Su un telefono
a schermo spento sono pacchetti che interrompono di continuo il suspend e tengono la radio
attiva.

**Direzione.**
- Un solo consumatore per sessione. O la chat chiude il suo stream e ferma reconcile e polling
  in STOPPED, e al ritorno fa refresh/reconcile come già fa; oppure il watcher non apre lo
  stream finché la chat ha il suo aperto.

### F4: il watcher riceve ogni delta del modello solo per cercare i dialog, e lo parsa sul main thread (alta)

**Evidenza.** In C (run avviata altrove, lista aperta) il watcher apre l'SSE e riceve **622
eventi/min (95 KB/min)**, quasi tutti delta di testo. Gli servono solo gli
`extension_ui_request` bloccanti.

**Codice.**
- `scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`
  (`notify/RunWatcherService.kt:39`).
- `api.events(id).collect { … }`, dove il `mapNotNull` con `parseToJsonElement`
  (`data/PiApi.kt:91-94`) gira nel contesto del collector, cioè **sul main thread**.
- Lo stesso vale per il ViewModel della chat. Nel profilo `parseToJsonElement` compare sul main
  thread:
  - 0,6 % con i delta di testo;
  - ~3 % con gli snapshot bash di F5, dove raccolta e parse degli eventi valgono ~5 % del main
    thread.
- R8 fonde le classi lambda, quindi i nomi dei collector nel profilo non sono affidabili;
  `parseToJsonElement` invece sì.

**Direzione.**
- `flowOn(Dispatchers.Default)` per il parse.
- Lato server, un filtro sull'SSE (per esempio `?types=extension_ui_request,…`) o un endpoint
  leggero per lo stato "in attesa di input", così il watcher non scarica i delta.

### F5: output bash come snapshot completo, 3,8 MB per un comando da 33 KB (alta per la rete)

**Evidenza (EBF).**
- Un comando che stampa 300 righe in 21 s produce **273 eventi, 3,84 MB di SSE** (media
  14 KB per evento, fino a 50 KB). Cioè ~150 KB/s mentre il comando scrive.
- Il main thread passa da ~64 (solo animazioni) a ~91 ms/s: ogni snapshot viene parsato sul
  main thread (F4) e `lines().takeLast(8)` ricalcola sull'intero output
  (`ui/chat/ChatItems.kt:592`).
- Gli update non passano dal flush a 50 ms: ogni `tool_execution_update` fa subito un
  `_state.update` (`ui/chat/ChatViewModel.kt:1184-1190`).

**Dove va la CPU (simpleperf EBF).** RenderThread fa il 74 % dei campioni dell'app: sono i
frame della run, con lo spinner del tool di F1. Il main thread fa il 19 %; di questo, raccolta e
parse degli snapshot valgono ~5 % e `ToolCard` ~2 %. **Il costo degli snapshot sta nella rete,
non nella CPU dell'app.**

**Direzione.** È la fix B1 dell'audit (coda via `?toolUpdates=tail`, lato server). Lato app:
coalescing degli update nel flush e parse fuori dal main thread.

### F6: notifica di servizio ripubblicata ogni 3 s (alta)

**Evidenza.**
- In H (schermo spento) batterystats registra **42 acquisizioni del wakelock
  `NotificationManagerService:post:app.pimobile` (8,5 s) in 2,2 min**, cioè una ogni poll.
- Nella variante che pubblica solo quando il contenuto cambia (V_notify): **1 acquisizione
  (0,2 s)**, SystemUI da 278 a 212 ms/min (−24 %).

**Codice.**
- `notify(ONGOING_ID, Notifications.ongoing(…))` a ogni poll con sessioni attive
  (`notify/RunWatcherService.kt:136-142`).
- Ogni build crea anche un `PendingIntent` con un token nuovo (`System.nanoTime()`,
  `notify/Notifications.kt:55`), quindi il contenuto risulta sempre "cambiato".

**Direzione.** Confrontare titolo, testo e stato prima di ripubblicare. Per la notifica ongoing
il token univoco non serve.

### F7: timer a 20 Hz perpetuo nel ViewModel della chat (alta sul meccanismo, impatto medio-basso)

**Evidenza.**

| Scenario | Risvegli del main thread |
|---|---|
| D (chat ferma), F (background "previous app"), K (visualizzatore file aperto dalla chat) | **1 198–1 206/min** anche senza nulla da fare |
| V_flush (flush avviato dal primo delta, poi fermo) in D e F | **2/min** |
| V_flush in G (background con run) | da 2 292 a 1 189/min (−48 %) |

**Quando conta.** Il loop gira finché il processo non viene congelato:
- in primo piano;
- in background come "previous app" (adj 700, non congelata: F);
- **mentre un servizio in foreground è attivo** (watcher durante le run, TTS: in I i risvegli
  sono 1 418/min).

Appena l'utente apre un'altra app, il processo diventa cached e il freezer lo ferma (scenario Fb:
102 risvegli/min, dovuti al nostro stesso campionamento).

**Codice**: `viewModelScope.launch { streamFlushLoop() }` (`ui/chat/ChatViewModel.kt:248`) con
`while (true) { delay(50) … }`.

**Direzione**: flush one-shot avviato dal delta (è la variante misurata). È la fix più economica
del report.

### F8: doppio polling nella lista sessioni con una run attiva (alta sul meccanismo, impatto basso)

**Evidenza.** In C `/api/agent/running` arriva a 39,3 req/min: `SessionsViewModel.watch()` e
`RunWatcherService` fanno lo stesso GET ogni 3 s (`ui/sessions/SessionsViewModel.kt:77-78`,
`notify/RunWatcherService.kt:192`). Il payload è piccolo (~130 B), quindi il costo è nei
risvegli della rete più che nei byte.

### F9: due frame per ogni aggiornamento dello streaming (media)

**Evidenza.** Senza animazioni (build B, streaming 5,6 KB) restano 1 067 frame per circa 560
aggiornamenti.

**Codice.** Lo `snapshotFlow` su `canScrollForward` scatta dopo il layout del contenuto nuovo, e
`scrollToItem` ne produce un secondo (`ui/chat/ChatAutoScroll.kt:99-102`).

**Da confermare** con un A/B dedicato.

### F10: il servizio TTS resta in foreground fino a 10 minuti dopo ogni "Listen" (alta)

**Evidenza.**
- In I i wakelock sono corretti:
  - `ExoPlayer:WakeLockManager` e `AudioMix` per 86 s su 90 s di audio, poi rilasciati;
  - wifi lock per 4,5 s.
- Però, con un audio di 27 s e l'app in background, `TtsPlaybackService` è ancora
  `isForeground=true` con notifica `NO_CLEAR` a +20, +60, +120 e **+240 s**.
- Per tutto quel tempo il processo resta "percepibile" (adj 200, procState 4): il freezer non
  interviene e il main thread continua a svegliarsi 1 200–1 350 volte al minuto (timer di F7).
- A circa +11 minuti il servizio non è più in foreground: il timeout è scaduto circa 10 minuti
  dopo la fine dell'audio.
- Resta però legato al controller dell'app stessa (`c:app.pimobile`, procState 10, adj 700) e il
  main thread è ancora a 1 208 risvegli/min.
- Non è stato verificato se, in questo stato, l'apertura di un'altra app faccia congelare il
  processo.

**Causa (verificata nel bytecode di Media3 1.11.1).** `MediaSessionService` tiene il servizio in
foreground dopo la fine della riproduzione fino a
`DEFAULT_FOREGROUND_SERVICE_TIMEOUT_MS = 600000`, cioè 10 minuti. `stopPlayback()` →
`stopSelf()` (`service/TtsPlaybackService.kt`) non basta perché la sessione ha ancora controller
collegati.

**Direzione.**
- `setForegroundServiceTimeoutMs(…)` con pochi secondi, oppure `pauseAllPlayersAndStopSelf()` e
  rimozione della sessione a `STATE_ENDED`.
- Ogni "Listen" smette così di tenere sveglio il processo per 10 minuti.

### F11: minori

- **Ritorno in primo piano** (R): 4 richieste, cioè refresh della sessione, reconcile e **due
  volte** `/subagents`. `onForeground()` chiama `refresh()`, che chiama già
  `refreshSubagents()`, e poi lo richiama (`ui/chat/ChatViewModel.kt:465-472`).
- **Compressione**: `/api/sessions` e `/api/sessions/:id` arrivano gzip. Non sono compressi il
  reconcile `/api/agent/:id` (3,2 KB, la richiesta più frequente durante le run) e l'SSE.
- **Doze** (H2): nessuna differenza rispetto a H. Con un servizio in foreground l'app è esente
  e il traffico continua uguale.

### Coppie alternate A↔variante (host a batteria)

Queste misure sono state fatte dopo il passaggio dell'host a batteria, perciò i valori assoluti
sono gonfiati. Ogni coppia però gira in sequenza stretta (A, V, A, V) nello stesso stato, quindi
i rapporti sono validi. Scarto fra le due ripetizioni: ≤ 2 %.

| Scenario | Metrica | A (attuale) | Variante | Differenza |
|---|---|---:|---:|---:|
| E · streaming 5,6 KB | CPU dell'app (s) | 27,1 | 20,8 (animazioni statiche) | **−23 %** |
| | RenderThread (s) | 14,1 | 8,8 | −37 % |
| | SurfaceFlinger (ms/min) | 6 270 | 4 670 | −25 % |
| | frame | 1 734 | 1 066 | **−38 %** |
| EXL · streaming 16 KB | CPU dell'app (s) | 86,6 | 69,5 (flush a 100 ms) | −20 % |
| | main thread (s) | 41,4 | 27,1 | **−35 %** |
| | GC (s) | 14,4 | 8,7 | **−40 %** |
| | main thread nella parte finale | 795 ms/s | 530 ms/s | −33 % |

Con il flush a 50 ms e il clock ridotto, nella parte finale il main thread di A è occupato
all'80 %: i frame scendono a 42 fps e i frame lenti arrivano a 836, contro 455 della variante.
Su un telefono di fascia media la saturazione arriva prima.

---

## 5. Verifica sperimentale dell'audit statico (P1–P14)

| ID | Claim dell'audit | Verdetto della verifica statica | **Verdetto dinamico** |
|---|---|---|---|
| P1 | gzip assente nel client | reale, esagerato | **Falso.** OkHttp manda `Accept-Encoding: gzip` su tutte le richieste (verificato dal proxy) e decomprime in modo trasparente. Il server comprime solo 2 endpoint; i volumi grossi (SSE, reconcile) il server non li comprime. |
| P2 | snapshot bash 30 MB per chiamata | reale, esagerato (1–2,5 MB) | **Confermato**: 3,8 MB per 33 KB di output in 24 s (F5). Cresce con output × durata. |
| P3 | watcher: poll fisso a 3 s | reale, dimensionato bene | **Confermato** (17,7/min) e **peggiore del previsto**: SSE doppio con la chat in background (F3) e tutti i delta ricevuti per cercare i dialog (F4). |
| P4 | re-parse markdown a 20 Hz, hotspot n° 1 | reale, esagerato | **Confermato, non esagerato** per la pipeline completa: 28 ms di main thread per aggiornamento a 14 KB, crescita lineare (F2). Il parse da solo è una parte; il resto è ricomposizione, layout e draw dell'intero messaggio. |
| P5 | ricomposizione a 20 Hz con fan-out | reale, "il vero problema CPU" | **Coerente**: ricomposizione ≈ 48 % del main thread in streaming. Il fan-out sugli altri item non è stato isolato. |
| P6 | timer a 20 Hz perpetuo | reale, esagerato | **Confermato**: 1 200 risvegli/min; la fix li porta a 2/min (F7). Impatto limitato dal freezer. |
| P7 | rebuild dell'output tool + `takeLast(8)` | reale, conservativo | **Confermato** sul meccanismo (+27 ms/s di main thread in EBF). Pesa meno di F1/F2. |
| P8 | `refreshSession` a ogni foreground | reale | **Confermato**: 4 richieste per ritorno, `/subagents` duplicato (F11). Payload piccoli nel bench. |
| P9 | reconcile per turno + poll a 15 s | reale, sottostimato | **Confermato**: 18,4 req/min in chat, e **16,6 req/min anche in background** (F3). |
| P10 | doppio poll a 3 s | reale, trascurabile | **Confermato**: 39,3 req/min (F8). Costo basso. |
| P11 | `DateFormat` a ogni ricomposizione | micro-inefficienza | Non visibile fra i metodi caldi. |
| P12 | PDF senza cache | reale, basso | Non misurato (funzione rara). |
| P13 | `HtmlDocument` | falso positivo | Non misurato. |
| **P14** | **animazioni infinite: «OK»** | **OK confermata** | **Smentito: è l'hotspot n° 1** (F1). Da solo spiega fino al 98 % della CPU nella lista sessioni con una run attiva. La tesi "si invalida solo il cerchietto" non tiene: ogni frame costa l'intera pipeline, e gli spinner indeterminati nelle card dei tool non erano stati considerati. |
| nuovo | parse SSE sul main thread | segnalato dalla verifica | **Confermato** (F4); pesa di più con gli snapshot bash. |

---

## 6. Priorità consigliate

| Ordine | Intervento | Finding | Impatto atteso | Costo |
|---|---|---|---|---|
| 1 | Indicatori di stato senza animazione continua (pallini, shimmer, spinner del tool) | F1 | −87 / −98 % di frame e CPU nelle schermate con run visibile | piccolo, decisione visiva |
| 2 | Flush one-shot al posto del loop perpetuo | F7 | −99 % di risvegli da fermo | minimo |
| 3 | Notifica ongoing solo quando cambia | F6 | 42 → 1 wakelock di sistema per 2 min | minimo |
| 4 | Timeout del foreground TTS a pochi secondi (o stop esplicito a fine audio) | F10 | processo di nuovo congelabile subito dopo l'ascolto, invece che dopo 10 min | minimo |
| 5 | Un solo stream per sessione in background; reconcile e polling fermi quando la chat non è visibile | F3 | ~−50 % di traffico in background, −17 req/min | medio |
| 6 | Parse SSE fuori dal main thread; filtro degli eventi per il watcher (lato server) | F4 | CPU del main thread; −95 KB/min per sessione osservata | piccolo + medio |
| 7 | Blocchi Markdown chiusi memorizzati, e parse fuori dal main thread (un flush a 100 ms fa da tampone: main thread −35 %) | F2 | costo per aggiornamento costante invece che lineare | medio |
| 8 | Coda degli update bash (B1 dell'audit) | F5 | da MB a KB per comando verboso | medio (server + app) |
| 9 | Minori: poll doppio (F8), frame doppio (F9), `/subagents` doppio, gzip del reconcile | F8, F9, F11 | basso | minimo |

I primi quattro interventi sono piccoli e indipendenti. Tre sono misurati con A/B; il quarto è
verificato sul bytecode di Media3. Insieme coprono la maggior parte del consumo in primo piano
durante le run e dei risvegli da fermo.

---

## 7. Riproducibilità

Gli strumenti sono solo nello scratchpad della sessione
(`/tmp/claude-1001/-home-angelus-dani-pi-web/355687da-000f-4866-bcc5-6afbf039a326/scratchpad/`),
di proposito fuori dal repository come il mock backend. Lo scratchpad è temporaneo: se servono
per ripetere le misure dopo i fix, vanno archiviati altrove.

| File | Ruolo |
|---|---|
| `fake-llm.mjs` | finto LLM |
| `fake-tts.mjs` | finto server TTS |
| `count-proxy.mjs` | proxy contatore |
| `gen-sessions.mjs`, `gen-bench.mjs` | sessioni sintetiche |
| `probe.py` | snapshot e diff delle metriche, carico dell'host |
| `ui.py` | automazione con uiautomator |
| `run_matrix.py` | scenari, timeline, wakelock, stato del processo |
| `run_case.py`, `run_perf.py`, `run_perf2.py` | caso di prova e simpleperf |
| `summarize.py` | aggregazione |
| `run_ab*.sh` | batch A/B |

Le varianti A/B stanno in `android-v*/` (le righe cambiate sono marcate `AB-TEST`); i risultati
grezzi in JSON in `results*/` e `pairs/`.
