# Sans Reels — état du projet

App Android qui **bloque l'onglet Reels et la page Explore dans l'app Instagram officielle**.
Ce n'est pas un client alternatif : Instagram n'expose aucune API de fil d'actualité. L'app
observe l'arbre de vues d'Instagram via un `AccessibilityService`, reconnaît l'écran affiché,
et déclenche un retour arrière quand c'est un écran bloqué.

**Statut : v1 terminée et fusionnée dans `main` (2026-08-16).** 27 commits, 76 tests JVM,
10 tests instrumentés, recette passée sur appareil réel.

## Prochain chantier demandé par l'utilisateur

**Esthétique.** L'utilisateur veut que l'app soit très belle, avec un beau logo et un nom cool.
L'interface actuelle est fonctionnelle et volontairement brute (Material 3 par défaut, barres
d'histogramme rectangulaires, aucune icône personnalisée). Le nom provisoire est « Sans Reels »
(`app_name` dans `strings.xml`), l'identifiant applicatif est `com.insta.reelsoff`.

Points à savoir avant de toucher à l'UI :
- Un seul écran : `app/src/main/kotlin/com/insta/reelsoff/ui/HomeScreen.kt`
- L'app est **100 % Compose**. `com.google.android.material` est délibérément absent et doit
  le rester. Le thème XML `Theme.ReelsOff` (`res/values/themes.xml`) ne sert qu'à peindre la
  fenêtre avant que Compose ne dessine.
- Aucune icône de lanceur personnalisée n'existe encore (pas de `mipmap` custom).
- Changer `applicationId` casserait le composant enregistré dans les réglages d'accessibilité —
  l'utilisateur devrait réactiver le service à la main. Changer `app_name` seul est sans risque.

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

1. **Esthétique, logo, nom** — demande explicite de l'utilisateur, prochain sujet.
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
