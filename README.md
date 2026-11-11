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
- Optionnelles : `SHNetwork` (mode réseau)

## Commandes

| Commande | Description | Permission |
|---|---|---|
| `/parkour create <nom>` | Crée un parcours. | `shparkour.admin.create` |
| `/parkour delete <nom>` | Supprime un parcours. | `shparkour.admin.delete` |
| `/parkour setup <nom> <option>` | `setstart`, `setend`, `addcheckpoint`, `editcheckpoint`, `deletecheckpoint`, `setrespawn`, `setendspawn`, `setname`. | `shparkour.admin.setup` |
| `/parkour reload` | Recharge `config.yml` et les parcours. | `shparkour.admin.reload` |
| `/parkour resettime <parcours> <pseudo\|uuid>` (`rt`) | Retire le meilleur temps d'un joueur, connecté ou non. Le parcours reste « terminé » : la récompense de première fois n'est pas redonnée. | `shparkour.admin.resettime` |
| `/parkour top <parcours> [page]` | Classement complet avec la date de chaque record. | `shparkour.admin.top` |
| `/parkour import` | Mode réseau : copie `playerdata.yml` dans la base, sans rien écraser. | `shparkour.admin.import` |

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

Les quatre parcours (`jump` et `usine` dans le monde `spawn`, `easter`, `christmas`) sont dans des mondes qui n'existent que sur le serveur du spawn, de l'arène et des events : c'est lui qui joue les parcours, et lui seul garde `playerstates/` (inventaire mis de côté pendant la course : une course ne passe jamais d'un serveur à l'autre, la quitter en changeant de serveur la termine sans temps et rend l'inventaire).

Avec **SHNetwork** installé (et `reseau.actif: true`, valeur par défaut), les données des joueurs sont partagées par tous les serveurs de jeu :

- en MariaDB, trois tables au lieu de `playerdata.yml` : `shparkour_players` (pseudo), `shparkour_times` (une ligne par joueur et par parcours terminé : meilleur temps, date du record ; la ligne vaut « parcours terminé ») et `shparkour_checkpoints` (récompenses de checkpoint données) ;
- à la connexion, une fois que l'autre serveur a fini de sauvegarder le joueur, ses données sont lues en base (la lecture commence à la pré-connexion, sans la bloquer). Tant qu'elles ne sont pas lues, il ne peut pas démarrer de parcours (« vos données sont en cours de chargement ») : sans elles, une arrivée passerait pour sa première et redonnerait la récompense ;
- chaque arrivée est écrite en base tout de suite, avant que le joueur ne puisse changer de serveur. Le meilleur temps s'écrit en une instruction atomique qui ne garde que le plus petit : deux serveurs ne s'écrasent jamais. Les récompenses de première arrivée et de checkpoint ne sont données qu'une fois ;
- les classements viennent de la base et sont gardés en mémoire : les placeholders `%shparkour_...%` et `/parkour top` ne touchent jamais la base et montrent les mêmes classements sur tous les serveurs. Ils sont relus après chaque arrivée ou retrait de temps (message du bus SHNetwork aux autres serveurs) et toutes les 15 secondes ;
- `/parkour resettime` retire le temps en base, quel que soit le serveur où se trouve le joueur, et le fait oublier à celui où il est connecté. Le pseudo est cherché dans la base du réseau. Le parcours reste « terminé » : pas de seconde récompense de première arrivée ;
- `/parkour import` copie une fois `playerdata.yml` dans la base (rien n'est écrasé : un temps retiré depuis par `resettime` ne revient pas, on peut le relancer). `playerdata.yml` n'est plus ni lu ni écrit en mode réseau ;
- l'anti-triche (passager, vol, téléportation, effets, commandes) ne change pas : il agit sur le serveur où se joue le parcours.

Sans SHNetwork, ou avec `reseau.actif: false`, ou sans les clés `reseau` dans la configuration, le plugin se comporte comme avant : `playerdata.yml`. Si la base est injoignable au démarrage, il reste en mode local. Les tables se créent toutes seules, ou à la main avec `sql/schema.sql`.

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
| `shparkour.admin.import` | Permet de copier `playerdata.yml` dans la base du réseau avec /parkour import. | `op` |

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHParkour/
├── <a href="src/main/resources/config.yml">config.yml</a>
├── <a href="src/main/resources/parkour_template.yml">parkour_template.yml</a>
└── playerdata.yml <i>(mode normal)</i>
</pre>

Le fichier `sql/schema.sql` du dépôt crée les tables du mode réseau.

`playerdata.yml` (mode normal ; écrit par le plugin, sauvegardé toutes les 5 minutes, aux déconnexions et à l'arrêt) :

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
