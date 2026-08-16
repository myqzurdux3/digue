# Blocage des Reels Instagram — Design

Date : 2026-08-16
Statut : approuvé, prêt pour le plan d'implémentation

## Problème

Temps excessif passé sur Instagram, principalement via les Reels. Objectif :
supprimer l'accès aux Reels sans quitter Instagram.

## Contrainte fondatrice : pas d'API

Instagram n'expose aucun accès au fil d'actualité.

- **Basic Display API** — arrêtée le 4 décembre 2024.
- **Graph API** — comptes Business/Creator uniquement, et limitée au contenu du
  compte propriétaire. Aucun accès aux publications des comptes suivis.
- Aucune API publique de fil d'actualité n'a jamais existé.

Un client alternatif imposerait de rétro-ingénierer l'API privée : bannissement
de compte probable, rupture à chaque mise à jour, violation des conditions
d'utilisation. **Écarté.**

L'approche retenue ne reconstruit pas Instagram : elle ampute le client officiel
depuis l'extérieur.

## Décisions de cadrage

| Question | Décision |
|---|---|
| Plateforme | App mobile native Android |
| Surfaces bloquées | Onglet Reels, page Explore |
| Hors périmètre | Reels dans le feed, Reels reçus en message privé |
| Comportement | Retour arrière silencieux, avec comptage des tentatives |
| Distribution | Sideload personnel (le Play Store refuse ce type d'app) |

**Limite assumée :** le service se désactive en dix secondes dans les réglages
Android. L'app casse un réflexe, elle ne contraint pas.

## Architecture

Deux modules Gradle. La frontière entre les deux est la décision centrale.

```
:app
  service/   InstagramWatcherService, TreeWalker, Blocker, TreeDumper
  data/      Room (block_event), DataStore (réglages)
  ui/        écran unique Compose
:detection            ← Kotlin pur, aucune dépendance Android
  ScreenSnapshot, NodeSummary
  RuleSet, Signal
  ScreenClassifier
```

**Règle invariante :** `:detection` ne connaît jamais `AccessibilityNodeInfo`.
Le service traduit l'arbre de vues Android en un instantané neutre, puis appelle
une fonction pure. C'est ce qui rend la détection testable sur des captures
réelles, sur JVM, sans appareil.

## Flux de détection

1. Service déclaré avec `packageNames = ["com.instagram.android"]`. Android ne
   livre alors que les événements d'Instagram — filtrage au niveau système.
2. `onAccessibilityEvent` reçoit `TYPE_WINDOW_STATE_CHANGED` et
   `TYPE_WINDOW_CONTENT_CHANGED`. Le second est très fréquent pendant un scroll :
   étranglement à une classification par 200 ms maximum.
3. Parcours borné de `rootInActiveWindow` — profondeur max 25, plafond 800
   nœuds, sortie anticipée.
4. Production d'un `ScreenSnapshot` : liste de
   `NodeSummary(viewId, contentDescription, className, isSelected, depth,
   indexInParent, boundsInScreen)`.
   Aucun autre champ n'est collecté. Le service peut techniquement tout lire ;
   la collecte est délibérément restreinte à ces champs, et rien ne quitte
   l'appareil.
5. `ScreenClassifier.classify(snapshot, ruleSet)` renvoie `REELS`, `EXPLORE` ou
   `AUTRE`, accompagné du palier de règle ayant répondu.
6. Surface bloquée : enregistrement de l'épisode, puis
   `performGlobalAction(GLOBAL_ACTION_BACK)`.

### Machine à états du blocage

Un `BACK` ne garantit pas de quitter l'écran. Si Explore est la racine de la
pile, ou si Instagram redémarre sur l'onglet Reels, la naïveté produit une
boucle infinie qui rend le téléphone inutilisable.

- Après un `BACK` : période morte de 600 ms, classifications ignorées.
- Compteur de `BACK` consécutifs ; à trois échecs en trois secondes, escalade
  vers `GLOBAL_ACTION_HOME`.
- Le compteur se réinitialise dès qu'une classification renvoie `AUTRE`.
- `GLOBAL_ACTION_HOME` ne peut pas se déclencher plus d'une fois par 30 secondes,
  quel que soit l'état. Une détection défaillante gêne, elle ne verrouille pas
  l'appareil.

### Comptage par épisode

Un blocage est un épisode, pas un `BACK`. Trois `BACK` en rafale sur une même
tentative comptent pour un. Un épisode se ferme après deux secondes sans
détection bloquée. Sinon les statistiques mesureraient la machine à états
plutôt que le réflexe de l'utilisateur.

## Règles de détection

Les identifiants de vues d'Instagram sont obfusqués, non documentés, et changent
d'une version à l'autre. C'est le seul risque de maintenance réel du projet.

Les règles vivent dans `assets/rules.json`, avec surcharge par un fichier du
stockage applicatif — réparation possible sans recompilation.

```json
{
  "version": 1,
  "surfaces": {
    "REELS": {
      "signals": [
        { "tier": "high",   "type": "viewId",
          "value": "com.instagram.android:id/clips_tab", "requireSelected": true },
        { "tier": "medium", "type": "contentDescription",
          "anyOf": ["Reels", "Réels"], "requireSelected": true },
        { "tier": "low",    "type": "navBarIndex",
          "value": 2, "requireSelected": true }
      ]
    }
  }
}
```

Les valeurs ci-dessus sont illustratives. Les valeurs réelles proviendront des
captures de l'étape 1, jamais d'une supposition.

Le classifieur évalue les signaux du palier le plus fiable au moins fiable,
premier trouvé gagne, et renvoie le palier ayant répondu. Quand Instagram renomme
ses identifiants, le palier `high` tombe, les suivants tiennent, et l'app sait
qu'elle fonctionne en mode dégradé.

**`requireSelected` conditionne le fonctionnement de l'app.** Le bouton Reels est
présent dans la barre de navigation sur tous les écrans, feed compris. Sa
présence ne signifie rien ; son état sélectionné, oui. Sans cette condition,
l'app déclenche un retour arrière depuis le fil d'actualité. C'est le bug le plus
probable du projet et il reçoit son test en premier.

Le palier `contentDescription` dépend de la langue du téléphone, d'où `anyOf`.

Le signal `navBarIndex` doit localiser la barre du bas. Sa seule caractéristique
stable est géométrique : un conteneur d'enfants sélectionnables aligné en bas
d'écran. Le nombre exact d'onglets et l'index de chaque surface seront fixés
d'après les captures, pas supposés. D'où la présence de `boundsInScreen` dans
`NodeSummary`.

## Stockage

Room, une table :

```
block_event(id, epochMillis, surface, ruleTier)
```

Une ligne par épisode. `ruleTier` fournit la santé de détection sans coût
supplémentaire : des épisodes provenant tous du palier `low` signalent
qu'Instagram a changé.

Pas de purge — environ cinquante lignes par jour au pire. Le journal brut sert
aussi de socle si le projet de robot de résumé est engagé plus tard.

Réglages en DataStore : `blockReels`, `blockExplore`.

## Interface

Un écran Compose unique :

- État réel du service, lu depuis `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`
  à chaque ouverture, avec un bouton vers les réglages d'accessibilité. Android
  interdit d'activer un service d'accessibilité par programme : la bascule est
  manuelle, une fois.
- Compteur du jour, par surface.
- Histogramme sur 14 jours.
- Deux interrupteurs : Reels, Explore.
- Bandeau d'alerte si la détection tourne en mode dégradé.

### Mode capture

Nécessaire à l'étape 1. Contrainte : impossible d'appuyer sur un bouton de l'app
pendant qu'Instagram est au premier plan, et dérouler le volet de notifications
changerait la fenêtre active — la capture porterait sur le volet.

Solution : bouton « capturer 60 secondes ». L'utilisateur bascule vers Instagram
et navigue librement ; le service enregistre un instantané toutes les 3 secondes.
Résultat : une vingtaine d'arbres JSON couvrant tous les écrans, qui deviennent
les fixtures de test.

## Gestion d'erreurs

Le risque dominant est le service qui meurt en silence. Une app de blocage
inactive sans le dire est pire qu'une app absente.

- **Exception dans `onAccessibilityEvent`** — une exception remontée fait planter
  le service, qu'Android peut alors désactiver définitivement. Le corps entier du
  gestionnaire est enveloppé dans un `try/catch` qui journalise et n'en laisse
  remonter aucune.
- **Fil principal** — `onAccessibilityEvent` s'exécute sur le thread principal.
  Le parcours est borné pour cette raison ; les écritures en base partent sur
  `Dispatchers.IO`.
- **`rootInActiveWindow` à `null`** — fréquent et transitoire pendant les
  transitions. L'événement est ignoré.
- **Fichier de règles invalide** — repli sur les règles embarquées dans `assets`,
  plus bandeau d'alerte. Aucun plantage sur un JSON mal formé.
- **Tueurs de tâches constructeurs** (Xiaomi, Samsung, Huawei) — non évitables,
  mais détectables. L'écran d'accueil affiche l'état réel du service et rappelle
  d'exempter l'app de l'optimisation de batterie.

**Non-objectif assumé :** l'app n'infère pas « règles cassées » à partir de
« zéro blocage cette semaine ». Zéro blocage est peut-être le succès du projet.
Seul le palier de règle constitue un signal honnête de panne.

## Stratégie de test

Les fixtures capturées sont l'entrée de tout, d'où l'ordre de construction.

**`:detection`, JVM pur** — les tests centraux du projet :
- feed → `AUTRE` (écrit en premier)
- onglet Reels → `REELS` ; Explore → `EXPLORE`
- profil, messages, visionneuse de stories → `AUTRE`
- cascade : fixture privée de `viewId` → le palier `medium` répond ; privée aussi
  de `contentDescription` → le palier `low` répond

**`Blocker`** — machine à états pure, horloge injectée comme interface : période
morte, escalade au troisième échec, réinitialisation sur `AUTRE`, et trois `BACK`
en rafale produisant un seul épisode compté.

**`TreeWalker`** — prend une interface `NodeLike` plutôt que
`AccessibilityNodeInfo`. L'adaptateur Android reste une fine couche ; la logique
de parcours et ses bornes se testent sur JVM avec des faux nœuds.

**Room** — tests instrumentés sur base en mémoire.

**Recette sur appareil** — inévitable et décisive. Aucun test unitaire ne prouve
qu'Instagram se comporte comme une fixture d'il y a trois semaines.

## Ordre de construction

1. Squelette et service minimal, mode capture uniquement — but unique : obtenir
   les fixtures
2. `:detection` en TDD sur ces fixtures
3. `Blocker` et sa machine à états, en TDD
4. Câblage du service : adaptateur de parcours, écritures asynchrones
5. Room et interface
6. Recette sur appareil

L'étape 1 ne produit rien d'utilisable. C'est attendu : c'est elle qui rend les
étapes 2 et 3 déterministes plutôt que tâtonnantes.

## Coût et maintenance

Cœur (capture, règles, blocage) : environ un week-end. Statistiques et réglages :
un second. Maintenance : quelques heures à chaque rupture de détection causée par
une mise à jour d'Instagram, vraisemblablement plusieurs fois par an.

## Suite hors périmètre

Un robot parcourant environ 100 Reels par jour pour en résumer le contenu est un
**projet distinct**, avec son propre cycle de conception. Deux réserves à
consigner :

1. Automatiser un compte Instagram correspond exactement au comportement que
   cherche la détection anti-robot. Risque de bannissement réel — contrairement
   à la présente app, qui n'émet aucune requête et ne touche pas au compte.
2. Résumer une vidéo suppose téléchargement, transcription audio et analyse
   visuelle. Coût unitaire non nul.

Question de fond à trancher avant de l'engager : déléguer les Reels ne les
supprime pas, et produirait encore une dizaine de vidéos à regarder par jour.

Le journal `block_event` de la présente app fournirait la mesure de départ.
