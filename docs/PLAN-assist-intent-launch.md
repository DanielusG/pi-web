# PLAN: lancio di Pi Mobile dai trigger assistente di sistema (intent ASSIST)

## Obiettivo

Dichiarare l'intent `android.intent.action.ASSIST` sull'activity principale così che Pi Mobile
appaia nel picker "App assistente" di Android (Impostazioni > App > App predefinite > App
assistente). Una volta impostato come assistente predefinito, i trigger di sistema —
**swipe-up dal bordo inferiore** (gesture navigation), **long-press tasto power** (se
abilitato) e **long-press home** (nav a 3 pulsanti) — aprono Pi Mobile in una
**nuova sessione** con il composer già pronto a scrivere.

Nota OnePlus: la Plus Key (tasto AI) ha opzioni fisse (Mind Space, camera, torcia, …) e non
si può mappare su app di terze parti — resta inutilizzabile per questo scopo. I trigger
sopra invece rispettano l'assistente predefinito.

Precedente: l'app ChatGPT di OpenAI usa esattamente questo meccanismo su Android recente
(diventa selezionabile come assistente predefinito e si apre dagli stessi trigger di Gemini).

## File da modificare

1. **`android/app/src/main/AndroidManifest.xml`**
   - Su `MainActivity`, affianco al filtro MAIN/LAUNCHER esistente:
     ```xml
     <intent-filter>
         <action android:name="android.intent.action.ASSIST" />
         <category android:name="android.intent.category.DEFAULT" />
     </intent-filter>
     ```

2. **`android/app/src/main/java/app/pimobile/MainActivity.kt`**
   - `handleIntent()`: riconoscere `Intent.ACTION_ASSIST` (sia in `onCreate` che in
     `onNewIntent`, come già avviene per i tap da notifica) → emettere una "assist request"
     su un nuovo `MutableStateFlow`.
   - `PiNavHost`: su assist request:
     - se esiste un `last_cwd` salvato → `nav.navigate(chatRoute(null, lastCwd, focus = true))`
       con `popUpTo(startDestination) { inclusive = false }`: la lista sessioni resta
       sotto la nuova chat (back torna alla lista, mai alle chat vecchie, che vengono
       poppate);
     - altrimenti → `sessions` con argomento `newSheet=true` (fallback: sheet nuova
       sessione), con `popUpTo(startDestination) { inclusive = true }`.
   - `chatRoute(null, cwd)` esiste già (id=null = nuova sessione).

3. **`android/app/src/main/java/app/pimobile/ui/chat/ChatViewModel.kt`**
   - Persistere l'ultimo cwd non vuoto: in init, se `cwd.isNotBlank()` →
     `SharedPreferences` chiave `last_cwd` (stesso file prefs usato per i prompt).

4. **`android/app/src/main/java/app/pimobile/ui/chat/ChatScreen.kt`**
   - Nuovo parametro `autoFocusComposer: Boolean = false`; se true (lancio assist) request
     focus sul composer col `focusRequester` già presente e mostra la tastiera.

5. **`android/app/src/main/java/app/pimobile/ui/sessions/SessionsScreen.kt`**
   - Nuovo parametro `startWithNewSheet: Boolean = false` → valore iniziale di `showNew`.

## Comportamenti

- Lancio da icona/launcher: invariato (ripristina l'ultimo stato).
- Lancio via ASSIST (app fredda o già aperta in foreground): sempre nuova sessione,
  composer focusato con tastiera, back torna alla lista sessioni (le chat precedenti
  vengono poppate).
- Impostare Pi Mobile come assistente predefinito fa sì che quei trigger non aprano più
  Gemini/Google Assistant (è l'obiettivo). Il risveglio vocale "Hey Google" non è
  toccato: è un servizio separato dall'assistente predefinito.
- Nessun nuovo permesso richiesto per gestire ACTION_ASSIST.

## Rischi / incognite

- **Principale**: non è garantito al 100% che un'app che dichiara solo il filtro ASSIST
  compaia nel picker "App assistente" su OxygenOS 16 (il ruolo è gestito da
  `RoleManager.ROLE_ASSISTANT`). ChatGPT ci riesce con una dichiarazione analoga, ma va
  verificato sul device reale. Se non compare: indagine sull'eligibilità del ruolo
  (eventuale `VoiceInteractionService` o metadata) — follow-up separato.
- Installazione fresca senza sessioni: `last_cwd` assente → fallback al sheet, coperto.

## Test

1. `cd android && ./gradlew assembleDebug`
2. Installare sul OnePlus: verificare che Pi Mobile compaia in
   Impostazioni > App > App predefinite > App assistente.
3. Impostarlo come default → corner swipe / long-press power: si apre una nuova sessione
   con tastiera; back non torna alle sessioni precedenti.
4. Lancio da icona: comportamento normale invariato.
