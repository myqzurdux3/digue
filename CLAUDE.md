# Digue — état du projet

App Android qui **bloque les fils de vidéos courtes dans plusieurs apps officielles**.
Ce n'est pas un client alternatif : aucune de ces apps n'expose d'API de fil. Digue observe
leur arbre de vues via un `AccessibilityService`, reconnaît l'écran affiché, et agit —
retour arrière la plupart du temps, ou un appui sur un nœud précis pour Explore.

**Cinq surfaces bloquées**, chacune avec son interrupteur :

| App | Surface | Signal principal |
|---|---|---|
| Instagram | `REELS` | `clips_tab`, et le lecteur `clips_viewer_view_pager` |
| Instagram | `EXPLORE` | `search_tab` — **redirige** vers la recherche au lieu de sortir |
| YouTube | `SHORTS` | `reel_player_page_container`, `reel_progress_bar` |
| Snapchat | `SPOTLIGHT` | `spotlight_container` |
| Snapchat | `DISCOVER` | la colonne d'actions `context_vertical_actions/...` |

**Statut au 2026-08-17.** 266 tests JVM verts et **24 tests instrumentés passés sur
l'appareil** — une première : ils ne compilaient plus du tout, `BlockEventDaoTest` appelant
`dao.since(...)`, une requête retirée du DAO quand l'écran est passé aux `Flow`, et personne
n'ayant relancé `compileDebugAndroidTestKotlin` depuis. Un `@Test` qui ne compile pas se compte
exactement comme un `@Test` qui passe. Arbre propre. Dépôt distant : `github.com/myqzurdux3/digue`, **privé**
(le dépôt documente les habitudes de l'utilisateur, et les commits antérieurs au 2026-08-17
portent encore le numéro de série de son téléphone — une ouverture demanderait une réécriture
d'historique).

Vérifié sur son appareil : les cinq surfaces, le quota avec sa plage horaire et son verrou, la
migration Room 1 → 2, l'enregistrement du temps regardé, et la **survie à un redémarrage**.

**Passe d'appareil du 2026-08-17, après l'audit**, tout mesuré et non déduit :

- **Reels : bloqué.** Un appui sur l'onglet, puis 12 s d'immobilité — **un seul** épisode
  `REELS`/HIGH puis silence, et l'écran est le fil. Coincé, le service en aurait journalisé un
  toutes les ~2 s.
- **L'écriture des captures est bien hors du fil principal.** Prouvé par logcat : l'échec d'une
  écriture est journalisé depuis le tid 13044 alors que le processus est 10804. Le déport
  demandé par l'audit fonctionne.
- **La purge des captures fonctionne.** Trois fichiers écrits par l'app, un nouvel armement,
  dossier vide. **Piège de méthode** : des fichiers factices poussés par `adb shell`
  appartiennent à `shell:ext_data_rw` et l'app **ne peut pas les supprimer**, même en `run-as` —
  le premier test était donc invalide, pas le code. Créer le dossier `captures` en `adb shell`
  le rend aussi **non inscriptible** par l'app (`EACCES`) : le supprimer et laisser l'app le
  recréer.
- **Le tag `ReelsOff` remonte de nouveau dans logcat** sur cet appareil, contrairement à ce que
  ce fichier a longtemps dit.
- **Un appui `adb` synthétique n'est PAS un instrument valable pour la redirection d'Explore.**
  Mesuré ainsi — `input swipe` sur l'onglet puis copies d'écran à 2 s et 14 s, plus
  `mInputShown` — la redirection paraissait ne pas se produire : clavier fermé, grille
  utilisable, aucun nouvel épisode sur 20 s. **Vérifié au doigt par l'utilisateur dans la
  minute : elle se produit.** La conclusion « Explore ne redirige plus » était fausse, et elle
  a bien failli être écrite comme un défaut. Ce que la mesure établissait vraiment, et qui
  reste vrai, c'est qu'Explore est détecté au palier HIGH et qu'il n'y a **qu'un seul épisode
  par visite** — ce qui est exactement la signature d'un clic qui a réussi.
- **`connectedDebugAndroidTest` désinstalle bien l'app**, ce qui **efface la base**. Sauvegarder
  avant : `adb exec-out run-as com.insta.reelsoff cat databases/reelsoff.db`, plus le `-wal`, et
  `files/datastore/settings.preferences_pb`. Restaurer via `base64` sur l'entrée standard —
  `adb push` vers le stockage externe ne marche pas, l'app n'y a pas accès en `run-as`.

**Trois comportements fins, à ne pas casser — les trois revérifiés à la main par
l'utilisateur le 2026-08-17, APRÈS le remaniement de l'audit :**

1. **Un reel qu'un contact envoie en message reste regardable**, mais les reels suggérés qui
   suivent sont bloqués.
2. **Ouvrir l'onglet Explore appuie sur la barre de recherche** au lieu de sortir de l'onglet,
   parce que bloquer Explore bloquait aussi la seule recherche d'Instagram. **Revérifié à la
   main le 2026-08-17 : fonctionne.**
3. **Une story d'un ami sur Snapchat reste regardable**, les vidéos Discover non.

Les trois tiennent après l'audit. Les deux premiers ont été confirmés par
l'utilisateur lui-même — un reel envoyé par un ami en message privé, et une story
d'ami — parce qu'ils touchent de vraies conversations et que je ne les ouvre pas
de mon côté. Le troisième est prouvé par `block_event` : `DISCOVER` bloqué,
`SPOTLIGHT` bloqué, aucune ligne pour la story.

Le quota ne change aucun des trois : quand un laissez-passer est ouvert, le service remet
simplement un **ensemble vide** de surfaces bloquées au `Blocker`, ce qui est son chemin déjà
testé « surface non bloquée ». Les règles de détection ne sont pas consultées différemment.

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
  **Toute retouche du dessin doit être reportée dans les quatre fichiers** — marque, avant-plan,
  monochrome, plus `docs/brand/digue.svg` pour le README — et la demi-diagonale de
  l'encombrement doit rester **sous 33** dans le repère 108×108, sinon les lanceurs à masque
  circulaire rognent la marque.
- **Le SVG du README est en SVG, pas en PNG, exprès** : c'est le même genre d'objet que le
  vecteur Android — des rectangles et des couleurs — donc une dérive se lit dans un diff, là
  où un binaire aurait dérivé en silence. Il porte en plus un fond papier que les trois autres
  n'ont pas, et ce fond n'est pas décoratif : le mur est en encre quasi noire et GitHub rend un
  README en thème clair **ou** sombre. Sans tuile, sur fond sombre, le mur disparaît et il ne
  reste que trois vagues — l'inverse de ce que la marque raconte.
- **La marque actuelle est le design 1A, « la digue et la houle »** : quatre rectangles à fond
  aligné, trois vagues qui montent vers un mur plus large et plus haut qu'elles. Encombrement
  39 × 42 centré dans la boîte de 48 ; échelle 1,1 et translation 27,6 sur les deux axes dans
  le repère 108×108, ce qui donne 42,9 × 46,2 centré sur (54, 54) et une demi-diagonale de
  **31,5**. Une échelle de 1,15 atteindrait déjà 32,96 : la règle sert à garder une marge, pas
  à la frôler.
- **Trois couleurs n'existent que pour la marque** : `houle_basse`, `houle_moyenne`,
  `houle_haute`, dans `colors.xml` seulement. Elles **n'ont volontairement pas de jumelle dans
  `Theme.kt`** — les vecteurs résolvent `@color` eux-mêmes, Compose ne les dessine jamais, un
  `val` serait mort le jour où il serait écrit. C'est la seule exception à la règle « les deux
  fichiers bougent ensemble », et rien dans l'interface ne doit s'en servir : ce n'est pas un
  second accent. Le mur reprend `encre` plutôt que le `#14231E` du design — deux noirs à six
  unités d'écart dans une palette tenue courte exprès, et l'écart est invisible à la taille
  d'une icône.
- **`accent` n'est plus référencé par aucun drawable** depuis 1A, et doit rester dans
  `colors.xml` : il y est la moitié XML de `Theme.kt`, ce qui est sa raison d'être. Ne pas le
  balayer comme ressource morte.

## Commandes

```bash
./gradlew build                                   # tout
./gradlew :detection:test :app:testDebugUnitTest  # 266 tests JVM
./gradlew :app:installDebug                       # installe sur l'appareil
# tests instrumentés : --tests ne marche PAS sur cette version d'AGP, utiliser :
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>
```

## Architecture

```
:detection   Kotlin pur, AUCUN import android.* — c'est LA contrainte structurante
             Surface, Bounds (+ isOnScreen), NodeSummary, ScreenSnapshot,
             Tier, SignalType, Signal, SurfaceRules, AppRules, RuleSet, RULES_VERSION,
             RuleSetParser, Classification, ScreenClassifier
:app         service/  InstagramWatcherService, TreeWalker, NodeLike, AccessibilityNodeLike,
                       Blocker, Clock, NEVER, EventThrottle, CaptureSession, RuleSetLoader,
                       DeclaredPackages (declaredPackages, packageNamesFor),
                       Allowance      (AllowanceSettings, AllowanceState, windowContains,
                                       minuteOfDay, epochDayOf, consumedMillisAt,
                                       remainingMillis, passIsOpen, canOpenPass, openPass,
                                       closePass, settle, PassClosure, closureOf,
                                       forcedClosureOf)
                       AllowanceLock  (LockedSettings, PendingChange, isLoosening, armChange,
                                       hasMatured, effectiveSettings,
                                       effectiveBlockedSurfaces)
             data/     BlockEvent, BlockEventDao, AppDatabase (+ MIGRATION_1_2),
                       DayBuckets     (bucketByDay — le fenêtrage en jours locaux,
                                       partagé, ce qui fait que les deux séries du
                                       graphique s'alignent index pour index)
                       DailyCount, dailyCounts, PassEvent, PassEventDao,
                       DailyWatched, dailyWatched,
                       SettingsStore, BlockSettings, RuleLoadStatus, CaptureStatus
             ui/       MainActivity, HomeScreen, HomeViewModel, ServiceStatus, Theme,
                       CaptureProgress, SurfaceGroups, TodayBreakdown, HistoryChart,
                       MaintenancePanel, AllowanceUiState, AllowancePanel,
                       AllowanceEditors, Format (formatDuration, formatChoice,
                       formatMinuteOfDay)
```

**Le service ne s'appelle plus que par habitude `InstagramWatcherService`** — il couvre
maintenant cinq surfaces sur trois apps. Le renommer casserait le composant enregistré dans
les réglages d'accessibilité, exactement comme l'identifiant applicatif. Ne pas le faire.

`:detection` ne connaît jamais `AccessibilityNodeInfo`. Le service traduit l'arbre Android en
`ScreenSnapshot` neutre, puis appelle une fonction pure. C'est ce qui rend la détection testable
sur JVM contre de vrais arbres capturés.

**`:detection` lit les assets de `:app`, et c'est invisible depuis `:app`.**
`detection/build.gradle.kts` ajoute `app/src/main/assets` aux ressources de **test** de
`:detection`, pour que `RealFixtureTest` lise le `rules.json` réellement livré plutôt qu'une
copie. C'est ce qui fait qu'un `rules.json` cassé est attrapé par un test JVM avant d'atterrir
sur le téléphone. Le couplage est voulu ; déplacer le fichier de règles demande de toucher les
deux modules.

**`Allowance` et `AllowanceLock` sont purs mais vivent dans `:app`, pas dans `:detection`** —
comme `Blocker`. Ils ne reconnaissent aucun écran ; les mettre dans `:detection` élargirait ce
module à autre chose que de la détection. Ils sont testés sur JVM exactement comme `Blocker`.

**Deux horloges, et il ne faut pas les confondre.** `Clock`/`SystemClock` rend
`elapsedRealtime`, monotone, dont se servent tous les délais de `Blocker`. Le quota, lui,
raisonne en **horloge murale plus `ZoneId`**, parce qu'une plage horaire est une question
d'heure locale et qu'`elapsedRealtime` ne s'y convertit pas. Le verrou utilise **les deux** :
horloge murale pour l'échéance affichée, `elapsedRealtime` pour empêcher qu'avancer l'horloge
fasse mûrir un délai.

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

`app/src/main/assets/rules.json`, **format version 2, indexé par paquet puis par surface**,
surchargeable par `filesDir/rules.json` (édition à la main sur le téléphone pour réparer sans
recompiler). Un fichier version 1 est rejeté proprement, jamais migré en douce. L'analyseur
**ne jette jamais**.

Trois paliers de confiance :

| Palier | Signal | Robustesse |
|---|---|---|
| HIGH | identifiant de ressource | casse au renommage |
| MEDIUM | `contentDescription` | dépend de la langue |
| LOW | position dans la barre du bas, trouvée géométriquement | survit aux renommages |

Un signal porte quatre options, toutes payées par un défaut trouvé sur du vrai :

- **`requireSelected`, défaut `true`.** Piège majeur : la plupart des nœuds hors barre
  d'onglets sont à `isSelected=false`, donc **toute règle sur un lecteur doit poser
  `false` explicitement**, sinon elle ne se déclenche jamais — indiscernable d'une règle
  qui marche.
- **`requireOnScreen`, défaut `false`.** N'accepte que les nœuds d'aire strictement positive.
- **`absentViewIds`.** Le signal ne compte que si aucun de ces identifiants n'est visible.
- **`clickViewId`** (au niveau de la surface). Appuie sur ce nœud au lieu de quitter l'écran.

### Quelle surface gagne quand deux répondent

Dans un palier donné, `ScreenClassifier` essaie les surfaces **dans l'ordre de l'énumération
`Surface`**, pas dans celui du fichier de règles. Ce n'est pas un détail d'implémentation : un
écran peut satisfaire deux surfaces de la même app au même palier — un Spotlight qui porterait
une colonne d'actions verticale répondrait à `SPOTLIGHT` **et** à `DISCOVER` — et la gagnante
décide quel interrupteur le gouverne. Adosser ça à l'ordre des clés d'un objet JSON, qu'une
édition à la main réordonne sans y penser, ferait taire un interrupteur sans un mot. Un test le
vérifie en construisant la même carte dans les deux ordres.

### Le piège qui s'est produit trois fois : les nœuds résiduels

**Ces apps ne démontent pas l'écran précédent.** Ses nœuds restent dans l'arbre avec des
bornes dégénérées. Mesuré trois fois, sur trois identifiants différents :

| Cas | Nœud résiduel | Bornes |
|---|---|---|
| fil Instagram | libellé « Reels », `isSelected=true` | hors écran |
| fil / profil / conversation | `clips_viewer_view_pager` | largeur **0** et **−2160** |
| fil Instagram | tout l'onglet Explore pré-monté | `left=3240, right=1080` |

D'où `requireOnScreen`. **Toute nouvelle règle sur un conteneur doit le poser**, sinon elle
bloquera le fil de l'utilisateur.

**REELS n'a toujours pas de palier MEDIUM, et ne doit pas en avoir.** `requireOnScreen` rend
la chose techniquement possible ; ce serait un changement de comportement non demandé sur le
chemin le plus dangereux de l'app.

### Les deux discriminations fines, et pourquoi elles sont ainsi

**Reel reçu en message.** Le reel qu'un contact envoie porte `reel_viewer_message_composer`,
`reply_bar_container`, `sender_username_or_fullname` ; le reel suggéré qui suit n'en a aucun,
et porte `suggested_title`. Vérifié que la barre de réponse **ne s'estompe pas** : 16
instantanés sur 46 s sans interaction, marqueurs présents du premier au dernier.

**Story d'ami contre vidéo Discover, sur Snapchat.** Les deux jouent dans le **même**
`opera_viewer` plein écran — vérifié une seconde fois le 2026-08-17 : il est à l'écran dans
Spotlight, Discover **et** la story d'ami. Toute règle qui s'appuierait dessus bloquerait les
trois. Le discriminant est la **colonne d'actions verticale**, absente des stories d'amis.

Sur `chrome_subscribe_button`, deux mesures se contredisent. La première le donnait présent
des deux côtés, donc inutilisable ; la capture du 2026-08-17 le montre présent côté Discover
et **absent** côté story. Un échantillon ne renverse pas une mesure antérieure, et la règle
n'a pas été touchée : la colonne d'actions marche dans les deux lectures. À trancher avec
d'autres captures avant d'y toucher — et se souvenir que l'analyse d'origine tronquait les
identifiants après le dernier `/`, ce qui a déjà produit une conclusion fausse ici.

**`reel_progress_bar` n'apparaît pas** dans la capture Shorts du 2026-08-17 — `youtube_shorts.json`
porte treize identifiants `reel_*` distincts, pas celui-là. Le premier signal
(`reel_player_page_container`) suffit et la fixture le couvre ; le second est donc moins fiable
qu'annoncé, et ne doit pas être considéré comme un filet.

**Il a néanmoins été gardé, et le raisonnement qui a failli le supprimer mérite d'être écrit**,
parce qu'il est faux et qu'il reviendra. Un audit l'a listé comme « code mort » : il ne
correspond à rien dans les deux fixtures. Mais un signal de détection n'est pas du code — il
n'apparaît dans **aucune** des deux captures, donc il ne peut produire **aucun** faux positif,
et il ne coûte qu'une comparaison de chaînes par marche. Il ne peut qu'**ajouter** une
détection. Or SHORTS n'a ni palier MEDIUM ni LOW : le retirer aurait laissé la surface sur un
seul identifiant, sans filet, le jour où Google renomme `reel_player_page_container`. C'est un
changement de comportement dans le sens **moins sûr**, et la doctrine de ce fichier vaut ici
aussi : un échantillon ne renverse pas une mesure antérieure.

**Le remplaçant évident, lui, est bel et bien disqualifié.** `reel_time_bar` est le seul autre
candidat de la capture Shorts, et il est **aussi présent sur l'accueil YouTube**, avec des
bornes plein écran `{0, 0, 1080, 2424}` — vérifiable dans `youtube_home.json`. `requireOnScreen`
ne le filtrerait donc pas, et le poser en signal bloquerait le fil YouTube. Un test l'interdit
désormais comme propriété, pas comme cas particulier : aucun signal SHORTS ne peut nommer un
identifiant que l'accueil YouTube affiche aussi.

### Identifiants : deux pièges de nommage

- **Snapchat obfusque 70 % de ses identifiants** (`0_resource_name_obfuscated`) : 241 nœuds
  obfusqués sur 343 en portant un, mesuré sur les trois fixtures Snapchat
  (`snapchat_spotlight`, `snapchat_discover`, `snapchat_story`). Refaisable en comptant les
  `viewId` **ni nuls ni vides** — 7 nœuds portent la chaîne vide, et les inclure au
  dénominateur donne 69 %, ce qui est la même chose dite moins proprement. Il ne reste que 38
  identifiants distincts lisibles, dont trois d'Android. Les règles Snapchat tiennent sur cette
  poignée de survivants et sont les plus fragiles du projet.
- **Snapchat expose une partie de son arbre hors du `<paquet>:id/` habituel** :
  `context_vertical_actions/context_vertical_action_comment`. La règle Discover a été livrée
  cassée une fois pour cette raison. **En analysant une capture, ne jamais tronquer
  l'identifiant après le dernier `/`** — c'est exactement ce qui avait masqué le défaut.

### Sources externes utiles

Trois projets comparables ont été lus. **Scrolless** (`duartebarbosadev/Scrolless`) est le plus
proche : il a convergé indépendamment sur `clips_viewer_view_pager`, et c'est de lui que
viennent les identifiants YouTube et Snapchat, plus l'idée du garde `suggested_title`. Il
couvre aussi TikTok (`player_view`, sortie par **accueil** et non retour) et Facebook.
**NoReel** est un autre paradigme — un navigateur qui injecte du JavaScript dans le site web
d'Instagram — et **télécharge son script depuis GitHub à l'exécution**, ce que nos invariants
interdisent. **Shorts-Blocker** est à ne pas imiter : il ne parcourt que 10 nœuds et ne filtre
aucun paquet.

## Faits sur l'appareil de test

- Pixel 9a, Android 17, branché en USB (`adb devices` donne son numéro de série ;
  il n'est pas noté ici, c'est un identifiant matériel qui n'apporte rien au projet)
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
- **Piège : `./gradlew :app:connectedDebugAndroidTest` désinstalle l'app en fin de
  course** (nettoyage par défaut d'AGP), ce qui retire le service d'accessibilité.
  C'est la commande que cette recette prescrit elle-même. **Après toute passe de
  tests instrumentés : réinstaller, rejouer `appops`, réactiver le service, et
  relire `dumpsys` au moins 5 s après.**
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

## Capturer une surface qui est bloquée

**Piège qui a fait rater la première capture Shorts.** Le service ne reçoit d'événements que
des paquets dont une surface est **allumée** — donc pour capturer Shorts il faut allumer
Shorts, mais l'allumer le fait bloquer, et la capture n'enregistre que l'écran précédent.
Mesuré : `block_event` portait deux SHORTS en plein dans la fenêtre, et les quatre instantanés
ne contenaient que l'accueil YouTube.

**La sortie est le quota** : un pass ouvert suspend le blocage tout en laissant le service
observer. Recette, pour chaque cible :

1. allumer l'interrupteur de la surface visée ;
2. dans Digue, **Ouvrir** un pass ;
3. **Capturer 60 secondes**, tout en bas de l'écran, section Maintenance ;
4. aller dans l'app et naviguer **lentement** — un instantané toutes les 3 s ;
5. revenir et **Fermer maintenant**, pour rendre le temps non consommé.

Les fichiers sortent dans `/sdcard/Android/data/com.insta.reelsoff/files/captures/`, lisibles
sans `run-as`. **Ils contiennent de vraies données personnelles** — les nettoyer avant tout
usage, et ne jamais les commiter.

**Armer une capture efface celles d'avant.** Le dossier n'était jamais vidé, donc chaque capture
jamais prise s'y accumulait, en clair, dans le stockage externe que le gestionnaire de fichiers
du téléphone sait ouvrir. C'est borné à une session maintenant. Le stockage externe est conservé
délibérément — passer à `filesDir` supprimerait l'exposition mais imposerait `run-as` à cette
recette-ci. **Récupérer les fichiers avant de relancer une capture.**

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
- Ne jamais commiter de capture d'écran ni de capture d'arbre brute, d'aucune des trois apps.
- **Les fixtures YouTube et Snapchat existent depuis le 2026-08-17** :
  `youtube_shorts`, `youtube_home`, `snapchat_spotlight`, `snapchat_discover`,
  `snapchat_story`. Trois sont des cas **négatifs** — l'accueil YouTube et la story d'ami ne
  doivent pas être bloqués. Toutes les `contentDescription` valent `[scrubbed]`, sans
  exception : les arbres bruts portaient un nom de groupe et l'aperçu d'un message dans une
  bannière de notification. Un test l'affirme, pour qu'une fixture ajoutée sans précaution
  soit attrapée avant d'atterrir.

## Ce que le service a le droit de voir

`android:packageNames` est **appliqué par Android**, pas par l'app : un paquet absent de la
liste est *incapable* d'atteindre le service. C'est la garantie la plus forte du projet.

**La permission suit l'interrupteur.** Le service redéclare sa liste à l'exécution via
`setServiceInfo`, depuis les surfaces activées (`declaredPackages`). Snapchat éteint, Snapchat
n'est pas dans la liste. Les nouvelles surfaces arrivent **éteintes**.

Trois choses à ne jamais casser là-dedans :

1. **Le plancher statique doit rester dans le manifeste.** `android:packageNames` y vaut
   Instagram seul. Une liste **nulle signifie « toutes les applications »** pour Android, et
   la déclaration à l'exécution peut ne jamais tourner — une exception au démarrage suffit.
   Le plancher n'est pas un plafond : `setServiceInfo` élargit librement au-delà. Ce défaut a
   été introduit une fois, par le plan, et rattrapé en revue finale.
2. **Une sélection vide s'écrit comme un paquet impossible**, jamais comme `null` ni comme un
   tableau vide (`packageNamesFor`).
3. **`applyDeclaredPackages` ne doit rien laisser échapper** : elle tourne dans le collecteur
   de réglages, et l'invariant 4 s'applique.

**Cette propriété n'est PAS vérifiée par la mesure.** `dumpsys accessibility` n'expose pas la
liste de paquets d'un service sur l'appareil de test, et un test par le comportement ne peut
pas y suppléer, puisque les réglages conditionnent le blocage de toute façon. Elle repose sur
le contrat documenté de `setServiceInfo`, plus les tests des deux fonctions pures.

## Quota, plage horaire et verrou

Spec et plan dans `docs/superpowers/`. Cinq minutes de vidéo courte
par jour, ouvrables seulement dans une plage choisie, et un verrou qui rend tout desserrement
lent au lieu d'instantané.

**Ce que le verrou ne peut pas atteindre, et qu'il faut dire tel quel :** couper le service
d'accessibilité dans les réglages Android reste à un geste, et désinstaller Digue aussi. Les
deux sont hors de l'app. Le verrou protège les réglages *de Digue*, rien d'autre. Un mot de
passe a été écarté pour cette raison exacte : un secret que l'utilisateur choisit lui-même ne
vaut rien contre lui-même, alors qu'un délai tient même en connaissant tout le code.

**Le temps se compte à l'horloge murale depuis un déblocage explicite**, pas en temps d'écran
réellement passé sur les surfaces. Le comptage d'écran est plus juste et a été écarté : le
service ne reçoit d'événements que des paquets qu'il déclare, donc « il est parti » ne
s'observe pas, et surtout ça permettrait de **mettre le compteur en pause** en sortant de
l'app trois secondes. Le bouton « Fermer maintenant » rend le temps non consommé.

Quatre pièges, chacun trouvé en écrivant, chacun capable de vider le verrou de son sens :

1. **`enabled = false` est l'état le plus STRICT, pas le plus permissif.** Le quota *accorde*
   du temps ; le blocage des surfaces ne dépend pas de lui. Donc **allumer** le quota est un
   assouplissement (différé) et l'**éteindre** est un resserrement (immédiat). Écrit à
   l'envers, le verrou se défait en une écriture.
2. **`cooldownMillis` vaut zéro à l'installation.** Sinon, allumer le quota armerait une
   attente d'un jour au moment même où l'utilisateur active la fonction. À zéro les réglages
   s'arrangent librement ; **choisir un délai est un resserrement**, donc immédiat, et
   verrouille tout ce qui suit. Le verrou s'arme délibérément, en un geste.
3. **Raccourcir le délai est un assouplissement**, et le délai facturé à l'armement est
   celui **en vigueur**, jamais celui proposé. Sinon : délai à zéro, applique tout de suite,
   verrou disparu.
4. **La maturité exige que les deux horloges soient d'accord.** L'horloge murale est à
   l'utilisateur : l'avancer d'une semaine ferait mûrir tout changement en attente.
   `elapsedRealtime` ne se règle pas, il se remet à zéro au redémarrage — ce qui se voit à une
   valeur inférieure à celle armée, et là l'horloge murale décide seule, faute de mieux.
   **Cette parade n'existe pas pour la plage horaire ni pour la remise à zéro quotidienne** :
   la plage *est* une question d'heure locale. Limite assumée.

**La comparaison de deux plages se fait sur un ensemble de 1440 minutes**, pas en arithmétique
d'intervalle circulaire. Une plage peut être à cheval sur minuit, et c'est exactement là qu'un
décalage d'un cran transformerait silencieusement un resserrement en assouplissement.
Une fin **égale** au début est une plage **vide**, jamais ouvrable — entre les deux lectures
possibles, celle qui bloque.

**`writeThroughLock` dans `HomeViewModel` est la porte unique.** Toute écriture de réglage y
passe, y compris `setSurfaceBlocked` : un verrou qui ne garderait que le quota se contournerait
en éteignant REELS. Un resserrement **annule aussi tout changement en attente**, sinon
l'assouplissement encore armé déferait le resserrement plus tard, sans rien à l'écran pour le
dire.

**Vérifié sur l'appareil le 2026-08-17**, par paire :

| Geste | Pass ouvert | Pass fermé |
|---|---|---|
| Appui long sur l'onglet Reels, puis 13 s d'immobilité | reste sur Reels, **0 épisode** | **2 épisodes** REELS/HIGH, retour au fil |

Vérifié aussi : le décompte descend en direct (57 min 56 s → 57 min 42 s en 14 s) ; choisir un
délai s'applique **sans attente** et n'arme rien ; augmenter le quota avec un délai en vigueur
est **retenu** dans `pending_change` avec la bonne échéance ; « Annuler » retire l'attente
immédiatement ; l'affichage correspond au protobuf au millième près.

**Non vérifié sur appareil**, couvert seulement par les tests purs : le resserrement pendant
qu'un changement est en attente, et la maturation réelle d'un délai.

### Le temps regardé

Table `pass_event` (instant de fermeture, durée), à côté de `block_event`. Le compte de
blocages dit à quelle fréquence l'app t'a rattrapé ; celui-ci dit combien de temps tu as
regardé quand même — c'est le chiffre que le quota existe pour faire baisser.

- **C'est le service qui enregistre.** Un pass qui s'épuise pendant que tu défiles n'est
  constaté par personne d'autre : l'écran peut être fermé, et les fonctions pures ne font que
  dériver. `recordAnyClosedPass` le voit à l'événement suivant.
- **`closureFrom` est le seul endroit où la durée se calcule**, pour que le bouton « Fermer
  maintenant » et une expiration ne puissent pas raconter deux choses différentes.
- **La migration 1 → 2 est écrite à la main et `fallbackToDestructiveMigration` est
  délibérément absent** : il effacerait `block_event`, seule preuve que l'app ait jamais
  fonctionné. Comme elle n'a pas pu être jouée sur l'appareil, `exportSchema` est activé et un
  **test JVM compare le `CREATE TABLE` à `app/schemas/…/2.json`**, qui est ce que Room
  exigera. L'autorité est le JSON, pas le code. Ne pas supprimer `app/schemas/` du dépôt.
- **Le temps banqué est plafonné au quota**, et c'est une correction de justesse, pas de
  coquetterie. Un pass ne se ferme que lorsque quelqu'un le constate — le service a besoin
  d'un événement d'une app surveillée, l'écran doit être ouvert — donc un pass laissé courir
  téléphone posé continue d'accumuler de l'horloge murale. Le blocage, lui, était juste :
  `passIsOpen` passe à faux dès l'épuisement. C'est le **chiffre affiché** qui mentait.
  Mesuré sur l'appareil : **48 min 36 s annoncées contre un quota de 30 min**.
- **Défaut connu, non corrigé** : si tu appuies sur « Fermer maintenant » — ou sur
  « Ouvrir », qui settle d'abord — dans les millisecondes où le pass expire, le service et
  l'interface peuvent chacun inscrire une ligne, et la journée compte le pass deux fois.
  Fenêtre minuscule, et l'erreur va dans le sens prudent : elle affiche **plus** de temps
  regardé, jamais moins.
- **Le temps regardé n'est PAS sous-déclaré, contrairement à ce que l'audit a affirmé.**
  Cette entrée a d'abord dit que « Ouvrir » perdait le temps d'un pass expiré. C'est faux, et
  l'erreur mérite d'être gardée : le bouton « Ouvrir » est **désactivé** tant qu'un horodatage
  d'ouverture traîne, parce que `canOpenPass` l'exige nul — donc le chemin qui aurait perdu la
  ligne n'était pas atteignable. Vérifié en énumérant les quatre façons dont un pass finit :
  faute de quota et hors plage laissent `remaining` à 0 ou la plage fermée, donc bouton mort ;
  un jour antérieur donne une durée nulle par conception ; relever le quota ne change rien,
  `closePass` plafonnant l'écoulé au quota. **Aucun cas ne rend l'écriture atteignable.**
  C'est le service, via `recordAnyClosedPass`, qui inscrit les lignes, et il le fait au premier
  événement suivant. `openPass` passe tout de même par `closureOf` plutôt que `settle` : même
  état écrit, mais l'arithmétique de durée reste au même endroit pour tout le monde.
- **En revanche l'horodatage résiduel bloquait le bouton, et ça c'était réel.** `canOpen` se
  calculait sur l'état **stocké** : un pass ouvert puis abandonné — le service ne le constate
  qu'avec un événement d'une app surveillée, et il n'en reçoit plus — gardait son horodatage,
  et **le lendemain, quota frais et plage ouverte, l'écran affichait « 5 min restantes sur
  5 min » au-dessus d'un bouton mort**. Ça ne se débloquait qu'en ouvrant une app surveillée,
  c'est-à-dire la chose même pour laquelle on voulait le pass. `allowanceUiState` settle
  maintenant avant de décider, comme le fait `openPass` avant d'écrire. Deux tests JVM.
- **Le bandeau de règles illisibles porte maintenant un bouton « Recharger les règles »**
  (`ACTION_RELOAD_RULES`). Avant, le statut n'était écrit qu'au `onServiceConnected` : on
  réparait `rules.json` et le bandeau restait, pendant que le service tournait toujours sur le
  repli. Rafraîchir le seul statut aurait été pire — il aurait effacé le bandeau en laissant
  le repli en place.
- **Vérifié sur appareil le 2026-08-17** : la migration a été jouée en mise à jour depuis une
  base réelle en version 1 (2 lignes `block_event` conservées, `pass_event` créée au schéma
  exact, rien dans logcat), et deux `pass_event` ont été inscrits par le chemin normal.
  **La migration ne se joue que sur une mise à jour** : après une désinstallation, la base
  naît directement en version 2 et le chemin n'est jamais éprouvé.

### Deux pièges d'outillage, tous deux rencontrés ici

- **`uiautomator dump` échoue en renvoyant 0.** « ERROR: null root node returned by
  UiTestAutomationBridge » part sur la sortie standard, code 0, et `cat` relit alors le
  **dump précédent**. Un écran figé dans le passé, et une heure passée à chercher un défaut
  inexistant. Toujours supprimer le fichier avant, et vérifier que la sortie contient
  « dumped to ». Sur l'écran Compose de Digue il échoue souvent : passer par
  `adb exec-out screencap -p`, qui n'a jamais menti.
- **Ne pas découper le XML à la ligne pour trouver des bornes.** Un nœud porteur de texte et
  ses ancêtres portent tous un `bounds` ; un `grep | head -1` prend celui du **parent**, et
  chaque appui atterrit à des centaines de pixels de la cible. Parser le XML.
- L'instrument le plus fiable reste **le protobuf de DataStore** :
  `adb shell run-as com.insta.reelsoff cat files/datastore/settings.preferences_pb`, décodé
  (entrées de map : champ 1 = clé, champ 2 = valeur ; dans la valeur, 1=bool, 3=int, 4=long,
  5=string, 6=set).
- **`input tap` est trop bref pour la barre d'onglets d'Instagram** : il ne change pas
  d'onglet. `adb shell input swipe X Y X Y 120` — un appui de 120 ms — fonctionne.

## Chantiers de suite, par priorité

1. **Le tag `ReelsOff` remonte de nouveau dans logcat** sur cet appareil, contrairement à ce
   que ce fichier a longtemps dit. La base `block_event` reste l'autre preuve, à lire ainsi :
   `adb exec-out run-as com.insta.reelsoff cat databases/reelsoff.db > x.sqlite` — et **prendre
   aussi le `-wal`**, sinon les dernières lignes manquent.
2. **Les nœuds d'accessibilité ne sont pas recyclés, et c'est une décision, pas un oubli.**
   `recycle()` est un no-op à partir de l'API 33 ; `minSdk` vaut 26, donc la fuite n'existe
   que de l'API 26 à 32 — précisément les versions dont ce projet n'a aucun appareil. Le
   recyclage a été écrit puis **retiré** : il n'aurait jamais tourné là où on peut l'observer,
   et n'aurait tourné que là où on ne peut pas. Et il échoue mal — recycler un nœud que
   `AccessibilityCache` détient encore lève plus tard, depuis le framework, sur un appel sans
   rapport ; avalé par le garde de `onAccessibilityEvent`, ça donne un service lié qui ne
   bloque rien. Bénéfice mesuré : aucun, aucune fuite n'a jamais été constatée.
   **Deux sorties, toutes deux à ton choix** : un appareil sous Android 8-12 pour éprouver le
   mécanisme, ou remonter `minSdk` à 33 — ce qui supprime la classe entière de problème sans
   une ligne de code, au prix d'Android 8 à 12.
3. **Heuristique de la barre de navigation (F9, différée — et l'utilisateur l'a
   explicitement dépriorisée le 2026-08-17 : « c'est pas important pour l'instant »).** `ScreenClassifier.findNavBar`
   retient « ≥4 frères cliquables, la rangée la plus basse ». Sur les captures réelles cela
   laisse 3-4 rangées candidates par écran, départagées par la seule géométrie. Un panneau ou
   une feuille à 4 boutons pourrait déplacer la vraie barre. C'est le seul repli restant pour
   REELS après la suppression du palier MEDIUM. Resserrer demande des seuils qui pourraient
   casser sur d'autres géométries d'écran : à faire avec des captures sur plus d'un appareil,
   plus un départage déterministe en cas d'égalité.
4. **Résiduels connus, non bloquants** : la cause interpolée dans le bandeau de règles est du
   texte anglais dans une phrase française ; `isServiceEnabled` n'a pas de test car le code n'a
   pas de couture pure pour `Settings.Secure` ; et `HomeViewModel` n'a aucun test du tout — ses
   fonctions pures le sont, mais le fait qu'il les appelle dans le bon ordre ne l'est pas, ce
   qui est précisément par où un défaut de comptage du temps regardé était passé.
5. **Non vérifié** : la persistance sur 24 h — l'utilisateur doit la constater
   lui-même et la rapporter, aucune manipulation à faire d'ici là ; côté quota, le resserrement pendant qu'un
   changement est en attente, et la maturation réelle d'un délai d'une heure.

**Deux points fermés par la mesure le 2026-08-17 :**

- **Survie au redémarrage : oui.** Vérifié après un vrai redémarrage (1 min d'uptime) — le
  service se relie seul, sans réactivation, et l'état du quota survit.
- **Le débit d'événements ne coûte rien.** `EventThrottle` est à 200 ms, donc jusqu'à cinq
  parcours d'arbre par seconde. Mesuré sur `/proc/<pid>/stat` : **2 ticks CPU en 20 s au
  repos, 105 ticks en 20 s de défilement actif** dans une app surveillée — environ 5 % d'un
  cœur pendant le défilement, et rien le reste du temps. Trente minutes de défilement par jour
  font 90 s de CPU. **Ne pas « optimiser » ce réglage** : il n'y a rien à y gagner.

## Limites produit à connaître

- ~~Bloquer Explore bloque aussi la recherche Instagram~~ — **résolu.** Explore ne sort plus
  de l'onglet : il **appuie sur la barre de recherche** (`clickViewId`), donc la loupe reste
  utilisable et seule la grille devient inatteignable. Budget de trois appuis par visite, puis
  repli sur le retour arrière, et **jamais d'escalade vers l'accueil** depuis ce chemin.
- **Un service d'accessibilité ne peut pas annuler un geste.** Il ne filtre que les touches
  physiques. Après un glissement, l'utilisateur voit toujours ~1 s du contenu suivant avant que
  l'app réagisse. Irréductible, à ne pas promettre autrement.
- **Un reel ouvert depuis un profil ou un commentaire est coupé immédiatement**, pas après un :
  il n'a pas de barre de réponse non plus. Écart assumé par rapport à « un reel autorisé
  partout sauf l'onglet ».

## Idée 2, hors périmètre

Un robot qui parcourt ~100 Reels par jour et les résume est un **projet distinct**. Deux
réserves consignées : automatiser le compte correspond au comportement que cherche la détection
anti-robot (risque de bannissement réel, contrairement à cette app qui n'émet aucune requête) ;
et résumer une vidéo suppose téléchargement, transcription et analyse visuelle. Question de fond :
déléguer les Reels ne les supprime pas. Le journal `block_event` fournirait la mesure de départ.
