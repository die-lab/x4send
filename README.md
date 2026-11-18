# X4 Send

App Android (Kotlin + Jetpack Compose) per inviare EPUB direttamente a un Xteink X4 con firmware **CrossPoint Reader**, via WiFi.

## Cosa fa
- **Condividi → X4 Send** da qualsiasi app (Files, Chrome, Gmail...) e l'EPUB parte subito.
- Selettore file multiplo, coda con progresso, velocita' media (KB/s) a fine invio.
- Due metodi di upload, selezionabili: HTTP multipart (`POST /upload`) e WebSocket veloce (porta 81), con ripiego automatico su HTTP.
- Libreria: sfoglia, crea cartelle, elimina file sull'X4.
- Usa la rete WiFi anche se non ha internet (hotspot dell'X4) e prova a risolvere `crosspoint.local`.

## Uso
1. Sull'X4: **File Transfer** e scegli la rete (oppure *Create Hotspot*).
2. Telefono sullo stesso WiFi (o connesso all'hotspot `CrossPoint-Reader`).
3. In app: indirizzo `crosspoint.local` oppure l'IP mostrato sull'X4 (`192.168.4.1` in hotspot) → *Prova connessione*.

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
Foreground service per upload a schermo spento, scoperta automatica via mDNS, supporto .txt/.xtc, conversione pagine web in EPUB.
