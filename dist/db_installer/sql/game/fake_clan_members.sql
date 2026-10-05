-- Fake players that joined a players' clan (FakeClans.ini, FakeClanManager).
-- They are members for good: name, class line (build) and looks never change.
-- level grows over the day cycles; seed makes their gear and passive tree,
-- and changes at the levels of FakeClanMemberRerollLevels. academy_level is
-- the level a member of the clan academy joined it at (0 for the main clan).
-- The server also creates this table (and the academy_level column) on its
-- own if it is missing.

CREATE TABLE IF NOT EXISTS `fake_clan_members` (
  `name` VARCHAR(35) NOT NULL,
  `clan_id` INT UNSIGNED NOT NULL,
  `build` VARCHAR(64) NOT NULL,
  `class_id` SMALLINT UNSIGNED NOT NULL,
  `level` TINYINT UNSIGNED NOT NULL,
  `female` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `hair` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `hair_color` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `face` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `title` VARCHAR(16) NOT NULL DEFAULT '',
  `seed` BIGINT NOT NULL DEFAULT 0,
  `joined` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `academy_level` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`name`),
  KEY `clan_id` (`clan_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
