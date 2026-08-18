<p align="center">
  <img src="docs/brand/digue.svg" alt="" width="88" height="88">
</p>

<h1 align="center">Digue</h1>

<p align="center"><em>Les fils de vidéos courtes, retenus</em></p>

---

App Android qui **bloque les fils de vidéos courtes dans plusieurs apps officielles**.

Ce n'est pas un client alternatif : aucune de ces apps n'expose d'API de fil. Digue observe
leur arbre de vues via un `AccessibilityService`, reconnaît l'écran affiché, et agit — retour
arrière la plupart du temps, ou un appui sur un nœud précis pour Explore.

La marque dit la même chose en quatre rectangles : trois vagues qui montent, une digue qui ne
bouge pas.

| App | Surface | Interrupteur |
|---|---|---|
| Instagram | Reels | oui |
| Instagram | Explore | oui — **redirige** vers la recherche au lieu de sortir |
| YouTube | Shorts | oui |
| Snapchat | Spotlight | oui |
| Snapchat | Discover | oui |

Plus un **quota quotidien** — quelques minutes par jour, ouvrables seulement dans une plage
horaire choisie — et un **verrou** qui rend tout assouplissement lent au lieu d'instantané.

## Vie privée, et ce qu'il ne faut jamais commiter ici

Ce dépôt a été privé jusqu'au 2026-08-18, pour deux raisons dont une seule demandait un
travail réel. Les deux sont traitées :

- Les commits antérieurs au 2026-08-17 portaient le **numéro de série du téléphone de test**.
  L'historique a été réécrit le 2026-08-18 et le dépôt distant recréé, pour qu'aucun objet de
  l'ancienne histoire ne survive côté serveur.
- Le dépôt décrit des mesures faites sur un appareil réel — des lignes de logcat horodatées
  d'une séance de recette. Aucune donnée d'usage n'a été exportée ici : ni base, ni journal de
  visionnage, ni capture d'écran.

**Un incident de confidentialité a déjà eu lieu**, et c'est la raison de la règle qui suit :
des captures d'arbres de vues contenant des noms de contacts et des extraits de conversations
privées ont été commitées, puis purgées et l'historique réécrit.

> **Ne jamais commiter de capture d'écran ni de capture d'arbre brute**, d'aucune des trois
> apps. Une capture d'arbre porte de vraies données personnelles dans ses
> `contentDescription`, même si l'app ne lit jamais le champ `text`.

Les fixtures de test versionnées ici sont nettoyées — toutes leurs `contentDescription` valent
`[scrubbed]`, à l'exception de sept chaînes de chrome Instagram sans contenu personnel — et un
test le vérifie à chaque exécution, pour qu'une fixture ajoutée sans précaution soit attrapée
avant d'atterrir.

## Ce que l'app ne fait pas, par construction

- **Aucun appel réseau, aucune dépendance réseau.** Rien ne quitte l'appareil, jamais.
- **Le champ `text` des vues n'est jamais lu, ni journalisé, ni persisté.** Un service
  d'accessibilité voit tout le texte à l'écran ; celui-ci ne lit que l'identifiant de
  ressource, la description d'accessibilité, la classe, l'état sélectionné, la cliquabilité
  et les bornes.
- **Le service ne voit que les applications dont une surface est allumée.** La liste des
  paquets est redéclarée à l'exécution depuis les réglages, et elle est appliquée par Android,
  pas par l'app : Snapchat éteint, Snapchat est *incapable* d'atteindre le service.

## Construire et tester

```bash
./gradlew build                                   # tout, y compris le lint
./gradlew :detection:test :app:testDebugUnitTest  # 266 tests JVM
./gradlew :app:installDebug                       # installe sur un appareil branché
./gradlew :app:connectedDebugAndroidTest          # 24 tests instrumentés — DÉSINSTALLE l'app
```

La dernière commande efface la base de l'app en fin de course : sauvegarder avant si l'appareil
porte un historique qui compte.

## Structure

```
:detection   Kotlin pur, AUCUN import android.* — c'est la contrainte structurante.
             Reconnaissance d'écran, règles, analyse du fichier de règles.
:app         Le service d'accessibilité, la base, les réglages, l'écran unique en Compose.
```

Le service traduit l'arbre Android en instantané neutre, puis appelle une fonction pure. C'est
ce qui rend la détection testable sur JVM contre de vrais arbres capturés, sans appareil.

## Où est la vraie documentation

**[`CLAUDE.md`](CLAUDE.md)** — architecture, invariants, format des règles, pièges
d'identifiants, mécanique du quota et du verrou, recette d'appareil, et les limites assumées.
À lire avant toute reprise : chaque piège qui y figure a coûté une erreur réelle, et le fichier
est tenu à jour.

`docs/` contient les spécifications et plans d'implémentation, plus l'audit du 2026-08-17 et
les deux corrections qu'il a fallu lui annuler.

## Limites connues

- Un service d'accessibilité **ne peut pas annuler un geste**. Après un glissement, environ une
  seconde du contenu suivant reste visible avant que l'app réagisse. Irréductible.
- Le verrou protège les réglages *de Digue*, rien d'autre. Couper le service dans les réglages
  Android reste à un geste, et désinstaller aussi. Un mot de passe a été écarté pour cette
  raison exacte : un secret qu'on choisit soi-même ne vaut rien contre soi-même, alors qu'un
  délai tient même en connaissant tout le code.
- Les règles reposent sur des identifiants de ressources internes. Le jour où une de ces apps
  en renomme un, la surface concernée cesse d'être bloquée ; l'app le signale en affichant une
  détection dégradée, mais la réparation demande une nouvelle capture.

## Licence

MIT — voir [`LICENSE`](LICENSE).
