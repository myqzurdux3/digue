# Reel reçu en message — Design

Date : 2026-08-16
Statut : approuvé, prêt pour le plan d'implémentation

## Problème

Un pote envoie un reel en message privé. L'utilisateur veut **le regarder**, mais
pas tomber dans le défilement infini qui suit : Instagram enchaîne
automatiquement sur des reels suggérés, et la conversation devient une porte
d'entrée vers exactement ce que l'app est censée bloquer.

Le design de v1 (`2026-08-16-blocage-reels-instagram-design.md`) plaçait
explicitement les « Reels reçus en message privé » hors périmètre. **Ce document
lève cette exclusion**, et seulement celle-là : les vidéos du fil restent hors
périmètre, elles feront l'objet d'un chantier distinct.

## Ce que les captures ont établi

Deux sessions de capture sur l'appareil réel (Pixel 9a, Instagram 442.x),
analysées en ne lisant que `viewId` et `className` — jamais les
`contentDescription`, qui portent le contenu des conversations.

**Fait 1 — le reel reçu n'est pas bloqué aujourd'hui.** `clips_tab` est absent de
tous les instantanés du lecteur, et l'heuristique de barre de navigation ne
trouve aucune barre. Ni le palier HIGH ni le palier LOW ne peuvent s'accrocher.
Cette fonction **ajoute** un blocage, elle n'en assouplit aucun.

**Fait 2 — le reel reçu et le reel suivant sont deux écrans distincts.** Le reel
envoyé par un contact porte une grappe de nœuds propres à la conversation, tous
absents après un glissement vers le suivant :

| Présents sur le reel reçu | Apparus après le glissement |
|---|---|
| `reel_viewer_message_composer` | `suggested_title` |
| `reply_bar_container`, `reply_bar_edittext` | `send_cta` |
| `reply_bar_facepile`, `reply_bar_reaction_sheet_button` | `pill_container` |
| `sender_username_or_fullname`, `sender_timestamp` | `avatar_image_view` |
| `sender_profile_pic`, `scrubber`, `save_button` | `button_container` |

L'arbre passe de 108 à 79 nœuds. `clips_viewer_view_pager`,
`clips_viewer_container` et `root_clips_layout` sont présents des deux côtés :
c'est bien le même lecteur, avec ou sans le contexte de conversation.

**Fait 3 — la barre de réponse ne s'estompe pas.** C'était le risque principal :
si l'interface se masquait toute seule après quelques secondes, rester immobile
sur le reel d'un ami serait indiscernable d'un glissement, et l'app couperait en
plein visionnage. Une session dédiée l'a réfuté — 16 instantanés couvrant
**46 secondes sans aucune interaction**, les six marqueurs présents sur tous,
arbre stable à 108 nœuds du premier au dernier.

**Fait 4 — le carrousel ne publie pas sa position.** `clips_viewer_view_pager`
n'a qu'un `RecyclerView` pour enfant, sans index exploitable dans l'arbre.

## Signaux écartés

| Signal | Raison du rejet |
|---|---|
| Position dans le carrousel | **N'existe pas** dans l'arbre de vues (fait 4). |
| Comptage des défilements | Demande un état à mémoire ; un rebond élastique ou un défilement horizontal compte comme un passage au reel suivant. |
| Comparaison du contenu (auteur qui change) | Imposerait de lire et comparer des libellés de comptes — de la donnée personnelle traitée pour rien, alors que le signal retenu fait mieux sans. |
| Chronomètre (« N secondes puis on coupe ») | Robuste à tout et faux tout le temps : tronque un reel de trois minutes, en offre quatre pour un reel de sept secondes. |

## Décisions de cadrage

| Question | Décision |
|---|---|
| Ce qui est autorisé | Le reel **reçu en message**, sans limite de durée ni de relecture |
| Ce qui est bloqué | Tout lecteur de reel **sans barre de réponse** |
| Atterrissage | La conversation — retour arrière, mécanique existante |
| Quota, mémoire, compteur | **Aucun.** Le signal est un fait d'écran, pas un historique |
| Onglet Reels | Inchangé : bloqué dès la première image |
| Hors périmètre | Vidéos du fil, Shorts YouTube, Snapchat |

**Arbitrage assumé.** L'utilisateur avait d'abord retenu « un reel autorisé
partout sauf l'onglet ». Un reel ouvert depuis un profil ou un commentaire n'a
pas de barre de réponse non plus : il sera donc coupé **immédiatement**, pas
après un. Plus strict que la formulation retenue, plus proche de la demande
d'origine. Rétablir « un reel partout » ramènerait la mémoire et les compteurs
pour un cas rare — écarté par YAGNI, révisable.

## Extension du moteur de règles

La règle nécessaire est « ce nœud est présent **et** ceux-là sont absents ». Le
moteur ne sait aujourd'hui exprimer que la présence.

Ajout d'un champ unique à `Signal` :

```kotlin
data class Signal(
    val tier: Tier,
    val type: SignalType,
    val value: String? = null,
    val anyOf: List<String> = emptyList(),
    val requireSelected: Boolean = true,
    /** Le signal ne compte que si AUCUN de ces identifiants n'est dans l'arbre. */
    val absentViewIds: List<String> = emptyList(),
)
```

Sémantique : un signal correspond si sa condition propre est vraie **et**
qu'aucun `absentViewIds` n'apparaît dans l'instantané. Liste vide — le cas de
toutes les règles existantes — se comporte exactement comme aujourd'hui.

Le test d'absence est une **pure présence dans l'instantané**, indépendante de
`requireSelected` : un nœud de garde compte qu'il soit sélectionné ou non, ce qui
est le comportement voulu — la barre de réponse existe, ou elle n'existe pas.

Délibérément pas d'algèbre booléenne générale (`not`, `and`, `or` imbriqués) :
un seul cas d'usage est connu, et un langage de règles complet est du coût de
maintenance payé d'avance pour des besoins hypothétiques.

`SignalType.VIEW_ID` accepte désormais `anyOf` en plus de `value`, pour éviter
d'écrire trois règles identiques à l'identifiant près.

## Règle ajoutée

Pas de nouvelle surface : **un reel suggéré est un Reel**. La règle rejoint la
surface `REELS` existante, en second signal HIGH.

```json
{
  "tier": "HIGH",
  "type": "VIEW_ID",
  "value": "com.instagram.android:id/clips_viewer_view_pager",
  "requireSelected": false,
  "absentViewIds": [
    "com.instagram.android:id/reel_viewer_message_composer",
    "com.instagram.android:id/reply_bar_container",
    "com.instagram.android:id/sender_username_or_fullname"
  ]
}
```

**`requireSelected: false` est obligatoire ici, et c'est un piège.** Le champ
vaut `true` par défaut dans le moteur, et la mesure sur les captures donne
`isSelected=False` pour `clips_viewer_view_pager` comme pour les trois gardes.
Sans ce drapeau explicite, la règle ne se déclencherait **jamais** — et un
blocage qui ne se déclenche jamais est exactement le mode de panne que le projet
s'interdit, puisqu'il est indiscernable d'un succès.

Trois identifiants de garde plutôt qu'un : ils disent la même chose, donc si
Instagram en renomme un, les autres tiennent. Le choix des trois est arbitraire
parmi une grappe d'une dizaine — ceux-ci sont les plus explicitement liés à la
notion d'expéditeur.

Conséquences :

- Reel reçu en message → aucun signal ne correspond → **pas de blocage**.
- Reel suggéré qui suit → signal HIGH → **blocage**, épisode compté en `REELS`.
- Onglet Reels → correspond désormais par `clips_tab` **et** par ce signal, ce
  qui **renforce** un blocage qui ne tenait qu'à un identifiant.
- L'interrupteur « Bloquer les Reels » gouverne l'ensemble, sans ajout d'écran.

## Interface

Un seul changement : le libellé `block_reels` passe de « Bloquer l'onglet
Reels » à « Bloquer les Reels ». Il ne parle plus seulement de l'onglet.

Aucun nouveau compteur, aucune nouvelle section, aucun nouveau réglage.

## Confidentialité

- La règle ne s'appuie que sur des **identifiants de ressources**. Aucun texte,
  aucune `contentDescription`, aucun nom d'expéditeur n'est lu, comparé ou
  journalisé. L'invariant 2 du projet reste entier.
- Les fixtures dérivées de ces captures auront **toutes** leurs
  `contentDescription` remplacées par `[scrubbed]`, sans exception : contrairement
  aux fixtures de v1, aucune règle ici ne dépend d'un libellé, donc aucune chaîne
  n'a besoin de survivre.
- Les captures brutes ont été effacées du téléphone après analyse et ne sont
  jamais commitées.

## Stratégie de test

**JVM, `:detection`**

- `RuleSetParser` : `absentViewIds` lu, absent par défaut, tolérant à un champ
  inconnu ou mal typé comme le reste de l'analyseur.
- `ScreenClassifier` : un signal avec `absentViewIds` correspond quand aucun
  garde n'est présent, et ne correspond pas dès qu'un seul l'est.
- `SignalType.VIEW_ID` avec `anyOf`.
- Non-régression : les règles existantes, sans `absentViewIds`, classent comme
  avant.

**JVM, contre fixtures réelles nettoyées**

| Fixture | Attendu |
|---|---|
| `direct_thread.json` (la conversation) | `OTHER` |
| `dm_reel.json` (le reel reçu) | **`OTHER`** — le test qui compte |
| `suggested_reel.json` (après glissement) | `REELS`, palier `HIGH` |
| fixtures v1 (`feed`, `reels`, `explore`, `profile`, `direct`) | inchangées |

**Sur appareil**

- Ouvrir un reel reçu, ne rien toucher **20 secondes** → aucun blocage
  journalisé.
- Glisser une fois → **exactement un** épisode `REELS` en palier `HIGH`, puis
  silence.
- Vérifier **où atterrit le retour arrière** (voir incertitudes).

## Incertitudes à lever pendant l'implémentation

1. **Destination du retour arrière.** Il devrait fermer le lecteur et ramener
   dans la conversation. Il pourrait ne reculer que d'un reel dans le carrousel,
   auquel cas l'escalade existante (trois retours en trois secondes, puis
   accueil) prendrait le relais — comportement acceptable mais bruyant. À
   mesurer, et à corriger seulement si la mesure le demande.
2. **Délai de coupure.** Étranglement à 200 ms plus parcours de l'arbre :
   l'utilisateur verra le reel suivant environ une seconde. Irréductible avec un
   service d'accessibilité, qui ne peut pas annuler un geste. À constater, pas à
   corriger.

## Hors périmètre

- **Vidéos du fil** — chantier distinct : détection au niveau de l'élément dans
  un écran qu'on veut garder, et une action qui ne peut pas être le retour
  arrière.
- **Shorts YouTube, vidéos Snapchat** — chantier distinct : élargir
  `android:packageNames` étend ce que le service a le droit de voir, ce qui est
  la garantie de confidentialité la plus forte du projet et se décide
  séparément.
