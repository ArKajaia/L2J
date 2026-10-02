-- Passive Tree: allocated nodes per character, per subclass slot.
-- Node DEFINITIONS live in data/passivetree/*.xml, not in the DB -
-- this keeps the tree itself easy to hand-edit/diff like your other
-- spawn/skill data, and this table only ever stores player choices.

CREATE TABLE IF NOT EXISTS `character_passive_tree` (
  `char_id` INT NOT NULL,
  `class_index` INT NOT NULL DEFAULT 0,
  `node_id` INT NOT NULL,
  `allocated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`char_id`, `class_index`, `node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- Passive Tree templates: a snapshot of every template that is NOT the active
-- one. The active template always lives in `character_passive_tree` above
-- (so the rest of the tree code never has to know templates exist); switching
-- swaps the two sets. Which template is active, and when the player last
-- switched, are kept in character_variables.
CREATE TABLE IF NOT EXISTS `character_passive_tree_template` (
  `char_id` INT NOT NULL,
  `class_index` INT NOT NULL DEFAULT 0,
  `template_id` TINYINT NOT NULL,
  `node_id` INT NOT NULL,
  PRIMARY KEY (`char_id`, `class_index`, `template_id`, `node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
