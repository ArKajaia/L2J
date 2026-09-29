-- Alternative Class Transfer Challenges: one row per cleared trial that has
-- not yet been used for a Class Master transfer. The row is tied to the class
-- the character had when clearing it (from_class_id) and to the class slot
-- (class_index, 0 = main class), and is deleted when the transfer happens.
-- Challenge DEFINITIONS live in data/ClassTransferChallenges/*.xml.

CREATE TABLE IF NOT EXISTS `class_transfer_challenge_completion` (
  `char_id` INT UNSIGNED NOT NULL,
  `class_index` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `stage` TINYINT UNSIGNED NOT NULL,
  `from_class_id` SMALLINT UNSIGNED NOT NULL,
  `challenge_id` VARCHAR(64) NOT NULL,
  `completed_at` BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (`char_id`, `class_index`, `stage`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
