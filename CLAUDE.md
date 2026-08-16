# Digue — état du projet

App Android qui **bloque l'onglet Reels et la page Explore dans l'app Instagram officielle**.
Ce n'est pas un client alternatif : Instagram n'expose aucune API de fil d'actualité. L'app
observe l'arbre de vues d'Instagram via un `AccessibilityService`, reconnaît l'écran affiché,
et déclenche un retour arrière quand c'est un écran bloqué.

**Statut : v1 terminée et fusionnée dans `main` (2026-08-16).** 27 commits, 76 tests JVM,
10 tests instrumentés, recette passée sur appareil réel. **Passe esthétique faite ensuite**
(nom, logo, écran), branche `feat/digue-aesthetics`.

## Identité et interface

Le nom affiché est **« Digue »** (`app_name`). L'identifiant applicatif reste
`com.insta.reelsoff` : **le changer casserait le composant enregistré dans les réglages
d'accessibilité** et l'utilisateur devrait réactiver le service à la main. Changer `app_name`
seul est sans risque — c'est ce qui a été fait.

Direction visuelle : **encre sur papier**. Fond crème mat, encre noir chaud, un seul accent
bleu-vert, filets d'un pixel à la place des cartes. Verrouillée en clair.

- Un seul écran : `ui/HomeScreen.kt`. Palette, typo et formes : `ui/Theme.kt`.
- La palette existe en double, `ui/Theme.kt` et `res/values/colors.xml` : Compose ne peut pas
  lire les ressources XML à la compilation, et le XML sert au fond de fenêtre et à l'icône.
  **Les deux doivent bouger ensemble.**
- **Aucune police n'est embarquée ni téléchargée.** Les polices téléchargeables passent par le
  réseau, ce que les invariants interdisent. Le caractère éditorial vient du poids, de la taille
  et de l'interlettrage sur la famille système.
- L'app est **100 % Compose**. `com.google.android.material` est délibérément absent et doit
  le rester. Le thème XML `Theme.ReelsOff` ne fait que peindre la fenêtre en crème avant que
  Compose ne dessine, pour supprimer le flash blanc au lancement.
- **Les formes de boutons ne suivent pas `MaterialTheme.shapes`** : `ButtonDefaults.shape`
  vient des tokens Material et vaut `CornerFull`. Il faut passer `shape =` explicitement à
  chaque bouton, sinon ils restent en gélules alors que tout le reste est à angles vifs.
- Logo : `res/drawable/ic_digue.xml`, dessiné dans une boîte 48×48. L'icône de lanceur
  (`ic_launcher_foreground.xml`) reprend la même géométrie via un `<group>` mis à l'échelle.
  **Toute retouche du dessin doit être reportée dans les trois fichiers** (marque, avant-plan,
  monochrome) et la demi-diagonale de l'encombrement doit rester **sous 33** dans le repère
  108×108, sinon les lanceurs à masque circulaire rognent la marque.

## Commandes

```bash
./gradlew build                                   # tout
./gradlew :detection:test :app:testDebugUnitTest  # 76 tests JVM
./gradlew :app:installDebug                       # installe sur l'appareil
# tests instrumentés : --tests ne marche PAS sur cette version d'AGP, utiliser :
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>
```

## Architecture

```
:detection   Kotlin pur, AUCUN import android.* — c'est LA contrainte structurante
             Surface, Bounds, NodeSummary, ScreenSnapshot, Tier, SignalType, Signal,
             RuleSet, RuleSetParser, Classification, ScreenClassifier
:app         service/  InstagramWatcherService, TreeWalker, NodeLike, AccessibilityNodeLike,
                       Blocker, Clock, NEVER, EventThrottle, CaptureSession, RuleSetLoader
             data/     BlockEvent, BlockEventDao, AppDatabase, DailyCount, dailyCounts,
                       SettingsStore, BlockSettings
             ui/       MainActivity, HomeScreen, HomeViewModel, ServiceStatus
```

`:detection` ne connaît jamais `AccessibilityNodeInfo`. Le service traduit l'arbre Android en
`ScreenSnapshot` neutre, puis appelle une fonction pure. C'est ce qui rend la détection testable
sur JVM contre de vrais arbres capturés.

## Invariants — à ne jamais casser

1. **`:detection` sans aucun import `android.*`.**
2. **Le champ `text` des vues n'est JAMAIS lu, journalisé ni persisté.** Un service
   d'accessibilité voit tout le texte à l'écran. `AccessibilityNodeLike` est la frontière : il
   ne lit que `viewIdResourceName`, `contentDescription`, `className`, `isSelected`,
   `isClickable`, `boundsInScreen`.
3. **Aucune dépendance ni appel réseau, jamais.** Rien ne quitte l'appareil.
4. **Aucune exception ne peut s'échapper de `onAccessibilityEvent` ni de `onServiceConnected`.**
   Android peut désactiver définitivement un service qui plante — l'utilisateur se croit alors
   protégé sans l'être. C'est le pire résultat possible, pire qu'un blocage qui ne marche pas.
5. **Versions dans `gradle/libs.versions.toml`**, jamais de coordonnée en dur.
6. Textes d'interface en français ; code, symboles et commits en anglais.

## Règles de détection

`app/src/main/assets/rules.json`, surchargeable par `filesDir/rules.json` (édition à la main sur
le téléphone pour réparer sans recompiler). Trois paliers de confiance :

| Palier | Signal | Robustesse |
|---|---|---|
| HIGH | identifiant de ressource | casse au renommage |
| MEDIUM | `contentDescription` | dépend de la langue |
| LOW | position dans la barre du bas, trouvée géométriquement | survit aux renommages |

Valeurs réelles calibrées depuis des captures d'Instagram 442.0.0.46.79 :
`clips_tab` (Reels, indexInParent **1**), `search_tab` (Explore, indexInParent **3**).

**REELS n'a que HIGH et LOW, pas de MEDIUM — c'est délibéré.** La capture réelle du fil
d'actualité contient un nœud résiduel hors écran libellé « Reels » avec `isSelected=true`
(Instagram ne démonte pas l'état de l'écran précédent). Une règle par libellé aurait donc bloqué
le fil de l'utilisateur. **Ne jamais réintroduire ce palier.**

## Faits sur l'appareil de test

- Pixel 9a, série `<retiré>`, Android 17, USB
- Écran 1080x2424. **Centres des onglets : y = 2298** (barre 2235..2361).
  Ne PAS utiliser y=2350 : trop près de la zone de navigation gestuelle, les taps sont
  interceptés et mettent Instagram en arrière-plan.
  x par index : 0=108 fil, 1=324 **Reels**, 2=540 messages, 3=756 **Explore**, 4=972 profil
- Activer le service (l'app n'a pas le droit de le faire elle-même) :
  ```bash
  adb shell settings put secure enabled_accessibility_services com.insta.reelsoff/com.insta.reelsoff.service.InstagramWatcherService
  adb shell settings put secure accessibility_enabled 1
  ```
  **Piège : la commande échoue silencieusement juste après un `installDebug`** — le gestionnaire
  de paquets n'a pas fini d'enregistrer le composant, et `settings put` retourne 0 quand même.
  **Toujours relire avec `settings get` et réessayer si ça renvoie `null`.** Une passe de tests
  lancée avec le service désactivé ressemble à un succès et ne mesure rien.
- **Piège : `adb shell am force-stop com.insta.reelsoff` désactive le service.** Android retire
  de `enabled_accessibility_services` le service d'un paquet arrêté de force, et le réglage
  repasse à `null` quelques secondes plus tard. Une relecture immédiate renvoie encore la bonne
  valeur, donc la vérification standard ne voit rien. **Toujours activer le service en dernier,
  après le dernier redémarrage de l'app, et relire au moins 5 s après.** Pour relancer l'écran
  sans casser le service, utiliser `KEYCODE_HOME` puis `am start`, jamais `force-stop`.
- **Piège : une réinstallation remet le drapeau « réglages restreints » du paquet.** Android
  refuse alors l'accessibilité pour une app installée hors magasin et révoque le réglage.
  À relancer après chaque `installDebug` :
  ```bash
  adb shell appops set com.insta.reelsoff ACCESS_RESTRICTED_SETTINGS allow
  ```
- **La source de vérité est `dumpsys`, pas `settings get`.** Le service tourne réellement
  quand cette commande renvoie une ligne :
  ```bash
  adb shell dumpsys accessibility | grep -A1 "Bound services"
  ```
  L'écran d'accueil de l'app peut afficher « Service inactif » alors que le service est lié :
  le statut n'est relu qu'au `onResume`, donc un lancement concomitant de l'activation lit une
  valeur périmée. Repasser par l'accueil et rouvrir l'app suffit à rafraîchir.
- Couper le service (noter les guillemets, sinon « Bad arguments ») :
  ```bash
  adb shell "settings put secure enabled_accessibility_services ''"
  adb shell settings put secure accessibility_enabled 0
  ```
- Le déclencheur de capture `am broadcast` ne marche PAS depuis un shell (récepteur non exporté,
  posture correcte). Lancer `MainActivity` par adb et taper le bouton « Capturer 60 secondes ».

## Méthode de vérification qui marche

Pour savoir si l'utilisateur peut rester coincé dans Reels : taper une fois, puis **rester
immobile 12 secondes**. S'il était resté sur Reels, le service détecterait en continu et
journaliserait un nouvel épisode toutes les ~2 s. Le silence prouve le retour au fil.
Mesuré 5/5 correct. Le simple comptage de blocages est trompeur : sous taps rapprochés,
l'animation de transition d'Instagram avale les taps, donc Reels n'apparaît jamais et il n'y a
rien à bloquer. Le compte mesure la réactivité d'Instagram, pas la fiabilité du bloqueur.

## Confidentialité — incident survenu, ne pas répéter

Les captures d'arbres de vues contiennent de **vraies données personnelles** : noms de contacts
et **extraits de conversations privées** dans les `contentDescription`, pseudo et nombre
d'abonnés. Elles ont été commitées une fois, puis nettoyées et l'historique réécrit
(`git commit --amend` + purge du reflog + `gc --prune=now`).

Leçons :
- `git log --all` ne prouve PAS qu'une donnée a disparu : il parcourt les références, pas le
  reflog. Vérifier en balayant tous les objets (`git cat-file --batch-all-objects`).
- La frontière « on ne lit pas `text` » est vraie mais insuffisante : Instagram met des aperçus
  de messages dans `contentDescription`, que l'app lit.
- Les fixtures actuelles sont nettoyées : toutes les `contentDescription` valent `[scrubbed]`
  sauf 7 chaînes de chrome Instagram (`Reels`, `Home`, `Rechercher et explorer`, `Profil`,
  `Plus`, `Créer un reel`, `Créer`). **`Reels` doit rester** : c'est le nœud piège du fil.
- Ne jamais commiter de capture d'écran d'Instagram ni de capture d'arbre brute.

## Chantiers de suite, par priorité

1. ~~Esthétique, logo, nom~~ — **fait.** Voir « Identité et interface » plus haut.
2. **Heuristique de la barre de navigation (F9, différée).** `ScreenClassifier.findNavBar`
   retient « ≥4 frères cliquables, la rangée la plus basse ». Sur les captures réelles cela
   laisse 3-4 rangées candidates par écran, départagées par la seule géométrie. Un panneau ou
   une feuille à 4 boutons pourrait déplacer la vraie barre. C'est le seul repli restant pour
   REELS après la suppression du palier MEDIUM. Resserrer demande des seuils qui pourraient
   casser sur d'autres géométries d'écran : à faire avec des captures sur plus d'un appareil,
   plus un départage déterministe en cas d'égalité.
3. **Résiduels connus, non bloquants** : le statut de chargement des règles n'est écrit qu'au
   `onServiceConnected`, donc le bandeau persiste après réparation jusqu'à reconnexion du
   service ; la cause interpolée est du texte anglais dans une phrase française ;
   `isServiceEnabled` n'a pas de test car le code n'a pas de couture pure pour `Settings.Secure`.
4. **Non vérifié** : survie à un redémarrage, persistance sur 24 h. Commandes dans la recette.

## Limite produit à connaître

Bloquer Explore bloque aussi **la recherche Instagram** : c'est le même onglet (`search_tab`).
L'utilisateur ne peut pas chercher un compte sans désactiver l'interrupteur Explore.

## Idée 2, hors périmètre

Un robot qui parcourt ~100 Reels par jour et les résume est un **projet distinct**. Deux
réserves consignées : automatiser le compte correspond au comportement que cherche la détection
anti-robot (risque de bannissement réel, contrairement à cette app qui n'émet aucune requête) ;
et résumer une vidéo suppose téléchargement, transcription et analyse visuelle. Question de fond :
déléguer les Reels ne les supprime pas. Le journal `block_event` fournirait la mesure de départ.
