/*
 * Copyright (c) 2013 L2jMobius
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.l2jmobius.gameserver.managers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.custom.FakePlayerPvpConfig;
import org.l2jmobius.gameserver.data.AugmentationData;
import org.l2jmobius.gameserver.data.xml.OptionData;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.Weapon;
import org.l2jmobius.gameserver.model.item.holders.Elementals;
import org.l2jmobius.gameserver.model.item.type.CrystalType;
import org.l2jmobius.gameserver.model.options.Augmentation;
import org.l2jmobius.gameserver.model.options.OptionSkillHolder;
import org.l2jmobius.gameserver.model.options.Options;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.network.clientpackets.AbstractRefinePacket;

/**
 * Attributes and augments of a fake player of level 76 or more (FakePvpRefine*, FakePvp*AttributeChance and FakePvpAugment* in config/Custom/FakePlayerPvp.ini), like a player that spent on them what it could:
 * <ul>
 * <li>its weapon, its armor and its augment are each rolled on their own, with a chance of its level grown by its wealth (see {@link FakePlayerPvpEnchant#roll(double)}): a rich one most often has them but not always, a poor one now and then
 * does. The strong fake players of the PvP spots keep the better of two rolls;</li>
 * <li>weapon: one of the six attributes, 75 to 150 with attribute stones, up to 300 with crystals;</li>
 * <li>armor: one to three attributes that aren't opposites on every armor piece that takes them, 30 to 60 each with stones, up to 120 with crystals (the High Five caps);</li>
 * <li>augment: a random weapon augment of the life stone of its level, top-grade or high-grade (data/stats/augmentation). Its stats and its passive or chance skill count; it has no use for an active augment skill.</li>
 * </ul>
 */
public class FakePlayerPvpRefine
{
	/** {minLevel, life stone level (as in AbstractRefinePacket), high-grade id, top-grade id}, sorted by level. */
	private static final int[][] LIFE_STONES =
	{
		// @formatter:off
		{76, 9, 8752, 8762},
		{80, 10, 9575, 9576},
		{82, 11, 10485, 10486},
		{84, 12, 14168, 14169},
		{85, 13, 16162, 16163},
		// @formatter:on
	};
	/** Attributes that can't be on the same armor piece, in pairs. */
	private static final byte[][] OPPOSITES =
	{
		{
			Elementals.FIRE,
			Elementals.WATER
		},
		{
			Elementals.WIND,
			Elementals.EARTH
		},
		{
			Elementals.HOLY,
			Elementals.DARK
		}
	};
	/** Weapon attribute: 25 per step, stones up to 150 (from 75), crystals up to 300. */
	private static final int WEAPON_STEP = 25;
	private static final int WEAPON_STONE_MIN = 75;
	private static final int WEAPON_STONE_MAX = 150;
	private static final int WEAPON_CRYSTAL_MAX = 300;
	/** Armor attribute: 6 per step, stones up to 60 (from 30), crystals up to 120. */
	private static final int ARMOR_STEP = 6;
	private static final int ARMOR_STONE_MIN = 30;
	private static final int ARMOR_STONE_MAX = 60;
	private static final int ARMOR_CRYSTAL_MAX = 120;
	/** Chance (in %, for wealth 1) of every further step past the stone level once it uses crystals. */
	private static final double CRYSTAL_STEP_CHANCE = 75;
	/** Chances (in %, for wealth 1) of a second and a third armor attribute. */
	private static final double SECOND_ARMOR_ATTRIBUTE_CHANCE = 55;
	private static final double THIRD_ARMOR_ATTRIBUTE_CHANCE = 30;

	private byte _attackElement = Elementals.NONE;
	private int _attackValue;
	private final int[] _defence = new int[6];
	/** What each armor piece has (item id -> its value of each element), which adds up to {@link #_defence}: a piece it drops keeps its own. */
	private final Map<Integer, int[]> _pieceDefence = new HashMap<>();
	private int _augmentationId;

	private FakePlayerPvpRefine()
	{
	}

	/**
	 * @param enchanter the fake player's enchanter (its level, wealth and whether it is one of the strong ones)
	 * @param weapon the weapon it holds, {@code null} for none
	 * @param armors the armor pieces it wears (chest, legs, helmet, gloves, boots), {@code null} for none
	 * @return what it has: nothing below FakePvpRefineMinLevel or with FakePvpRefineEnabled off
	 */
	public static FakePlayerPvpRefine roll(FakePlayerPvpEnchant enchanter, Weapon weapon, ItemTemplate... armors)
	{
		final FakePlayerPvpRefine result = new FakePlayerPvpRefine();
		final int level = enchanter.getLevel();
		if (!FakePlayerPvpConfig.REFINE_ENABLED || (level < FakePlayerPvpConfig.REFINE_MIN_LEVEL))
		{
			return result;
		}

		// Weapon attribute.
		if ((weapon != null) && weapon.isElementable() && chance(enchanter, FakePlayerPvpConfig.WEAPON_ATTRIBUTE_CHANCE))
		{
			result._attackElement = (byte) Rnd.get(6);
			result._attackValue = rollValue(enchanter, WEAPON_STEP, WEAPON_STONE_MIN, WEAPON_STONE_MAX, WEAPON_CRYSTAL_MAX);
		}

		// Armor attributes: one to three of them, never two opposites, on every piece that takes them.
		if (chance(enchanter, FakePlayerPvpConfig.ARMOR_ATTRIBUTE_CHANCE))
		{
			int count = 1;
			if (enchanter.roll(SECOND_ARMOR_ATTRIBUTE_CHANCE))
			{
				count++;
				if (enchanter.roll(THIRD_ARMOR_ATTRIBUTE_CHANCE))
				{
					count++;
				}
			}

			final List<byte[]> pairs = new ArrayList<>(List.of(OPPOSITES));
			for (int i = pairs.size() - 1; i > 0; i--)
			{
				Collections.swap(pairs, i, Rnd.get(i + 1));
			}
			final boolean crystals = chance(enchanter, FakePlayerPvpConfig.ATTRIBUTE_CRYSTAL_CHANCE);
			for (int i = 0; i < count; i++)
			{
				final byte element = pairs.get(i)[Rnd.get(2)];
				for (ItemTemplate armor : armors)
				{
					if ((armor != null) && armor.isElementable())
					{
						final int value = crystals ? rollCrystal(enchanter, ARMOR_STEP, ARMOR_STONE_MAX, ARMOR_CRYSTAL_MAX) : rollStone(ARMOR_STEP, ARMOR_STONE_MIN, ARMOR_STONE_MAX);
						result._defence[element] += value;
						result._pieceDefence.computeIfAbsent(armor.getId(), id -> new int[6])[element] += value;
					}
				}
			}
		}

		// Augment.
		if (isAugmentable(weapon) && chance(enchanter, FakePlayerPvpConfig.AUGMENT_CHANCE))
		{
			int[] stone = null;
			for (int[] row : LIFE_STONES)
			{
				if (level >= row[0])
				{
					stone = row;
				}
			}

			if (stone != null)
			{
				final boolean top = chance(enchanter, FakePlayerPvpConfig.AUGMENT_TOP_GRADE_CHANCE);
				final Augmentation augmentation = AugmentationData.getInstance().generateRandomWeaponAugmentation(stone[1], top ? AbstractRefinePacket.GRADE_TOP : AbstractRefinePacket.GRADE_HIGH, top ? stone[3] : stone[2], weapon.isMagicWeapon());
				result._augmentationId = augmentation != null ? augmentation.getAugmentationId() : 0;
			}
		}

		return result;
	}

	/**
	 * @return {@code true} for a weapon a life stone can augment (see AbstractRefinePacket): C grade or more, not a hero, shadow, common, time-limited or (unless allowed) PvP weapon
	 */
	private static boolean isAugmentable(Weapon weapon)
	{
		return (weapon != null) && !weapon.isHeroItem() && !weapon.isCommon() && (weapon.getDuration() <= 0) && (weapon.getTime() <= 0) && (!weapon.isPvpItem() || PlayerConfig.ALT_ALLOW_AUGMENT_PVP_ITEMS) && !weapon.getCrystalType().isLesser(CrystalType.C);
	}

	/**
	 * @return {@code true} with the chance of the fake player's level grown by its wealth, the better of two rolls for a strong one of the PvP spots
	 */
	private static boolean chance(FakePlayerPvpEnchant enchanter, List<int[]> chances)
	{
		final int chance = FakePlayerPvpConfig.getTierChance(chances, enchanter.getLevel());
		return enchanter.roll(chance) || (enchanter.isElite() && enchanter.roll(chance));
	}

	private static int rollValue(FakePlayerPvpEnchant enchanter, int step, int stoneMin, int stoneMax, int crystalMax)
	{
		return chance(enchanter, FakePlayerPvpConfig.ATTRIBUTE_CRYSTAL_CHANCE) ? rollCrystal(enchanter, step, stoneMax, crystalMax) : rollStone(step, stoneMin, stoneMax);
	}

	/**
	 * @return {@code min} to {@code max} in steps: where a player stopped with attribute stones
	 */
	private static int rollStone(int step, int min, int max)
	{
		return min + (step * Rnd.get(((max - min) / step) + 1));
	}

	/**
	 * @return one step past the stone level, then every further step with {@link #CRYSTAL_STEP_CHANCE} grown by the wealth, up to {@code max}
	 */
	private static int rollCrystal(FakePlayerPvpEnchant enchanter, int step, int stoneMax, int max)
	{
		int value = stoneMax + step;
		while ((value < max) && enchanter.roll(CRYSTAL_STEP_CHANCE))
		{
			value += step;
		}

		return value;
	}

	/**
	 * @return the attribute of its weapon (Elementals.FIRE...), Elementals.NONE for none
	 */
	public byte getAttackElement()
	{
		return _attackElement;
	}

	/**
	 * @return the attribute value of its weapon, 0 for none
	 */
	public int getAttackValue()
	{
		return _attackValue;
	}

	/**
	 * @param element an attribute (Elementals.FIRE...)
	 * @return the resistance its armor gives to it, all pieces together
	 */
	public int getDefence(byte element)
	{
		return _defence[element];
	}

	/**
	 * @return the attribute its weapon has, as the value of each element (index Elementals.FIRE ... Elementals.DARK), all 0 for none
	 */
	public int[] getWeaponAttributes()
	{
		final int[] attributes = new int[6];
		if ((_attackElement >= 0) && (_attackValue > 0))
		{
			attributes[_attackElement] = _attackValue;
		}

		return attributes;
	}

	/**
	 * @param itemId the id of one of the armor pieces it wears
	 * @return what that piece has, as the value of each element (index Elementals.FIRE ... Elementals.DARK), all 0 for none; the pieces add up to {@link #getDefence}
	 */
	public int[] getArmorAttributes(int itemId)
	{
		final int[] attributes = _pieceDefence.get(itemId);
		return attributes != null ? attributes.clone() : new int[6];
	}

	/**
	 * @return the augmentation id of its weapon (the two option ids, as in packets), 0 for none
	 */
	public int getAugmentationId()
	{
		return _augmentationId;
	}

	/**
	 * @param augmentationId an augmentation id, 0 for none
	 * @return the passive skills of its options, for the template's skills
	 */
	public static List<Skill> getPassiveSkills(int augmentationId)
	{
		final List<Skill> skills = new ArrayList<>(1);
		for (Options option : getOptions(augmentationId))
		{
			if (option.hasPassiveSkill())
			{
				skills.add(option.getPassiveSkill());
			}
		}

		return skills;
	}

	/**
	 * Gives a fake player that just spawned the stats and chance skills of its augment (its passive skill is in its template).
	 * @param npc the fake player
	 * @param augmentationId its augmentation id, 0 for none
	 */
	public static void applyToFakePlayer(Npc npc, int augmentationId)
	{
		for (Options option : getOptions(augmentationId))
		{
			npc.removeStatsOwner(option);
			if (option.hasFuncs())
			{
				npc.addStatFuncs(option.getStatFuncs(null, npc));
			}

			for (OptionSkillHolder holder : option.getActivationSkills())
			{
				npc.addTriggerSkill(holder);
			}
		}
	}

	/**
	 * @param augmentationId an augmentation id, 0 for none
	 * @return a short description: "options 16341/15543 (skill Prayer)"
	 */
	public static String describeAugment(int augmentationId)
	{
		if (augmentationId == 0)
		{
			return "none";
		}

		final StringBuilder sb = new StringBuilder("options " + (augmentationId & 0xFFFF) + "/" + (augmentationId >> 16));
		for (Options option : getOptions(augmentationId))
		{
			final Skill skill = option.hasActiveSkill() ? option.getActiveSkill() : option.hasPassiveSkill() ? option.getPassiveSkill() : option.hasActivationSkills() ? option.getActivationSkills().get(0).getSkill() : null;
			if (skill != null)
			{
				sb.append(" (").append(option.hasActiveSkill() ? "active " : option.hasPassiveSkill() ? "passive " : "chance ").append(skill.getName()).append(')');
			}
		}

		return sb.toString();
	}

	/**
	 * @return its attributes: "weapon Holy 300, armor Fire 600 Wind 360", "none" for none
	 */
	public String describeAttributes()
	{
		final StringBuilder sb = new StringBuilder();
		if (_attackElement != Elementals.NONE)
		{
			sb.append("weapon ").append(Elementals.getElementName(_attackElement)).append(' ').append(_attackValue);
		}

		final StringBuilder armor = new StringBuilder();
		for (byte element = 0; element < 6; element++)
		{
			if (_defence[element] > 0)
			{
				armor.append(' ').append(Elementals.getElementName(element)).append(' ').append(_defence[element]);
			}
		}

		if (armor.length() > 0)
		{
			sb.append(sb.length() > 0 ? ", " : "").append("armor").append(armor);
		}

		return sb.length() > 0 ? sb.toString() : "none";
	}

	/**
	 * @return its attributes by name ("weaponHoly" -> 300, "Fire" -> 600...) for reports, empty for none
	 */
	public Map<String, Integer> toMap()
	{
		final Map<String, Integer> map = new LinkedHashMap<>();
		if (_attackElement != Elementals.NONE)
		{
			map.put("weapon" + Elementals.getElementName(_attackElement), _attackValue);
		}

		for (byte element = 0; element < 6; element++)
		{
			if (_defence[element] > 0)
			{
				map.put(Elementals.getElementName(element), _defence[element]);
			}
		}

		return map;
	}

	private static List<Options> getOptions(int augmentationId)
	{
		if (augmentationId == 0)
		{
			return Collections.emptyList();
		}

		final List<Options> options = new ArrayList<>(2);
		for (int id : new int[]
		{
			augmentationId & 0xFFFF,
			augmentationId >> 16
		})
		{
			final Options option = id > 0 ? OptionData.getInstance().getOptions(id) : null;
			if (option != null)
			{
				options.add(option);
			}
		}

		return options;
	}
}
