-- One-time update for existing databases (fresh installs don't need it).
--
-- The custom skills moved from the 90000s to the 27000s, because the High
-- Five client does not resolve skill ids that high:
--   Hot Zone buffs        90000-90004 -> 27000-27004
--   Passive tree actives  90400-90465 -> 27400-27465
-- Every id moves down by 63000. Run this once, with the game server stopped,
-- so hotbar shortcuts, saved cooldowns and saved buffs follow the new ids.

UPDATE `character_shortcuts` SET `shortcut_id` = `shortcut_id` - 63000
WHERE `type` = 2 AND `shortcut_id` BETWEEN 90000 AND 90499;

UPDATE `character_skills_save` SET `skill_id` = `skill_id` - 63000
WHERE `skill_id` BETWEEN 90000 AND 90499;

UPDATE `character_skills` SET `skill_id` = `skill_id` - 63000
WHERE `skill_id` BETWEEN 90000 AND 90499;
