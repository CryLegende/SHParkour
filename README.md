<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHParkour

*Un plugin de parkour avancé avec création de parcours, checkpoints, hologrammes et protections.*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Un plugin de parkour avancé avec création de parcours, checkpoints, hologrammes et protections.

Utilisé sur : **Opale**.

## Dépendances

- Obligatoires : `PlaceholderAPI`, `FancyHolograms`, `SHInventoryPages`

## Commandes

| Commande | Description | Permission |
|---|---|---|
| `/parkour create <nom>` | Crée un parcours. | `shparkour.admin.create` |
| `/parkour delete <nom>` | Supprime un parcours. | `shparkour.admin.delete` |
| `/parkour setup <nom> <option>` | `setstart`, `setend`, `addcheckpoint`, `editcheckpoint`, `deletecheckpoint`, `setrespawn`, `setendspawn`, `setname`. | `shparkour.admin.setup` |
| `/parkour reload` | Recharge `config.yml` et les parcours. | `shparkour.admin.reload` |
| `/parkour resettime <parcours> <pseudo\|uuid>` (`rt`) | Retire le meilleur temps d'un joueur, connecté ou non. Le parcours reste « terminé » : la récompense de première fois n'est pas redonnée. | `shparkour.admin.resettime` |
| `/parkour top <parcours> [page]` | Classement complet avec la date de chaque record. | `shparkour.admin.top` |

Alias : `/shpk`. Le pseudo de `resettime` est cherché dans les données du plugin (pas de requête Mojang : le proxy est en online-mode et rendrait l'UUID premium au lieu de l'UUID du réseau).

## Placeholders

| Placeholder | Valeur |
|---|---|
| `%shparkour_<parcours>_time_formatted%` | Meilleur temps du joueur, en secondes (`19.850`), `N/A` sinon |
| `%shparkour_<parcours>_time_ms%` | Meilleur temps du joueur en millisecondes |
| `%shparkour_<parcours>_rank%` | Position du joueur au classement |
| `%shparkour_<parcours>_top_<n>_name%` / `_time%` / `_ms%` | n-ième du classement |

Un hologramme FancyHolograms qui affiche ces placeholders doit avoir un `update_text_interval` (par exemple `10s`), sinon son texte n'est jamais recalculé et un temps retiré reste affiché.

## Plusieurs serveurs Opale

SHParkour n'a pas de mode réseau, et n'en a pas besoin. Les quatre parcours (`jump` et `usine` dans le monde `spawn`, `easter`, `christmas`) sont dans des mondes qui n'existent que sur le serveur du spawn, de l'arène et des events : c'est lui qui joue les parcours et qui garde seul `playerdata.yml` (temps, classements, récompenses de première arrivée) et `playerstates/` (inventaire mis de côté pendant la course).

Sur un autre serveur, ces mondes ne sont que des copies où aucun joueur ne reste, donc personne n'y démarre un parcours. Les temps lus par les placeholders y restent ceux de la copie de `playerdata.yml`. Un parcours créé un jour dans un monde présent sur plusieurs serveurs demanderait un stockage partagé (meilleur temps en `LEAST`, récompense de première arrivée en `INSERT IGNORE`, inventaire mis de côté en base).

## Protections pendant un parcours

Vol, téléportation, ender pearls, effets (vitesse, saut, lévitation, chute lente) et commandes hors liste blanche sont refusés. Un joueur qui devient **passager** (monture, véhicule, siège posé sur la tête d'un autre joueur...) quitte le parcours : il serait porté par une entité qui, elle, peut voler ou faire des doubles sauts. Chaque arrivée et chaque `resettime` sont écrits dans la console.

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shparkour.admin.*` | Donne un accès complet à toutes les commandes d'administration de SHParkour. | `op` |
| `shparkour.admin.create` | Permet de créer un nouveau parcours avec /parkour create. | `op` |
| `shparkour.admin.delete` | Permet de supprimer un parcours existant avec /parkour delete. | `op` |
| `shparkour.admin.setup` | Permet de configurer les points d'un parcours avec /parkour setup. | `op` |
| `shparkour.admin.reload` | Permet de recharger la configuration du plugin avec /parkour reload. | `op` |
| `shparkour.admin.bypass.commands` | Permet à un administrateur d'utiliser des commandes normalement bloquées pendant un parkour. | `op` |
| `shparkour.admin.resettime` | Permet de retirer le temps d'un joueur d'un parcours (classements compris). | `op` |
| `shparkour.admin.top` | Permet de consulter le classement complet d'un parcours avec /parkour top. | `op` |

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHParkour/
├── <a href="src/main/resources/config.yml">config.yml</a>
├── <a href="src/main/resources/parkour_template.yml">parkour_template.yml</a>
└── playerdata.yml
</pre>

`playerdata.yml` (écrit par le plugin, sauvegardé toutes les 5 minutes, aux déconnexions et à l'arrêt) :

```yaml
players:
  <uuid>:
    name: Pseudo            # dernier pseudo connu
    best-times:
      usine: 19850          # meilleur temps en ms
    record-dates:
      usine: 1761955200000  # date du record (epoch ms)
    completed: [usine]      # parcours déjà terminés : récompense de première fois acquise
    checkpoint-rewards:
      easter: [0, 1]        # récompenses de checkpoint déjà données
```

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
