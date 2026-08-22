# MFD-24

[English](../../README.md) · **Français** · [Deutsch](README.de.md) · [Italiano](README.it.md) · [日本語](README.ja.md) · [中文](README.zh.md)

**Un cadran d'instrument 24 heures pour le poignet** — conçu pour ceux qui prennent le quart :
pilotes, marins, opérateurs de drones, officiers de permanence.

L'aiguille des heures fait un tour par jour. Votre quart est dessiné comme un arc de ce jour, et
il se vide à mesure qu'il est servi. Un moniteur « homme mort » optionnel demande un signe de vie
à intervalle régulier, escalade en SOS si personne ne répond, et laisse derrière lui un
enregistrement `MAN DOWN` qu'une manche de veste ne peut pas effacer. Le reste du cadran porte la
télémétrie dont un quart a réellement besoin : l'heure Zulu avec le groupe date-heure, le QNH, la
météo en abrégés METAR, l'aérodrome ou le port le plus proche, et les heures de jour restantes.

| En veille active (AUTO, la nuit) | Quart en cours | MAN DOWN |
|---|---|---|
| ![](../screenshots/ambient-auto.png) | ![](../screenshots/duty.png) | ![](../screenshots/mandown.png) |

![Un contrôle manqué, acquitté tard](../media/mandown.gif)

## L'essentiel

- **Cadran 24 h** — midi en haut, minuit en bas (ou l'inverse). L'aiguille des minutes tourne sur
  son propre anneau de 60 divisions ; la seconde est un curseur qui saute de graduation en
  graduation, rien ne balaie.
- **Le quart comme un arc** — démarrage immédiat ou programmé, compte à rebours `DUTY: 02:29 REM`,
  signaux sonores et vibrants aux deux bornes, même écran éteint.
- **Vigilance** — une impulsion au poignet toutes les 5/10/15 minutes, 30 secondes pour répondre
  d'un geste ou d'un toucher, puis un SOS en salves : trente secondes d'appel, puis un SOS doublé
  une fois par minute, quatre fois — ressenti une fois, sonné deux fois, avec un volume réglable.
  Sans réponse : `MAN DOWN`, l'instant Zulu, une marque sur l'arc, un journal de la vacation en
  cours — il porte les paramètres du quart et le quart suivant le remet à zéro. En option,
  le pouls pendant le contrôle manqué, comparé au dernier pouls relevé en mouvement. Se suspend
  hors du poignet — et le cadran le dit.
- **Nadir** — les heures entre lever et coucher du soleil à votre position, calculées hors ligne,
  jour polaire et nuit polaire compris.
- **Marques solaire et lunaire** — le soleil et la lune sur l'anneau des heures, à leur véritable
  angle horaire ; la lune porte sa phase réelle. Pointez la marque vers l'astre lui-même et le
  cadran devient une boussole, de jour comme de nuit.
- **Verrouillage de site à 5 km** — 9 649 aérodromes, ports, héliports et bases de lancement dans
  un index embarqué de 194 Ko. Silhouette distincte par type ; les sites militaires en couleur
  d'accent.
- **Veille active honnête** — le même cadran, atténué. `AUTO` le réduit à un pixel sur deux après
  le coucher du soleil — jamais pendant un quart de nuit.
- **Hors ligne par principe** — seuls la météo et un contrôle quotidien des versions sur GitHub
  touchent le réseau. Une nouvelle version se signale une fois, et s'installe depuis la montre
  là où la plateforme le permet. Pas d'application compagnon, pas
  de compte, pas de télémétrie vers qui que ce soit.

## Installation

Wear OS 3.0+ (API 30). Téléchargez `app-earth-release.apk` depuis la
[dernière version](https://github.com/amorroma1/expedition24/releases/latest) et installez par ADB :

```
adb install -r app-earth-release.apk
```

Le guide complet — trois routes, de « un téléphone et rien d'autre » à la compilation depuis le
source, avec le dépannage — est dans **[INSTALL.fr.md](INSTALL.fr.md)**. MFD-24 n'est pas sur
Google Play, à dessein ; le guide explique pourquoi.

## Licence

GPL-3.0-or-later. Les bases navales et héliports proviennent d'OpenStreetMap (© contributeurs
OSM, ODbL).
