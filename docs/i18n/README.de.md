# MFD-24

[English](../../README.md) · [Français](README.fr.md) · **Deutsch** · [Italiano](README.it.md) · [日本語](README.ja.md) · [中文](README.zh.md)

**Ein 24-Stunden-Instrumentenzifferblatt fürs Handgelenk** — gebaut für Menschen, die Wache
gehen: Piloten, Seeleute, Drohnenbesatzungen, Diensthabende.

Der Stundenzeiger macht eine Umdrehung pro Tag. Die eigene Wache liegt als Bogen auf diesem Tag
und leert sich, während sie abgeleistet wird. Ein optionaler Totmann-Monitor fordert in festen
Abständen ein Lebenszeichen, eskaliert unbeantwortet zum SOS und hinterlässt einen
`MAN DOWN`-Eintrag, den kein Ärmel wegwischen kann. Der Rest des Zifferblatts trägt die
Telemetrie, mit der eine Wache tatsächlich läuft: Zulu-Zeit mit Datum-Zeit-Gruppe, QNH, Wetter in
METAR-Kürzeln, den nächstgelegenen Flugplatz oder Hafen und das verbleibende Tageslicht.

| Always-on (AUTO, nachts) | Laufende Wache | MAN DOWN |
|---|---|---|
| ![](../screenshots/ambient-auto.png) | ![](../screenshots/duty.png) | ![](../screenshots/mandown.png) |

![Ein versäumter Check, spät quittiert](../media/mandown.gif)

## Das Wesentliche

- **24-h-Zifferblatt** — Mittag oben, Mitternacht unten (oder umgekehrt). Der Minutenzeiger läuft
  auf seinem eigenen 60er-Ring; die Sekunde ist ein Cursor, der von Strich zu Strich springt —
  nichts wischt über das Blatt.
- **Die Wache als Bogen** — sofort starten oder vorbestellen, Countdown `DUTY: 02:29 REM`, Ton
  und Vibration an beiden Grenzen, auch bei dunklem Display.
- **Vigilanz** — alle 5/10/15 Minuten ein Stups ans Handgelenk, 30 Sekunden Zeit für eine Bewegung
  oder eine Berührung, danach SOS in Salven: dreißig Sekunden Rufen, dann ein doppeltes SOS im
  Minutentakt, viermal — einmal gefühlt, zweimal gehört, mit einstellbarer Lautstärke. Bleibt es
  aus: `MAN DOWN`, der Zulu-Moment, eine Marke auf dem Bogen, ein Protokoll dieser Wache — es
  nennt deren Eckdaten, und die nächste Wache leert es. Optional
  mit dem Puls während des unbeantworteten Checks, gegen den letzten Puls in Bewegung gelesen.
  Abseits des Handgelenks pausiert der Monitor — und das Blatt sagt es.
- **Nadir** — die Stunden zwischen Sonnenauf- und -untergang an der eigenen Position, offline
  berechnet, Polartag und Polarnacht inklusive.
- **Sonnen- und Mondmarke** — Sonne und Mond auf dem Stundenring, an ihrem wahren Stundenwinkel;
  der Mond trägt seine echte Phase. Die Marke auf den Himmelskörper selbst gerichtet, wird das
  Zifferblatt zum Kompass, bei Tag wie bei Nacht.
- **5-km-Ortserkennung** — 9 649 Flugplätze, Häfen, Heliports und Startanlagen in einem 194-KB-
  Index an Bord. Eigene Silhouette je Typ; militärische Anlagen in der Akzentfarbe.
- **Ehrliches Always-on** — dasselbe Blatt, gedimmt. `AUTO` dünnt es nach Sonnenuntergang auf jedes
  zweite Pixel aus — nie während einer Nachtwache.
- **Offline aus Prinzip** — nur das Wetter und eine tägliche Versionsprüfung gegen GitHub
  berühren das Netz. Ein neues Release meldet sich genau einmal und installiert sich von der
  Uhr aus, wo die Plattform es zulässt. Keine Begleit-App, kein Konto, keine
  Telemetrie an Dritte.

## Installation

Wear OS 3.0+ (API 30). `app-earth-release.apk` aus dem
[aktuellen Release](https://github.com/amorroma1/expedition24/releases/latest) laden und per ADB
installieren:

```
adb install -r app-earth-release.apk
```

Die vollständige Anleitung — drei Wege, von „nur ein Telefon“ bis zum Bauen aus dem Quellcode,
samt Fehlerbehebung — steht in **[INSTALL.de.md](INSTALL.de.md)**. MFD-24 ist mit Absicht nicht
bei Google Play; die Anleitung erklärt, warum.

## Lizenz

GPL-3.0-or-later. Marinestützpunkte und Heliports stammen aus OpenStreetMap (© OSM-Mitwirkende,
ODbL).
