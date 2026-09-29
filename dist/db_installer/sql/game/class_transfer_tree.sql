-- ---------------------------------------------------------------------------
-- class_transfer_tree
--
-- Full H5 class transfer tree (every race, Kamael included), built from the
-- real PlayerClass IDs provided.
-- Defines every valid transfer: which class ID a character must currently be
-- (from_class_id) to unlock which class ID (to_class_id) at which tier
-- (1/2/3). Branching classes get multiple rows with the same
-- from_class_id/tier - ClassMaster presents all of them as choices.
--
-- required_item_id is NULL throughout (level-only gating, per current
-- design). Left nullable so a per-transfer item requirement can be added
-- later without a schema change, if that design question gets revisited.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS class_transfer_tree
(
	id INT NOT NULL AUTO_INCREMENT,
	from_class_id INT NOT NULL,
	to_class_id INT NOT NULL,
	tier TINYINT NOT NULL,
	required_item_id INT NULL,
	required_item_count BIGINT NOT NULL DEFAULT 1,
	PRIMARY KEY (id),
	KEY idx_from_class_tier (from_class_id, tier)
);

-- =============================================================================
-- HUMAN
-- =============================================================================

-- Tier 1: Fighter (0) -> Warrior/Knight/Rogue; Mage (10) -> Wizard/Cleric
INSERT INTO class_transfer_tree (from_class_id, to_class_id, tier) VALUES
(0, 1, 1),
(0, 4, 1),
(0, 7, 1),
(10, 11, 1),
(10, 15, 1);

-- Tier 2
INSERT INTO class_transfer_tree (from_class_id, to_class_id, tier) VALUES
(1, 2, 2),
(1, 3, 2),
(4, 5, 2),
(4, 6, 2),
(7, 8, 2),
(7, 9, 2),
(11, 12, 2),
(11, 13, 2),
(11, 14, 2),
(15, 16, 2),
(15, 17, 2);

-- Tier 3
INSERT INTO class_transfer_tree (from_class_id, to_class_id, tier) VALUES
(2, 88, 3),
(3, 89, 3),
(5, 90, 3),
(6, 91, 3),
(8, 93, 3),
(9, 92, 3),
(12, 94, 3),
(13, 95, 3),
(14, 96, 3),
(16, 97, 3),
(17, 98, 3);

-- =============================================================================
-- ELF
-- =============================================================================

-- Tier 1: Fighter (18) -> Knight/Scout; Mage (25) -> Wizard/Oracle
INSERT INTO class_transfer_tree (from_class_id, to_class_id, tier) VALUES
(18, 19, 1),
(18, 22, 1),
(25, 26, 1),
(25, 29, 1);

-- Tier 2
INSERT INTO class_transfer_tree (from_class_id, to_class_id, tier) VALUES
(19, 20, 2),
(19, 21, 2),
(22, 23, 2),
(22, 24, 2),
(26, 27, 2),
(26, 28, 2),
(29, 30, 2);

-- Tier 3
INSERT INTO class_transfer_tree (from_class_id, to_class_id, tier) VALUES
(20, 99, 3),
(21, 100, 3),
(23, 101, 3),
(24, 102, 3),
(27, 103, 3),
(28, 104, 3),
(30, 105, 3);

-- =============================================================================
-- DARK ELF
-- =============================================================================

-- Tier 1: Fighter (31) -> Palus Knight/Assassin; Mage (38) -> Dark Wizard/Shillien Oracle
INSERT INTO class_transfer_tree (from_class_id, to_class_id, tier) VALUES
(31, 32, 1),
(31, 35, 1),
(38, 39, 1),
(38, 42, 1);

-- Tier 2
INSERT INTO class_transfer_tree (from_class_id, to_class_id, tier) VALUES
(32, 33, 2),
(32, 34, 2),
(35, 36, 2),
(35, 37, 2),
(39, 40, 2),
(39, 41, 2),
(42, 43, 2);

-- Tier 3
INSERT INTO class_transfer_tree (from_class_id, to_class_id, tier) VALUES
(33, 106, 3),
(34, 107, 3),
(36, 108, 3),
(37, 109, 3),
(40, 110, 3),
(41, 111, 3),
(43, 112, 3);

-- =============================================================================
-- ORC
-- =============================================================================

-- Tier 1: Fighter (44) -> Raider/Monk; Mage (49) -> Shaman
INSERT INTO class_transfer_tree (from_class_id, to_class_id, tier) VALUES
(44, 45, 1),
(44, 47, 1),
(49, 50, 1);

-- Tier 2
INSERT INTO class_transfer_tree (from_class_id, to_class_id, tier) VALUES
(45, 46, 2),
(47, 48, 2),
(50, 51, 2),
(50, 52, 2);

-- Tier 3
INSERT INTO class_transfer_tree (from_class_id, to_class_id, tier) VALUES
(46, 113, 3),
(48, 114, 3),
(51, 115, 3),
(52, 116, 3);

-- =============================================================================
-- DWARF
-- =============================================================================

-- Tier 1: Fighter (53) -> Scavenger/Artisan
INSERT INTO class_transfer_tree (from_class_id, to_class_id, tier) VALUES
(53, 54, 1),
(53, 56, 1);

-- Tier 2
INSERT INTO class_transfer_tree (from_class_id, to_class_id, tier) VALUES
(54, 55, 2),
(56, 57, 2);

-- Tier 3
INSERT INTO class_transfer_tree (from_class_id, to_class_id, tier) VALUES
(55, 117, 3),
(57, 118, 3);

-- =============================================================================
-- KAMAEL
-- =============================================================================
-- Written so the block can also be run on its own on an existing database:
-- it only adds the rows that are missing.
--
-- Tier 1: Male Soldier (123) -> Trooper; Female Soldier (124) -> Warder
-- Tier 2: Trooper (125) -> Berserker/Male Soul Breaker; Warder (126) -> Female Soul Breaker/Arbalester
-- Tier 3: Berserker -> Doombringer, Male Soul Breaker -> Male Soul Hound,
--         Female Soul Breaker -> Female Soul Hound, Arbalester -> Trickster,
--         Inspector (135, a subclass only) -> Judicator
INSERT INTO class_transfer_tree (from_class_id, to_class_id, tier)
SELECT k.from_class_id, k.to_class_id, k.tier FROM (
	SELECT 123 AS from_class_id, 125 AS to_class_id, 1 AS tier
	UNION ALL SELECT 124, 126, 1
	UNION ALL SELECT 125, 127, 2
	UNION ALL SELECT 125, 128, 2
	UNION ALL SELECT 126, 129, 2
	UNION ALL SELECT 126, 130, 2
	UNION ALL SELECT 127, 131, 3
	UNION ALL SELECT 128, 132, 3
	UNION ALL SELECT 129, 133, 3
	UNION ALL SELECT 130, 134, 3
	UNION ALL SELECT 135, 136, 3
) k
WHERE NOT EXISTS (SELECT 1 FROM class_transfer_tree t WHERE t.from_class_id = k.from_class_id AND t.to_class_id = k.to_class_id AND t.tier = k.tier);
