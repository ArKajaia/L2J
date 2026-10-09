-- One-time update for existing databases (fresh installs don't need it).
--
-- Create Item (skill 172) is no longer in the class skill trees of the
-- non-dwarven classes and the Scavenger line: the passive tree's Dwarven
-- Craft node is now the only way for them to craft dwarven recipes (up to
-- TreeCreateItemMaxLevel in config/Custom/DwarvenTrades.ini). Artisans,
-- Warsmiths and Maestros keep their own Create Item as in retail.
--
-- Characters that already learned it from the class master keep it in
-- character_skills, and SkillCheckRemove would strip it at their next login
-- and report every one of them for an invalid skill. Run this once, with
-- the game server stopped, to take it back quietly. The recipes they
-- registered are kept, and come back into use when they take the node.
--
-- Dwarven classes: 53 Dwarven Fighter, 54 Scavenger, 55 Bounty Hunter,
-- 56 Artisan, 57 Warsmith, 117 Fortune Seeker, 118 Maestro.

-- Non-dwarven classes lose Create Item: base class (class_index 0) ...
DELETE cs FROM `character_skills` cs
JOIN `characters` c ON c.`charId` = cs.`charId`
WHERE cs.`skill_id` = 172 AND cs.`class_index` = 0
AND c.`base_class` NOT IN (53, 54, 55, 56, 57, 117, 118);

-- ... and subclasses.
DELETE cs FROM `character_skills` cs
JOIN `character_subclasses` s ON s.`charId` = cs.`charId` AND s.`class_index` = cs.`class_index`
WHERE cs.`skill_id` = 172 AND cs.`class_index` > 0
AND s.`class_id` NOT IN (53, 54, 55, 56, 57, 117, 118);

-- The Scavenger line goes back to the Dwarven Fighter's level 1.
UPDATE `character_skills` cs
JOIN `characters` c ON c.`charId` = cs.`charId`
SET cs.`skill_level` = 1
WHERE cs.`skill_id` = 172 AND cs.`class_index` = 0 AND cs.`skill_level` > 1
AND c.`base_class` IN (54, 55, 117);

UPDATE `character_skills` cs
JOIN `character_subclasses` s ON s.`charId` = cs.`charId` AND s.`class_index` = cs.`class_index`
SET cs.`skill_level` = 1
WHERE cs.`skill_id` = 172 AND cs.`class_index` > 0 AND cs.`skill_level` > 1
AND s.`class_id` IN (54, 55, 117);

-- Artisans learned levels 5-10 before their time: back to 4 (the Warsmith learns 5-9 again).
UPDATE `character_skills` cs
JOIN `characters` c ON c.`charId` = cs.`charId`
SET cs.`skill_level` = 4
WHERE cs.`skill_id` = 172 AND cs.`class_index` = 0 AND cs.`skill_level` > 4
AND c.`base_class` = 56;

UPDATE `character_skills` cs
JOIN `character_subclasses` s ON s.`charId` = cs.`charId` AND s.`class_index` = cs.`class_index`
SET cs.`skill_level` = 4
WHERE cs.`skill_id` = 172 AND cs.`class_index` > 0 AND cs.`skill_level` > 4
AND s.`class_id` = 56;

-- Warsmiths could take level 10 early the same way: back to 9 (the Maestro learns 10 again).
UPDATE `character_skills` cs
JOIN `characters` c ON c.`charId` = cs.`charId`
SET cs.`skill_level` = 9
WHERE cs.`skill_id` = 172 AND cs.`class_index` = 0 AND cs.`skill_level` > 9
AND c.`base_class` = 57;

UPDATE `character_skills` cs
JOIN `character_subclasses` s ON s.`charId` = cs.`charId` AND s.`class_index` = cs.`class_index`
SET cs.`skill_level` = 9
WHERE cs.`skill_id` = 172 AND cs.`class_index` > 0 AND cs.`skill_level` > 9
AND s.`class_id` = 57;
