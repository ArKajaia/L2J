-- Dynamic Quests: the quests running for each character, what each account
-- completed today (and its cooldowns), and the board offers each account took
-- today. The game server also creates these tables at startup
-- (DynamicQuestTable), so a database set up before the feature came gets them
-- without running this file.
CREATE TABLE IF NOT EXISTS `character_dynamic_quests` (
  `instance_id` BIGINT NOT NULL,
  `char_id` INT UNSIGNED NOT NULL,
  `template_id` INT UNSIGNED NOT NULL,
  `seed` BIGINT NOT NULL,
  `tier` VARCHAR(8) NOT NULL,
  `quest_level` TINYINT UNSIGNED NOT NULL,
  `party_size` TINYINT UNSIGNED NOT NULL DEFAULT 1,
  `stage` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `escalations` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `chain` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `optional_done` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `expire_at` BIGINT NOT NULL DEFAULT 0,
  `context` TEXT NOT NULL,
  `objective_state` TEXT NOT NULL,
  `participants` VARCHAR(255) NOT NULL DEFAULT '',
  `offer_key` VARCHAR(64) NOT NULL DEFAULT '',
  `created_at` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`instance_id`),
  KEY `char_id` (`char_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

CREATE TABLE IF NOT EXISTS `dynamic_quest_usage` (
  `account_name` VARCHAR(45) NOT NULL,
  `template_id` INT UNSIGNED NOT NULL,
  `day` INT UNSIGNED NOT NULL DEFAULT 0,
  `daily_count` SMALLINT UNSIGNED NOT NULL DEFAULT 0,
  `cooldown_until` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`account_name`, `template_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

CREATE TABLE IF NOT EXISTS `dynamic_quest_taken` (
  `account_name` VARCHAR(45) NOT NULL,
  `offer_key` VARCHAR(64) NOT NULL,
  `day` INT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`account_name`, `offer_key`),
  KEY `day` (`day`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;
