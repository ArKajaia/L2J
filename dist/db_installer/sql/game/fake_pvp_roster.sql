-- Known fake players of the PvP spots (PvpSpotRoster, PvpSpots.ini).
-- A character is saved when it first comes to a spot and made again from
-- build, level, elite and seed (the seed decides its gear, enchants, looks and
-- passive tree). daily_play is its play time per day in milliseconds, kept for
-- life, window_hour the hour of the day its play window starts at; play_day
-- (days since 1970, server time zone) and played (milliseconds) count today's
-- play time. The server also creates this table on its own if it is missing.

CREATE TABLE IF NOT EXISTS `fake_pvp_roster` (
  `name` VARCHAR(35) NOT NULL,
  `build` VARCHAR(64) NOT NULL,
  `class_id` SMALLINT UNSIGNED NOT NULL,
  `level` TINYINT UNSIGNED NOT NULL,
  `elite` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `seed` BIGINT NOT NULL,
  `created` BIGINT UNSIGNED NOT NULL,
  `daily_play` BIGINT UNSIGNED NOT NULL,
  `window_hour` TINYINT UNSIGNED NOT NULL,
  `play_day` INT NOT NULL DEFAULT 0,
  `played` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `last_seen` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
