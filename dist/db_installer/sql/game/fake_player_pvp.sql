-- PvP kills of fake players by name, for the Community Board PvP ranking
-- (PvpRankingManager). A name a fake player takes again keeps its count.
-- The server also creates this table on its own if it is missing.

CREATE TABLE IF NOT EXISTS `fake_player_pvp` (
  `name` VARCHAR(35) NOT NULL,
  `pvp_kills` INT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
