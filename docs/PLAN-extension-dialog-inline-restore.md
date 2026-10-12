# Plan — Extension dialog: ripristino nello slot della input bar

**Data**: 2026-09-07
**Stato**: approvato da Daniele (2026-09-07)

## Contesto

Il merge upstream v0.9.0 (`3669132`) ha sostituito il nostro layout pre-merge — dialog
renderizzato **inline nello slot della input bar**, al suo posto — con un overlay assoluto
sull'area messaggi (card espansa ancorata in basso, pill compressa ancorata **in alto**,
input bar sempre visibile e usabile sotto). Daniele non vuole la pill compressa in alto:
si ripristina il comportamento pre-merge **completo**, in entrambi gli stati.

Decisione esplicita (trade-off accettato): si perdono i miglioramenti upstream dentro il
dialog — countdown expiry a display (l'auto-close lato server resta, `expiresAt` esisteva
già pre-merge), nav da tastiera sulle select, card da 760px, guard `isComposing`, input
usabile durante il dialog.

## Cambiamenti

### 1. `components/ChatWindow.tsx`

- Ripristino del componente `ExtensionDialog` pre-merge (da `3669132^1`):
  - card espansa `maxHeight: min(45vh, 360px)`, borderRadius 10;
  - pill compressa con dot pulsante, border accent-mix, `bg-panel`;
  - reset `value`/`collapsed` via `useEffect` su `[request]` (sicuro: `extensionDialog`
    è un `useState` in `useAgentSession`, identità stabile);
  - Escape→cancel nei campi input/editor; cancel senza autoFocus.
  - Adattamenti: key i18n esistenti `chat.extensionCollapse`/`chat.extensionExpand`
    (i vecchi `collapseRequest`/`expandRequest` sono stati rimossi dal merge).
- Rimozione dell'overlay `{extensionDialog && <ExtensionDialog .../>}` dall'area messaggi.
- Reintroduzione di `extensionDialogElement`: wrapper con padding identici a `ChatInput`
  (`0 16px 8px`, paddingRight 52 su desktop per l'allineamento col minimap) e
  `maxWidth: var(--chat-content-max-width, 820px)` (pre-merge era `maxWidth: 820`
  letterale, vietato da `ChatAppearance.test.mjs`).
- Slot in basso: `{chatInputElement}` → `{extensionDialogElement ?? chatInputElement}`
  (slot unico condiviso da sessioni normali e new-session vuota).
- Rimozione di `getExtensionDialogSummary` (orphan dopo il ripristino).
- `useId` aggiunto all'import di react (usato dal componente ripristinato).
- `ExtensionCustomPanel` intatto: resta l'overlay attuale nella content region.

### 2. `components/ChatWindow.extension-request.test.mjs`

Riscritto per pinare il layout del fork:
- dialog inline nello slot composer (`extensionDialogElement ?? chatInputElement`);
- nessuna absolute positioning / `inset: 0` nel componente dialog;
- asserzioni sul custom panel invariate (overlay nella content region);
- test collapse/cancel mantenuti; test reset adeguato a `setCollapsed(false); }, [request])`.

### 3. `components/ChatAppearance.test.mjs`

Il conteggio di 3 usi di `var(--chat-content-max-width, 820px)` in `ChatWindow.tsx` resta
valido (message content + header isEmptyNew + wrapper dialog al posto della card);
aggiornato solo il commento.

### 4. `AGENTS.md`

Sezione "Extension dialog is bottom-anchored" aggiornata: descrive il layout ripristinato
(sostituzione inline della input bar in entrambi gli stati) e la nota per i merge futuri
(mantenere questo layout, non riapplicare l'overlay upstream).

## Verifica

- `node_modules/.bin/tsc --noEmit`
- `npm run lint`
- `node --test components/ChatWindow.extension-request.test.mjs components/ChatAppearance.test.mjs`
- Check visivo sul dev server (porta 30141) con un'extension che apre un dialog, se disponibile.
