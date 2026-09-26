# Pi Mobile: perché le formule LaTeX si vedono male (2026-09-26)

Indagine sulla segnalazione "le formule pesanti sono tutte sconquassate", fatta sulla sessione
`01a0c53b-bb5d-73cf-8a10-3e561cddfa99` della workstation (una lezione sulla linear attention:
nell'ultima risposta 24 formule display e 177 inline). Il renderer è
`io.github.huarangmeng:latex-renderer` 1.5.4-kt2.1.0.

Metodo:
- schermate del telefono (OnePlus DN2103) con la build della workstation;
- sorgente upstream della libreria al tag `release-1.5.4`, e il suo parser eseguito sulla JVM;
- rendering con Compose Desktop alla larghezza e densità del telefono, libreria 1.5.4 e 1.5.6;
- un pilota sul telefono con una build accanto a quella della workstation (`app.pimobile.prof`).

«Funziona» lo dichiara l'admin: qui ci sono cause, misure e proposte.

## Cosa si vede e perché

| # | Sintomo sul telefono | Causa | Di chi |
|---|---|---|---|
| 1 | Somme e produttorie inline (`$\sum_{j\le t-1}\dots$`, `$\prod_i\dots$`) con i limiti sotto e l'operatore grande: la riga si apre | La libreria disegna tutto in `MathStyle.DISPLAY` (`RenderStyle.kt:332`, mai cambiato da `LatexConfig.toContext()`); né `Latex()` né `LatexMeasurerState.inlineContent()` hanno un'opzione "inline", né in 1.5.4 né in 1.5.6. Passa a `TEXT` solo per un `$…$` **dentro** il sorgente | default della libreria, rimediabile dall'app |
| 2 | `\,` diventa uno spazio largo (`\gamma \, \mathbf R`, `\mathbf S_t \, \mathbf q_t`) | Il parser trasforma ogni spazio del sorgente in uno spazio normale da 0,25em: `a \, b` diventa spazio + 3mu + spazio ≈ 0,67em. In math mode gli spazi vanno ignorati. Le costanti di `\,` `\;` `\quad` sono giuste; `O(n\,m\,d_v)` senza spazi viene corretto. Gli stessi spazi annullano anche la spaziatura TeX attorno agli operatori | bug della libreria (ancora in 1.5.6) |
| 3 | Radice con la barra staccata, barra della frazione e della radice aggrovigliate, parentesi troppo grandi, pedice di ∏ sovrapposto | Il **nostro** `disablePreciseGlyphBounds()` (`Markdown.kt`, chiamato da `PiApp`): senza i byte del font ogni glifo è alto quanto l'intera riga di testo, e radici, delimitatori e script vengono piazzati di conseguenza. Con i limiti precisi `\sqrt{n!}` passa da 112×116 a 104×58 px e la frazione dentro `\left(\prod…\right)` viene pulita | scelta nostra, fatta per le prestazioni |
| 4 | Il meno è un trattino corto | La libreria disegna `-` come U+002D; la spaziatura è giusta, solo il glifo è sbagliato. `\mathbin{−}` viene corretto | bug della libreria |
| 5 | Formule display larghe e il blocco `aligned` tagliati a destra | Scorrono di lato (`DisplayMath`), ma niente lo segnala. Il line breaker della libreria (`LineBreakingConfig`) non è utilizzabile: non spezza dentro `\left…\right`, `\underbrace`, `aligned`, e sceglie il primo punto (dopo `o_t`) | layout nostro |
| 6 | Tabelle con formule: si vedono due colonne e il testo tagliato | `TableBlock` dimensiona le colonne dal **numero di caratteri** del sorgente (`chars * 8 + 28` dp, `Markdown.kt:494`): i comandi LaTeX gonfiano le larghezze. Le formule nelle celle sono misurate a 16sp su testo a 14sp | codice nostro |

Versioni più nuove: 1.5.5 e 1.5.6 (entrambe con build `-kt2.1.0`) non correggono nessuno dei sei
punti; la 1.5.6 è stata renderizzata ed è identica. Il manutentore corregge le issue segnalate
in pochi giorni.

## Misura: quanto costano i limiti precisi (perché esiste la scorciatoia)

Telefono, stessa sessione, avvio a freddo, stessa build (con `$…$`), cambia solo l'interruttore:

| Limiti dei glifi | Main thread nei 15 s di apertura | Frame peggiore | Scorrimento di 14 pagine |
|---|---|---|---|
| Veloci (oggi) | 1 230 ms | 700 ms | 10 480 ms, jank 1,3 % |
| Precisi | 3 690 ms | **3 200 ms** | 10 400 ms, jank 1,0 % |

Con i limiti precisi l'apertura blocca l'app per ~3 s: le formule della risposta vengono misurate
tutte insieme, e per ogni chiamata la libreria scrive il font in un file temporaneo e lo ricarica
(`Typeface.createFromFile`, senza cache; ancora così su master). Dopo la misura lo scorrimento
costa uguale. Il rimedio è la cache nella libreria (7 font in tutto), non togliere la
scorciatoia.

## Proposte, per valore e sforzo (da decidere)

1. **Inline come `$…$`:** in `MathScope`, `measurer.inlineContent("$" + latex + "$", config)`.
   Una riga, rischio basso; corregge l'1 e la maggior parte delle righe "sconquassate". È già
   nella build pilota.
2. **Normalizzare i sorgenti matematici** (in `MathNormalize.kt`, inline e display): togliere gli
   spazi, tranne dove chiudono un comando prima di una lettera (`\le t`, `\mathbf x`) e dentro
   `\text{}`; `-` → `\mathbin{−}`. Corregge il 2 e il 4 e stringe le formule larghe. Rischio medio
   per i casi limite, servono test unitari; c'è un prototipo nello scratchpad
   (`latexlib/render/Normalize.kt`).
3. **Limiti precisi senza blocco:** una piccola patch upstream (cache del `Typeface` per ciascuno
   dei 7 font); nel frattempo una copia patchata dell'AAR. Poi via `disablePreciseGlyphBounds()`.
   Corregge il 3.
4. **Formule display che entrano:** misurare la larghezza con il measurer della libreria e ridurre
   la dimensione fino a ~75 % quando serve; oltre, scorrimento orizzontale con una sfumatura sul
   bordo come segnale. Rischio basso. Corregge il 5 nella maggior parte dei casi (la formula con
   l'underbrace della lezione entra al 91 %).
5. **Tabelle:** larghezza delle colonne dalla larghezza misurata delle formule e non dai
   caratteri, formule alla dimensione del testo della cella. Corregge il 6.
6. **Issue upstream:** opzione inline/text style nel measurer, spazi in math mode, U+2212, cache
   del `Typeface`, scelta del punto di rottura nel line breaker.

## Pilota sul telefono

Build `app.pimobile.prof` (solo nello scratchpad) con la proposta 1 e l'interruttore
`adb shell setprop debug.pimobile.precise 1` per i limiti precisi; stessi punti della lezione
fotografati nella build della workstation (oggi), nel pilota con `$…$` e nel pilota con `$…$` e
limiti precisi.

- **Somma inline** ("Perché il gate cambia tutto: $\mathbf S = \sum_j …$"): oggi Σ grande con
  `j` sotto e righe allargate; con `$…$` `Σ_j` in linea e righe normali. Resta lo spazio largo
  dopo `k_j^⊤` (punto 2, non toccato).
- **Radici e frazione** ("ogni fattore ha rango infinito" e la formula display che segue): oggi e
  con il solo `$…$`, barra della radice staccata, barra della frazione doppia sulla radice,
  pedice di ∏ schiacciato, ℝ^∞ che sembra "ℝ°"; con i limiti precisi √n!, x_i^{n_i}/√(n_i!),
  ∏_i e ℝ^∞ corretti.
- Schermate: `scratchpad/phone/results/latex/` della sessione (`today_*`, `wrap_*`,
  `wrapprecise_*`, confronti `cmp_sum.png`, `cmp_sqrt.png`).

Quindi le proposte 1 e 3 bastano per le due cose più visibili; la 3 va fatta con la cache,
perché oggi costa 3,2 s di blocco all'apertura della lezione.

## Implementazione (tutte le proposte)

Su richiesta dell'admin ("implement all of the latex fixes"):

| # | Cosa | Dove |
|---|---|---|
| 1 | Formule inline passate come `$…$`, cioè in stile testo; anche quelle nelle celle delle tabelle | `inlineLatex()` in `LatexSource.kt`, `MathScope` in `Markdown.kt` |
| 2 | Sorgenti normalizzati: spazi tolti (restano dopo una parola di controllo davanti a una lettera, dentro `\text{}`, `\ce{}`, `\operatorname{}` e simili), `-` e `−` diventano `\mathbin{−}` (graffe se sono un apice intero: `e^-x`), segni delle dimensioni (`\kern-3mu`, `\hspace{-1em}`, `\\[-2pt]`) e commenti `%` lasciati stare | `normalizeLatex()` in `LatexSource.kt`, 16 test in `LatexSourceTest` |
| 3 | Limiti precisi dei glifi attivi, senza blocco: la build reindirizza l'unica chiamata della libreria a `cachedGlyphBounds` (un `Typeface` per font, misure dei glifi in cache) e fa usare a `LatexFontFamilies.hashCode` l'hash d'identità degli array dei font, che prima venivano letti per intero a ogni ricerca nella cache di layout. I font si caricano all'avvio (`PreloadLatexFonts`), così le formule non vengono impaginate due volte. Tolti `disablePreciseGlyphBounds()` e la sua regola ProGuard | `LatexGlyphBounds.kt`, `LatexRendererPatches` in `app/build.gradle.kts` (la build fallisce se un aggiornamento sposta il codice patchato) |
| 4 | Formule display più larghe dello schermo ridotte fino al 75 % (scalate al disegno, misurate una volta sola); oltre, scorrimento orizzontale con i bordi sfumati | `DisplayMath`, `shrinkToFit`, `fadingEdges` in `Markdown.kt` |
| 5 | Tabelle: colonne misurate (testo e formule), poi il layout automatico dei browser: se non sta su una riga il testo va a capo, ogni colonna tiene almeno la parola o la formula più larga e si divide il resto in proporzione; scorre solo se neanche i minimi entrano, con i bordi sfumati. Formule alla dimensione del testo della cella | `TableBlock` in `Markdown.kt`, `tableWidths()` in `TableLayout.kt`, 4 test in `TableLayoutTest` |
| 6 | Issue upstream: bozze pronte (cache del `Typeface`, stile inline, spazi in math mode, U+2212, line breaker), non aperte: servono il via dell'admin | fuori dal repository |

Misure dell'apertura della lezione, a freddo, 15 s:

| Build | Dove | Main thread | Frame peggiore (p99) |
|---|---|---|---|
| Pilota `$…$`, limiti veloci | telefono | 1 230 ms | 700 ms |
| Pilota `$…$`, limiti precisi senza cache | telefono | 3 690 ms | 3 200 ms |
| Prima versione con cache (hash dei font ancora pieno) | telefono | 1 400–1 500 ms | 1 050–1 150 ms |
| HEAD (prima delle correzioni) | emulatore, 4 aperture | 910–1 070 ms | 500–650 ms |
| Senza precaricare i font | emulatore, 2 aperture | 1 100–1 120 ms | 700 ms |
| Versione finale | emulatore, 2 aperture | 930–950 ms | 600–650 ms |

Sull'emulatore la versione finale costa quanto HEAD; il profilo mostrava l'hash dei byte dei font
(nostra chiave `Glyph` compresa) come prima voce, ora sparito. La versione finale non è ancora stata
misurata sul telefono, staccato durante il lavoro.

