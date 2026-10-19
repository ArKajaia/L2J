/*
 * This file is part of the L2J Mobius project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.l2jmobius.gameserver.model.guide;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.config.custom.AdventurersGuideConfig;
import org.l2jmobius.gameserver.config.custom.ChampionMonstersConfig;
import org.l2jmobius.gameserver.config.custom.ClassTransferConfig;
import org.l2jmobius.gameserver.config.custom.CommunityBoardConfig;
import org.l2jmobius.gameserver.config.custom.CustomBuffConfig;
import org.l2jmobius.gameserver.config.custom.DynamicQuestConfig;
import org.l2jmobius.gameserver.config.custom.HiddenQuestConfig;
import org.l2jmobius.gameserver.config.custom.InfusedMonsterConfig;
import org.l2jmobius.gameserver.config.custom.LuckyLootConfig;
import org.l2jmobius.gameserver.config.custom.MageMonsterConfig;
import org.l2jmobius.gameserver.config.custom.MonsterRageConfig;
import org.l2jmobius.gameserver.config.custom.NightCycleConfig;
import org.l2jmobius.gameserver.config.custom.PassiveTreeConfig;
import org.l2jmobius.gameserver.config.custom.ResonantMonsterConfig;
import org.l2jmobius.gameserver.config.custom.ThiefMonsterConfig;
import org.l2jmobius.gameserver.config.custom.TreasureChestConfig;
import org.l2jmobius.gameserver.config.custom.WaveChallengeConfig;

/**
 * A lesson of the Adventurer's Guide (see {@link org.l2jmobius.gameserver.managers.AdventurersGuideManager}). Its page is {@code data/html/guide/<page>.htm}; the class transfer lessons are written for the player's class instead.
 * <p>
 * A lesson is unlocked at its level (when the feature's monsters or npcs become available to the character), or earlier by the event that shows it (meeting such a monster, finding a Champion Medal...). A lesson with no level comes only by its event. The ids of the tutorial
 * question marks follow the order of this enum, so new lessons go at the end.
 */
public enum GuideLesson
{
	WELCOME(GuideCategory.BASICS, "The Adventurer's Guide", "welcome", () -> true, () -> 1),
	COMMUNITY_BOARD(GuideCategory.BASICS, "The Community Board", "community_board", () -> CommunityBoardConfig.CUSTOM_CB_ENABLED, () -> AdventurersGuideConfig.COMMUNITY_BOARD_LEVEL),
	PASSIVE_TREE(GuideCategory.BASICS, "The Passive Skill Tree", "passive_tree", () -> PassiveTreeConfig.PASSIVE_TREE_ENABLED, () -> PassiveTreeConfig.PASSIVE_TREE_START_LEVEL),
	CLASS_1(GuideCategory.CLASS, "1st Class Transfer", null, () -> ClassTransferConfig.CLASS_TRANSFER_ENABLED, () -> Math.min(18, ClassTransferConfig.TIER1_MIN_LEVEL)),
	CLASS_2(GuideCategory.CLASS, "2nd Class Transfer", null, () -> ClassTransferConfig.CLASS_TRANSFER_ENABLED, () -> Math.min(35, ClassTransferConfig.TIER2_MIN_LEVEL)),
	CLASS_3(GuideCategory.CLASS, "3rd Class Transfer", null, () -> ClassTransferConfig.CLASS_TRANSFER_ENABLED, () -> Math.min(76, ClassTransferConfig.TIER3_MIN_LEVEL)),
	NOTICE_BOARD(GuideCategory.QUESTS, "The Notice Board", "notice_board", () -> DynamicQuestConfig.ENABLED, () -> DynamicQuestConfig.MIN_LEVEL),
	QUEST_TAKEN(GuideCategory.QUESTS, "Running a Quest", "quest_taken", () -> DynamicQuestConfig.ENABLED, () -> 0),
	HIDDEN_QUESTS(GuideCategory.QUESTS, "Hidden Quests", "hidden_quests", () -> HiddenQuestConfig.ENABLED, () -> HiddenQuestConfig.MIN_LEVEL),
	MONSTER_TRAITS(GuideCategory.MONSTERS, "Archetypes and Rage", "monster_traits", () -> RatesConfig.RANDOM_PASSIVE_SKILLS_ENABLED || MonsterRageConfig.ENABLED, () -> AdventurersGuideConfig.MONSTER_TRAITS_LEVEL),
	NEMESIS(GuideCategory.MONSTERS, "Nemesis", "nemesis", () -> RatesConfig.NEMESIS_SYSTEM_ENABLED, () -> 0),
	CHAMPIONS(GuideCategory.MONSTERS, "Champion Monsters", "champions", () -> ChampionMonstersConfig.CHAMPION_ENABLE, () -> ChampionMonstersConfig.CHAMP_MIN_LEVEL),
	MAGE_MONSTERS(GuideCategory.MONSTERS, "Mage Monsters", "mage_monsters", () -> MageMonsterConfig.ENABLED, () -> MageMonsterConfig.MIN_LEVEL),
	TREASURE_CHESTS(GuideCategory.MONSTERS, "Treasure Chests and Mimics", "treasure_chests", () -> TreasureChestConfig.ENABLED, () -> AdventurersGuideConfig.TREASURE_CHESTS_LEVEL),
	RESONANT(GuideCategory.MONSTERS, "Resonant Monsters", "resonant", () -> ResonantMonsterConfig.ENABLED, () -> ResonantMonsterConfig.MIN_LEVEL),
	THIEVES(GuideCategory.MONSTERS, "Thieves", "thieves", () -> ThiefMonsterConfig.ENABLED, () -> ThiefMonsterConfig.MIN_LEVEL),
	WAVE_CHALLENGES(GuideCategory.MONSTERS, "Wave Challenges", "wave_challenges", () -> WaveChallengeConfig.ENABLED, () -> WaveChallengeConfig.MIN_LEVEL),
	INFUSED(GuideCategory.MONSTERS, "Infused Monsters", "infused", () -> InfusedMonsterConfig.ENABLED, () -> InfusedMonsterConfig.MIN_LEVEL),
	BUFFS(GuideCategory.REWARDS, "Learning Buffs", "buffs", () -> CustomBuffConfig.ENABLE, () -> AdventurersGuideConfig.BUFFS_LEVEL),
	ARENA(GuideCategory.REWARDS, "The Survival Arena", "arena", () -> RatesConfig.ARENA_SYSTEM_ENABLED, () -> AdventurersGuideConfig.ARENA_LEVEL),
	LUCK(GuideCategory.REWARDS, "Luck and Sealed Caches", "luck", () -> LuckyLootConfig.LUCK_ENABLED || LuckyLootConfig.SEALED_CACHE_ENABLED, () -> LuckyLootConfig.SEALED_CACHE_MIN_LEVEL),
	HOT_ZONES(GuideCategory.WORLD, "Hot Zones", "hot_zones", () -> true, () -> AdventurersGuideConfig.HOT_ZONES_LEVEL),
	NIGHT(GuideCategory.WORLD, "The Night Cycle", "night", () -> NightCycleConfig.ENABLED, () -> AdventurersGuideConfig.NIGHT_LEVEL);

	private final GuideCategory _category;
	private final String _title;
	private final String _page;
	private final BooleanSupplier _enabled;
	private final IntSupplier _level;

	GuideLesson(GuideCategory category, String title, String page, BooleanSupplier enabled, IntSupplier level)
	{
		_category = category;
		_title = title;
		_page = page;
		_enabled = enabled;
		_level = level;
	}

	public GuideCategory getCategory()
	{
		return _category;
	}

	public String getTitle()
	{
		return _title;
	}

	/**
	 * @return the page name under {@code data/html/guide/}, {@code null} for a class transfer lesson
	 */
	public String getPage()
	{
		return _page;
	}

	/**
	 * @return {@code true} if the feature this lesson teaches is on
	 */
	public boolean isEnabled()
	{
		return _enabled.getAsBoolean();
	}

	/**
	 * @return the level that unlocks this lesson, 0 if only its event does
	 */
	public int getLevel()
	{
		return Math.max(0, _level.getAsInt());
	}

	/**
	 * @return the class transfer tier (1-3) this lesson is about, 0 if none
	 */
	public int getClassTier()
	{
		switch (this)
		{
			case CLASS_1:
			{
				return 1;
			}
			case CLASS_2:
			{
				return 2;
			}
			case CLASS_3:
			{
				return 3;
			}
			default:
			{
				return 0;
			}
		}
	}

	/**
	 * @param name a lesson name, as {@link #name()} gives it (case does not matter)
	 * @return the lesson, {@code null} if there is none of that name
	 */
	public static GuideLesson find(String name)
	{
		for (GuideLesson lesson : values())
		{
			if (lesson.name().equalsIgnoreCase(name))
			{
				return lesson;
			}
		}
		return null;
	}
}
