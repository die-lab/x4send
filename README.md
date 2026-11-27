# X4 Send

App Android (Kotlin + Jetpack Compose) per inviare libri e documenti direttamente a un Xteink X4 con firmware **CrossPoint Reader**, via WiFi.

## Cosa fa
- **Condividi → X4 Send** da qualsiasi app (Files, Chrome, Gmail...) e il file parte subito.
- **Conversione sul telefono**: EPUB, TXT, XTC/XTCH e BMP partono cosi' come sono; DOCX, ODT, PDF (con testo), RTF, FB2, HTML e Markdown diventano EPUB (titoli → capitoli, autore letto dai metadati); JPG/PNG/WEBP/GIF/HEIC diventano BMP in scala di grigi per lo schermo 480x800. MOBI/AZW e .doc non sono supportati.
- **Cerca X4**: trova l'X4 su `crosspoint.local` o scandendo la rete locale (utile con l'hotspot del telefono, dove l'IP cambia).
- Guida al collegamento dentro l'app (pulsante *Come collegarsi*, mostrata al primo avvio).
- Selettore file multiplo, coda con progresso, velocita' media (KB/s) a fine invio.
- Due metodi di upload, selezionabili: HTTP multipart (`POST /upload`) e WebSocket veloce (porta 81), con ripiego automatico su HTTP.
- Libreria: sfoglia, crea cartelle, elimina file sull'X4.
- Usa la rete WiFi anche se non ha internet (hotspot dell'X4) e prova a risolvere `crosspoint.local`.

## Uso
**Ovunque (consigliato): hotspot del telefono**
1. Attiva l'hotspot personale del telefono.
2. Sull'X4: **File Transfer** → collegati a una rete WiFi → scegli l'hotspot del telefono (la password serve solo la prima volta).
3. In app: *Cerca X4* (oppure l'IP mostrato sull'X4) → *Prova connessione* → *Scegli file da inviare*.

**A casa**: X4 e telefono sullo stesso WiFi, indirizzo `crosspoint.local` o l'IP mostrato.

**Hotspot dell'X4**: telefono sulla rete `CrossPoint-Reader`, indirizzo `192.168.4.1`. Con una VPN attiva conviene escludere X4 Send dalla VPN; se Android vieta comunque il collegamento al WiFi, l'app riprova da sola sulla rete normale.

## Compilare l'APK
- **GitHub Actions**: carica il progetto in un repo, il workflow `.github/workflows/build.yml` produce l'artefatto `x4send-debug-apk`.
- **Android Studio**: apri la cartella, *Build > Build APK*.
- **Da terminale**: `./gradlew assembleDebug` (serve Android SDK 35 e JDK 17).

## API usata (CrossPoint webserver)
| Azione | Chiamata |
|---|---|
| Stato | `GET /api/status` |
| Elenco | `GET /api/files?path=/` |
| Upload HTTP | `POST /upload?path=/Cartella`, campo multipart `file` |
| Nuova cartella | `POST /mkdir` (`name`, `path`) |
| Elimina | `POST /delete` (`path`, `type=file\|folder`) |
| Upload WS | `ws://host:81/` → `START:<nome>:<byte>:<cartella>` → `READY` → frame binari → `DONE` |

I frame WebSocket sono da 8 KB: il server ESP32 accetta payload di ~15 KB al massimo.

## Stato
Codice non ancora compilato ne' provato su dispositivo. Primi punti da verificare sull'X4: risoluzione di `crosspoint.local`, upload HTTP, upload WebSocket.

## Idee successive
Foreground service per upload a schermo spento, immagini dentro gli EPUB convertiti, conversione pagine web (link condivisi) in EPUB.
