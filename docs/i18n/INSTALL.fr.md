# Installer MFD-24

[English](../INSTALL.md) · **Français** · [Deutsch](INSTALL.de.md) · [Italiano](INSTALL.it.md) · [日本語](INSTALL.ja.md) · [中文](INSTALL.zh.md)

MFD-24 est distribué d'une seule manière : un `app-earth-release.apk` signé sur la
[page des versions GitHub](https://github.com/amorroma1/expedition24/releases/latest). Wear OS n'a
pas d'interface de sideloading, donc chaque route ci-dessous finit par ADB — la seule question est
de savoir quelle machine l'exécute. Trois routes, de la plus simple à la plus avancée. Toutes
exigent les [options développeur](#dabord--les-options-développeur-sur-la-montre) : commencez là.

Nécessite Wear OS 3.0 ou plus récent (API 30). Conçu et porté sur une TicWatch Pro 3 Ultra
(454 × 454) ; la disposition est proportionnelle au rayon, les autres écrans ronds devraient
convenir.

## Pourquoi MFD-24 n'est pas sur Google Play

Une décision, pas un retard. Trois raisons :

- **Un moniteur « homme mort » ne doit pas être une installation impulsive.** La fonction de
  vigilance est une aide non certifiée — utile précisément à ceux qui lisent ce qu'elle fait, et ce
  qu'elle ne promet pas, avant de s'y fier. Une fiche de magasin est faite pour être tapée en
  trente secondes ; un sideload est lu, vérifié et installé délibérément, par le public pour
  lequel ce cadran a été conçu.
- **Les permissions sont du genre coûteux.** Localisation en arrière-plan, capteurs corporels et
  service de premier plan de type santé sont légitimes ici — la météo se rafraîchit en
  arrière-plan, le moniteur lit l'accéléromètre écran éteint — mais sur Play elles placent un
  projet de loisir dans la même machinerie de révision permanente que les applications commerciales
  de bien-être, avec un renouvellement des règles chaque année et le retrait comme issue par
  défaut du silence. Ce temps est mieux employé sur le cadran.
- **Vous pouvez vérifier ce que vous installez.** Chaque version porte le SHA-256 de l'APK dans
  ses notes, l'APK est signé avec la même clé depuis la 1.0.0, et le source qui l'a produit est à
  un tag de distance. Un magasin ajouterait un intermédiaire, pas une garantie.

Rien de tout cela ne tient à la licence — les logiciels GPL sont admis sur Play. Il s'agit de qui
installe un instrument de quart, et avec quel degré de délibération.

## D'abord : les options développeur sur la montre

1. Sur la montre : **Paramètres → Système → À propos → Versions** (le libellé varie selon le
   fabricant), puis tapez sept fois sur **Numéro de build**, jusqu'au message vous déclarant
   développeur.
2. De retour dans les Paramètres, ouvrez **Options pour les développeurs** et activez
   **Débogage ADB** et **Débogage sans fil** (sur Wear OS 3, parfois **Débogage par Wi-Fi**).
3. Mettez la montre sur le **même réseau Wi-Fi** que le téléphone ou l'ordinateur qui fera
   l'installation.

## Route 1 — un téléphone et rien d'autre : Wear Installer 2

La route la plus douce : une application Android gratuite qui exécute la poignée de main ADB pour
vous et affiche chaque étape. C'est un gratuiciel tiers (Wear Installer 2, de Malcolm Bryant /
freepoc) — étranger à ce projet, mais largement utilisé pour exactement cette tâche.

1. Sur le **téléphone**, installez **Wear Installer 2** depuis Google Play.
2. Sur le **téléphone**, téléchargez `app-earth-release.apk` depuis la
   [dernière version](https://github.com/amorroma1/expedition24/releases/latest).
3. Dans Wear Installer 2, suivez l'assistant : il demande l'adresse IP de la montre et le code
   d'appairage — les deux sont sur la montre sous **Options développeur → Débogage sans fil →
   Associer un appareil**.
4. Désignez l'APK téléchargé et laissez-le installer.
5. Sur la montre : appui long sur le cadran actuel, glissez jusqu'à **MFD-24**, touchez-le.

## Route 2 — un ordinateur avec ADB

La route canonique, celle dont tout le reste n'est que l'enrobage.

1. Récupérez les [platform-tools Android](https://developer.android.com/tools/releases/platform-tools)
   (un petit zip ; `adb` est dedans) et téléchargez `app-earth-release.apk` depuis la
   [dernière version](https://github.com/amorroma1/expedition24/releases/latest).
2. *(Dix secondes bien employées)* Vérifiez le téléchargement contre le SHA-256 imprimé dans les
   notes de version : `certutil -hashfile app-earth-release.apk SHA256` sous Windows,
   `shasum -a 256 app-earth-release.apk` sous macOS/Linux.
3. Sur la montre : **Options développeur → Débogage sans fil → Associer un appareil**. Elle
   affiche une IP avec un **port d'appairage** et un code à six chiffres. Pendant que ce dialogue
   est ouvert :

   ```
   adb pair 192.168.1.50:37000 123456
   ```

4. De retour sur l'écran Débogage sans fil, la montre affiche un second port, **différent** — le
   port de connexion :

   ```
   adb connect 192.168.1.50:41234
   adb install -r app-earth-release.apk
   ```

5. Faites-en le cadran actif — dans le sélecteur de cadrans de la montre, ou :

   ```
   adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE \
       --es operation set-watchface \
       --ecn component com.avdesign.mfd24/com.avdesign.mfd24.MfdWatchFaceService
   ```

Ce qui va mal tourner, parce que ça arrivera :

| Symptôme | Cause et remède |
|---|---|
| `adb connect` échoue ou reste suspendu | Vous lui avez donné le port d'**appairage**. Le port de connexion est l'autre nombre, sur l'écran principal du débogage sans fil. |
| `protocol fault (couldn't read status message)` | Le code d'appairage a expiré avec son dialogue. Rouvrez **Associer un appareil** et lancez `adb pair` pendant qu'il est affiché. |
| `error: closed` ou `device offline` en pleine commande | La montre a quitté le Wi-Fi quand son écran s'est endormi. Réveillez l'écran, reconnectez — attendez-vous à un **nouveau port** si le débogage sans fil a été rebasculé. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Le build installé est signé d'une autre clé que celui que vous installez (un APK auto-compilé par-dessus une release, ou l'inverse). Désinstallez d'abord — les réglages partent avec. |

## Route 3 — compiler depuis le source

Pour lire, corriger, ou ne faire confiance à aucun binaire sauf le vôtre.

```
git clone https://github.com/amorroma1/expedition24.git
cd expedition24
./gradlew :app:assembleEarthDebug
adb install -r app/build/outputs/apk/earth/debug/app-earth-debug.apk
```

Il faut un JDK 17+ (`JAVA_HOME` pointant sur celui d'Android Studio suffit) et le SDK Android.
`assembleEarthRelease` compile la variante release ; sans clé de signature configurée, elle
retombe sur le keystore de debug et l'APK s'installe quand même sur votre propre montre.

**La signature est la frontière :** un APK auto-compilé et la release GitHub ne peuvent pas
s'installer l'un par-dessus l'autre, car Android exige qu'une mise à jour partage la clé de
signature. Traverser signifie désinstaller d'abord — et les réglages, l'état du quart et le
journal d'incidents partent avec. Choisissez une voie — les releases pour porter, vos builds pour
bidouiller — et restez-y.

## Mise à jour

La montre vous dira qu'une nouvelle version existe. Elle ne l'installera pas, et ne prétend pas le
pouvoir.

- **Une fois par jour, hors quart, elle demande à GitHub s'il existe quelque chose de plus récent.**
  Un petit corps JSON. **Rien n'est jamais téléchargé.** Jamais pendant un quart, et
  **ABOUT → RELEASE CHECK** désactive tout.
- **Une notification par version**, et **ABOUT → RELEASES** nomme celle qui attend.
- **Un appui affiche la page de la version en QR code.** Pointez-y un téléphone : les notes, le
  SHA-256 et l'APK sont sur cette page, dans un navigateur, à une taille lisible.
- **Puis installez normalement**, depuis un ordinateur :

  ```
  adb install -r app-earth-release.apk
  ```

**Pourquoi pas depuis la montre ?** Parce que Wear OS ne le permet pas : la session est validée, la
plateforme demande confirmation, et son propre installateur répond *« Install/Uninstall actions not
supported on Wear »* — vérifié sur l'émulateur API 30 et sur une TicWatch Pro 3 Ultra en Wear 3.5.
Toutes les voies de contournement sont fermées à une application ordinaire.

## Après l'installation

- **Les réglages** sont derrière un appui long sur le cadran, puis le crayon. Tout ce dont le
  cadran a besoin, il le demande là — la localisation pour la météo, le Nadir et le verrouillage
  de site ; les permissions capteurs au moment où un emplacement qui en exige une est choisi. Rien
  n'est demandé à l'installation. La table complète des permissions est dans le
  [README](../../README.md#permissions).
- **Les mises à jour** arrivent d'elles-mêmes — voir [Mise à jour](#mise-à-jour) ci-dessus ;
  `adb install -r` avec la nouvelle version marche toujours aussi. Les réglages survivent dans
  les deux cas, sauf pour les versions qui changent le schéma des réglages, ce que les notes de
  version signalent quand cela arrive.
- **Pas d'application compagnon, pas de compte.** L'APK est tout le produit.
