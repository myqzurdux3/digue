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

**Statut au 2026-08-17.** Les cinq surfaces sont vérifiées sur l'appareil réel et `main` les
porte toutes (`feat/snapchat-discover` est fusionnée). 241 tests JVM, 24 tests instrumentés.

Le **quota quotidien, la plage horaire et le verrou par délai** sont dans `main`, vérifiés sur
appareil — voir « Quota » plus bas pour la paire de mesures qui le prouve et pour ce qui reste
couvert par les seuls tests purs.

La branche `feat/temps-regarde` ajoute la **mesure du temps réellement regardé**. Suite verte,
mais **rien n'a tourné sur l'appareil** : une autre session y travaillait, donc aucun `adb`.
La migration Room 1 → 2 est en particulier non jouée — voir « Le temps regardé ».

**Trois comportements fins, déjà livrés et vérifiés, à ne pas casser :**

1. **Un reel qu'un contact envoie en message reste regardable**, mais les reels suggérés qui
   suivent sont bloqués.
2. **Ouvrir l'onglet Explore appuie sur la barre de recherche** au lieu de sortir de l'onglet,
   parce que bloquer Explore bloquait aussi la seule recherche d'Instagram.
3. **Une story d'un ami sur Snapchat reste regardable**, les vidéos Discover non.

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
  **Toute retouche du dessin doit être reportée dans les trois fichiers** (marque, avant-plan,
  monochrome) et la demi-diagonale de l'encombrement doit rester **sous 33** dans le repère
  108×108, sinon les lanceurs à masque circulaire rognent la marque.

## Commandes

```bash
./gradlew build                                   # tout
./gradlew :detection:test :app:testDebugUnitTest  # 241 tests JVM
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
                                       remainingMillis, passIsOpen, canOpenPass, openPass,
                                       closePass, settle)
                       AllowanceLock  (LockedSettings, PendingChange, isLoosening, armChange,
                                       hasMatured, effectiveSettings,
                                       effectiveBlockedSurfaces)
             data/     BlockEvent, BlockEventDao, AppDatabase (+ MIGRATION_1_2),
                       DailyCount, dailyCounts, PassEvent, PassEventDao,
                       DailyWatched, dailyWatched,
                       SettingsStore, BlockSettings, RuleLoadStatus, CaptureStatus
             ui/       MainActivity, HomeScreen, HomeViewModel, ServiceStatus, Theme,
                       CaptureProgress, SurfaceGroups, TodayBreakdown,
                       AllowanceUiState, AllowancePanel, AllowanceEditors
```

**Le service ne s'appelle plus que par habitude `InstagramWatcherService`** — il couvre
maintenant cinq surfaces sur trois apps. Le renommer casserait le composant enregistré dans
les réglages d'accessibilité, exactement comme l'identifiant applicatif. Ne pas le faire.

`:detection` ne connaît jamais `AccessibilityNodeInfo`. Le service traduit l'arbre Android en
`ScreenSnapshot` neutre, puis appelle une fonction pure. C'est ce qui rend la détection testable
sur JVM contre de vrais arbres capturés.

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
`opera_viewer` plein écran. `chrome_subscribe_button` **n'est PAS un discriminant** — il est
présent des deux côtés, contre toute intuition. Le discriminant est la colonne d'actions
verticale, absente des stories d'amis.

### Identifiants : deux pièges de nommage

- **Snapchat obfusque ~60 % de ses identifiants** (`0_resource_name_obfuscated`). Les règles
  Snapchat tiennent sur une poignée de survivants et sont les plus fragiles du projet.
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
- Les fixtures Snapchat/YouTube n'existent pas encore : leurs règles sont vérifiées sur
  appareil mais **sans aucun test de non-régression**.

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
- **Défaut connu, non corrigé** : si tu appuies sur « Fermer maintenant » dans les
  millisecondes où le pass expire, le service et l'interface peuvent chacun inscrire une
  ligne, et la journée compte le pass deux fois. Fenêtre minuscule, et l'erreur va dans le
  sens prudent — elle affiche **plus** de temps regardé, jamais moins.
- **Non vérifié sur appareil** : la migration elle-même, et l'enregistrement de bout en bout.

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

1. **Aucune fixture pour YouTube ni Snapchat.** Leurs règles marchent, mais rien ne préviendra
   quand un identifiant sera renommé : l'utilisateur le découvrira. Capturer les deux apps et
   en tirer des fixtures nettoyées est le vrai reste à faire.
2. **Le tag `ReelsOff` ne remonte plus dans logcat** sur cet appareil, alors que la recette du
   projet s'appuie dessus. La base `block_event` a servi de preuve à la place — la lire ainsi :
   `adb shell run-as com.insta.reelsoff cat databases/reelsoff.db > x.sqlite`.
3. **Heuristique de la barre de navigation (F9, différée).** `ScreenClassifier.findNavBar`
   retient « ≥4 frères cliquables, la rangée la plus basse ». Sur les captures réelles cela
   laisse 3-4 rangées candidates par écran, départagées par la seule géométrie. Un panneau ou
   une feuille à 4 boutons pourrait déplacer la vraie barre. C'est le seul repli restant pour
   REELS après la suppression du palier MEDIUM. Resserrer demande des seuils qui pourraient
   casser sur d'autres géométries d'écran : à faire avec des captures sur plus d'un appareil,
   plus un départage déterministe en cas d'égalité.
4. **Résiduels connus, non bloquants** : le statut de chargement des règles n'est écrit qu'au
   `onServiceConnected`, donc le bandeau persiste après réparation jusqu'à reconnexion du
   service ; la cause interpolée est du texte anglais dans une phrase française ;
   `isServiceEnabled` n'a pas de test car le code n'a pas de couture pure pour `Settings.Secure`.
5. **Non vérifié** : survie à un redémarrage, persistance sur 24 h ; côté quota, le
   resserrement pendant qu'un changement est en attente, et la maturation réelle d'un délai
   d'une heure. Commandes dans la recette.

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
