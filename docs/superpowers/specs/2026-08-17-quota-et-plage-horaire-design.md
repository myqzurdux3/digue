# Quota quotidien, plage horaire et verrou par délai

Date : 2026-08-17
État : validé en conception, non implémenté

## Le problème

Digue bloque ou ne bloque pas. L'interrupteur d'une surface est binaire, et il se
change en deux appuis. L'utilisateur veut trois choses que ce modèle ne sait pas
exprimer :

1. un budget quotidien — cinq minutes de vidéo courte par jour, pas zéro ;
2. une plage horaire — ces cinq minutes ne sont ouvrables qu'entre deux heures ;
3. un verrou — quelque chose qui l'empêche de tout desserrer au moment où il en a
   le plus envie.

## Ce que ça ne fera pas

**Désactiver le service d'accessibilité dans les réglages Android reste à un
geste, et désinstaller Digue aussi.** Ces deux chemins sont hors de l'application :
aucun délai, aucun mot de passe, aucun code ne les atteint. Le verrou décrit ici
protège les réglages *de Digue*, rien d'autre.

Il faut le dire ainsi plutôt que laisser croire à une contrainte forte. La parade
partielle — une notification permanente quand le service est coupé — est un
chantier distinct, hors de cette spec.

**Un mot de passe choisi par l'utilisateur ne vaut rien contre lui-même.** C'est
la raison pour laquelle le verrou retenu est un délai et non un secret : un délai
tient même en connaissant tout le code.

## Décisions prises

| Question | Choix | Pourquoi |
|---|---|---|
| Dépense du quota | déblocage explicite | un reel ouvert par erreur ne coûte rien, et l'aller-retour dans l'app est une friction utile |
| Nature du verrou | délai de refroidissement | rien à retenir, rien à perdre, efficace même contre soi-même |
| Portée | un quota commun aux cinq surfaces | c'est le défilement total qui compte, et changer d'app ne contourne rien |
| Plage horaire | une plage, identique chaque jour | couvre le besoin exprimé, modèle minimal |
| Mesure du temps | horloge murale depuis l'ouverture | aucun compteur à tenir, aucune dérive, aucune mise en pause possible |

### Pourquoi l'horloge murale plutôt que le temps réellement passé sur les surfaces

Le comptage du temps d'écran réel est plus juste et a été écarté. Le service ne
reçoit d'événements que des paquets qu'il déclare, donc « l'utilisateur est parti »
ne s'observe pas — il se devine par le silence. Il faudrait persister un compteur
en continu, il dériverait dès que les événements s'espacent, et surtout il
permettrait de *mettre le compteur en pause* en sortant de l'application trois
secondes, ce qui vide la contrainte de son sens.

Le laissez-passer à l'horloge coûte un cas : un appel téléphonique de trois minutes
pendant le pass mange trois minutes. Le bouton « Fermer maintenant », qui rend le
temps non consommé au budget du jour, le récupère.

Une variante hybride — fermeture automatique du pass après ~60 s sans événement
d'une application bloquée — reste possible plus tard **sans changer le modèle de
données**. Elle n'est pas dans cette spec.

## Modèle de données

Deux objets purs, aucun import `android.*`, testables sur JVM comme `Blocker`
l'est déjà.

```kotlin
data class AllowanceSettings(
    val enabled: Boolean = false,
    val quotaMillis: Long = 5 * 60_000,
    val windowStartMinutes: Int = 20 * 60,  // minutes depuis minuit local
    val windowEndMinutes: Int = 21 * 60,
    val cooldownMillis: Long = 0,
)

data class AllowanceState(
    val day: Long = 0,                      // epochDay local du quota courant
    val consumedMillis: Long = 0,
    val passOpenedAtEpochMillis: Long = 0,  // 0 = fermé
)
```

`enabled` vaut `false` à l'installation : une fonction neuve arrive éteinte, comme
chaque surface ajoutée jusqu'ici.

**`cooldownMillis` vaut zéro à l'installation, et c'est ce qui rend le verrou
utilisable.** Allumer le quota est lui-même un assouplissement : avec un délai par
défaut de 24 h, une installation neuve armerait une attente d'un jour au moment
même où l'utilisateur active la fonction, qui ne ferait donc rien pendant un jour.
À zéro, les réglages s'arrangent librement ; **choisir un délai est un
resserrement**, donc immédiat, et verrouille tout ce qui vient après. Le verrou
s'arme délibérément, en un geste, au lieu de se déclencher au premier usage.

Les deux vivent dans le `SettingsStore` existant, en clés plates. Rien de nouveau
côté Room — un quota n'est pas un historique.

**La plage se lit en minutes depuis minuit local**, pas en horodatage. Une plage
dont la fin est strictement inférieure au début est **à cheval sur minuit** :
`22:00 → 01:00` s'écrit `windowStartMinutes = 1320`, `windowEndMinutes = 60`. Une
fin **égale** au début est une plage **vide**, jamais ouvrable — et non pas une
journée entière : entre les deux lectures possibles, on prend celle qui bloque. La
journée du quota reste le jour calendaire local, donc la portion d'après minuit
d'une plage à cheval puise dans le budget du jour nouveau. C'est une conséquence
assumée, pas un défaut à corriger.

## La décision de blocage

Une fonction pure décide, le service applique :

```kotlin
fun passIsOpen(nowEpochMillis: Long, zone: ZoneId, settings: AllowanceSettings, state: AllowanceState): Boolean
```

Vraie seulement si, toutes conditions réunies :

- `settings.enabled`
- `state.passOpenedAtEpochMillis != 0`
- l'heure locale de `now` tombe dans `[windowStartMinutes, windowEndMinutes)`
- le temps restant du jour est strictement positif

Dans `InstagramWatcherService.handle()`, une seule ligne change :

```kotlin
val effective = if (passIsOpen(...)) emptySet() else settings.blockedSurfaces
val decision = blocker.decide(classification, effective)
```

`Blocker` n'est pas modifié. Il reçoit un ensemble vide, c'est-à-dire le chemin
« surface non bloquée » déjà couvert par ses tests. Aucune notion de quota n'entre
dans le blocage lui-même.

### Échec fermé

État illisible, journée incohérente, réglages absents : **pass fermé, blocage
normal**. Même posture que le `BlockSettings()` par défaut d'aujourd'hui, qui
bloque REELS et EXPLORE tant que DataStore n'a pas répondu.

### Fermeture du pass

Le pass se ferme de trois façons :

- expiration : le temps restant tombe à zéro ;
- sortie de la plage horaire ;
- appui sur « Fermer maintenant ».

Les trois créditent `consumedMillis` du temps écoulé et remettent
`passOpenedAtEpochMillis` à 0. L'écriture est faite par celui qui l'observe en
premier — l'interface ou le service — et elle est idempotente : recréditer un pass
déjà fermé ne fait rien, puisque `passOpenedAtEpochMillis` vaut déjà 0.

### Passage de jour

`state.day` porte l'`epochDay` local du quota courant. Toute lecture qui trouve
`state.day != epochDay(now)` traite le quota comme neuf : `consumedMillis = 0`. Un
pass ouvert qui traverse minuit est **fermé** au passage, et son temps écoulé est
imputé au jour où il a été ouvert.

## Le verrou par délai

Toute écriture de réglage passe par un classifieur pur. Il porte sur **les deux**
objets de réglage : le quota nouveau et les interrupteurs de surface existants —
sinon le verrou serait contournable en éteignant simplement REELS.

```kotlin
data class LockedSettings(
    val allowance: AllowanceSettings,
    val blockedSurfaces: Set<Surface>,
)

fun isLoosening(current: LockedSettings, proposed: LockedSettings): Boolean
```

`LockedSettings` est le couple sur lequel le verrou raisonne ; il ne remplace pas
`BlockSettings`, qui reste ce que lit le service.

**Immédiat** — un resserrement s'applique tout de suite :

- quota réduit
- plage rétrécie
- délai rallongé
- quota **désactivé**
- surface ajoutée au blocage

**Différé de `cooldownMillis`** — tout le reste, y compris **activer** le quota et
retirer une surface du blocage.

Le sens de `enabled` se lit à l'envers de l'intuition, et il faut le poser
explicitement : le quota **accorde** du temps, il n'en retire pas. Le blocage des
surfaces ne dépend pas de lui. Donc `enabled = false` est l'état le **plus**
strict — aucun laissez-passer n'est ouvrable — et allumer le quota est un
assouplissement, à différer. L'écrire dans l'autre sens donnerait un verrou
contournable en une écriture.

Un changement qui mélange resserrement et assouplissement compte **entièrement**
comme un assouplissement. C'est le choix sûr, et il évite d'avoir à découper une
modification en deux écritures dont l'une seulement serait différée.

Trois pièges, sans lesquels le verrou est décoratif :

1. **Raccourcir le délai est un assouplissement.** Sinon : délai à zéro, puis tout
   le reste passe immédiatement.
2. **Annuler un changement en attente est immédiat** — c'est un resserrement.
3. **Avancer l'horloge du téléphone fait mûrir le délai.** Contre-mesure : à
   l'armement on enregistre l'instant en horloge murale **et** en
   `elapsedRealtime`. Le changement ne prend effet que si les deux ont assez
   avancé. Après un redémarrage, `elapsedRealtime` repart à zéro — on le détecte
   (valeur courante inférieure à la valeur armée) et on retombe sur l'horloge
   murale seule, faute de mieux.

Cette contre-mesure ferme le cas réaliste — avancer l'horloge sans redémarrer —
et pas le cas déterminé. Elle **n'existe pas** pour la plage horaire ni pour la
remise à zéro quotidienne : la plage *est* une question d'heure locale, et une
horloge avancée y entre légitimement. Limite assumée, écrite ici pour ne pas être
redécouverte.

### Représentation d'un changement en attente

```kotlin
data class PendingChange(
    val proposed: LockedSettings,
    val effectiveAtEpochMillis: Long,
    val armedAtElapsedRealtime: Long,
    val cooldownMillis: Long,
)
```

Sérialisé en une clé texte via kotlinx.serialization, déjà présent dans le projet.
Un seul changement en attente à la fois : en armer un second **remplace** le
premier, et l'attente repart de zéro. Deux files concurrentes seraient un moyen
simple de contourner le délai en le fractionnant.

## Interface

`HomeScreen.kt` est à 487 lignes. La section quota part dans son propre fichier,
`ui/AllowancePanel.kt`, sur le modèle de `SurfaceGroups.kt` et `TodayBreakdown.kt`.

Contenu :

- « Quota du jour — 3 min 20 restantes sur 5 min »
- hors plage : « Ouvrable de 20 h 00 à 21 h 00 »
- bouton **Ouvrir**, actif seulement si : dans la plage, quota restant, service actif
- pendant le pass : compte à rebours et **Fermer maintenant**
- réglages quota / plage / délai, avec la mention explicite que tout assouplissement
  est différé
- bandeau si changement en attente : « Actif dans 21 h 14 — Annuler »

Le compte à rebours se dérive de `passOpenedAtEpochMillis` et d'un tick local ; il
ne dépend pas du service, qui peut être arrêté sans figer l'affichage.

Textes en français, code et symboles en anglais, conformément aux invariants.

## Tests

Tout ce qui décide est pur, donc sur JVM :

- contenance de la plage, y compris à cheval sur minuit, et aux deux bornes
- arithmétique du quota : reste, épuisement, crédit à la fermeture
- passage de jour, dont un pass ouvert qui traverse minuit
- ouverture et fermeture par les trois chemins, et idempotence de la fermeture
- `isLoosening` champ par champ, dans les deux sens, plus les cas mixtes
- maturation du délai, dont le garde-fou d'horloge et le cas du redémarrage
- remplacement d'un changement en attente par un autre

Instrumenté : l'aller-retour DataStore des deux objets et du changement en attente.

Aucun test ne peut vérifier qu'un utilisateur ne contourne pas le verrou en coupant
le service. Ce n'est pas une lacune de la suite, c'est la limite énoncée en tête.

## Invariants respectés

- `:detection` n'est pas touché — le quota n'est pas de la reconnaissance d'écran.
  Les nouveaux fichiers purs vivent dans `:app`, comme `Blocker`.
- Aucun accès réseau, aucune dépendance nouvelle.
- Le champ `text` reste non lu.
- Rien de neuf ne peut s'échapper de `onAccessibilityEvent` : la lecture du quota
  se fait dans le corps déjà enveloppé de `handle()`, et l'échec y est fermé.
