# Plan: Android — deep link notifiche + pulizia notifiche stale (Vikunja 678 / #15)

## Bug

Tappare una notifica "finished"/"waiting" non apre la sessione di riferimento quando il
sistema ha distrutto l'activity tenendo vivo il processo (il foreground service
`RunWatcherService` pinna il processo; l'activity è la prima cosa buttata per memoria).
Il tap ricrea `MainActivity` con `savedInstanceState != null`, e `onCreate` chiama
`handleIntent(intent)` solo se `savedInstanceState == null` → l'extra della sessione va
perso, l'app ripristina la schermata precedente (lista sessioni).

Cold start (processo morto) e warm start (`onNewIntent`) funzionano; è rotta solo la
via di ricreazione. Il guard c'è dal primo commit (`2cd259b`): bug latente, non
regressione.

## Parte A — token one-shot per intent

Ogni PendingIntent di notifica porta un token univoco; `MainActivity` consuma un token
esattamente una volta, tenendone traccia attraverso la ricreazione dell'activity via
saved instance state.

### A1. `android/app/src/main/java/app/pimobile/notify/Notifications.kt`

- Nuova costante `EXTRA_TOKEN = "app.pimobile.extra.TOKEN"`.
- `openIntent()`: `putExtra(EXTRA_TOKEN, System.nanoTime().toString())` su ogni open
  intent. Il re-post della stessa sessione (finished → waiting, o una nuova run)
  aggiorna il token tramite `FLAG_UPDATE_CURRENT`, quindi ogni nuova notifica = nuovo
  token. La notifica ongoing (senza session id) riceve il token ma il consumer lo
  ignora (nessun session id → early return).

### A2. `android/app/src/main/java/app/pimobile/MainActivity.kt`

- Nuovo campo `private var consumedToken: String?` + chiave
  `KEY_CONSUMED_TOKEN` in un `companion object`.
- `onCreate`: ripristina `consumedToken` da `savedInstanceState` (prima di
  `handleIntent`), poi chiama **sempre**
  `handleIntent(intent, firstCreation = savedInstanceState == null)` (via il guard
  `if (savedInstanceState == null)`).
- `onNewIntent`: `handleIntent(intent, firstCreation = true)`.
- `handleIntent(intent, firstCreation)`:
  - `ACTION_ASSIST`: `if (!firstCreation) return` — comportamento invariato (nessuna
    fresh session spuria su ricreazione).
  - else: legge `EXTRA_SESSION_ID` (early return se assente, come oggi); legge
    `EXTRA_TOKEN`; skip se `token != null && token == consumedToken`; altrimenti
    `consumedToken = token` e setta `openRequests` come oggi. Token nullo (notifica
    pubblicata da una versione APK precedente ancora nella shade dopo un update) →
    processato senza tracking, come oggi.
- `onSaveInstanceState`: persiste `consumedToken` se non nullo.

La navigazione resta invariata: `LaunchedEffect(request)` →
`nav.navigate(chatRoute(...)) { launchSingleTop = true }`.

### Matrice dei comportamenti

| Scenario | Prima | Dopo |
|---|---|---|
| Cold start, tap notifica | apre la sessione | apre la sessione |
| Warm start (activity viva), tap | apre la sessione | apre la sessione |
| Activity distrutta (processo vivo), tap | **lista sessioni (bug)** | apre la sessione |
| Seconda notifica stessa sessione dopo ricreazione | — | apre la sessione (token nuovo) |
| Riapertura da "recenti" con intent vecchio (no nuovo tap) | — | nessuna re-navigazione (token consumato) |
| Trigger ASSIST, ricreazione | saltato | saltato (invariato) |

## Parte B — cancellazione notifiche stale quando la sessione è in schermo

Scenario: task finita → notifica postata → utente riapre l'app dalle recenti e si
ritrova già nella chat di quella sessione → la notifica è stale ma resta in shade e si
accumulano decine di "Task finished". (Le "waiting" sono già coperte dal watcher quando
navighi sulla sessione, ma solo finché il watcher è vivo e con lag di un poll; le
"finished" non vengono mai cancellate.)

Regola: una notifica one-shot (finished o waiting) è stale nel momento in cui la sua
sessione è in schermo.

### B1. `android/app/src/main/java/app/pimobile/notify/Notifications.kt`

- Estrae i tag in helper privati `tagFinished(id)` / `tagWaiting(id)` (oggi stringhe
  inline in `finished()`/`waitingForInput()`/`cancelWaiting()`); i tre call site li
  usano.
- Nuova funzione pubblica:
  ```kotlin
  /** Cancels both one-shot notifications of a session: stale once the session is on screen. */
  fun cancelForSession(context: Context, sessionId: String) {
      val manager = NotificationManagerCompat.from(context)
      manager.cancel(tagFinished(sessionId), FINISHED_ID)
      manager.cancel(tagWaiting(sessionId), FINISHED_ID)
  }
  ```
  Non tocca la notifica ongoing (foreground service, gestita dal service).

### B2. `android/app/src/main/java/app/pimobile/ui/chat/ChatScreen.kt`

- Nell'hook esistente che setta `AppVisibility.viewingSessionId` (il
  `LaunchedEffect(lifecycleOwner, state.sessionId)` con `repeatOnLifecycle(STARTED)`),
  subito dopo l'assegnazione:
  ```kotlin
  // Stale once the session is on screen: drop its pending one-shot notifications.
  Notifications.cancelForSession(context, id)
  ```
  `context` (LocalContext) è già nello scope. Si aggiunge l'import di `Notifications`.
- `repeatOnLifecycle(STARTED)` ri-entra a ogni (ri)avvio della schermata: apertura da
  recenti, navigazione sulla sessione, ritorno dal background → la cancellazione
  copre tutti i casi; cancel su notifica inesistente è no-op. Il watcher mantiene la
  sua logica di `cancelWaiting` (chiusura dialog, fine run): l'hook è complementare e
  copre anche il caso watcher già fermo.

### 3. `android/README.md` (una riga)

Sezione Notifications: nota che ogni tap è un deep link one-shot consumato esattamente
una volta (anche attraverso la ricreazione dell'activity) e che le notifiche
finished/waiting di una sessione vengono rimosse quando la sessione è in schermo.

## Verifica

- `./gradlew :app:compileDebugKotlin` (SDK in `~/Android/Sdk`).
- Manuale su device:
  1. Warm start: app in background con activity viva → tap → apre la sessione.
  2. Ricreazione: app in background abbastanza a lungo da far distruggere l'activity
     (processo vivo grazie al watcher) → tap → apre la sessione.
  3. Cold start: forza-chiudi l'app → tap → apre la sessione.
  4. Trigger ASSIST da app già aperta → fresh session (regression check).
  5. Stale: task finita con app in background → riapri dalle recenti restando nella
     chat di quella sessione → la notifica "Task finished" sparisce da sola.
  6. Stale (negativo): task finita con app in background → riapri dalle recenti su
     un'altra schermata (es. lista sessioni) → la notifica resta, tap → apre la
     sessione.
  7. La notifica ongoing ("pi is working") non viene mai toccata da questi hook.
- Niente unit test: suite JUnit pura senza Robolectric, la logica è framework-bound.

## Vikunja

Markare il task 678 come done dopo la verifica manuale.
