# Plan — Android: ripristinare scroll del chat e bozza del composer

**Data:** 2026-10-05 · **Revisione 2** (foldate le tue annotazioni fra parentesi quadre)
**Obiettivo:** se ero fermo a leggere un punto della conversazione (es. un messaggio lungo
dell'assistente), e torno alla lista delle sessioni o ne apro un'altra (anche da notifica),
tornando su quella conversazione trovo la stessa posizione — anche se nel frattempo l'agente
ha scritto altro, e anche se ho riaperto l'app. Vale anche per il testo non inviato nel composer.

**Cosa è cambiato nella revisione 2:** cap bozza portato a 500 000 char come tuo commento;
`indexAfterChange` eliminata (una sola funzione di risoluzione); `lastIndex` derivato da
`chatListKeys` (una fonte sola, marker A); tracking dell'ancora in una classe piccola in
`ChatAutoScroll.kt` (marker B, scelta motivata sotto); nessuna immagine allegata (marker C);
chat fresche = solo bozza (marker D); verifica con `assembleRelease` + e2e sul telefono via adb.

## Decisioni prese con Daniele

- **Persistenza:** solo in-process. Nessun DataStore. `RunWatcherService` è un foreground
  service che tiene vivo il processo mentre una sessione gira, quindi "riaprire l'app" ricade
  quasi sempre nel caso processo vivo. Se Android ammazza il processo resta il ripristino del
  bundle di Navigation (vedi "Aperta 1", con una proposta).
- **Ancora:** per **chiave** (entryId), non per indice. Gli indici si spostano con i prepend
  di "Load earlier messages" e con i reload della history; le chiavi no.
- **Fallback:** se il messaggio-su-cui-eri non c'è più (compaction, cambio di branch,
  messaggio cancellato) → **in fondo, come oggi**. Nessuna notice.
- **Bozze:** per `sessionId`, più `new:<cwd>` per le chat fresche prima del primo prompt.
  Cap **500 000 caratteri** — sopra quel valore un prompt sarebbe comunque più grande della
  context window del modello, quindi non è un caso reale. Nessuna immagine allegata salvata
  (base64 in memoria, pesanti; oggi si perdono al pop e resta così).
- **"Load earlier messages" che sposta la posizione:** sistemato in questo giro, con la stessa
  risoluzione chiave→indice del ripristino.
- **Quante voci:** 24, LRU per `seenAt`. Si salva **solo** se c'è qualcosa da dire (staccato o
  bozza non vuota); altrimenti la voce si rimuove → mappa piccola e nessun ripristino
  ridondante di "attaccato".
- **Cancellazione sessione:** la voce resta nella mappa (innocua, la LRU la taglia). Nessun
  intervento in `SessionsViewModel`.

## Cosa c'è già (e cosa lo distrugge)

| punto | dove | stato |
|---|---|---|
| Bozza del composer | `ChatScreen.kt:192` `rememberSaveable(TextFieldValue.Saver)` | sopravvive alla **push** (altra chat sopra, notifica) e alla rotazione; muore quando l'entry viene **poppata** (Back verso la lista, `/clone`, assistant trigger che poppa le chat precedenti) |
| Scroll | `ChatScreen.kt:190` `rememberLazyListState()` | saveable come la bozza, ma è **indice + offset** → fragile ai prepend |
| Auto-follow | `ChatScreen.kt:373` + `ChatAutoScroll.kt:50-124` | **il pezzo che distrugge tutto**: `BottomFollow` nasce sempre `attached = true` e `SideEffect { follow.onComposed(...) }` (`:378`) scrolla all'ultimo item appena il contenuto è cambiato. Lo stato "staccato" non è persistito da nessuna parte |
| Chiavi degli item | `ChatViewModel.parseContext:975` → `LoadedMessage(entryId ?: "m-N")`, `ChatItem.key = entryId` | stabili across reload. Unici volatili: i messaggi ottimistici/pending (`m-N`) |
| Hook per salvare | `ChatScreen.kt:278-287`: `repeatOnLifecycle(STARTED)` con `finally { vm.onBackground() }` | il `LocalLifecycleOwner` qui è il **NavBackStackEntry**, quindi il `finally` scatta sia su app in background sia su navigazione via sia su pop → punto naturale per salvare |
| Dove mettere lo store | `ChatViewModel` è scoped al NavBackStackEntry → **cancellato al pop** | serve uno store fuori dal VM: istanza su `PiApp` (come `api`/`settings`/`runStatus`) |

Niente `ChatViewModel`, niente server, niente API contract.

## Design

### 1. `android/app/src/main/java/app/pimobile/data/ChatResume.kt` (nuovo)

```kotlin
/** Dove avevo lasciato il chat e cosa c'era nel composer. */
data class ChatResume(
    val anchorKey: String?,   // entryId del primo messaggio *reale* visibile; null = fondo
    val anchorOffset: Int,    // offset dentro quell'item, px
    val detached: Boolean,    // false = follow attaccato → fondo
    val draft: String,
    val cursor: Int,
    val seenAt: Long,         // per la LRU
)
```

Pure logic (testabile, nessuna dipendenza Android):

- `chatListKeys(loading: Boolean, hasMore: Boolean, showEmpty: Boolean, itemKeys: List<String>,
  streaming: Boolean): List<String>` — le chiavi della LazyColumn in ordine (`loading`,
  `earlier`, `empty`, item, `streaming`). **Una sola fonte della verità**: `lastIndex` in
  `ChatScreen.kt:375` diventa `chatListKeys(...).size - 1` invece del `count { it }` a mano
  (marker A). Usata per indice→chiave (cattura) e chiave→indice (ripristino e re-anchor su
  prepend).
- `resolveAnchorIndex(keys: List<String>, anchorKey: String?): Int?` — indice della chiave,
  `null` se è sparita o `anchorKey` è `null` → il chiamante va in fondo. **Unica** funzione di
  risoluzione: serve sia al ripristino sia al re-anchor, quindi `indexAfterChange` della
  revisione 1 è eliminata (l'indice lo ricordiamo nel tracking, vedi sotto).
- `pruneResume(map: Map<String, ChatResume>, max: Int): Map<String, ChatResume>` — LRU per
  `seenAt`, taglia a `max`.
- `sessionKeyFor(id: String?, cwd: String): String` — `id` se non null/blank, else `"new:$cwd"`.
- `const val MAX_DRAFT_CHARS = 500_000`, `const val MAX_ENTRIES = 24`.

Store:

```kotlin
/** Posizione del chat e bozza, per sessione. Solo in-process. */
class ChatResumeStore {
    private val entries = ConcurrentHashMap<String, ChatResume>()
    fun peek(key: String): ChatResume? = entries[key]
    fun save(key: String, resume: ChatResume) { ... cap bozza + prune ... }
    fun drop(key: String) { entries.remove(key) }
}
```

`save` scappa via subito se `!resume.detached && resume.draft.isBlank()` → `drop(key)`: una
voce che dice "ero in fondo e non c'era bozza" non vale nulla e fa solo rumore.

**Scelta deliberata:** `ConcurrentHashMap` nudo, **non** un `StateFlow` come `RecentFiles`. Non
serve reattività (si legge una volta a composition time), e un flow osservato causerebbe
recomposizioni a ogni frame di scroll. Si legge dentro `remember(...)` → lettura non osservata,
va bene perché non deve reagire.

### 2. `PiApp.kt`

`val resumeStore = ChatResumeStore()` — istanza sul container manuale, non `object` globale,
così è sostituibile nei test. Accessibile dal chat come `vm.resume` (getter che passa
l'istanza, senza far sapere al `ChatViewModel` cosa ci sta dentro) o passata a `ChatScreen`
dalla nav: **si passa a `ChatScreen`**, perché è lì che vive il cablaggio e il `ChatViewModel`
non toccano nulla.

### 3. `ui/chat/ChatAutoScroll.kt`

Due cose, una dentro `BottomFollow` e una accanto.

**Gate del follow** (marker B risolto qui): `BottomFollow(listState, scope, reattachPx,
startAttached: Boolean = true)` → `attached` nasce da lì invece che fisso `true`;
`rememberBottomFollow(listState, startAttached = true)`. Senza questo il follow scatta in fondo
*prima* che il ripristino venga applicato e il resto del piano non servirebbe a nulla.

**`ViewportAnchor`** — classe piccola nello stesso file, che **non** conosce le chiavi:

```kotlin
/** Primo messaggio reale visibile: chiave, offset dentro l'item, indice al momento della lettura. */
class ViewportAnchor {
    var anchorKey: String? = null; private set
    var offsetPx: Int = 0; private set
    var indexAtCapture: Int = -1; private set
    fun update(keys: List<String>, info: LazyListLayoutInfo) { ... salta le pseudo-chiavi ... }
}
```

Perché qui e non dentro `BottomFollow` (la domanda del marker B): `BottomFollow` oggi riceve da
`ChatScreen` un array di valori **per confronto di identità** (`onComposed` riga 96-103:
`content.indices.any { content[it] !== composedContent[it] }`). Passargli anche la lista delle
chiavi significherebbe passarne una nuova istanza a ogni composizione → `changed` sempre vero →
`requestScrollToItem` a ogni composizione mentre è attaccato. È un trabocchetto reale. Tenere
l'ancora in una classe separata, aggiornata da un `snapshotFlow` su `layoutInfo` che scrive
campi **non** di stato (zero recomposizioni), lascia `BottomFollow` ignorante delle chiavi e
`ChatScreen` a ~3 righe di cablaggio.

### 4. `ui/chat/ChatScreen.kt` — il cablaggio (~30 righe)

1. **Chiavi in una sola lista:** `val listKeys = remember(state.items, state.loading,
   state.hasMore, showEmpty, state.streaming) { chatListKeys(...) }` → `lastIndex =
   listKeys.size - 1` (sostituisce il conteggio a mano a `:375`).
2. **Chiave dello store:** `val resumeKey = remember(state.sessionId, state.cwd) {
   sessionKeyFor(state.sessionId, state.cwd) }`. Quando una chat fresca prende il suo id (dopo
   il primo prompt) si migra: `save(id, peek("new:$cwd"))` + `drop("new:$cwd")`. Finché è fresca
   la voce contiene **solo la bozza** (`anchorKey = null`, `detached = false`) — marker D: è
   atteso così, una chat nuova ha al massimo un prompt nel composer.
3. **Gate del follow:** `val pending = remember(resumeKey) { resumeStore.peek(resumeKey) }` →
   `rememberBottomFollow(listState, startAttached = !(pending?.detached == true ||
   listState.firstVisibleItemIndex > 0))`. La seconda parte copre il caso kill del processo
   (vedi "Aperta 1").
4. **Ripristino scroll:** `LaunchedEffect(pending, listKeys)` → `resolveAnchorIndex(listKeys,
   pending?.anchorKey)` → `jumpTo(indice, pending.anchorOffset)`; `null` → `follow.attach()` +
   fondo (fallback scelto). Si applica solo quando gli item sono arrivati: durante `state.loading`
   la lista ha solo l'item `loading`.
5. **Ripristino bozza:** stessa forma del `LaunchedEffect(state.restoredDraft)` esistente (`:328`)
   — applica `pending.draft` **solo se `draft.text.isBlank()`**, cursore coerente alla lunghezza.
   Non calpesta una bozza già restaurata dal bundle di Navigation e non calpesta `restoredDraft`
   (edit-from-here), che resta prioritario.
6. **Tracking:** `LaunchedEffect(listKeys, listState)` con `snapshotFlow { listState.layoutInfo }`
   → `anchor.update(listKeys, info)`. Scrittura di campi plain, nessuna recomposizione.
7. **Re-anchor su prepend (fix "Load earlier"):** nello stesso `LaunchedEffect(state.items)`,
   quando `!follow.attached`: `resolveAnchorIndex(listKeys, anchor.anchorKey)` diverso da
   `anchor.indexAtCapture` → `jumpTo(indiceNuovo, anchor.offsetPx)`. Gli append dello streaming
   non cambiano l'indice dell'ancora → nessun effetto; da attaccato comanda il follow → nessun
   effetto. Vale anche quando l'item `earlier` scompare a fine caricamento (sposta l'indice di 1):
   la posizione viene tenuta, non saltata.
8. **Salvataggio:** un `LaunchedEffect(lifecycleOwner)` che specchia `:278-287`; nel `finally`
   (→ navigazione via, app in background, pop, distruzione) legge `anchor` + `follow.attached` +
   `draft` e chiama `resumeStore.save(resumeKey, ...)`. Se `attached` → `detached = false`,
   `anchorKey = null` (e `save` rimuove la voce se anche la bozza è vuota).
9. **Guardia pseudo-chiavi:** mai ancorarsi a `loading` / `earlier` / `empty` / `streaming` → si
   prende il messaggio reale successivo, o si tiene l'ancora precedente. Gestito dentro
   `ViewportAnchor.update`.

### 5. `app/src/test/java/app/pimobile/data/ChatResumeTest.kt`

- `chatListKeys`: ordine con le pseudo-items; `size - 1` combacia con `lastIndex` per gli stessi
  input (il commento vicino alla LazyColumn tiene il legame visibile).
- `resolveAnchorIndex`: chiave presente, chiave sparita → `null`, `anchorKey = null` → `null`.
- `ViewportAnchor.update` (testabile, `LazyListLayoutInfo` costruito a mano): ignora le pseudo
  chiavi, salta al messaggio reale successivo, indice registrato.
- `pruneResume` LRU a 24; `save` che rimuove la voce quando non c'è nulla da dire; cap bozza.
- migrazione `new:<cwd>` → id.

### 6. `android/README.md`

Una bullet nella sezione **Chat** (il README è la source of truth dell'app). `docs/agents/android.md`
parla solo delle route server fork-only → non lo tocco.

## Peso (perché lo scope in-process non è un problema)

Stime ART, compressed oops + compact strings (minSdk 26):

| parte | contenuto tipico | bytes |
|---|---|---|
| key della mappa | UUID 36 char, o `new:` + cwd (~30 char) | ~80 |
| `ChatResume` | 2 ref + 2 int + bool + long | ~40 |
| `anchorKey` | entryId, 8 hex char | ~40 |
| `draft` | 50–200 char | ~60–210 |
| `ConcurrentHashMap.Node` | + slot di tabella | ~40 |

**≈ 250–400 bytes per voce → 24 voci ≈ 6–10 KB.** Con cap bozza a 500 000 char il worst case
teorico è 24 × ~1 MB UTF-16 ≈ 24 MB, ma è solo teorico: un prompt da 500 000 char non arriva
nemmeno al modello, e la LRU a 24 voci lo limita. Il caso comune resta sotto i 10 KB.

## Verifica

1. `cd android && ./gradlew test` (baseline: 105 test su v0.9.3) → nuovi `ChatResumeTest`.
2. `./gradlew assembleRelease` (APK firmato con la debug key locale) + `adb install -r
   app/build/outputs/apk/release/app-release.apk`: **release, non debug**, così l'installazione
   non resetta l'app già sul telefono, e poi `adb shell cmd package bg-dexopt-job app.pimobile`
   per compilare subito (su OnePlus `cmd package compile` è rifiutato, il background job funziona).
3. **e2e sul telefono via adb** (dispositivo `9DZH4XHYXGZ95PJB` / DN2103 già connesso e
   autorizzato): `adb shell input swipe` / `input tap` per scrollare e toccare, `adb exec-out
   screencap` per confrontare le schermate, `adb logcat` per gli eventi. Serve pi-web in ascolto
   sulla LAN (`npm run start:lan` + `PI_WEB_PASSWORD`) — al momento :30141 non è in ascolto, lo
   avvio io come parte della verifica.
4. Casi manuali/e2e: (a) salgo su un messaggio lungo → Back alla lista → riapro la stessa
   sessione → stessa posizione; (b) stesso giro ma aprendo un'altra sessione da notifica e
   tornando con Back; (c) mentre sono staccato l'agente streaming scrive → la posizione non si
   muove; (d) "Load earlier messages" da staccato → la posizione non salta; (e) bozza non inviata
   → Back → riapro → bozza e cursore ripristinati; (f) compaction → apertura in fondo;
   (g) `adb shell am force-stop app.pimobile` mentre sono staccato → riaprire l'app (vedi
   "Aperta 1").

## Aperta (una sola)

**Kill del processo.** Oggi, dopo una kill, il bundle di Navigation restaura da sé bozza **e**
indice (`rememberSaveable`): se nel frattempo non è arrivato contenuto nuovo la posizione torna
già da sola. Con lo store vuoto, però, il follow nasce attaccato e la porta in fondo → si vede un
salto e poi il fondo.

- **(a)** lasciare così (= comportamento di oggi, semplice).
- **(b)** sopprimere il ripristino del bundle quando lo store è vuoto → apertura netta in fondo.
  Perde però il ripristino dell'indice, che oggi dopo una kill funziona.
- **(c) proposta:** `startAttached = !(pending?.detached == true ||
  listState.firstVisibleItemIndex > 0)` — se il bundle ha restaurato un indice diverso da 0 il
  follow parte staccato e la posizione resta dov'era. Nessuna nuova API, ~1 riga, e non si perde
  nulla dell'attuale comportamento post-kill. Se lo store ha una voce, quella vince (è più
  fresca e risolta per chiave). **Propendo (c): dimmi se confermi.**

## Implementato (2026-10-05, revisione 3)

Approvato (c) → codice scritto. Due differenze rispetto al design della revisione 2, entrambe
nello spirito del marker A (meno duplicazione, niente codice morto):

1. **`ViewportAnchor` non è più una classe separata**: la cattura dell'ancora, il gate del
   ripristino e la correzione dei prepend sono finiti dentro `BottomFollow`, che già veniva
   chiamato a ogni composizione con `SideEffect` e già conosce `attached`. `ChatScreen` passa
   solo `listKeys` in più nella chiamata esistente. `refreshAnchor()` legge `layoutInfo` lì, nel
   `SideEffect` (prima del prossimo measure pass), con scritture su campi plain → nessuna
   recomposizione.
2. **`positionCorrection` al posto di `indexAfterChange`** (già annunciato): pura, in
   `data/ChatResume.kt`, testata. E `viewportAnchor` prende una lista di `(chiave, offset)`
   invece di `LazyListLayoutInfo`, così il test non costruisce interfacce Compose.

Un bug trovato dai test: la prune dello store era girata al contrario (`sortedBy(seenAt).drop(24)`
teneva il più vecchio e buttava i 24 nuovi). Ora riusa `pruneResume` (la funzione pura testata) →
`theStoreKeepsOnlyTheMostRecentSessions` passa.

File toccati:

- `data/ChatResume.kt` (nuovo): `ChatResume`, `chatListKeys`, `resolveAnchorIndex`,
  `positionCorrection`, `viewportAnchor` rules via `isPseudoKey`, `sessionKeyFor`, `pruneResume`,
  `ChatResumeStore` (`peek`/`save`/`move`/`drop`, cap bozza, prune).
- `PiApp.kt`: `val resumeStore = ChatResumeStore()`.
- `ui/chat/ChatAutoScroll.kt`: `BottomFollow(..., resume)` con `attached` iniziale da lì,
  `onComposed(lastIndex, keys, vararg content)` con ripristino/segue/correzione,
  `anchorKey`/`anchorOffset`, `resumeState(draft, cursor)`, `viewportAnchor` pura,
  `rememberBottomFollow(listState, resume)`.
- `ui/chat/ChatScreen.kt`: `resumeStore` nella firma, `resumeKey`/`resume`, `follow` spostato
  vicino a `listState`, `listKeys` = unica fonte di `lastIndex`, salvataggio nel `finally` del
  lifecycle, migrazione `new:<cwd>` → id, bozza ripristinata per ultimo tra gli effetti che
  riempiono il composer.
- `MainActivity.kt`: passa `app.resumeStore`.
- Test: `data/ChatResumeTest.kt` (17), `ui/chat/ViewportAnchorTest.kt` (5) → `./gradlew
  testDebugUnitTest` 177 test, tutti verdi.
- `android/README.md`: due bullet nella sezione Chat (paging che tiene la posizione,
  ripristino di posizione + bozza con i limiti).

Rifiniture review (2026-10-07):
- `ChatAutoScroll.kt`: cattura continua dell'ancora via `snapshotFlow { viewportAnchor(...) }`
  quando `!follow.attached` (scroll idle su sessioni ferme); ripristino indice 0 con offset > 0.
- `ChatResume.kt`: `positionCorrection` basata su `anchorKey` (non su `previousKeys[firstVisible]`,
  che sull'earlier button a indice 0 restituiva null e faceva scivolare il contenuto);
  `BOTTOM_KEY` come pseudo-chiave per pareggiare il conteggio del LazyColumn con lo spacer.
- `ChatScreen.kt`: migrazione bozza `resumeStore.move` protetta da `startedFresh` (non tocca
  sessioni pre-esistenti).

Restano da fare: install sul telefono (`assembleRelease` + `adb install -r` + `bg-dexopt-job`) e
gli e2e (a–g) con pi-web in ascolto sulla LAN. Commenti nel codice in inglese, come il resto
dell'app.

## Da rifinire sul file (marker per Daniele)

- **E** — altro. I marker A–D della revisione 1 sono risolti e foldati nel testo.
