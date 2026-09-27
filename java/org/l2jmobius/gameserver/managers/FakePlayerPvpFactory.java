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
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.actor.enums.npc.AIType;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.enums.player.Sex;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild.Role;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild.SkillCategory;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpCombo;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpGearTier;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpWeapon;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.actor.templates.PlayerTemplate;
import org.l2jmobius.gameserver.model.item.Armor;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.Weapon;
import org.l2jmobius.gameserver.model.item.enums.BodyPart;
import org.l2jmobius.gameserver.model.item.holders.ItemEnchantHolder;
import org.l2jmobius.gameserver.model.item.type.ArmorType;
import org.l2jmobius.gameserver.model.item.type.WeaponType;
import org.l2jmobius.gameserver.model.itemcontainer.Inventory;
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
 * {@code FuncPDefMod}/{@code FuncMDefMod}), with enchant bonuses like {@code FuncEnchant};</li>
 * <li>all class passive skills learned up to the level, armor set skills and the build's active skills at the level of the class skill tree.</li>
 * </ul>
 * NPCs already get the same STR/CON/DEX/INT/WIT/MEN and level multipliers as players ({@code Formulas#getStdNPCCalculators()}), so nothing else needs to be special cased.
 */
public class FakePlayerPvpFactory
{
	/** Parameters that keep the AttackableAI archetype roll predictable (it only scales hate for these npcs). */
	private static final StatSet PARAMETERS = new StatSet(Map.of("AIArchetype", "BALANCED"));
	
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
		final PlayerClass playerClass = build.getPlayerClass(level);
		final PlayerTemplate classTemplate = PlayerTemplateData.getInstance().getTemplate(playerClass);
		if (classTemplate == null)
		{
			return null;
		}
		
		final FakePlayerPvpData data = FakePlayerPvpData.getInstance();
		final FakePlayerPvpGearTier weapons = data.getGear(build.getWeaponKit(), level);
		final FakePlayerPvpGearTier armors = data.getGear(build.getArmorKit(), level);
		final FakePlayerPvpGearTier jewels = data.getGear(build.getJewelKit(), level);
		
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
		
		// Higher levels have better enchanted gear.
		final int weaponEnchant = weapon != null ? FakePlayerPvpConfig.rollEnchant(FakePlayerPvpConfig.WEAPON_ENCHANT, level) : 0;
		final int armorEnchant = FakePlayerPvpConfig.rollEnchant(FakePlayerPvpConfig.ARMOR_ENCHANT, level);
		
		// Armor set.
		final ArmorSet armorSet = chest != null ? ArmorSetData.getInstance().getSet(chest.getId()) : null;
		final boolean fullSet = (armorSet != null) && armorSet.containAll(getId(chest), getId(legs), getId(head), getId(gloves), getId(feet));
		
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
		
		// Extra points in the main stats (like dyes or passive tree points), growing with the level.
		if (FakePlayerPvpConfig.BONUS_STAT_MAX > 0)
		{
			final int maxBonus = Math.max(1, (int) Math.round((FakePlayerPvpConfig.BONUS_STAT_MAX * level) / 85.0));
			if (playerClass.isMage())
			{
				intel += Rnd.get(1, maxBonus);
				wit += Rnd.get(1, maxBonus);
				men += Rnd.get(1, maxBonus);
			}
			else
			{
				str += Rnd.get(1, maxBonus);
				dex += Rnd.get(1, maxBonus);
				con += Rnd.get(1, maxBonus);
			}
		}
		
		// P. Def.: the class default of every covered slot is replaced by the item (FuncPDefMod).
		double pDef = classTemplate.getBasePDef();
		int armorPieces = 0;
		if (chest != null)
		{
			pDef -= classTemplate.getBaseDefBySlot(Inventory.PAPERDOLL_CHEST);
			armorPieces++;
		}
		if ((legs != null) || fullArmor)
		{
			pDef -= classTemplate.getBaseDefBySlot(Inventory.PAPERDOLL_LEGS);
			armorPieces += legs != null ? 1 : 0;
		}
		if (head != null)
		{
			pDef -= classTemplate.getBaseDefBySlot(Inventory.PAPERDOLL_HEAD);
			armorPieces++;
		}
		if (gloves != null)
		{
			pDef -= classTemplate.getBaseDefBySlot(Inventory.PAPERDOLL_GLOVES);
			armorPieces++;
		}
		if (feet != null)
		{
			pDef -= classTemplate.getBaseDefBySlot(Inventory.PAPERDOLL_FEET);
			armorPieces++;
		}
		pDef += sumStat(Stat.POWER_DEFENCE, chest, legs, head, gloves, feet) + (defenceEnchantBonus(armorEnchant) * armorPieces);
		
		// M. Def.: same for jewels (FuncMDefMod).
		double mDef = classTemplate.getBaseMDef();
		int jewelPieces = 0;
		if (earring != null)
		{
			mDef -= classTemplate.getBaseDefBySlot(Inventory.PAPERDOLL_REAR) + classTemplate.getBaseDefBySlot(Inventory.PAPERDOLL_LEAR);
			mDef += 2 * getStat(earring, Stat.MAGIC_DEFENCE);
			jewelPieces += 2;
		}
		if (necklace != null)
		{
			mDef -= classTemplate.getBaseDefBySlot(Inventory.PAPERDOLL_NECK);
			mDef += getStat(necklace, Stat.MAGIC_DEFENCE);
			jewelPieces++;
		}
		if (ring != null)
		{
			mDef -= classTemplate.getBaseDefBySlot(Inventory.PAPERDOLL_RFINGER) + classTemplate.getBaseDefBySlot(Inventory.PAPERDOLL_LFINGER);
			mDef += 2 * getStat(ring, Stat.MAGIC_DEFENCE);
			jewelPieces += 2;
		}
		mDef += defenceEnchantBonus(armorEnchant) * jewelPieces;
		
		// Weapon: its stats replace the unarmed ones (weapon "set" functions).
		final FakePlayerPvpWeapon mainWeapon = createWeapon(classTemplate, weapon, shield, weaponEnchant);
		
		// Tanks and tyrants carry a bow for players they can't catch.
		FakePlayerPvpWeapon bow = null;
		if (FakePlayerPvpConfig.WEAPON_SWAP_ENABLED && (build.getBowKit() != null) && (level >= FakePlayerPvpConfig.WEAPON_SWAP_MIN_LEVEL))
		{
			final FakePlayerPvpGearTier bows = data.getGear(build.getBowKit(), level);
			final ItemTemplate bowItem = getItem(bows != null ? bows.getRHand() : 0);
			if ((bowItem instanceof Weapon) && ((Weapon) bowItem).isRange())
			{
				// A spare weapon is rarely enchanted as high as the main one.
				bow = createWeapon(classTemplate, (Weapon) bowItem, null, FakePlayerPvpConfig.rollEnchant(FakePlayerPvpConfig.WEAPON_ENCHANT, level) / 2);
			}
		}
		
		// HP/MP (a real character also has CP on top of HP).
		final double hp = classTemplate.getBaseHpMax(level) + (FakePlayerPvpConfig.INCLUDE_CP_IN_HP ? classTemplate.getBaseCpMax(level) : 0);
		final double mp = classTemplate.getBaseMpMax(level) + sumStat(Stat.MAX_MP, weapon, shield, chest, legs, head, gloves, feet, earring, earring, necklace, ring, ring);
		
		// Looks.
		final boolean female = Rnd.nextBoolean();
		final Role role = build.getRole();
		
		final StatSet set = new StatSet(new HashMap<>());
		set.set("id", npcId);
		set.set("level", level);
		set.set("type", "Monster");
		set.set("name", name);
		set.set("title", "");
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
		set.set("weaponEnchantLevel", weaponEnchant);
		set.set("armorEnchantLevel", armorEnchant);
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
		
		if (fullSet)
		{
			addSkills(skills, armorSet.getSkills());
			if ((shield != null) && armorSet.containShield(shield.getId()))
			{
				addSkills(skills, armorSet.getShieldSkillId());
			}
			if (armorEnchant >= 6)
			{
				addSkills(skills, armorSet.getEnchant6skillId());
			}
		}
		
		// How many energy charges the class can hold: the Sonic Focus/Focused Force level, or Sonic/Force Mastery for 3rd classes.
		final int maxCharges = Math.max(Math.max(learned.getOrDefault(8, 0), learned.getOrDefault(50, 0)), Math.max(learned.getOrDefault(992, 0), learned.getOrDefault(993, 0)));
		
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
		
		// What it wears, for the equipment drop.
		final List<ItemEnchantHolder> equipment = new ArrayList<>();
		if (weapon != null)
		{
			equipment.add(new ItemEnchantHolder(weapon.getId(), 1, weaponEnchant));
		}
		for (ItemTemplate item : new ItemTemplate[]
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
			ring
		})
		{
			if (item != null)
			{
				equipment.add(new ItemEnchantHolder(item.getId(), 1, armorEnchant));
			}
		}
		if (bow != null)
		{
			equipment.add(new ItemEnchantHolder(bow.getWeaponId(), 1, bow.getEnchant()));
		}
		
		final FakePlayerPvpProfile profile = new FakePlayerPvpProfile(build, playerClass, level, data.getBuffs(build.getBuffList(), level), armorWornMask, chest != null ? chest.getItemMask() : 0, maxCharges, equipment, mainWeapon, bow);
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
		
		template.setSkills(skills);
		template.setFakePlayerPvpProfile(profile);
		return template;
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
		final double pAtk = weapon != null ? getStat(weapon, Stat.POWER_ATTACK) + weaponPAtkBonus(weapon, enchant) : classTemplate.getBasePAtk();
		final double mAtk = weapon != null ? getStat(weapon, Stat.MAGIC_ATTACK) + weaponMAtkBonus(weapon, enchant) : classTemplate.getBaseMAtk();
		final double pAtkSpd = weapon != null ? getStat(weapon, Stat.POWER_ATTACK_SPEED) : classTemplate.getBasePAtkSpd();
		final double critRate = weapon != null ? getStat(weapon, Stat.CRITICAL_RATE) : classTemplate.getBaseCritRate();
		final double atkRange = weapon != null ? getStat(weapon, Stat.POWER_ATTACK_RANGE) : classTemplate.getBaseAttackRange();
		final double randomDamage = weapon != null ? getStat(weapon, Stat.RANDOM_DAMAGE) : classTemplate.getRandomDamage();
		final WeaponType attackType = weapon != null ? weapon.getItemType() : WeaponType.FIST;
		final int shieldDefence = shield != null ? (int) getStat(shield, Stat.SHIELD_DEFENCE) : 0;
		final int shieldRate = shield != null ? (int) getStat(shield, Stat.SHIELD_RATE) : 0;
		return new FakePlayerPvpWeapon(weapon, shield, weapon != null ? enchant : 0, Math.max(1, (int) Math.round(pAtk)), Math.max(1, (int) Math.round(mAtk)), (int) Math.round(pAtkSpd), (int) Math.round(critRate), (int) Math.round(atkRange), (int) Math.round(randomDamage), attackType, shieldDefence, shieldRate);
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
