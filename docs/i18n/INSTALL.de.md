# MFD-24 installieren

[English](../INSTALL.md) · [Français](INSTALL.fr.md) · **Deutsch** · [Italiano](INSTALL.it.md) · [日本語](INSTALL.ja.md) · [中文](INSTALL.zh.md)

MFD-24 wird auf genau eine Weise verteilt: als signierte `app-earth-release.apk` auf der
[GitHub-Releases-Seite](https://github.com/amorroma1/expedition24/releases/latest). Wear OS hat
keine Sideloading-Oberfläche, also endet jeder Weg unten bei ADB — die Frage ist nur, welche
Maschine es ausführt. Drei Wege, der einfachste zuerst. Alle brauchen die
[Entwickleroptionen](#zuerst-die-entwickleroptionen-auf-der-uhr), also dort anfangen.

Benötigt Wear OS 3.0 oder neuer (API 30). Gebaut und getragen auf einer TicWatch Pro 3 Ultra
(454 × 454); das Layout ist proportional zum Radius, andere runde Displays sollten passen.

## Warum MFD-24 nicht bei Google Play ist

Eine Entscheidung, kein Rückstand. Drei Gründe:

- **Ein Totmann-Monitor darf keine Impuls-Installation sein.** Die Vigilanz-Funktion ist ein
  unzertifiziertes Hilfsmittel — nützlich genau für die, die lesen, was sie tut und was sie nicht
  verspricht, bevor sie ihr vertrauen. Ein Store-Eintrag ist dafür gebaut, in dreißig Sekunden
  angetippt zu werden; ein Sideload wird gelesen, geprüft und mit Absicht installiert — vom
  Publikum, für das dieses Zifferblatt gebaut wurde.
- **Die Berechtigungen sind die teure Sorte.** Hintergrund-Standort, Körpersensoren und ein
  Vordergrunddienst vom Typ Gesundheit sind hier legitim — der Wetterabruf läuft im Hintergrund,
  der Monitor liest den Beschleunigungssensor bei dunklem Display — aber bei Play stecken sie ein
  Hobbyprojekt in dieselbe Dauerprüfungsmaschinerie wie kommerzielle Wellness-Apps, mit jährlich
  wechselnden Richtlinien und Entfernung als Standardausgang des Schweigens. Diese Zeit ist am
  Zifferblatt besser angelegt.
- **Man kann prüfen, was man installiert.** Jedes Release trägt die SHA-256 der APK in seinen
  Notizen, die APK ist seit 1.0.0 mit demselben Schlüssel signiert, und der Quellcode, der sie
  erzeugt hat, ist einen Tag entfernt. Ein Store würde einen Mittelsmann hinzufügen, keine
  Gewissheit.

Mit der Lizenz hat all das nichts zu tun — GPL-Software ist bei Play zugelassen. Es geht darum,
wer ein Wachdienst-Instrument installiert, und wie bewusst.

## Zuerst: die Entwickleroptionen auf der Uhr

1. Auf der Uhr: **Einstellungen → System → Info → Versionen** (Wortlaut je nach Hersteller) und
   sieben Mal auf die **Build-Nummer** tippen, bis sie Sie zum Entwickler erklärt.
2. Zurück in den Einstellungen die **Entwickleroptionen** öffnen und **ADB-Debugging** sowie
   **Drahtloses Debugging** einschalten (auf Wear OS 3 ggf. **Debugging über WLAN**).
3. Die Uhr ins **selbe WLAN** bringen wie das Telefon oder den Rechner, der installieren soll.

## Weg 1 — ein Telefon und sonst nichts: Wear Installer 2

Der sanfteste Weg: eine kostenlose Android-App, die den ADB-Handschlag für Sie ausführt und jeden
Schritt anzeigt. Sie ist Freeware von Dritten (Wear Installer 2, von Malcolm Bryant / freepoc) —
nicht Teil dieses Projekts, aber für genau diese Aufgabe weit verbreitet.

1. Auf dem **Telefon** **Wear Installer 2** aus Google Play installieren.
2. Auf dem **Telefon** `app-earth-release.apk` vom
   [neuesten Release](https://github.com/amorroma1/expedition24/releases/latest) laden.
3. In Wear Installer 2 dem Assistenten folgen: Er fragt nach der IP-Adresse der Uhr und dem
   Kopplungscode — beides steht auf der Uhr unter **Entwickleroptionen → Drahtloses Debugging →
   Gerät koppeln**.
4. Die geladene APK auswählen und installieren lassen.
5. Auf der Uhr: langes Drücken auf das aktuelle Zifferblatt, zu **MFD-24** wischen, antippen.

## Weg 2 — ein Rechner mit ADB

Der kanonische Weg, über den alles andere nur Zuckerguss ist.

1. Die [Android-Platform-Tools](https://developer.android.com/tools/releases/platform-tools)
   besorgen (ein kleines Zip; `adb` liegt darin) und `app-earth-release.apk` vom
   [neuesten Release](https://github.com/amorroma1/expedition24/releases/latest) laden.
2. *(Die zehn Sekunden wert)* Den Download gegen die in den Release-Notizen abgedruckte SHA-256
   prüfen: `certutil -hashfile app-earth-release.apk SHA256` unter Windows,
   `shasum -a 256 app-earth-release.apk` unter macOS/Linux.
3. Auf der Uhr: **Entwickleroptionen → Drahtloses Debugging → Gerät koppeln**. Sie zeigt eine IP
   mit **Kopplungsport** und einen sechsstelligen Code. Solange dieser Dialog offen ist:

   ```
   adb pair 192.168.1.50:37000 123456
   ```

4. Zurück auf dem Drahtlos-Debugging-Bildschirm zeigt die Uhr einen zweiten, **anderen** Port —
   den Verbindungsport:

   ```
   adb connect 192.168.1.50:41234
   adb install -r app-earth-release.apk
   ```

5. Zum aktiven Zifferblatt machen — im Zifferblatt-Wähler der Uhr, oder:

   ```
   adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE \
       --es operation set-watchface \
       --ecn component com.avdesign.mfd24/com.avdesign.mfd24.MfdWatchFaceService
   ```

Was schiefgehen wird, denn das wird es:

| Symptom | Ursache und Abhilfe |
|---|---|
| `adb connect` scheitert oder hängt | Sie haben den **Kopplungsport** angegeben. Der Verbindungsport ist die andere Nummer auf dem Hauptbildschirm des drahtlosen Debuggings. |
| `protocol fault (couldn't read status message)` | Der Kopplungscode ist mit seinem Dialog abgelaufen. **Gerät koppeln** neu öffnen und `adb pair` ausführen, solange er angezeigt wird. |
| `error: closed` oder `device offline` mitten im Befehl | Die Uhr hat das WLAN verlassen, als ihr Display einschlief. Display wecken, neu verbinden — mit einem **neuen Port** rechnen, falls das drahtlose Debugging umgeschaltet wurde. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Der installierte Build ist mit einem anderen Schlüssel signiert als der, den Sie installieren (eine selbstgebaute APK über ein Release, oder umgekehrt). Erst deinstallieren — die Einstellungen gehen mit. |

## Weg 3 — aus dem Quellcode bauen

Zum Lesen, Patchen, oder wenn Sie keinem Binary trauen außer Ihrem eigenen.

```
git clone https://github.com/amorroma1/expedition24.git
cd expedition24
./gradlew :app:assembleEarthDebug
adb install -r app/build/outputs/apk/earth/debug/app-earth-debug.apk
```

Nötig sind ein JDK 17+ (`JAVA_HOME` auf das von Android Studio mitgelieferte reicht) und das
Android-SDK. `assembleEarthRelease` baut die Release-Variante; ohne konfigurierten
Signaturschlüssel fällt sie auf den Debug-Keystore zurück, und die APK lässt sich trotzdem auf
der eigenen Uhr installieren.

**Die Signatur ist die Grenze:** Eine selbstgebaute APK und das GitHub-Release können einander
nicht überschreiben, weil Android für Updates denselben Signaturschlüssel verlangt. Der Wechsel
bedeutet vorheriges Deinstallieren — und Einstellungen, Wachdienst-Zustand und Vorfallsprotokoll
gehen mit. Eine Spur wählen — Releases zum Tragen, eigene Builds zum Basteln — und dort bleiben.

## Aktualisieren

Die Uhr sagt Ihnen, dass es ein neues Release gibt. Installieren wird sie es nicht, und sie tut
auch nicht so.

- **Einmal täglich, außerhalb der Wache, fragt sie GitHub nach etwas Neuerem.** Ein kleiner
  JSON-Körper. **Es wird nie etwas heruntergeladen.** Nie während einer Wache, und
  **ABOUT → RELEASE CHECK** schaltet alles ab.
- **Eine Benachrichtigung pro Release**, und **ABOUT → RELEASES** nennt das wartende.
- **Ein Antippen zeigt die Release-Seite als QR-Code.** Ein Telefon darauf richten: Notizen,
  SHA-256 und APK stehen dort, im Browser, in lesbarer Größe.
- **Dann ganz normal installieren**, von einem Rechner:

  ```
  adb install -r app-earth-release.apk
  ```

**Warum nicht von der Uhr?** Weil Wear OS es nicht zulässt: die Sitzung wird bestätigt, die
Plattform fragt nach der Zustimmung, und ihr eigener Installer antwortet *"Install/Uninstall actions
not supported on Wear"* — geprüft auf dem API-30-Emulator und auf einer TicWatch Pro 3 Ultra mit
Wear 3.5. Jeder Umweg ist einer gewöhnlichen App verschlossen.

## Nach der Installation

- **Die Einstellungen** liegen hinter einem langen Druck aufs Zifferblatt, dann dem Stift. Alles,
  was das Zifferblatt braucht, erfragt es dort — den Standort für Wetter, Nadir und die
  Standorterkennung; Sensor-Berechtigungen in dem Moment, in dem ein Slot gewählt wird, der eine
  braucht. Bei der Installation wird nichts angefragt. Die vollständige Berechtigungstabelle steht
  im [README](../../README.md#permissions).
- **Updates** kommen von selbst — siehe [Aktualisieren](#aktualisieren) oben; `adb install -r`
  mit dem neuen Release funktioniert weiterhin. Die Einstellungen überleben in beiden Fällen,
  außer bei Releases, die das Einstellungsschema ändern, was die Release-Notizen dann ansagen.
- **Keine Begleit-App, kein Konto.** Die APK ist das ganze Produkt.
