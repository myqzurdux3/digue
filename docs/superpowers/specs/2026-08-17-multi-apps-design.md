# Shorts YouTube et Spotlight Snapchat — Design

Date : 2026-08-17
Statut : approuvé, prêt pour le plan d'implémentation

## Problème

Digue ne connaît qu'Instagram. L'utilisateur veut aussi couper les Shorts de
YouTube et Spotlight — plus Discover — sur Snapchat, dans la même app.

Le blocage lui-même est déjà résolu : reconnaître un écran par identifiant de
ressource et appuyer sur retour marche partout. Ce qui manque est structurel :
**tout, du manifeste aux réglages, suppose une seule application.**

## Ce qui bloque aujourd'hui

| Endroit | Ce qui est figé |
|---|---|
| `AndroidManifest` / `accessibility_service_config.xml` | `android:packageNames="com.instagram.android"` |
| `InstagramWatcherService.handle()` | `if (event.packageName != INSTAGRAM_PACKAGE) return` |
| `rules.json` | indexé par écran, pas par application |
| `BlockSettings` | deux booléens nommés `blockReels` et `blockExplore` |
| Mode capture | ne capture que si l'événement vient d'Instagram |

Ce dernier point compte : **généraliser le service est un préalable même pour
aller regarder à quoi ressemblent YouTube et Snapchat.**

## La décision de fond : ce que le service a le droit de voir

`android:packageNames` n'est pas une préférence, c'est **Android qui filtre en
amont**. Aujourd'hui le service est *incapable* de recevoir quoi que ce soit
d'une autre app — ce n'est pas une promesse tenue par du code, c'est une
propriété du système. C'est la garantie de confidentialité la plus forte du
projet, et l'élargir est la vraie décision de ce chantier.

L'enjeu concret : les arbres de Snapchat incluent les écrans de discussion, et
l'app lit les `contentDescription`, là précisément où les messageries mettent
les aperçus de conversations.

**Décision retenue : la permission suit l'interrupteur.** Un service peut
redéclarer sa liste de paquets à l'exécution via `setServiceInfo`. Un paquet
n'est donc dans la liste que si son blocage est activé. Snapchat éteint,
Android ne lui envoie rien.

> **Cette propriété n'est pas vérifiée par la mesure, et ne l'a pas été.**
> `dumpsys accessibility` **n'expose pas** la liste de paquets d'un service sur
> l'appareil de test (Android 17) : la ligne du service porte son libellé, ses
> types d'événements et son délai, rien d'autre, et une recherche insensible à
> la casse sur tout le dump ne trouve ni `packageNames` ni `packages=`. Un test
> par le comportement ne peut pas y suppléer : les réglages conditionnent le
> blocage de toute façon, donc l'app se comporte à l'identique que la
> déclaration se soit restreinte ou non.
>
> Ce qui est réellement établi : les deux fonctions pures qui calculent la liste
> et la convertissent en tableau sont testées, dont le cas vide qui sépare
> « n'observer rien » de « tout observer ». Le reste repose sur le contrat
> documenté de `setServiceInfo`. **À reformuler, ou à démontrer autrement, avant
> d'être présenté comme une garantie.**

**Un plancher statique reste indispensable.** `android:packageNames` demeure
déclaré dans la configuration du service, à Instagram seul. Une liste nulle
signifie **toutes les applications** pour Android, et la déclaration à
l'exécution peut ne jamais tourner — une exception au démarrage suffit. Le
plancher n'est pas un plafond : `setServiceInfo` élargit librement au-delà.
Sans lui, un service qui échoue à se restreindre observerait tout, et une
capture armée écrirait les arbres de toutes les apps sur le disque.

Conséquences, toutes obligatoires :

- **Les nouvelles apps arrivent éteintes.** Instagram reste allumé — c'est
  l'existant de l'utilisateur, l'éteindre serait une régression silencieuse.
- **Échec en mode fermé, côté exposition.** Si les réglages sont illisibles, le
  service garde les surfaces Instagram bloquées (le défaut fail-closed actuel)
  et **n'inclut aucun autre paquet**. Deux prudences différentes qui ne se
  contredisent pas : on continue de protéger ce que l'utilisateur avait déjà, on
  n'ouvre pas ce qu'il n'a pas demandé.
- **La redéclaration ne peut pas jeter.** `setServiceInfo` est appelé depuis un
  collecteur de réglages ; une exception qui s'en échappe tuerait le service, et
  l'invariant 4 du projet l'interdit.

## Modèle de règles

`rules.json` gagne une dimension. Version portée à 2.

```json
{
  "version": 2,
  "apps": {
    "com.instagram.android": {
      "surfaces": { "REELS": { ... }, "EXPLORE": { ... } }
    },
    "com.google.android.youtube": {
      "surfaces": { "SHORTS": { ... } }
    },
    "com.snapchat.android": {
      "surfaces": { "SPOTLIGHT": { ... } }
    }
  }
}
```

Un paquet peut apparaître plusieurs fois sous des noms différents : YouTube a
trois variantes installables (`com.google.android.youtube`,
`com.google.android.apps.youtube.kids`, `app.revanced.android.youtube`). Chacune
est une entrée à part entière ; dupliquer trois petits blocs vaut mieux
qu'inventer un mécanisme d'alias pour un seul cas.

**Le fichier reste surchargeable** depuis `filesDir`, et l'analyseur garde sa
propriété cardinale : **il ne jette jamais**. Un fichier de version 1 est un
échec de lecture propre, pas une migration silencieuse — l'app retombe sur les
règles embarquées et le bandeau d'avertissement le dit.

## Surfaces

`Surface` gagne `SHORTS` et `SPOTLIGHT`. Une surface reste le concept de son
app plutôt qu'une abstraction commune : un interrupteur et un compteur par
surface, ce que l'interface sait déjà afficher.

`DISCOVER` n'est **pas** créée à ce stade. Personne n'en publie l'identifiant,
et la réserve initiale tient : Discover est mêlé à l'écran des discussions dans
certaines versions de Snapchat, donc une règle trop large mordrait sur les
conversations. Elle attend une capture.

## Réglages

`BlockSettings(blockReels, blockExplore)` devient un ensemble de surfaces
actives. Les deux clés booléennes existantes sont **lues comme valeurs de
départ** pour ne pas réinitialiser les choix de l'utilisateur, puis l'ensemble
fait foi.

Défauts : `REELS` et `EXPLORE` actives, `SHORTS` et `SPOTLIGHT` inactives.

## Règles livrées, et leur statut

L'utilisateur a choisi de livrer les identifiants publiés par Scrolless — projet
Kotlin actif, mis à jour cette semaine — sans attendre nos propres captures.

| Surface | Signal HIGH | Source |
|---|---|---|
| SHORTS | `reel_player_page_container` | Scrolless |
| SHORTS | `reel_progress_bar` | Shorts-Blocker |
| SPOTLIGHT | `spotlight_container` | Scrolless |

**Statut : vérifiées sur appareil, sans fixture.** L'utilisateur a confirmé sur
son Pixel 9a que le blocage des Shorts YouTube et de Spotlight Snapchat
fonctionne, et sans faux positif signalé sur les vidéos YouTube normales. Le
pari d'emprunter ces identifiants était le point le plus incertain du chantier ;
il est gagné.

Ce qui manque encore : **aucune fixture, donc aucun test de non-régression.** Le
jour où YouTube ou Snapchat renommeront un identifiant, rien ne le dira avant
que l'utilisateur ne le constate. Une capture de chaque app reste à faire.

Deux signaux indépendants pour YouTube plutôt qu'un : ils viennent de deux
projets différents, donc si l'un est périmé l'autre tient.

**Ce chantier n'est pas terminé quand le code l'est.** Il se termine par une
capture de YouTube et de Snapchat sur l'appareil, des fixtures nettoyées, et des
tests — comme pour Instagram.

## Interface

Les interrupteurs sont groupés par application, chaque groupe portant le nom de
l'app.

Les deux grands chiffres du jour, aujourd'hui « Reels » et « Explore », ne
tiennent plus à quatre surfaces. Ils deviennent **un seul grand nombre — le
total du jour — suivi d'une ligne de détail par surface active**, en petits
caractères. L'histogramme des quatorze jours agrège tout : l'utilisateur veut
savoir combien de fois il a été retenu, pas tenir une comptabilité par
plateforme.

Un groupe dont l'app n'est pas installée n'est pas affiché — proposer de bloquer
Snapchat à quelqu'un qui ne l'a pas est du bruit.

## Confidentialité

- L'invariant 2 tient inchangé : le champ `text` n'est jamais lu, et la
  frontière reste les six membres autorisés.
- Aucune règle de ce document ne s'appuie sur un `contentDescription`.
- Les captures de YouTube et Snapchat suivront la procédure établie après
  l'incident : nettoyage intégral des libellés, jamais de capture brute commitée.
- La liste des paquets déclarés est visible dans l'écran d'accueil, pour que
  l'utilisateur sache ce que le service peut voir à cet instant.

## Stratégie de test

**JVM**

- Analyseur : lit le format version 2 ; refuse proprement la version 1 ; tolère
  un paquet inconnu ; ne jette sur aucune entrée malformée.
- Classificateur : les règles d'une app ne peuvent pas s'appliquer à une autre.
- Réglages : migration depuis les deux booléens, défauts corrects, ensemble vide
  possible.
- Liste de paquets : dérivée des surfaces actives ; Instagram seul quand les
  réglages sont illisibles ; jamais vide au point de tuer le service.
- Non-régression : les fixtures Instagram existantes classent exactement comme
  avant.

**Sur appareil**

- Instagram se comporte comme aujourd'hui, blocage et redirection Explore
  compris.
- Activer SHORTS ajoute YouTube à la liste déclarée ; le désactiver l'en retire.
- YouTube et Snapchat éteints : aucun événement reçu de ces paquets.

## Hors périmètre

- **Discover Snapchat** — attend une capture, voir plus haut.
- **L'étagère Shorts dans le fil d'accueil de YouTube** — c'est une bande dans
  un écran qu'on veut garder, donc le même problème que les vidéos du fil
  Instagram, et il se traitera avec lui.
- **TikTok, Facebook** — Scrolless les couvre ; l'utilisateur ne les a pas
  demandés.
