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
import java.util.List;
import java.util.Map;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.FakePlayerPvpConfig;
import org.l2jmobius.gameserver.data.holders.ArmorSet;
import org.l2jmobius.gameserver.data.xml.ArmorSetData;
import org.l2jmobius.gameserver.data.xml.FakePlayerPvpData;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.PlayerTemplateData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.data.xml.SkillTreeData;
import org.l2jmobius.gameserver.managers.FakePlayerPvpEnchant.Kind;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.actor.enums.creature.Race;
import org.l2jmobius.gameserver.model.actor.enums.npc.AIType;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.enums.player.Sex;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild.Role;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild.SkillCategory;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpCombo;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpGearTier;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpPassives;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpPersonality;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpWeapon;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.actor.templates.PlayerTemplate;
import org.l2jmobius.gameserver.model.effects.AbstractEffect;
import org.l2jmobius.gameserver.model.item.Armor;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.Weapon;
import org.l2jmobius.gameserver.model.item.enums.BodyPart;
import org.l2jmobius.gameserver.model.item.holders.ItemEnchantHolder;
import org.l2jmobius.gameserver.model.item.type.ArmorType;
import org.l2jmobius.gameserver.model.item.type.WeaponType;
import org.l2jmobius.gameserver.model.itemcontainer.Inventory;
import org.l2jmobius.gameserver.model.skill.EffectScope;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.holders.SkillHolder;
import org.l2jmobius.gameserver.model.skill.holders.SkillLearn;
import org.l2jmobius.gameserver.model.stats.MoveType;
import org.l2jmobius.gameserver.model.stats.Stat;
import org.l2jmobius.gameserver.model.stats.functions.FuncTemplate;

/**
 * Builds the npc template of a roaming fake player so that, once the regular stat formulas run on it, it ends up with the stats of a real character of the same class and level wearing the same gear:
 * <ul>
 * <li>base STR/DEX/CON/INT/WIT/MEN, HP/MP/CP tables, regeneration, speed and collision come from the class {@link PlayerTemplate} (plus armor set bonuses);</li>
 * <li>P. Atk., M. Atk., attack speed, critical rate and range are the weapon's (like the weapon "set" functions of a real character), P. Def./M. Def. are the class base values with the default of every worn slot replaced by the item (like
 * {@code FuncPDefMod}/{@code FuncMDefMod}), with the enchant bonus of every piece like {@code FuncEnchant} (each piece is enchanted on its own, see {@link FakePlayerPvpEnchant});</li>
 * <li>a shirt and a belt from some level on, and a cloak once its armor set opens the cloak slot, like players wear them;</li>
 * <li>all class passive skills learned up to the level, armor set skills and the build's active skills at the level of the class skill tree.</li>
 * </ul>
 * NPCs already get the same STR/CON/DEX/INT/WIT/MEN and level multipliers as players ({@code Formulas#getStdNPCCalculators()}), so nothing else needs to be special cased.
 */
public class FakePlayerPvpFactory
{
	/** Parameters that keep the AttackableAI archetype roll predictable (it only scales hate for these npcs). */
	private static final StatSet PARAMETERS = new StatSet(Map.of("AIArchetype", "BALANCED"));
	/** Chance (in %) a town fake player wears one of {@link #TOWN_HAIR_ACCESSORIES}. */
	private static final int TOWN_HAIR_ACCESSORY_CHANCE = 22;
	/** Hats, masks, ears and hairpins (hair slot) players wear in town. */
	private static final int[] TOWN_HAIR_ACCESSORIES =
	{
		6843, // Cat Ears
		6844, // Lady's Hair Pin
		6845, // Pirate's Eye Patch
		6846, // Monocle
		7680, // Raccoon Ears
		7681, // Outlaw's Eyepatch
		7682, // Maiden's Hairpin
		7683, // Rabbit Ears
		7695, // Forget-me-not Hairpin
		7696, // Daisy Hairpin
		8184, // Party Hat
		8185, // Feathered Hat
		8186, // Artisan's Goggles
		8187, // Demon Horns
		8188, // Little Angel Wings
		8189, // Fairy Antennae
		8552, // Mask of Spirits
		8557, // Blue Party Hat
		8559, // Diadem
		8560, // Teddy Bear Hat
		8561, // Piggy Hat
		8562, // Jester Hat
		8563, // Wizard Hat
		8564, // Dapper Cap
		8565, // Romantic Chapeau
		8569, // Half Face Mask
		8910, // Black Feather Mask
		8912, // Single Stem Flower
		8913, // Butterfly Hairpin
		8916, // Eye Patch
		8918, // Leather Cap
		8919, // First Mate's Hat
		8920, // Angel Halo
		8922, // Pirate Hat
	};
	
	/** The kit of the shirts and belts every fake player may wear (data/FakePlayerPvp.xml). */
	private static final String ACCESSORY_KIT = "ACCESSORIES";
	/** The effect of the armor set skills that open the cloak slot (Dynasty, Moirai, Vesper Noble...). */
	private static final String CLOAK_SLOT_EFFECT = "EnableCloak";
	
	/** The armor kits a Kamael wears when its build's kit holds heavy armor or a robe (see {@link #getArmorGear}). */
	private static final String KAMAEL_MAGE_ARMOR_KIT = "LIGHT_MAGE";
	private static final String KAMAEL_FIGHTER_ARMOR_KIT = "LIGHT_FIGHTER";
	
	/**
	 * How a fake player looks, kept for one that is made again and again (a member of a players' clan, see {@link FakeClanManager}).
	 * @param female {@code true} for a female character
	 * @param hair the hair style
	 * @param hairColor the hair color
	 * @param face the face
	 */
	public record Looks(boolean female, int hair, int hairColor, int face)
	{
	}
	
	private FakePlayerPvpFactory()
	{
	}
	
	/**
	 * @param build the build
	 * @param level the level
	 * @param npcId a free npc id for the template
	 * @param name the character name
	 * @return a new template with its {@link FakePlayerPvpProfile} attached, or {@code null} if the build can't be made at this level
	 */
	public static NpcTemplate createTemplate(FakePlayerPvpBuild build, int level, int npcId, String name)
	{
		return createTemplate(build, level, npcId, name, null, "");
	}
	
	/**
	 * @param build the build
	 * @param level the level
	 * @param npcId a free npc id for the template
	 * @param name the character name
	 * @param forcedClass a class of the build's class line to use instead of the one its level gives ({@code null} for the level's class)
	 * @param title the character title
	 * @return a new template with its {@link FakePlayerPvpProfile} attached, or {@code null} if the build can't be made at this level
	 */
	public static NpcTemplate createTemplate(FakePlayerPvpBuild build, int level, int npcId, String name, PlayerClass forcedClass, String title)
	{
		return createTemplate(build, level, npcId, name, forcedClass, title, null);
	}
	
	/**
	 * @param build the build
	 * @param level the level
	 * @param npcId a free npc id for the template
	 * @param name the character name
	 * @param forcedClass a class of the build's class line to use instead of the one its level gives ({@code null} for the level's class)
	 * @param title the character title
	 * @param looks how it looks, {@code null} for random looks
	 * @return a new template with its {@link FakePlayerPvpProfile} attached, or {@code null} if the build can't be made at this level
	 */
	public static NpcTemplate createTemplate(FakePlayerPvpBuild build, int level, int npcId, String name, PlayerClass forcedClass, String title, Looks looks)
	{
		return createTemplate(build, level, npcId, name, forcedClass, title, looks, false);
	}
	
	/**
	 * @param build the build
	 * @param level the level
	 * @param npcId a free npc id for the template
	 * @param name the character name
	 * @param forcedClass a class of the build's class line to use instead of the one its level gives ({@code null} for the level's class)
	 * @param title the character title
	 * @param looks how it looks, {@code null} for random looks
	 * @param elite {@code true} for one of the stronger players of the PvP spots (see {@link PvpSpotManager}): the best gear of its level, enchanted above the usual roll (PvpSpotEliteEnchantBonus), the most dye points, every subclass in its passive tree and a
	 *            skilled, aggressive temper; it never runs away
	 * @return a new template with its {@link FakePlayerPvpProfile} attached, or {@code null} if the build can't be made at this level
	 */
	public static NpcTemplate createTemplate(FakePlayerPvpBuild build, int level, int npcId, String name, PlayerClass forcedClass, String title, Looks looks, boolean elite)
	{
		final PlayerClass playerClass = forcedClass != null ? forcedClass : build.getPlayerClass(level);
		final PlayerTemplate classTemplate = PlayerTemplateData.getInstance().getTemplate(playerClass);
		if (classTemplate == null)
		{
			return null;
		}
		
		// Like players, not everyone wears the best gear for their level: weapon, armor and jewels each lag behind on their own.
		final FakePlayerPvpData data = FakePlayerPvpData.getInstance();
		// The strong ones wear the best of their level.
		final FakePlayerPvpGearTier weapons = data.getGear(build.getWeaponKit(), elite ? level : rollGearLevel(level), elite);
		final FakePlayerPvpGearTier armors = getArmorGear(build, playerClass, elite ? level : rollGearLevel(level), elite);
		final FakePlayerPvpGearTier jewels = data.getGear(build.getJewelKit(), elite ? level : rollGearLevel(level), elite);
		final FakePlayerPvpGearTier accessories = data.getGear(ACCESSORY_KIT, elite ? level : rollGearLevel(level), elite);
		
		final ItemTemplate weaponItem = getItem(weapons != null ? weapons.getRHand() : 0);
		final Weapon weapon = weaponItem instanceof Weapon ? (Weapon) weaponItem : null;
		final ItemTemplate shieldItem = getItem(weapons != null ? weapons.getLHand() : 0);
		final Armor shield = ((shieldItem instanceof Armor) && (shieldItem.getItemType() == ArmorType.SHIELD) && ((weapon == null) || (weapon.getBodyPart() != BodyPart.LR_HAND))) ? (Armor) shieldItem : null;
		final ItemTemplate chest = getItem(armors != null ? armors.getChest() : 0);
		final ItemTemplate legs = getItem(armors != null ? armors.getLegs() : 0);
		final ItemTemplate head = getItem(armors != null ? armors.getHead() : 0);
		final ItemTemplate gloves = getItem(armors != null ? armors.getGloves() : 0);
		final ItemTemplate feet = getItem(armors != null ? armors.getFeet() : 0);
		final ItemTemplate earring = getItem(jewels != null ? jewels.getEarring() : 0);
		final ItemTemplate necklace = getItem(jewels != null ? jewels.getNecklace() : 0);
		final ItemTemplate ring = getItem(jewels != null ? jewels.getRing() : 0);
		final boolean fullArmor = (chest != null) && (chest.getBodyPart() == BodyPart.FULL_ARMOR);
		
		// Armor set.
		final ArmorSet armorSet = chest != null ? ArmorSetData.getInstance().getSet(chest.getId()) : null;
		final boolean fullSet = (armorSet != null) && armorSet.containAll(getId(chest), getId(legs), getId(head), getId(gloves), getId(feet));
		
		// Like players, its wealth decides how much it enchanted and what else it wears: a shirt and a belt, and a cloak once its armor set opens the cloak slot.
		final FakePlayerPvpEnchant enchanter = new FakePlayerPvpEnchant(level, elite);
		final ItemTemplate shirt = (accessories != null) && enchanter.roll(FakePlayerPvpConfig.SHIRT_CHANCE) ? getItem(accessories.rollShirt()) : null;
		final ItemTemplate belt = (accessories != null) && enchanter.roll(FakePlayerPvpConfig.BELT_CHANCE) ? getItem(accessories.rollBelt()) : null;
		final ItemTemplate cloak = fullSet && opensCloakSlot(armorSet) && enchanter.roll(FakePlayerPvpConfig.CLOAK_CHANCE) ? getItem(data.getRandomCloak(level)) : null;
		
		// Every piece is enchanted on its own, higher levels further.
		final int weaponEnchant = enchanter.roll(weapon, Kind.WEAPON);
		final int shieldEnchant = enchanter.roll(shield, Kind.ARMOR);
		final int chestEnchant = enchanter.roll(chest, Kind.ARMOR, fullSet);
		final int legsEnchant = enchanter.roll(legs, Kind.ARMOR, fullSet);
		final int headEnchant = enchanter.roll(head, Kind.ARMOR, fullSet);
		final int glovesEnchant = enchanter.roll(gloves, Kind.ARMOR, fullSet);
		final int feetEnchant = enchanter.roll(feet, Kind.ARMOR, fullSet);
		final int shirtEnchant = enchanter.roll(shirt, Kind.ARMOR);
		final int beltEnchant = enchanter.roll(belt, Kind.ARMOR);
		final int leftEarringEnchant = enchanter.roll(earring, Kind.JEWEL);
		final int rightEarringEnchant = enchanter.roll(earring, Kind.JEWEL);
		final int necklaceEnchant = enchanter.roll(necklace, Kind.JEWEL);
		final int leftRingEnchant = enchanter.roll(ring, Kind.JEWEL);
		final int rightRingEnchant = enchanter.roll(ring, Kind.JEWEL);
		
		// Base stats.
		int str = classTemplate.getBaseSTR();
		int dex = classTemplate.getBaseDEX();
		int con = classTemplate.getBaseCON();
		int intel = classTemplate.getBaseINT();
		int wit = classTemplate.getBaseWIT();
		int men = classTemplate.getBaseMEN();
		if (fullSet)
		{
			str += armorSet.getSTR();
			dex += armorSet.getDEX();
			con += armorSet.getCON();
			intel += armorSet.getINT();
			wit += armorSet.getWIT();
			men += armorSet.getMEN();
		}
		
		// Extra points in the main stats (like dyes), growing with the level.
		if (FakePlayerPvpConfig.BONUS_STAT_MAX > 0)
		{
			final int maxBonus = Math.max(1, (int) Math.round((FakePlayerPvpConfig.BONUS_STAT_MAX * level) / 85.0));
			if (playerClass.isMage())
			{
				intel += elite ? maxBonus : Rnd.get(1, maxBonus);
				wit += elite ? maxBonus : Rnd.get(1, maxBonus);
				men += elite ? maxBonus : Rnd.get(1, maxBonus);
			}
			else
			{
				str += elite ? maxBonus : Rnd.get(1, maxBonus);
				dex += elite ? maxBonus : Rnd.get(1, maxBonus);
				con += elite ? maxBonus : Rnd.get(1, maxBonus);
			}
		}
		
		// P. Def.: the class default of every covered slot is replaced by the item (FuncPDefMod). A belt has no default.
		double pDef = classTemplate.getBasePDef();
		if (chest != null)
		{
			pDef -= classTemplate.getBaseDefBySlot(Inventory.PAPERDOLL_CHEST);
		}
		if ((legs != null) || fullArmor)
		{
			pDef -= classTemplate.getBaseDefBySlot(Inventory.PAPERDOLL_LEGS);
		}
		if (head != null)
		{
			pDef -= classTemplate.getBaseDefBySlot(Inventory.PAPERDOLL_HEAD);
		}
		if (gloves != null)
		{
			pDef -= classTemplate.getBaseDefBySlot(Inventory.PAPERDOLL_GLOVES);
		}
		if (feet != null)
		{
			pDef -= classTemplate.getBaseDefBySlot(Inventory.PAPERDOLL_FEET);
		}
		if (shirt != null)
		{
			pDef -= classTemplate.getBaseDefBySlot(Inventory.PAPERDOLL_UNDER);
		}
		if (cloak != null)
		{
			pDef -= classTemplate.getBaseDefBySlot(Inventory.PAPERDOLL_CLOAK);
		}
		pDef += sumStat(Stat.POWER_DEFENCE, chest, legs, head, gloves, feet, shirt, belt, cloak);
		pDef += defenceEnchantBonus(chestEnchant) + defenceEnchantBonus(legsEnchant) + defenceEnchantBonus(headEnchant) + defenceEnchantBonus(glovesEnchant) + defenceEnchantBonus(feetEnchant) + defenceEnchantBonus(shirtEnchant) + defenceEnchantBonus(beltEnchant);
		
		// M. Def.: same for jewels (FuncMDefMod).
		double mDef = classTemplate.getBaseMDef();
		if (earring != null)
		{
			mDef -= classTemplate.getBaseDefBySlot(Inventory.PAPERDOLL_REAR) + classTemplate.getBaseDefBySlot(Inventory.PAPERDOLL_LEAR);
			mDef += (2 * getStat(earring, Stat.MAGIC_DEFENCE)) + defenceEnchantBonus(leftEarringEnchant) + defenceEnchantBonus(rightEarringEnchant);
		}
		if (necklace != null)
		{
			mDef -= classTemplate.getBaseDefBySlot(Inventory.PAPERDOLL_NECK);
			mDef += getStat(necklace, Stat.MAGIC_DEFENCE) + defenceEnchantBonus(necklaceEnchant);
		}
		if (ring != null)
		{
			mDef -= classTemplate.getBaseDefBySlot(Inventory.PAPERDOLL_RFINGER) + classTemplate.getBaseDefBySlot(Inventory.PAPERDOLL_LFINGER);
			mDef += (2 * getStat(ring, Stat.MAGIC_DEFENCE)) + defenceEnchantBonus(leftRingEnchant) + defenceEnchantBonus(rightRingEnchant);
		}
		
		// Weapon: its stats replace the unarmed ones (weapon "set" functions). A polearm (Dreadnoughts) also gives its Polearm Multi-attack while it holds it.
		final FakePlayerPvpWeapon mainWeapon = createWeapon(classTemplate, weapon, shield, weaponEnchant, (weapon != null) && (weapon.getItemType() == WeaponType.POLE) ? getPassiveSkills(weapon) : Collections.emptyList());
		
		// Tanks and tyrants carry a bow for players they can't catch.
		FakePlayerPvpWeapon bow = null;
		if (FakePlayerPvpConfig.WEAPON_SWAP_ENABLED && (build.getBowKit() != null) && (level >= FakePlayerPvpConfig.WEAPON_SWAP_MIN_LEVEL))
		{
			final FakePlayerPvpGearTier bows = data.getGear(build.getBowKit(), elite ? level : rollGearLevel(level), elite);
			final ItemTemplate bowItem = getItem(bows != null ? bows.getRHand() : 0);
			if ((bowItem instanceof Weapon) && ((Weapon) bowItem).isRange())
			{
				// A spare weapon is rarely enchanted as high as the main one.
				bow = createWeapon(classTemplate, (Weapon) bowItem, null, enchanter.roll(bowItem, Kind.WEAPON) / 2);
			}
		}
		
		// Warriors carry a polearm for when monsters surround them. It hits several of them with the Polearm Multi-attack of the item, which it only has while it holds it.
		FakePlayerPvpWeapon polearm = null;
		if (FakePlayerPvpConfig.POLEARM_SWAP_ENABLED && (build.getPolearmKit() != null) && (level >= FakePlayerPvpConfig.POLEARM_SWAP_MIN_LEVEL))
		{
			final FakePlayerPvpGearTier polearms = data.getGear(build.getPolearmKit(), elite ? level : rollGearLevel(level), elite);
			final ItemTemplate polearmItem = getItem(polearms != null ? polearms.getRHand() : 0);
			if ((polearmItem instanceof Weapon) && (polearmItem.getItemType() == WeaponType.POLE))
			{
				polearm = createWeapon(classTemplate, (Weapon) polearmItem, null, enchanter.roll(polearmItem, Kind.WEAPON) / 2, getPassiveSkills(polearmItem));
			}
		}
		
		// HP/MP (a real character also has CP on top of HP).
		final double hp = classTemplate.getBaseHpMax(level) + (FakePlayerPvpConfig.INCLUDE_CP_IN_HP ? classTemplate.getBaseCpMax(level) : 0);
		final double mp = classTemplate.getBaseMpMax(level) + sumStat(Stat.MAX_MP, weapon, shield, chest, legs, head, gloves, feet, earring, earring, necklace, ring, ring, shirt, belt, cloak);
		
		int armorWornMask = 0;
		for (ItemTemplate item : new ItemTemplate[]
		{
			chest,
			legs,
			head,
			gloves,
			feet
		})
		{
			if (item != null)
			{
				armorWornMask |= item.getItemMask();
			}
		}
		
		// Its passive tree, like a player that spent its points (see FakePlayerPvpPassiveTree), grown for its gear: a bonus that needs heavy armour or a shield only counts if it wears one. Max HP % bonuses only grow the HP part of an HP pool that holds the CP too.
		final int treeWornMask = armorWornMask | (weapon != null ? weapon.getItemMask() : 0) | (shield != null ? shield.getItemMask() : 0);
		final FakePlayerPvpPassives passives = FakePlayerPvpPassiveTree.isEnabled() ? FakePlayerPvpPassiveTree.getInstance().roll(build, playerClass, level, elite, chest != null ? chest.getItemMask() : 0, treeWornMask) : null;
		if ((passives != null) && (hp > 0))
		{
			passives.setHpShare(classTemplate.getBaseHpMax(level) / hp);
		}
		
		// Looks. Kamael classes are male (Trooper, Berserker, Doombringer...) or female (Warder, Arbalester, Trickster...) from their base class on.
		final PlayerClass baseClass = build.getPlayerClass(0);
		final boolean female = (baseClass == PlayerClass.FEMALE_SOLDIER) || ((baseClass != PlayerClass.MALE_SOLDIER) && (looks != null ? looks.female() : Rnd.nextBoolean()));
		final Role role = build.getRole();
		
		final StatSet set = new StatSet(new HashMap<>());
		set.set("id", npcId);
		set.set("level", level);
		set.set("type", "Monster");
		set.set("name", name);
		set.set("title", title != null ? title : "");
		set.set("race", playerClass.getRace().name());
		set.set("sex", female ? Sex.FEMALE.name() : Sex.MALE.name());
		set.set("baseSTR", str);
		set.set("baseDEX", dex);
		set.set("baseCON", con);
		set.set("baseINT", intel);
		set.set("baseWIT", wit);
		set.set("baseMEN", men);
		set.set("baseHpMax", hp);
		set.set("baseMpMax", mp);
		set.set("baseHpReg", classTemplate.getBaseHpRegen(level) + (FakePlayerPvpConfig.INCLUDE_CP_IN_HP ? classTemplate.getBaseCpRegen(level) : 0)); // CP regenerates too.
		set.set("baseMpReg", classTemplate.getBaseMpRegen(level));
		set.set("basePAtk", mainWeapon.getPAtk());
		set.set("baseMAtk", mainWeapon.getMAtk());
		set.set("basePDef", Math.max(1, (int) Math.round(pDef)));
		set.set("baseMDef", Math.max(1, (int) Math.round(mDef)));
		// Attribute resistances of its gear (a cloak's).
		final ItemTemplate[] worn =
		{
			shield,
			chest,
			legs,
			head,
			gloves,
			feet,
			earring,
			earring,
			necklace,
			ring,
			ring,
			shirt,
			belt,
			cloak
		};
		set.set("baseFireRes", (int) sumStat(Stat.FIRE_RES, worn));
		set.set("baseWaterRes", (int) sumStat(Stat.WATER_RES, worn));
		set.set("baseWindRes", (int) sumStat(Stat.WIND_RES, worn));
		set.set("baseEarthRes", (int) sumStat(Stat.EARTH_RES, worn));
		set.set("baseHolyRes", (int) sumStat(Stat.HOLY_RES, worn));
		set.set("baseDarkRes", (int) sumStat(Stat.DARK_RES, worn));
		set.set("basePAtkSpd", mainWeapon.getPAtkSpd());
		set.set("baseMAtkSpd", classTemplate.getBaseMAtkSpd());
		set.set("baseCritRate", mainWeapon.getCritRate());
		set.set("baseMCritRate", classTemplate.getBaseMCritRate());
		set.set("baseRndDam", mainWeapon.getRandomDamage());
		set.set("baseAtkType", mainWeapon.getAttackType());
		set.set("baseAtkRange", mainWeapon.getAttackRange());
		set.set("baseShldDef", mainWeapon.getShieldDefence());
		set.set("baseShldRate", mainWeapon.getShieldRate());
		set.set("baseRunSpd", classTemplate.getBaseMoveSpeed(MoveType.RUN));
		set.set("baseWalkSpd", classTemplate.getBaseMoveSpeed(MoveType.WALK));
		set.set("baseSwimRunSpd", classTemplate.getBaseMoveSpeed(MoveType.FAST_SWIM));
		set.set("baseSwimWalkSpd", classTemplate.getBaseMoveSpeed(MoveType.SLOW_SWIM));
		set.set("collisionRadius", female ? classTemplate.getFCollisionRadiusFemale() : classTemplate.getFCollisionRadius());
		set.set("collisionHeight", female ? classTemplate.getFCollisionHeightFemale() : classTemplate.getFCollisionHeight());
		set.set("rhandId", weapon != null ? weapon.getId() : 0);
		set.set("lhandId", shield != null ? shield.getId() : 0);
		set.set("attackable", true);
		set.set("targetable", true);
		set.set("talkable", false);
		set.set("undying", false);
		set.set("randomWalk", true);
		set.set("randomAnimation", false);
		set.set("aiType", role == Role.MAGE ? AIType.MAGE.name() : role == Role.ARCHER ? AIType.ARCHER.name() : AIType.FIGHTER.name());
		set.set("aggroRange", FakePlayerPvpConfig.HUNT_RANGE);
		set.set("clanHelpRange", 0);
		set.set("isAggressive", false);
		set.set("fakePlayerPvp", true);
		set.set("corpseTime", Rnd.get(FakePlayerPvpConfig.CORPSE_TIME_MIN, FakePlayerPvpConfig.CORPSE_TIME_MAX)); // A dead player lies there a while before going to town.
		
		// Fake player appearance.
		set.set("fakePlayer", true);
		set.set("classId", playerClass.getId());
		set.set("hair", looks != null ? looks.hair() : female ? Rnd.get(7) : Rnd.get(5));
		set.set("hairColor", looks != null ? looks.hairColor() : Rnd.get(4));
		set.set("face", looks != null ? looks.face() : Rnd.get(3));
		set.set("equipRHand", weapon != null ? weapon.getId() : 0);
		set.set("equipLHand", shield != null ? shield.getId() : 0);
		set.set("equipChest", getId(chest));
		set.set("equipLegs", getId(legs));
		set.set("equipHead", getId(head));
		set.set("equipGloves", getId(gloves));
		set.set("equipFeet", getId(feet));
		set.set("equipCloak", getId(cloak));
		set.set("equipShirt", getId(shirt));
		set.set("equipBelt", getId(belt));
		set.set("weaponEnchantLevel", weaponEnchant);
		set.set("armorEnchantLevel", chestEnchant);
		set.set("slotEnchantLevels", slotEnchantLevels(shieldEnchant, headEnchant, chestEnchant, legsEnchant, glovesEnchant, feetEnchant, shirtEnchant, beltEnchant));
		set.set("recommends", Rnd.get(0, 30));
		set.set("fakePlayerTalkable", true);
		
		final NpcTemplate template = new NpcTemplate(set);
		template.setParameters(PARAMETERS);
		template.setClans(null);
		template.setIgnoreClanNpcIds(null);
		template.setAISkillLists(null);
		
		// Every skill the class has learned by this level (id -> level), like a character that learned all its skills.
		final Map<Integer, Integer> learned = getLearnedSkills(playerClass, level);
		final Map<Integer, Skill> skills = new HashMap<>();
		for (Map.Entry<Integer, Integer> entry : learned.entrySet())
		{
			final Skill skill = SkillData.getInstance().getSkill(entry.getKey(), entry.getValue());
			if ((skill != null) && skill.isPassive())
			{
				skills.put(skill.getId(), skill);
			}
		}

		for (Skill skill : mainWeapon.getSkills())
		{
			skills.put(skill.getId(), skill);
		}

		if (fullSet)
		{
			addSkills(skills, armorSet.getSkills());
			if ((shield != null) && armorSet.containShield(shield.getId()))
			{
				addSkills(skills, armorSet.getShieldSkillId());
			}
			// Every piece of the set at +6 or more (a full body armor has no legs).
			if ((chestEnchant >= 6) && ((legs == null) || (legsEnchant >= 6)) && ((head == null) || (headEnchant >= 6)) && ((gloves == null) || (glovesEnchant >= 6)) && ((feet == null) || (feetEnchant >= 6)))
			{
				addSkills(skills, armorSet.getEnchant6skillId());
			}
		}
		
		// How many energy charges the class can hold: the Sonic Focus/Focused Force level, or Sonic/Force Mastery for 3rd classes.
		final int maxCharges = Math.max(Math.max(learned.getOrDefault(8, 0), learned.getOrDefault(50, 0)), Math.max(learned.getOrDefault(992, 0), learned.getOrDefault(993, 0)));
		
		// What it wears, for the equipment drop.
		final List<ItemEnchantHolder> equipment = new ArrayList<>();
		addEquipment(equipment, weapon, weaponEnchant);
		addEquipment(equipment, shield, shieldEnchant);
		addEquipment(equipment, chest, chestEnchant);
		addEquipment(equipment, legs, legsEnchant);
		addEquipment(equipment, head, headEnchant);
		addEquipment(equipment, gloves, glovesEnchant);
		addEquipment(equipment, feet, feetEnchant);
		addEquipment(equipment, earring, leftEarringEnchant);
		addEquipment(equipment, earring, rightEarringEnchant);
		addEquipment(equipment, necklace, necklaceEnchant);
		addEquipment(equipment, ring, leftRingEnchant);
		addEquipment(equipment, ring, rightRingEnchant);
		addEquipment(equipment, shirt, shirtEnchant);
		addEquipment(equipment, belt, beltEnchant);
		addEquipment(equipment, cloak, 0);
		if (bow != null)
		{
			equipment.add(new ItemEnchantHolder(bow.getWeaponId(), 1, bow.getEnchant()));
		}
		if (polearm != null)
		{
			equipment.add(new ItemEnchantHolder(polearm.getWeaponId(), 1, polearm.getEnchant()));
		}
		
		final FakePlayerPvpProfile profile = new FakePlayerPvpProfile(build, playerClass, level, data.getBuffs(build.getBuffList(), level), armorWornMask, chest != null ? chest.getItemMask() : 0, maxCharges, equipment, mainWeapon, bow, polearm, elite ? FakePlayerPvpPersonality.elite() : FakePlayerPvpPersonality.random());
		
		profile.setPassives(passives);
		profile.setElite(elite);
		
		// The top of its HP pool is its CP, which CP potions refill (see FakePlayerPvpManager#tryCpPotion).
		profile.setCpShare(FakePlayerPvpConfig.INCLUDE_CP_IN_HP && (hp > 0) ? classTemplate.getBaseCpMax(level) / hp : 0);
		
		// High levels sometimes carry Blessed Scrolls of Escape.
		profile.setBlessedEscape((level >= FakePlayerPvpConfig.BLESSED_ESCAPE_MIN_LEVEL) && (Rnd.get(100) < FakePlayerPvpConfig.BLESSED_ESCAPE_CHANCE));
		
		for (SkillCategory category : SkillCategory.values())
		{
			final List<Skill> list = new ArrayList<>();
			for (int[] alternatives : build.getSkills(category))
			{
				for (int skillId : alternatives)
				{
					final Integer skillLevel = learned.get(skillId);
					if (skillLevel == null)
					{
						continue;
					}
					
					final Skill skill = SkillData.getInstance().getSkill(skillId, skillLevel);
					if ((skill != null) && !skill.isPassive())
					{
						list.add(skill);
						skills.put(skill.getId(), skill);
					}
					break;
				}
			}
			profile.setSkills(category, list);
		}
		
		// The combos it has the skills for at this level.
		for (FakePlayerPvpCombo combo : build.getCombos())
		{
			final FakePlayerPvpCombo.Chain chain = combo.resolve(learned);
			if (chain != null)
			{
				profile.addCombo(chain);
				for (int i = 0; i < chain.size(); i++)
				{
					final Skill skill = chain.getSkill(i);
					if (skill != null)
					{
						skills.put(skill.getId(), skill);
					}
				}
			}
		}
		
		// Bare hands (keeping its shield), for when a player's Disarm takes its weapon.
		profile.setUnarmed(createWeapon(classTemplate, null, shield, 0));
		
		template.setSkills(skills);
		template.setFakePlayerPvpProfile(profile);
		return template;
	}
	
	/**
	 * A peaceful fake player that walks around a town: it looks like a character of the build's class and level in the gear of that level, but it can't be attacked and doesn't fight (no {@link FakePlayerPvpProfile}).
	 * @param build the build (its gear kits, and its class unless {@code forcedClass} is given)
	 * @param level the level
	 * @param npcId a free npc id for the template
	 * @param name the character name
	 * @param forcedClass the class it shows, {@code null} for the one of the build at this level (town buffers wear the gear of a build of the same kind)
	 * @param sitting {@code true} if it sits when it appears
	 * @return a new template, or {@code null} if the build can't be made at this level
	 */
	public static NpcTemplate createTownTemplate(FakePlayerPvpBuild build, int level, int npcId, String name, PlayerClass forcedClass, boolean sitting)
	{
		final PlayerClass playerClass = forcedClass != null ? forcedClass : build.getPlayerClass(level);
		final PlayerTemplate classTemplate = PlayerTemplateData.getInstance().getTemplate(playerClass);
		if (classTemplate == null)
		{
			return null;
		}

		final FakePlayerPvpData data = FakePlayerPvpData.getInstance();
		final FakePlayerPvpGearTier weapons = data.getGear(build.getWeaponKit(), rollGearLevel(level));
		final FakePlayerPvpGearTier armors = getArmorGear(build, playerClass, rollGearLevel(level), false);
		final FakePlayerPvpGearTier accessories = data.getGear(ACCESSORY_KIT, rollGearLevel(level));
		final ItemTemplate weaponItem = getItem(weapons != null ? weapons.getRHand() : 0);
		final Weapon weapon = weaponItem instanceof Weapon ? (Weapon) weaponItem : null;
		final ItemTemplate shieldItem = getItem(weapons != null ? weapons.getLHand() : 0);
		final Armor shield = ((shieldItem instanceof Armor) && (shieldItem.getItemType() == ArmorType.SHIELD) && ((weapon == null) || (weapon.getBodyPart() != BodyPart.LR_HAND))) ? (Armor) shieldItem : null;
		final ItemTemplate chest = getItem(armors != null ? armors.getChest() : 0);
		final ItemTemplate legs = getItem(armors != null ? armors.getLegs() : 0);
		final ItemTemplate head = getItem(armors != null ? armors.getHead() : 0);
		final ItemTemplate gloves = getItem(armors != null ? armors.getGloves() : 0);
		final ItemTemplate feet = getItem(armors != null ? armors.getFeet() : 0);
		final ArmorSet armorSet = chest != null ? ArmorSetData.getInstance().getSet(chest.getId()) : null;
		final boolean fullSet = (armorSet != null) && armorSet.containAll(getId(chest), getId(legs), getId(head), getId(gloves), getId(feet));
		
		// Dressed and enchanted like a roaming fake player of its level (see createTemplate).
		final FakePlayerPvpEnchant enchanter = new FakePlayerPvpEnchant(level, false);
		final ItemTemplate shirt = (accessories != null) && enchanter.roll(FakePlayerPvpConfig.SHIRT_CHANCE) ? getItem(accessories.rollShirt()) : null;
		final ItemTemplate belt = (accessories != null) && enchanter.roll(FakePlayerPvpConfig.BELT_CHANCE) ? getItem(accessories.rollBelt()) : null;
		final ItemTemplate cloak = fullSet && opensCloakSlot(armorSet) && enchanter.roll(FakePlayerPvpConfig.CLOAK_CHANCE) ? getItem(data.getRandomCloak(level)) : null;
		final int chestEnchant = enchanter.roll(chest, Kind.ARMOR, fullSet);

		PlayerClass baseClass = playerClass;
		while (baseClass.getParent() != null)
		{
			baseClass = baseClass.getParent();
		}
		final boolean female = (baseClass == PlayerClass.FEMALE_SOLDIER) || ((baseClass != PlayerClass.MALE_SOLDIER) && Rnd.nextBoolean());

		final StatSet set = new StatSet(new HashMap<>());
		set.set("id", npcId);
		set.set("level", level);
		set.set("type", "Folk");
		set.set("name", name);
		set.set("title", "");
		set.set("race", playerClass.getRace().name());
		set.set("sex", female ? Sex.FEMALE.name() : Sex.MALE.name());
		set.set("baseSTR", classTemplate.getBaseSTR());
		set.set("baseDEX", classTemplate.getBaseDEX());
		set.set("baseCON", classTemplate.getBaseCON());
		set.set("baseINT", classTemplate.getBaseINT());
		set.set("baseWIT", classTemplate.getBaseWIT());
		set.set("baseMEN", classTemplate.getBaseMEN());
		set.set("baseHpMax", classTemplate.getBaseHpMax(level) + classTemplate.getBaseCpMax(level));
		set.set("baseMpMax", classTemplate.getBaseMpMax(level));
		set.set("basePDef", classTemplate.getBasePDef());
		set.set("baseMDef", classTemplate.getBaseMDef());
		set.set("baseRunSpd", classTemplate.getBaseMoveSpeed(MoveType.RUN));
		set.set("baseWalkSpd", classTemplate.getBaseMoveSpeed(MoveType.WALK));
		set.set("baseSwimRunSpd", classTemplate.getBaseMoveSpeed(MoveType.FAST_SWIM));
		set.set("baseSwimWalkSpd", classTemplate.getBaseMoveSpeed(MoveType.SLOW_SWIM));
		set.set("collisionRadius", female ? classTemplate.getFCollisionRadiusFemale() : classTemplate.getFCollisionRadius());
		set.set("collisionHeight", female ? classTemplate.getFCollisionHeightFemale() : classTemplate.getFCollisionHeight());
		set.set("rhandId", weapon != null ? weapon.getId() : 0);
		set.set("lhandId", shield != null ? shield.getId() : 0);
		set.set("attackable", false);
		set.set("targetable", true);
		set.set("talkable", false);
		set.set("undying", true);
		set.set("randomWalk", false); // FakePlayerTownManager moves it.
		set.set("randomAnimation", false);
		set.set("isAggressive", false);
		set.set("fakePlayerPvp", true); // Player stats, no npc stat multipliers.

		// Fake player appearance.
		set.set("fakePlayer", true);
		set.set("classId", playerClass.getId());
		set.set("hair", female ? Rnd.get(7) : Rnd.get(5));
		set.set("hairColor", Rnd.get(4));
		set.set("face", Rnd.get(3));
		set.set("equipRHand", weapon != null ? weapon.getId() : 0);
		set.set("equipLHand", shield != null ? shield.getId() : 0);
		set.set("equipChest", getId(chest));
		set.set("equipLegs", getId(legs));
		set.set("equipHead", getId(head));
		set.set("equipGloves", getId(gloves));
		set.set("equipFeet", getId(feet));
		set.set("equipCloak", getId(cloak));
		set.set("equipShirt", getId(shirt));
		set.set("equipBelt", getId(belt));
		set.set("weaponEnchantLevel", enchanter.roll(weapon, Kind.WEAPON));
		set.set("armorEnchantLevel", chestEnchant);
		set.set("slotEnchantLevels", slotEnchantLevels(enchanter.roll(shield, Kind.ARMOR), enchanter.roll(head, Kind.ARMOR, fullSet), chestEnchant, enchanter.roll(legs, Kind.ARMOR, fullSet), enchanter.roll(gloves, Kind.ARMOR, fullSet), enchanter.roll(feet, Kind.ARMOR, fullSet), enchanter.roll(shirt, Kind.ARMOR), enchanter.roll(belt, Kind.ARMOR)));
		set.set("recommends", Rnd.get(0, 30));
		set.set("fakePlayerTalkable", true);
		set.set("sitting", sitting);

		// Some wear a hat, a mask or ears from an event, like players in town.
		if (Rnd.get(100) < TOWN_HAIR_ACCESSORY_CHANCE)
		{
			set.set("equipHair", getId(getItem(TOWN_HAIR_ACCESSORIES[Rnd.get(TOWN_HAIR_ACCESSORIES.length)])));
		}

		final NpcTemplate template = new NpcTemplate(set);
		template.setParameters(PARAMETERS);
		template.setClans(null);
		template.setIgnoreClanNpcIds(null);
		template.setAISkillLists(null);
		template.setSkills(Collections.emptyMap());
		return template;
	}

	/**
	 * Kamael wear light armor only, like {@link org.l2jmobius.gameserver.network.clientpackets.UseItem} enforces for players: the client has no Kamael model of heavy armor and robes and shows them untextured. A Kamael whose armor kit holds such a piece wears
	 * the light armor kit of its role instead.
	 * @param build the build
	 * @param playerClass the class it shows
	 * @param gearLevel the level its armor is picked for
	 * @param best {@code true} for the best armor of that level, {@code false} for one of them picked by weight
	 * @return the armor it wears, {@code null} if none
	 */
	private static FakePlayerPvpGearTier getArmorGear(FakePlayerPvpBuild build, PlayerClass playerClass, int gearLevel, boolean best)
	{
		final FakePlayerPvpData data = FakePlayerPvpData.getInstance();
		final FakePlayerPvpGearTier armors = data.getGear(build.getArmorKit(), gearLevel, best);
		if ((armors == null) || (playerClass.getRace() != Race.KAMAEL))
		{
			return armors;
		}
		
		for (int itemId : new int[]
		{
			armors.getChest(),
			armors.getLegs()
		})
		{
			final ItemTemplate item = getItem(itemId);
			if ((item != null) && ((item.getItemType() == ArmorType.HEAVY) || (item.getItemType() == ArmorType.MAGIC)))
			{
				final FakePlayerPvpGearTier light = data.getGear(build.getRole() == Role.MAGE ? KAMAEL_MAGE_ARMOR_KIT : KAMAEL_FIGHTER_ARMOR_KIT, gearLevel, best);
				return light != null ? light : armors;
			}
		}
		return armors;
	}
	
	/**
	 * @param armorSet an armor set
	 * @return {@code true} if wearing all of it opens the cloak slot: one of its skills has the {@value #CLOAK_SLOT_EFFECT} effect (Dynasty, Moirai, Vesper Noble..., not the plain Vesper sets)
	 */
	private static boolean opensCloakSlot(ArmorSet armorSet)
	{
		for (SkillHolder holder : armorSet.getSkills())
		{
			final Skill skill = holder.getSkill();
			if (skill == null)
			{
				continue;
			}
			
			// A set skill is passive: its effects are in the passive scope.
			for (EffectScope scope : EffectScope.values())
			{
				final List<AbstractEffect> effects = skill.getEffects(scope);
				if (effects != null)
				{
					for (AbstractEffect effect : effects)
					{
						if (CLOAK_SLOT_EFFECT.equals(effect.getClass().getSimpleName()))
						{
							return true;
						}
					}
				}
			}
		}
		
		return false;
	}
	
	/**
	 * @return the enchant of the shield, armor pieces, shirt and belt by paperdoll slot, for its {@link org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerHolder}
	 */
	private static Map<Integer, Integer> slotEnchantLevels(int shield, int head, int chest, int legs, int gloves, int feet, int shirt, int belt)
	{
		final Map<Integer, Integer> result = new HashMap<>();
		result.put(Inventory.PAPERDOLL_LHAND, shield);
		result.put(Inventory.PAPERDOLL_HEAD, head);
		result.put(Inventory.PAPERDOLL_CHEST, chest);
		result.put(Inventory.PAPERDOLL_LEGS, legs);
		result.put(Inventory.PAPERDOLL_GLOVES, gloves);
		result.put(Inventory.PAPERDOLL_FEET, feet);
		result.put(Inventory.PAPERDOLL_UNDER, shirt);
		result.put(Inventory.PAPERDOLL_BELT, belt);
		return result;
	}
	
	private static void addEquipment(List<ItemEnchantHolder> equipment, ItemTemplate item, int enchant)
	{
		if (item != null)
		{
			equipment.add(new ItemEnchantHolder(item.getId(), 1, enchant));
		}
	}
	
	/**
	 * @param level the character level
	 * @return the level a piece of its gear is picked for: up to {@link FakePlayerPvpConfig#GEAR_LEVEL_DROP_ABOVE_80} levels lower above level 80, up to {@link FakePlayerPvpConfig#GEAR_LEVEL_DROP_ABOVE_51} levels lower above level 51 (a player still in last grade's gear), its level
	 *         below
	 */
	private static int rollGearLevel(int level)
	{
		final int maxDrop = level > 80 ? FakePlayerPvpConfig.GEAR_LEVEL_DROP_ABOVE_80 : level > 51 ? FakePlayerPvpConfig.GEAR_LEVEL_DROP_ABOVE_51 : 0;
		return maxDrop > 0 ? Math.max(1, level - Rnd.get(0, maxDrop)) : level;
	}
	
	/**
	 * @param playerClass the class
	 * @param level the character level
	 * @return the highest level of every skill of the class skill tree (parents included) that a character of {@code level} can learn
	 */
	private static Map<Integer, Integer> getLearnedSkills(PlayerClass playerClass, int level)
	{
		final Map<Integer, Integer> result = new HashMap<>();
		for (SkillLearn skillLearn : SkillTreeData.getInstance().getCompleteClassSkillTree(playerClass).values())
		{
			if ((skillLearn.getGetLevel() > level) || (skillLearn.getSkillLevel() >= 100))
			{
				continue;
			}
			
			result.merge(skillLearn.getSkillId(), skillLearn.getSkillLevel(), Math::max);
		}
		
		return result;
	}
	
	/**
	 * @param classTemplate the class
	 * @param weapon the weapon, {@code null} for bare hands
	 * @param shield the shield, {@code null} for none
	 * @param enchant the weapon enchant level
	 * @return the stats holding {@code weapon} gives, like its "set" functions for a real character, with the enchant bonus
	 */
	private static FakePlayerPvpWeapon createWeapon(PlayerTemplate classTemplate, Weapon weapon, Armor shield, int enchant)
	{
		return createWeapon(classTemplate, weapon, shield, enchant, Collections.emptyList());
	}
	
	/**
	 * @param classTemplate the class
	 * @param weapon the weapon, {@code null} for bare hands
	 * @param shield the shield, {@code null} for none
	 * @param enchant the weapon enchant level
	 * @param skills the passive skills it gives while held
	 * @return the stats holding {@code weapon} gives, like its "set" functions for a real character, with the enchant bonus
	 */
	private static FakePlayerPvpWeapon createWeapon(PlayerTemplate classTemplate, Weapon weapon, Armor shield, int enchant, List<Skill> skills)
	{
		final double pAtk = weapon != null ? getStat(weapon, Stat.POWER_ATTACK) + weaponPAtkBonus(weapon, enchant) : classTemplate.getBasePAtk();
		final double mAtk = weapon != null ? getStat(weapon, Stat.MAGIC_ATTACK) + weaponMAtkBonus(weapon, enchant) : classTemplate.getBaseMAtk();
		final double pAtkSpd = weapon != null ? getStat(weapon, Stat.POWER_ATTACK_SPEED) : classTemplate.getBasePAtkSpd();
		final double critRate = weapon != null ? getStat(weapon, Stat.CRITICAL_RATE) : classTemplate.getBaseCritRate();
		final double atkRange = weapon != null ? getStat(weapon, Stat.POWER_ATTACK_RANGE) : classTemplate.getBaseAttackRange();
		final double randomDamage = weapon != null ? getStat(weapon, Stat.RANDOM_DAMAGE) : classTemplate.getRandomDamage();
		final WeaponType attackType = weapon != null ? weapon.getItemType() : WeaponType.FIST;
		final int shieldDefence = shield != null ? (int) getStat(shield, Stat.SHIELD_DEFENCE) : 0;
		final int shieldRate = shield != null ? (int) getStat(shield, Stat.SHIELD_RATE) : 0;
		return new FakePlayerPvpWeapon(weapon, shield, weapon != null ? enchant : 0, Math.max(1, (int) Math.round(pAtk)), Math.max(1, (int) Math.round(mAtk)), (int) Math.round(pAtkSpd), (int) Math.round(critRate), (int) Math.round(atkRange), (int) Math.round(randomDamage), attackType, shieldDefence, shieldRate, skills);
	}
	
	/**
	 * @param item an item
	 * @return the passive skills of {@code item} (a polearm's Polearm Multi-attack)
	 */
	private static List<Skill> getPassiveSkills(ItemTemplate item)
	{
		final List<Skill> skills = new ArrayList<>();
		final SkillHolder[] holders = item.getSkills();
		if (holders != null)
		{
			for (SkillHolder holder : holders)
			{
				final Skill skill = holder.getSkill();
				if ((skill != null) && skill.isPassive())
				{
					skills.add(skill);
				}
			}
		}
		
		return skills;
	}
	
	private static void addSkills(Map<Integer, Skill> skills, List<SkillHolder> holders)
	{
		for (SkillHolder holder : holders)
		{
			final Skill skill = holder.getSkill();
			if (skill != null)
			{
				skills.put(skill.getId(), skill);
			}
		}
	}
	
	private static ItemTemplate getItem(int itemId)
	{
		return itemId > 0 ? ItemData.getInstance().getTemplate(itemId) : null;
	}
	
	private static int getId(ItemTemplate item)
	{
		return item != null ? item.getId() : 0;
	}
	
	/**
	 * @param item the item
	 * @param stat the stat
	 * @return the value of {@code stat} in the item {@code <stats>}, 0 if it has none
	 */
	private static double getStat(ItemTemplate item, Stat stat)
	{
		double value = 0;
		for (FuncTemplate func : item.getFuncTemplates())
		{
			if (func.getStat() == stat)
			{
				value += func.getValue();
			}
		}
		
		return value;
	}
	
	private static double sumStat(Stat stat, ItemTemplate... items)
	{
		double value = 0;
		for (ItemTemplate item : items)
		{
			if (item != null)
			{
				value += getStat(item, stat);
			}
		}
		
		return value;
	}
	
	/**
	 * @param enchant the enchant level of an armor piece or jewel
	 * @return the P. Def./M. Def. it adds (see {@code FuncEnchant})
	 */
	private static int defenceEnchantBonus(int enchant)
	{
		return Math.min(enchant, 3) + (3 * Math.max(0, enchant - 3));
	}
	
	/**
	 * @param weapon the weapon
	 * @param enchantLevel its enchant level
	 * @return the M. Atk. it adds (see {@code FuncEnchant})
	 */
	private static int weaponMAtkBonus(Weapon weapon, int enchantLevel)
	{
		final int enchant = Math.min(enchantLevel, 3);
		final int overEnchant = Math.max(0, enchantLevel - 3);
		switch (weapon.getCrystalTypePlus())
		{
			case S:
			{
				return (4 * enchant) + (8 * overEnchant);
			}
			case A:
			case B:
			case C:
			{
				return (3 * enchant) + (6 * overEnchant);
			}
			default:
			{
				return (2 * enchant) + (4 * overEnchant);
			}
		}
	}
	
	/**
	 * @param weapon the weapon
	 * @param enchantLevel its enchant level
	 * @return the P. Atk. it adds (see {@code FuncEnchant})
	 */
	private static int weaponPAtkBonus(Weapon weapon, int enchantLevel)
	{
		final int enchant = Math.min(enchantLevel, 3);
		final int overEnchant = Math.max(0, enchantLevel - 3);
		final WeaponType type = weapon.getItemType();
		final boolean twoHanded = weapon.getBodyPart() == BodyPart.LR_HAND;
		final int perLevel;
		switch (weapon.getCrystalTypePlus())
		{
			case S:
			{
				perLevel = type == WeaponType.BOW ? 10 : type == WeaponType.CROSSBOW ? 7 : twoHanded ? 6 : 5;
				break;
			}
			case A:
			{
				perLevel = type == WeaponType.BOW ? 8 : type == WeaponType.CROSSBOW ? 6 : twoHanded ? 5 : 4;
				break;
			}
			case B:
			case C:
			{
				perLevel = type == WeaponType.BOW ? 6 : type == WeaponType.CROSSBOW ? 5 : twoHanded ? 4 : 3;
				break;
			}
			default:
			{
				perLevel = type == WeaponType.BOW ? 4 : type == WeaponType.CROSSBOW ? 3 : 2;
				break;
			}
		}
		
		return (perLevel * enchant) + (2 * perLevel * overEnchant);
	}
}
