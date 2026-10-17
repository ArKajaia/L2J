-- Bestiary: how many of each monster every account has killed (all its
-- characters together). The game server also creates this table at startup
-- (BestiaryManager), so a database set up before the bestiary came gets it
-- without running this file, and moves the old character_bestiary into it.
-- variants: the rare forms killed (1 Champion, 2 Mage, 4 Infused, 8 Resonant);
-- best_time: the fastest kill in ms, by the character best_char.
CREATE TABLE IF NOT EXISTS `account_bestiary` (
  `account_name` VARCHAR(45) NOT NULL,
  `npc_id` INT UNSIGNED NOT NULL,
  `kills` INT UNSIGNED NOT NULL DEFAULT 0,
  `first_kill` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `last_kill` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `first_char` INT UNSIGNED NOT NULL DEFAULT 0,
  `variants` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `best_time` INT UNSIGNED NOT NULL DEFAULT 0,
  `best_char` INT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`account_name`, `npc_id`),
  KEY `npc_id` (`npc_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

-- Today's bounties of each account: the monsters and how many of each were
-- killed since (comma separated, in the same order). day is yyyymmdd.
CREATE TABLE IF NOT EXISTS `account_bestiary_bounty` (
  `account_name` VARCHAR(45) NOT NULL,
  `day` INT UNSIGNED NOT NULL DEFAULT 0,
  `npc_ids` VARCHAR(100) NOT NULL DEFAULT '',
  `progress` VARCHAR(100) NOT NULL DEFAULT '',
  PRIMARY KEY (`account_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

-- Every raid and grand boss kill, for the kill log of their profile pages.
CREATE TABLE IF NOT EXISTS `bestiary_boss_kills` (
  `id` INT UNSIGNED NOT NULL AUTO_INCREMENT,
  `npc_id` INT UNSIGNED NOT NULL,
  `kill_time` BIGINT UNSIGNED NOT NULL,
  `duration` INT UNSIGNED NOT NULL DEFAULT 0,
  `killer` VARCHAR(35) NOT NULL,
  `clan` VARCHAR(45) NOT NULL DEFAULT '',
  `party_size` TINYINT UNSIGNED NOT NULL DEFAULT 1,
  PRIMARY KEY (`id`),
  KEY `npc_time` (`npc_id`, `kill_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

-- What really came out of each monster's drops (spoil 0) and spoil (spoil 1)
-- across the server. item_id 0 counts the rolls: kills, or spoiled kills.
CREATE TABLE IF NOT EXISTS `bestiary_drop_stats` (
  `npc_id` INT UNSIGNED NOT NULL,
  `spoil` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `item_id` INT UNSIGNED NOT NULL,
  `times` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `amount` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`npc_id`, `spoil`, `item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;
