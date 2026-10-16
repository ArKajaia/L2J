-- Bestiary: how many of each monster every account has killed (all its
-- characters together). The game server also creates this table at startup
-- (BestiaryManager), so a database set up before the bestiary came gets it
-- without running this file, and moves the old character_bestiary into it.
CREATE TABLE IF NOT EXISTS `account_bestiary` (
  `account_name` VARCHAR(45) NOT NULL,
  `npc_id` INT UNSIGNED NOT NULL,
  `kills` INT UNSIGNED NOT NULL DEFAULT 0,
  `first_kill` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `last_kill` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `first_char` INT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`account_name`, `npc_id`),
  KEY `npc_id` (`npc_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;
