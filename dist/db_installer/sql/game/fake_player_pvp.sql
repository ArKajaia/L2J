-- PvP kills of fake players by name, for the Community Board PvP ranking
-- (PvpRankingManager). A name a fake player takes again keeps its count.
-- class_id is the class of the fake player when it last made a PvP kill
-- (-1 if not known); the server adds the column to an older table.
-- The server also creates this table on its own if it is missing.

CREATE TABLE IF NOT EXISTS `fake_player_pvp` (
  `name` VARCHAR(35) NOT NULL,
  `pvp_kills` INT UNSIGNED NOT NULL DEFAULT 0,
  `class_id` SMALLINT NOT NULL DEFAULT -1,
  PRIMARY KEY (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
