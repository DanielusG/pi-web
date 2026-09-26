# Pi Mobile: verifica sul telefono (2026-09-26)

Prima verifica pratica delle ottimizzazioni energetiche (`efficiency-plan.md`) su un telefono
vero, contro il pi-web della workstation. **Stato: prove completate.** La mattina A–E, I, J e
W; dopo una pausa (telefono scollegato), il pomeriggio H, F, G e la verifica della
correzione della voce. Restano le decisioni dell'admin: vedi "Da fare".

«Funziona» lo dichiara l'admin: qui ci sono misure, schermate e scostamenti.

## Ambiente

| | |
|---|---|
| Telefono | OnePlus Nord 2 5G (DN2103), Android 12, schermo 60/90 Hz, blocco con PIN |
| pi-web | workstation `192.168.1.155:30141`, build di `main` con `5be1e5b` e `97c8b9c`, password del server impostata |
| App | build della workstation (firma diversa da quella di questo PC), installata alle 11:08; l'APK contiene `toolUpdates=tail`, `?lite=1`, `/api/agent/running/events` e "Update pi-web" (verificato nei dex) |
| Modello | `qwen3.8-27b:general` via LiteLLM, thinking `xhigh`; lento, perché la GPU è condivisa con altri lavori (il prefill blocca il decoding) |
| Voce | TTS `http://192.168.1.56:8081` (kokoro, voce audio2), ASR `ws://192.168.1.56:8000/ws` |
| Progetto di prova | `/tmp/pi-test-android-20260926` sulla workstation, sessione `01a0dd30-d3e8-7059-b581-67620cc204fd` |

## Metodo

- **Rete:** proxy contatore su questo PC, raggiunto dal telefono via `adb reverse tcp:30177`.
  Durante le prove l'indirizzo del server nell'app era `http://127.0.0.1:30177`; alla fine è
  stato rimesso a `http://192.168.1.155:30141`.
- **Frame:** `dumpsys gfxinfo app.pimobile` azzerato a inizio finestra.
- **CPU:** `/proc/<pid>/stat` e `/proc/<pid>/task/*/stat`, leggibili dalla shell anche su
  un telefono non rootato.
- **Servizi e notifiche:** `dumpsys activity services` e `dumpsys notification --noredact`.
- **Permission gate:** l'estensione blocca ogni comando bash finché qualcuno non lo approva.
  Per le prove è stato messo in full throttle con `/permission f`, che vale per **tutte** le
  sessioni del server, poi rimesso su Default con `/permission d`.
- **Freezer di OxygenOS:** `logcat` di `OplusHansManager` (sotto, T5). Ogni `dumpsys gfxinfo`
  entra nel processo dell'app via binder e la scongela: le prove di background senza frame
  (F2, G) leggono solo servizi, notifiche e questo log.
- **Voce con la correzione:** build `app.pimobile.ttsfix` (stesso codice di questo PC più la
  correzione, con un package id diverso), installata accanto a quella della workstation, così
  non è servito disinstallarla. Inizio e fine dell'audio da `dumpsys audio`.

## Risultati

| Prova | Misurato | Atteso | Esito |
|---|---|---|---|
| A. Connessione | "Connect" accettato; nessuna schermata "Update pi-web" | come misurato | in linea |
| B. Lista con una run attiva (49 s) | 0 frame; CPU 0,15 s/min; 0 richieste | < 30 frame/min, CPU < 1 s/min | in linea |
| C. Chat con bash in esecuzione (38 s) | 126 frame/min (il cursore che lampeggia); CPU 3,6 s/min, di cui RenderThread 1,45; 7,4 KB/min | pallino fermo, frame solo dal cursore | in linea; vedi T3 |
| C. Stato della sessione | l'app chiede sempre `?lite=1`: 1,6 KB contro 13,6 KB della risposta completa (7 richieste nella run) | lite < 2 KB | in linea |
| W. Dialog "in attesa di input" (permission gate) | notifica "Waiting for your input." con l'app sulla lista; aprendo la chat la notifica sparisce e il dialog è al posto del campo di testo; dopo "Yes" la notifica torna "Working…" e il bash parte | come misurato | in linea |
| D. Scroll durante lo streaming | segue il fondo; scorrendo in su resta ferma (due schermate identiche a 4 s) e compare "Scroll to bottom"; toccandolo torna giù e segue | come misurato | in linea |
| D. Thinking e tool call in streaming (34 s) | 644 frame/min; CPU 21 s/min, di cui main thread 11,8; costo costante nel tempo | 10–13 fps | frame in linea; CPU vedi T2 |
| D2. Markdown in chat (34 s) | 749 frame/min (~13 fps); CPU 27 s/min, di cui main thread 16,7 (~28 ms per aggiornamento); stabile, 0,50 → 0,59 s di CPU per secondo di streaming | 10–13 fps, costo costante | frame in linea; CPU vedi T2 |
| E. Bash verboso, rete (stesso comando, due stream) | completo 8,65 MB (aggiornamenti fino a 42 KB l'uno), coda 0,93 MB (al massimo 1,8 KB): 9× meno | molte volte meno | in linea |
| E. Bash verboso, schermo | card chiusa con le ultime 8 righe che avanzano (75–82, poi 188–195), pallino fermo | come misurato | in linea; card aperta non provata sul telefono |
| J. Voce | la riproduzione di "OK" (1 s di audio) non finisce mai; vedi T1 | servizio chiuso entro ~5 s dalla fine dell'audio | **scostamento** |
| I. Chat ferma, campo non a fuoco (124 s) | 0 frame; CPU dell'app 0,11 s/min sul main thread, ma 3,7 s/min del thread ExoPlayer rimasto dalla prova J | frame vicini a 0 | frame in linea; CPU inquinata da T1 |
| H. Ritorno in chat durante lo streaming (HOME per 30 s) | lo stream della chat si chiude 10 s dopo HOME; nei 30 s resta aperto solo quello dello stato delle run, nessuna richiesta; al ritorno, dopo 1,5 s, la chat mostra il testo arrivato fino a quel momento in fondo, nessun "Reconnecting…", poi si completa senza duplicati | come misurato | in linea; al ritorno si riapre la tastiera, vedi Minori |
| F. Run bash in background, impostazioni di batteria predefinite | 15 s dopo HOME stream della chat chiuso, servizio attivo, "Working…"; **50 s dopo HOME OxygenOS congela l'app**; la run finisce ma la notifica resta "Working…" e il servizio resta attivo finché l'app non viene riaperta | "Task finished." e servizio chiuso ~10 s dopo la fine | **scostamento**, vedi T5 |
| F2. Come F, app nella whitelist `deviceidle` (esenzione standard) | congelata 30 s dopo HOME; notifica ferma su "Working…" | come sopra | **scostamento**: l'esenzione standard non basta |
| F2. Come F, "Allow background activity" acceso | nessun congelamento; a fine run "Task finished." subito, servizio chiuso e "Working…" tolta entro 10 s | come sopra | in linea |
| G. Schermo spento con una run bash, "Allow background activity" acceso | a fine run "Task finished." subito, servizio chiuso e "Working…" tolta entro 10 s; nessun congelamento nel log | come F | in linea. Con le impostazioni predefinite non ripetuta: F fallisce già a schermo acceso |
| J2. Voce, build della workstation (stessa risposta "OK") | dopo 88 s ancora PLAYING, posizione 84 s, `bufferedPosition` 89 478 485 ms; thread ExoPlayer ~4 s/min | — | bug riprodotto |
| J2. Voce, build con la correzione | "OK" (1,0 s): AudioTrack rilasciato 0,82 s dopo l'avvio. Frase di 7,28 s: avvio 13:19:31.119, rilascio 13:19:38.509 (7,39 s), servizio chiuso subito dopo | fine con l'audio | in linea: T1 corretto |

Note:
- Il modello, alla richiesta di una guida lunga, l'ha scritta in un file con il tool `write`
  invece che in chat; per questo D misura soprattutto il thinking e D2 usa un prompt che chiede
  di rispondere in chat.
- `uiautomator dump` fallisce mentre l'interfaccia si aggiorna di continuo (non trova mai uno
  stato "idle"): le fasi della run vanno rilevate dallo stream degli eventi, non dallo schermo.

## Problemi trovati

### T1. La voce non si ferma con un TTS che manda WAV in streaming (grave)

- **Sintomo:** dopo "Listen" su una risposta di due lettere, la riproduzione era ancora in
  corso dopo 3,5 minuti (posizione 210 s). Il servizio resta in primo piano e il thread
  ExoPlayer consuma 3,7 s/min di CPU con ~5 700 risvegli al minuto. Il tasto stop la ferma
  subito e il servizio si chiude.
- **Causa:** il server TTS, in streaming, non conosce la lunghezza e scrive 0xFFFFFFFF nei campi
  di dimensione del WAV: è la convenzione normale, e lo stream si chiude correttamente (1 s di
  audio per "OK", in 1,5 s). Il `WavExtractor` di Media3 1.11.1 tratta quel valore come
  sconosciuto solo negli RF64: nei WAV normali lo usa come durata, cioè 24,8 ore
  (`bufferedPosition` = 89 478 485 ms). ExoPlayer passa a `STATE_ENDED` solo quando la posizione
  raggiunge la durata, quindi dopo l'audio continua a "suonare" silenzio.
- **Perché non si vedeva:** il TTS finto dell'emulatore scriveva la lunghezza vera.
- **Correzione (verificata sul telefono, non ancora committata):**
  `media/StreamedWavExtractors.kt` avvolge il `WavExtractor` e dichiara la durata sconosciuta
  (`SeekMap.Unseekable(C.TIME_UNSET)`); `TtsPlaybackService` lo passa a
  `ProgressiveMediaSource.Factory`. La riproduzione finisce quando finisce lo stream. Non c'è
  niente da cambiare lato server. Sul telefono, con il TTS vero, l'audio viene suonato per
  intero (7,39 s di AudioTrack per 7,28 s di audio) e il servizio si chiude subito dopo (J2).
  Build release con R8: le classi anonime della correzione sopravvivono alla minificazione.

### T2. La lista delle sessioni è il costo di rete più grande

- **Misurato:** sulla workstation `GET /api/sessions` restituisce 2 276 sessioni di 144
  progetti: 7 MB, 1,5 MB compressi. `firstMessage` pesa 5,9 MB (fino a 413 KB per sessione);
  l'app ne mostra una riga.
- **Quando si scarica:** a ogni prompt, anche con la lista non visibile. In 35 minuti di prove:
  13 download, 19,5 MB, contro 2,4 MB di tutti gli stream delle chat e 0,11 MB delle 69
  richieste di stato. Non la scarica il `SessionsViewModel`, come scritto in una prima
  versione: `repeatOnLifecycle(STARTED)` lo ferma quando la chat lo copre. La scarica
  `RunWatcherService.loadSessions()` per ricavare i titoli delle notifiche: il servizio parte a
  ogni prompt (si chiude 10 s dopo la fine delle run) e alla prima sessione che non conosce
  scaricava la lista intera.
- **Proposta** (la prima parte è nella todo dell'admin):
  1. endpoint, o parametro dell'endpoint esistente, che restituisce le prime N sessioni per
     progetto con i metadati (totale delle sessioni del progetto, timestamp);
  2. "Show all" diventa "carica altre 10";
  3. `firstMessage` troncato lato server per l'app;
  4. il servizio delle notifiche chiede solo le sessioni che gli servono.
- **Implementata (da verificare sul telefono con una build della workstation):**
  - server: parametri opzionali di `GET /api/sessions` (`lib/session-list-page.ts`, descritti in
    `AGENTS.md`). `perProject=5` dà 5 sessioni per progetto con totale e ultima attività;
    `project=…&offset=…` la pagina successiva di un progetto; `ids=` solo quelle sessioni;
    `firstMessageChars=` taglia il primo messaggio dopo aver ricomposto un'espansione di skill
    nel suo `/skill:`, che si riconosce solo sul testo intero. Senza parametri la risposta è
    quella di prima, quindi il web non cambia.
  - app: la lista carica la prima pagina (200 caratteri di primo messaggio); "Show 10 more"
    chiede i 10 successivi e "Show less" torna a 5. Ogni ricarica rifà le pagine già aperte,
    con una richiesta per ogni progetto aperto. Una risposta senza `projects` viene dal server
    vecchio: messaggio "update pi-web". Il servizio delle notifiche chiede `ids=` con i soli id
    che non conosce.
  - sull'emulatore (120 sessioni finte più una espansione di skill e un primo messaggio da
    60 KB): lista 12,5 → 3,5 KB compressi; pagina successiva ~1 KB; titoli per le notifiche
    410 B a prompt invece della lista intera; lo `/skill:` e il taglio a 200 caratteri corretti;
    una ricarica dal server con due progetti aperti mantiene 15 e 25 righe. Schermate in
    `scratchpad/results_list/` della sessione.
- **Stima sui dati reali:**

  | Lista inviata all'app | Sessioni | Compressa |
  |---|---|---|
  | Oggi, lista completa | 2 276 | 1 527 KB |
  | Prime 5 per progetto | 407 | 635 KB |
  | Prime 5 per progetto, primo messaggio fino a 300 caratteri | 407 | 59 KB |
  | Prime 10 per progetto, primo messaggio fino a 300 caratteri | 610 | 84 KB |

### T3. Il cursore del campo di testo costa CPU per tutta la run

Dopo l'invio il campo di testo resta a fuoco e il cursore lampeggia: ~120 frame/min, e sul
telefono ~3,6 s/min di CPU (soprattutto RenderThread e driver Mali) finché la chat resta aperta.
Con il campo non a fuoco la chat ferma fa 0 frame. Possibile intervento, da decidere perché
cambia il comportamento: togliere il fuoco al campo quando si chiude la tastiera o dopo l'invio.

### T4. Costo dello streaming sul telefono

Frame in linea con l'emulatore (~11–13 fps) e costo costante nel tempo, quindi la
rielaborazione della sola coda funziona. Il livello però è alto: ~20–28 ms di main thread per
aggiornamento, contro i 4–7 dell'emulatore. I millisecondi non si confrontano direttamente
(sul telefono i core girano a frequenza bassa con carichi leggeri).

**Profilo sull'emulatore (build profilabile dell'albero corrente, AOT, 2026-09-26).**
- **Nessun fan-out** (piano, punto 4.5): con un contatore di ricomposizioni per item, in una
  copia di prova, durante lo streaming si ricompongono solo il messaggio in streaming e la
  radice di `ChatScreen`, ~9,5 volte al secondo. Gli altri item a schermo: 0. Si muovono solo
  all'invio del prompt (2–3 volte per run). Lo strong skipping del compilatore Compose di
  Kotlin 2.1 basta, e il punto 4.5 non serve.
- **simpleperf**, 15 s di testo in streaming, 5 800 campioni: RenderThread 44 % (gonfiato:
  sull'emulatore disegna in software), main thread 37 %, parse degli eventi su un thread di
  background 12 %, GC 3,6 % (era il 19 % prima delle ottimizzazioni). Main thread: 1 140 ms su
  ~141 aggiornamenti, ~8 ms ciascuno con il profiler attivo. Ripartizione:
  - frame (ricomposizione, misura, layout, disegno) 71 %. Dentro:
    - la coda Markdown del messaggio in streaming: ricomposizione ~15 %, layout del testo
      ~10 %, disegno del testo ~8 %;
    - radice di `ChatScreen` e barra in alto ~4 %;
    - il resto è lavoro fisso di Compose (snapshot, misura dell'albero, display list);
  - eventi SSE 13 %: il collector gira sul main thread per ogni evento (~50 al secondo), non
    per ogni flush. Dentro, `setStatus` copia lo stato e lo confronta anche quando lo stato
    non cambia (~1–2 %).
- **Nessun punto caldo singolo**, sull'emulatore. Le leve possibili, da decidere:
  1. accumulare gli eventi fuori dal main thread e passarli al main thread solo al flush:
     −10 % circa di main thread e da ~60 a ~10 risvegli al secondo;
  2. `setStatus` senza copia quando lo stato non cambia: banale, ~1–2 %;
  3. frequenza degli aggiornamenti: il costo scala con i flush al secondo (oggi 10);
  4. una coda più corta: i blocchi che crescono a lungo senza righe vuote (code fence,
     tabelle, liste "loose") si rifanno interi a ogni aggiornamento finché non si chiudono.
**Profilo sul telefono (stesso giorno, build profilabile `app.pimobile.prof` accanto a quella
della workstation, risposta in chat di ~1500 parole dal modello vero).** Due finestre di 20 s
durante il testo: la prima senza profiler (CPU del main thread, frame, core e frequenze ogni
100 ms), la seconda con `simpleperf record --app`, che funziona senza root.

| Build | Main thread | Frame | Eventi di testo | Per frame |
|---|---|---|---|---|
| Appena installata (`verify`: interprete e JIT) | 4 540 ms in 19,6 s | 266 | 472 | 17,1 ms |
| Compilata `speed-profile` | 4 610 ms in 19,7 s | 321 | 558 | 14,4 ms |

- **Il fattore ~4 rispetto all'emulatore è soprattutto la frequenza.** Il main thread gira per
  metà sui core piccoli (A55) e per metà sugli A78, ma con frequenze mediane di 500 MHz
  (piccoli), 700 MHz (A78) e 660 MHz (prime), su massimi di 2,0, 2,6 e 3,0 GHz. Il governor
  vede un carico leggero e intermittente e non alza il clock: i millisecondi si gonfiano, ma
  a bassa frequenza ogni ciclo costa meno energia. In cicli, un aggiornamento costa sul
  telefono quanto sull'emulatore o meno.
- **La compilazione vale ~15 %.** Le app installate con `adb install` restano in `verify`
  finché il job notturno non le compila (in carica e a riposo): quella della workstation,
  reinstallata alle 11:08, era ancora interpretata. Su OnePlus `cmd package compile` dalla
  shell è bloccato ("Failed to cpmpile !"), ma `cmd package bg-dexopt-job <pacchetto>`
  funziona e porta l'app a `speed-profile` subito.
- **Accessibilità ≈ 11 % del main thread.** Sul telefono sono attivi due servizi di
  accessibilità (autofill di Bitwarden e il mouse di KDE Connect). Con un servizio attivo,
  Compose a ogni aggiornamento ripercorre l'albero semantico della chat e manda gli eventi di
  modifica ai servizi, che a loro volta si svegliano (costo non misurato, è nei loro processi).
  Sull'emulatore non c'è. Per l'utente di un lettore di schermo è lavoro utile, per un
  autofill no; eventuali interventi (per esempio semantica ridotta sul messaggio in
  streaming) vanno decisi con cautela.
- **Resto del main thread (self time):** runtime di Compose 13,7 %, misura e layout 8,2 %,
  kernel 7,9 %, metodi ancora interpretati 5 %, codice dell'app 4,9 %, allocazioni 4,5 %,
  testo 3,3 %, `equals` dello stato 2,2 %, regex 1,8 %, libreria Markdown 1,4 %. Sul telefono
  gli stack di simpleperf non si ricostruiscono (né interpretati né compilati), quindi c'è
  solo il self time e non l'albero.
- **Conclusione:** nessun punto caldo nuovo. Le leve restano quelle dell'emulatore (eventi fuori
  dal main thread, meno copie dello stato, frequenza dei flush), più la compilazione dopo
  l'installazione, che è la più semplice.

### T5. OxygenOS congela l'app in background: la notifica di fine run non arriva (grave)

- **Sintomo (F):** con le impostazioni di batteria predefinite, una run avviata dall'app e
  lasciata in background finisce, ma la notifica resta "Working…" e il servizio resta attivo
  finché l'utente non riapre l'app. "Task finished." non arriva mai: è proprio il caso per cui
  la notifica esiste.
- **Causa:** il freezer di ColorOS/OxygenOS (`OplusHansManager`, "Hans"). In background l'app
  passa per R (20 s), M (10 s), poi F: il processo finisce nel cgroup `freezer:/frozen`
  (`/dev/freezer/frozen/freezer.state` = FROZEN; il freezer di Android invece dice
  `isFrozen=false`). Il servizio in primo piano `dataSync` non lo impedisce. Lo ritarda solo il
  traffico di rete ("cannot transition from R to M, importance=traffic"), che finisce quando si
  chiude lo stream della chat. Il server manda l'evento di fine (i byte arrivano al socket),
  ma l'app congelata non lo legge. Riaprendola, Hans la scongela ("reason: Activity"), l'app si
  riconnette, vede la run finita e 10 s dopo chiude servizio e notifica.
  ```
  13:03:08.349 notifyAppGoBackground, uid=10818
  13:03:28.358 pkg=app.pimobile cannot transition from R to M, importance=traffic
  13:03:48.365 pkg=app.pimobile can transition from R to M
  13:03:58.374 freeze uid: 10818 app.pimobile pids: [10155] scene: LcdOn
  ```
- **Cosa non basta:** l'esenzione standard dall'ottimizzazione della batteria (whitelist
  `deviceidle`, quella che chiede `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`): congelata
  30 s dopo HOME, notifica ferma (F2).
- **Cosa basta:** Impostazioni → App → Pi → Utilizzo batteria → "Allow background activity".
  L'interruttore mette l'app nella whitelist `deviceidle`, imposta `RUN_ANY_IN_BACKGROUND` su
  allow e la toglie dal freezer. Con l'interruttore acceso F è in linea (F2).
- **Costo di tenerla sveglia:** basso con il design attuale. Il servizio vive solo durante le
  run e lo stream dello stato manda solo un heartbeat ogni 30 s. Il freezer però oggi copre
  anche i bug: senza la correzione T1 un'app esente avrebbe tenuto ExoPlayer acceso in
  background all'infinito.
- **Altri telefoni:** realme UI è ColorOS, quindi il telefono realme ha probabilmente lo
  stesso freezer (non provato).
- **Proposte, da decidere:**
  1. Su OnePlus, OPPO e realme (`Build.MANUFACTURER`), alla prima run lasciata in background
     con l'app non esente, un avviso una tantum con un pulsante che apre la pagina dell'app
     nelle impostazioni (`ACTION_APPLICATION_DETAILS_SETTINGS`) e dice quale interruttore
     accendere. Come segnale basta `PowerManager.isIgnoringBatteryOptimizations()`: da solo
     non distingue l'esenzione standard, ma l'interruttore Oplus imposta anche quella.
  2. Senza impostazioni da toccare: push dal server (FCM, ad alta priorità, scongela l'app, ma
     serve Google Play Services più un progetto Firebase; oppure UnifiedPush). Molto più lavoro.
  3. Sconsigliato: heartbeat più frequenti per restare in "traffic". La soglia non è
     documentata e ogni heartbeat sveglia la radio.

### Minori

- All'apertura della chat `GET /api/sessions/:id` parte due volte nello stesso istante
  (10–13 KB ciascuna).
- **Corretto:** toccare la notifica di una sessione, o un deep link, mentre è aperta la chat di
  un'altra sessione non faceva niente. Con `launchSingleTop` Navigation riusava la chat in cima
  passandole il nuovo id, ma il suo ViewModel restava quello della sessione precedente.
  Ora una chat diversa si apre sopra quella corrente (Back torna indietro); la stessa sessione
  resta un no-op. Verificato sul telefono.
- **Corretto:** con un server senza lista paginata la lista diceva "Can't reach the server"
  sopra il messaggio "too old"; ora il titolo è "Update pi-web". Verificato sul telefono.
- Tornando all'app dopo HOME la tastiera si riapre da sola: il campo di testo è rimasto a
  fuoco dopo l'invio (stessa radice di T3).
- I titoli delle sessioni, nella lista e nelle notifiche, iniziano con la riga
  "⏱ 2026-09-26 12:11:45 (N/A)" che un'estensione aggiunge al primo messaggio: si vede
  l'ora invece del prompt.

## Da fare

1. **Correzione della voce (T1):** verificata sul telefono; da committare. Arriva sul telefono
   con la prossima build della workstation.
2. **Lista delle sessioni (T2):** implementata, verificata sull'emulatore; da provare sul
   telefono con una build della workstation (server e app insieme).
3. **Decisioni dell'admin:**
   - T5, freezer di OxygenOS: avviso con collegamento alle impostazioni, push, o niente;
   - T3, cursore e tastiera che si riapre;
   - T4, leve sullo streaming (eventi fuori dal main thread, `setStatus`, frequenza) e un
     profilo sul telefono.
4. **Opzionale, stima della batteria:** `dumpsys batterystats --reset` azzera le statistiche
   di batteria del telefono: chiedere prima.

Stato del telefono a fine prove: app della workstation sull'indirizzo
`http://192.168.1.155:30141`, `adb reverse` tolto, build di prova `app.pimobile.ttsfix`
disinstallata, permission gate su Default. "Allow background activity" per Pi era Off: è
stato acceso per F2 e G e va rimesso come vuole l'admin.

## Come riprendere

- **Permesso USB:** il dispositivo appartiene alla sessione grafica di `sevenk34`. A ogni nuovo
  collegamento serve
  `sudo setfacl -m u:angelus:rw /dev/bus/usb/$(lsusb | awk '/DN2103/ {print $2"/"substr($4,1,3)}')`.
  L'autorizzazione al debug USB è già salvata.
- **adb:** usare un server su una porta propria (`ANDROID_ADB_SERVER_PORT=5038`, seriale
  `9DZH4XHYXGZ95PJB`). Mai `adb kill-server` sulla 5037, che è condivisa con l'altro utente.
- **Proxy:** `TARGET_HOST=192.168.1.155 node count-proxy.mjs`, poi
  `adb reverse tcp:30177 tcp:30177` e, nelle impostazioni dell'app, server
  `http://127.0.0.1:30177` (il pulsante "Connect" è sotto, bisogna scorrere). Alla fine
  rimettere `http://192.168.1.155:30141` e `adb reverse --remove-all`.
- **Prompt:** P1 `Usa bash per eseguire sleep 60, poi rispondi solo OK`; P2B
  `Rispondi direttamente in chat senza usare strumenti. Scrivi una guida di circa 800 parole in
  markdown sul risparmio energetico nelle app Android, con titoli, elenchi puntati e una
  tabella`; P3 `Usa bash per stampare 400 righe, ognuna con il suo numero seguito da 100 lettere
  x, aspettando 50 millisecondi fra una riga e la successiva, poi rispondi solo FATTO`.
- **Attesa della fine run:** controllare solo la sessione di prova in
  `/api/agent/running`: l'admin lavora in parallelo su altre sessioni.
- **Background:** niente `dumpsys gfxinfo` durante le prove di background, perché scongela
  l'app. Il freezer si legge con `logcat -d | grep OplusHansManager`.
- **Una seconda build accanto a quella della workstation:** copia di `android/` con
  `applicationIdSuffix` nel `defaultConfig`, così non serve disinstallare l'app né perdere
  le sue impostazioni.
- Gli script della prova (`phone_test.py`, `probe.py`, `ui.py`, `count-proxy.mjs`,
  `tts_check.py`) stanno nello scratchpad della sessione, fuori dal repository.
