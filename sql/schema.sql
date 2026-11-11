-- Tables de SHParkour avec SHNetwork. Le plugin les crée seul au démarrage si l'utilisateur a le droit CREATE ;
-- ce fichier sert à les créer à la main et à ne donner à l'utilisateur que les droits de lecture et d'écriture.
--
-- Utilisation (base bo_net pour les essais multi-Opale) :
--   mysql -u root < sql/schema.sql   puis remplacer CHANGE_ME par le mot de passe de config.yml

CREATE DATABASE IF NOT EXISTS bo_net;

-- Joueurs qui ont des données de parcours : dernier pseudo connu (classements, /parkour resettime)
CREATE TABLE IF NOT EXISTS bo_net.shparkour_players (
    uuid CHAR(36)    NOT NULL,                    -- uuid « hors ligne » du joueur, le même sur tous les serveurs
    name VARCHAR(32) NOT NULL DEFAULT '',
    PRIMARY KEY (uuid),
    KEY idx_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Une ligne par joueur et par parcours terminé : l'existence de la ligne vaut « parcours terminé » (récompense de
-- première arrivée acquise). best_ms est NULL quand /parkour resettime a retiré le temps : le parcours reste terminé.
-- Un meilleur temps ne s'écrit que s'il est plus petit (voir ParkourDatabase.recordFinish), jamais en écrasant.
CREATE TABLE IF NOT EXISTS bo_net.shparkour_times (
    uuid        CHAR(36)     NOT NULL,
    parkour     VARCHAR(100) NOT NULL,            -- nom interne du parcours, en minuscules
    best_ms     BIGINT       NULL,                -- meilleur temps en millisecondes
    record_date BIGINT       NOT NULL DEFAULT 0,  -- date du record (epoch ms), 0 si inconnue
    PRIMARY KEY (uuid, parkour),
    KEY idx_rank (parkour, best_ms, record_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Récompenses de checkpoint déjà données (INSERT IGNORE : donnée une seule fois)
CREATE TABLE IF NOT EXISTS bo_net.shparkour_checkpoints (
    uuid       CHAR(36)     NOT NULL,
    parkour    VARCHAR(100) NOT NULL,
    checkpoint INT          NOT NULL,
    PRIMARY KEY (uuid, parkour, checkpoint)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE USER IF NOT EXISTS 'shparkour'@'127.0.0.1' IDENTIFIED BY 'CHANGE_ME';
GRANT SELECT, INSERT, UPDATE ON bo_net.shparkour_players TO 'shparkour'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE ON bo_net.shparkour_times TO 'shparkour'@'127.0.0.1';
GRANT SELECT, INSERT ON bo_net.shparkour_checkpoints TO 'shparkour'@'127.0.0.1';
