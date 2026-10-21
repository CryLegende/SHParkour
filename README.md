<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHParkour

*Un plugin de parkour avancé avec création de parcours, checkpoints, hologrammes et protections.*

<img src="https://img.shields.io/badge/Paper-1.20.2-f2a900?style=for-the-badge" alt="Paper 1.20.2"> <img src="https://img.shields.io/badge/Java-17-e76f00?style=for-the-badge" alt="Java 17"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Un plugin de parkour avancé avec création de parcours, checkpoints, hologrammes et protections.

Utilisé sur : **Opale**.

## Dépendances

- Obligatoires : `PlaceholderAPI`, `FancyHolograms`, `SHInventoryPages`

## Commandes

| Commande | Alias | Description | Permission |
|---|---|---|---|
| `/parkour` | `/shpk` | Commande principale du plugin SHParkour. | — |

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shparkour.admin.*` | Donne un accès complet à toutes les commandes d'administration de SHParkour. | `op` |
| `shparkour.admin.create` | Permet de créer un nouveau parcours avec /parkour create. | `op` |
| `shparkour.admin.delete` | Permet de supprimer un parcours existant avec /parkour delete. | `op` |
| `shparkour.admin.setup` | Permet de configurer les points d'un parcours avec /parkour setup. | `op` |
| `shparkour.admin.reload` | Permet de recharger la configuration du plugin avec /parkour reload. | `op` |
| `shparkour.admin.bypass.commands` | Permet à un administrateur d'utiliser des commandes normalement bloquées pendant un parkour. | `op` |
| `shparkour.admin.resettime` | Permet de réinitialiser le temps d'un joueur sur un parcours. | `op` |

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`) :

- `config.yml`

## Compilation

Java 17 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
