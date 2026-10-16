-- Bestiary: how many of each monster every character has killed.
-- The game server also creates this table at startup (BestiaryManager), so a
-- database set up before the bestiary came gets it without running this file.
CREATE TABLE IF NOT EXISTS `character_bestiary` (
  `char_id` INT UNSIGNED NOT NULL,
  `npc_id` INT UNSIGNED NOT NULL,
  `kills` INT UNSIGNED NOT NULL DEFAULT 0,
  `first_kill` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `last_kill` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`char_id`, `npc_id`),
  KEY `npc_id` (`npc_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
