# Installare MFD-24

[English](../INSTALL.md) · [Français](INSTALL.fr.md) · [Deutsch](INSTALL.de.md) · **Italiano** · [日本語](INSTALL.ja.md) · [中文](INSTALL.zh.md)

MFD-24 è distribuito in un solo modo: un `app-earth-release.apk` firmato sulla
[pagina delle release GitHub](https://github.com/amorroma1/expedition24/releases/latest). Wear OS
non ha un'interfaccia di sideloading, quindi ogni percorso qui sotto finisce in ADB — l'unica
domanda è quale macchina lo esegue. Tre percorsi, dal più semplice. Tutti richiedono le
[opzioni sviluppatore](#prima-di-tutto-le-opzioni-sviluppatore-sullorologio): si comincia da lì.

Richiede Wear OS 3.0 o più recente (API 30). Costruito e indossato su un TicWatch Pro 3 Ultra
(454 × 454); il layout è proporzionale al raggio, altri schermi rotondi dovrebbero andare bene.

## Perché MFD-24 non è su Google Play

Una decisione, non un arretrato. Tre ragioni:

- **Un monitor a uomo morto non deve essere un'installazione d'impulso.** La funzione di
  vigilanza è un ausilio non certificato — utile esattamente a chi legge cosa fa, e cosa non
  promette, prima di fidarsene. Una scheda di store è fatta per essere toccata in trenta secondi;
  un sideload viene letto, verificato e installato di proposito, dal pubblico per cui questo
  quadrante è stato costruito.
- **I permessi sono del tipo costoso.** Posizione in background, sensori corporei e un servizio in
  primo piano di tipo salute qui sono legittimi — il meteo si aggiorna in background, il monitor
  legge l'accelerometro a schermo spento — ma su Play mettono un progetto amatoriale nella stessa
  macchina di revisione permanente delle app commerciali di benessere, con regole che cambiano
  ogni anno e la rimozione come esito predefinito del silenzio. Quel tempo è speso meglio sul
  quadrante.
- **Si può verificare ciò che si installa.** Ogni release porta lo SHA-256 dell'APK nelle sue
  note, l'APK è firmato con la stessa chiave dalla 1.0.0, e il sorgente che l'ha prodotto è a un
  tag di distanza. Uno store aggiungerebbe un intermediario, non una garanzia.

Niente di tutto questo riguarda la licenza — il software GPL è ammesso su Play. Riguarda chi
installa uno strumento da turno di guardia, e con quanta deliberazione.

## Prima di tutto: le opzioni sviluppatore sull'orologio

1. Sull'orologio: **Impostazioni → Sistema → Informazioni → Versioni** (la dicitura varia col
   produttore) e toccare sette volte il **Numero build**, finché non vi dichiara sviluppatore.
2. Tornati nelle Impostazioni, aprire **Opzioni sviluppatore** e attivare **Debug ADB** e
   **Debug wireless** (su Wear OS 3 può chiamarsi **Debug via Wi-Fi**).
3. Mettere l'orologio sulla **stessa rete Wi-Fi** del telefono o del computer che farà
   l'installazione.

## Percorso 1 — un telefono e nient'altro: Wear Installer 2

Il percorso più dolce: un'app Android gratuita che esegue la stretta di mano ADB per voi e mostra
ogni passo. È freeware di terzi (Wear Installer 2, di Malcolm Bryant / freepoc) — estraneo a
questo progetto, ma largamente usato esattamente per questo compito.

1. Sul **telefono**, installare **Wear Installer 2** da Google Play.
2. Sul **telefono**, scaricare `app-earth-release.apk` dall'
   [ultima release](https://github.com/amorroma1/expedition24/releases/latest).
3. In Wear Installer 2 seguire la procedura guidata: chiede l'indirizzo IP dell'orologio e il
   codice di associazione — entrambi sono sull'orologio sotto **Opzioni sviluppatore → Debug
   wireless → Associa nuovo dispositivo**.
4. Indicargli l'APK scaricato e lasciarlo installare.
5. Sull'orologio: pressione lunga sul quadrante attuale, scorrere fino a **MFD-24**, toccarlo.

## Percorso 2 — un computer con ADB

Il percorso canonico, quello di cui tutto il resto è solo zucchero.

1. Procurarsi i [platform-tools Android](https://developer.android.com/tools/releases/platform-tools)
   (uno zip piccolo; `adb` è dentro) e scaricare `app-earth-release.apk` dall'
   [ultima release](https://github.com/amorroma1/expedition24/releases/latest).
2. *(Valgono i dieci secondi)* Verificare il download contro lo SHA-256 stampato nelle note di
   release: `certutil -hashfile app-earth-release.apk SHA256` su Windows,
   `shasum -a 256 app-earth-release.apk` su macOS/Linux.
3. Sull'orologio: **Opzioni sviluppatore → Debug wireless → Associa nuovo dispositivo**. Mostra un
   IP con una **porta di associazione** e un codice a sei cifre. Mentre quel dialogo è aperto:

   ```
   adb pair 192.168.1.50:37000 123456
   ```

4. Tornati alla schermata del Debug wireless, l'orologio mostra una seconda porta, **diversa** —
   la porta di connessione:

   ```
   adb connect 192.168.1.50:41234
   adb install -r app-earth-release.apk
   ```

5. Renderlo il quadrante attivo — nel selettore di quadranti dell'orologio, oppure:

   ```
   adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE \
       --es operation set-watchface \
       --ecn component com.avdesign.mfd24/com.avdesign.mfd24.MfdWatchFaceService
   ```

Cosa andrà storto, perché succederà:

| Sintomo | Causa e rimedio |
|---|---|
| `adb connect` fallisce o resta appeso | Gli avete dato la porta di **associazione**. La porta di connessione è l'altro numero, sulla schermata principale del debug wireless. |
| `protocol fault (couldn't read status message)` | Il codice di associazione è scaduto col suo dialogo. Riaprire **Associa nuovo dispositivo** ed eseguire `adb pair` mentre è visibile. |
| `error: closed` o `device offline` a metà comando | L'orologio ha lasciato il Wi-Fi quando lo schermo si è addormentato. Svegliare lo schermo, riconnettersi — aspettarsi una **porta nuova** se il debug wireless è stato rispento e riacceso. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | La build installata è firmata con una chiave diversa da quella che state installando (un APK auto-compilato sopra una release, o viceversa). Prima disinstallare — le impostazioni se ne vanno con essa. |

## Percorso 3 — compilare dal sorgente

Per leggere, correggere, o non fidarsi di alcun binario tranne il proprio.

```
git clone https://github.com/amorroma1/expedition24.git
cd expedition24
./gradlew :app:assembleEarthDebug
adb install -r app/build/outputs/apk/earth/debug/app-earth-debug.apk
```

Servono un JDK 17+ (`JAVA_HOME` puntato a quello incluso in Android Studio basta) e l'SDK
Android. `assembleEarthRelease` compila la variante release; senza una chiave di firma
configurata ripiega sul keystore di debug e l'APK si installa comunque sul proprio orologio.

**La firma è il confine:** un APK auto-compilato e la release GitHub non possono installarsi
l'uno sopra l'altra, perché Android esige che gli aggiornamenti condividano la chiave di firma.
Attraversare significa prima disinstallare — e impostazioni, stato del turno e registro degli
incidenti se ne vanno con la disinstallazione. Scegliete una corsia — le release per indossare, le
proprie build per smanettare — e restateci.

## Aggiornare

L'orologio ti dirà che esiste una nuova release. Non la installerà, e non finge di poterlo fare.

- **Una volta al giorno, fuori turno, chiede a GitHub se esiste qualcosa di più recente.** Un
  piccolo corpo JSON. **Non scarica mai nulla.** Mai durante un turno, e
  **ABOUT → RELEASE CHECK** disattiva tutto.
- **Una notifica per release**, e **ABOUT → RELEASES** nomina quella in attesa.
- **Toccandolo compare la pagina della release come QR code.** Puntaci un telefono: note, SHA-256 e
  APK sono su quella pagina, in un browser, a una dimensione leggibile.
- **Poi installa nel modo ordinario**, da un computer:

  ```
  adb install -r app-earth-release.apk
  ```

**Perché non dall'orologio?** Perché Wear OS non lo consente: la sessione viene applicata, la
piattaforma chiede conferma, e il suo installer risponde *"Install/Uninstall actions not supported
on Wear"* — verificato sull'emulatore API 30 e su un TicWatch Pro 3 Ultra con Wear 3.5. Ogni
scorciatoia è chiusa a un'app ordinaria.

## Dopo l'installazione

- **Le impostazioni** stanno dietro una pressione lunga sul quadrante, poi la matita. Tutto ciò
  che serve al quadrante viene chiesto lì — la posizione per meteo, Nadir e l'aggancio del sito; i
  permessi dei sensori nel momento in cui si sceglie uno slot che ne richiede uno.
  All'installazione non viene chiesto nulla. La tabella completa dei permessi è nel
  [README](../../README.md#permissions).
- **Gli aggiornamenti** arrivano da soli — vedi [Aggiornare](#aggiornare) sopra; `adb install -r`
  con la nuova release funziona sempre. Le impostazioni sopravvivono in entrambi i casi, tranne
  nelle release che cambiano lo schema delle impostazioni, cosa che le note di release segnalano
  quando accade.
- **Nessuna app companion, nessun account.** L'APK è l'intero prodotto.
