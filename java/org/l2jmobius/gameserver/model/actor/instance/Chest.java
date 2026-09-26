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
package org.l2jmobius.gameserver.model.actor.instance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.TreasureChestConfig;
import org.l2jmobius.gameserver.config.custom.TreasureChestConfig.ChestMaterial;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.creature.InstanceType;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.effects.AbstractEffect;
import org.l2jmobius.gameserver.model.item.holders.ItemHolder;
import org.l2jmobius.gameserver.model.skill.BuffInfo;
import org.l2jmobius.gameserver.model.skill.EffectScope;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.stats.Stat;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillUse;

/**
 * This class manages all chest.<br>
 * With {@link TreasureChestConfig#ENABLED}, the world treasure chests work like retail: every treasure chest spawn point holds a real chest ({@link #FIRST_REAL_CHEST_ID}-{@link #LAST_REAL_CHEST_ID}) and a mimic (the real chest id + {@link #MIMIC_ID_OFFSET}). Both are shown with the mimic's model so
 * they can't be told apart. A real chest vanishes when it is hit and gives crafting materials when it is opened with a key; a mimic attacks whoever tries to open it (see handlers.skill.effects.OpenChest), curses whoever hits it with random debuffs, and chases its target up to a leash range.
 * @author Julian
 */
public class Chest extends Monster
{
	/** First and last NPC id of the real treasure chests. */
	public static final int FIRST_REAL_CHEST_ID = 18265;
	public static final int LAST_REAL_CHEST_ID = 18286;
	/** A real treasure chest's mimic has the id of the real chest + this offset (18265 -> 21801 ... 18286 -> 21822). Levels and names match. */
	public static final int MIMIC_ID_OFFSET = 3536;
	
	private volatile boolean _specialDrop;
	private volatile boolean _vanished;
	/** Object ids of the players this mimic has already cursed since it spawned. */
	private final Set<Integer> _cursedPlayers = ConcurrentHashMap.newKeySet();
	
	/**
	 * Creates a chest.
	 * @param template the chest NPC template
	 */
	public Chest(NpcTemplate template)
	{
		super(template);
		setInstanceType(InstanceType.Chest);
		setRandomWalking(false);
		_specialDrop = false;
	}
	
	@Override
	public void onSpawn()
	{
		super.onSpawn();
		// Npc#onSpawn turns random walking back on from the template. Chests never wander, so an idle mimic can't give itself away.
		setRandomWalking(false);
		_specialDrop = false;
		_vanished = false;
		_cursedPlayers.clear();
		setMustRewardExpSp(true);
	}
	
	public synchronized void setSpecialDrop()
	{
		_specialDrop = true;
	}
	
	/**
	 * @return {@code true} if this is a real world treasure chest (the one that can be opened with a key for a reward)
	 */
	public boolean isRealTreasureChest()
	{
		final int id = getId();
		return (id >= FIRST_REAL_CHEST_ID) && (id <= LAST_REAL_CHEST_ID);
	}
	
	/**
	 * @return {@code true} if this is a mimic, the treasure chest monster that fights back
	 */
	public boolean isMimic()
	{
		final int id = getId();
		return (id >= (FIRST_REAL_CHEST_ID + MIMIC_ID_OFFSET)) && (id <= (LAST_REAL_CHEST_ID + MIMIC_ID_OFFSET));
	}
	
	/**
	 * Real treasure chests borrow the model of their mimic, so the client shows both the same way.
	 */
	@Override
	public int getDisplayId()
	{
		if (TreasureChestConfig.ENABLED && isRealTreasureChest())
		{
			final NpcTemplate mimic = NpcData.getInstance().getTemplate(getId() + MIMIC_ID_OFFSET);
			if (mimic != null)
			{
				return mimic.getDisplayId();
			}
		}
		return super.getDisplayId();
	}
	
	@Override
	public void reduceCurrentHp(double amount, Creature attacker, boolean awake, boolean isDOT, Skill skill)
	{
		// A real treasure chest can only be opened with a key. Hitting it makes it vanish.
		if (TreasureChestConfig.ENABLED && isRealTreasureChest() && !_specialDrop)
		{
			vanish(attacker);
			return;
		}
		
		// A mimic curses whoever hits it.
		if (TreasureChestConfig.ENABLED && isMimic() && !isDOT && (attacker != null) && !isDead())
		{
			curse(attacker.asPlayer());
		}
		
		super.reduceCurrentHp(amount, attacker, awake, isDOT, skill);
	}
	
	/**
	 * Puts {@link TreasureChestConfig#MIMIC_DEBUFF_COUNT} different random debuffs from {@link TreasureChestConfig#MIMIC_DEBUFF_SKILLS} on the player, at a skill level that follows this mimic's level. Each player is cursed only once per spawn. Cancel and dispel skills are never used.
	 * @param player the player to curse
	 */
	public void curse(Player player)
	{
		if (!TreasureChestConfig.MIMIC_DEBUFF_ENABLED || (TreasureChestConfig.MIMIC_DEBUFF_COUNT <= 0) || !isMimic() || (player == null) || player.isDead() || !_cursedPlayers.add(player.getObjectId()))
		{
			return;
		}
		
		final List<Integer> skillIds = new ArrayList<>(TreasureChestConfig.MIMIC_DEBUFF_SKILLS);
		Collections.shuffle(skillIds);
		int applied = 0;
		Skill shown = null;
		for (int skillId : skillIds)
		{
			if (applied >= TreasureChestConfig.MIMIC_DEBUFF_COUNT)
			{
				break;
			}
			
			final Skill debuff = getCurseSkill(skillId);
			if (debuff == null)
			{
				continue;
			}
			
			if (TreasureChestConfig.MIMIC_DEBUFF_IGNORE_RESIST)
			{
				if (player.isInvul() || player.isInvulAgainst(debuff.getId(), debuff.getLevel()) || (player.calcStat(Stat.DEBUFF_IMMUNITY, 0, this, debuff) > 0))
				{
					continue;
				}
				
				// Only the lasting effects, so no instant damage (e.g. from the stun skill) and no land rate roll.
				final BuffInfo info = new BuffInfo(this, player, debuff);
				debuff.applyEffectScope(EffectScope.GENERAL, info, false, true);
				player.getEffectList().add(info);
			}
			else
			{
				debuff.applyEffects(this, player, false, 0);
			}
			
			if (shown == null)
			{
				shown = debuff;
			}
			applied++;
		}
		
		if (shown != null)
		{
			broadcastPacket(new MagicSkillUse(this, player, shown.getId(), shown.getLevel(), 0, 0));
			if (TreasureChestConfig.MESSAGES)
			{
				player.sendMessage("The Mimic curses you!");
			}
		}
	}
	
	/**
	 * @param skillId a debuff skill id
	 * @return the highest level of the skill this mimic's level allows (level 1 at least), or {@code null} if the skill doesn't exist or is a cancel / dispel skill
	 */
	private Skill getCurseSkill(int skillId)
	{
		final int maxLevel = SkillData.getInstance().getMaxLevel(skillId);
		Skill result = null;
		for (int level = 1; level <= maxLevel; level++)
		{
			final Skill skill = SkillData.getInstance().getSkill(skillId, level);
			if ((skill != null) && ((result == null) || (skill.getMagicLevel() <= getLevel())))
			{
				result = skill;
			}
		}
		
		if ((result == null) || isCancelSkill(result))
		{
			return null;
		}
		return result;
	}
	
	private static boolean isCancelSkill(Skill skill)
	{
		final List<AbstractEffect> effects = skill.getEffects(EffectScope.GENERAL);
		if (effects != null)
		{
			for (AbstractEffect effect : effects)
			{
				final String name = effect.getClass().getSimpleName();
				if (name.startsWith("Dispel") || name.contains("Cancel") || name.equals("StealAbnormal"))
				{
					return true;
				}
			}
		}
		return skill.getName().toLowerCase().contains("cancel");
	}
	
	private void vanish(Creature attacker)
	{
		synchronized (this)
		{
			if (_vanished || isDead())
			{
				return;
			}
			_vanished = true;
		}
		
		if (TreasureChestConfig.MESSAGES && (attacker != null) && (attacker.asPlayer() != null))
		{
			attacker.asPlayer().sendMessage("The treasure chest vanished before your eyes.");
		}
		
		// Removes the chest and lets its spawn point respawn it on the normal timer.
		deleteMe();
	}
	
	@Override
	public void doItemDrop(NpcTemplate npcTemplate, Creature lastAttacker)
	{
		if (TreasureChestConfig.ENABLED && _specialDrop && isRealTreasureChest())
		{
			giveMaterials(lastAttacker);
			return;
		}
		
		int id = getTemplate().getId();
		if (!_specialDrop)
		{
			if ((id >= 18265) && (id <= 18286))
			{
				id += 3536;
			}
			else if ((id == 18287) || (id == 18288))
			{
				id = 21671;
			}
			else if ((id == 18289) || (id == 18290))
			{
				id = 21694;
			}
			else if ((id == 18291) || (id == 18292))
			{
				id = 21717;
			}
			else if ((id == 18293) || (id == 18294))
			{
				id = 21740;
			}
			else if ((id == 18295) || (id == 18296))
			{
				id = 21763;
			}
			else if ((id == 18297) || (id == 18298))
			{
				id = 21786;
			}
		}
		
		super.doItemDrop(NpcData.getInstance().getTemplate(id), lastAttacker);
	}
	
	/**
	 * Rolls the reward tier of an opened real chest (Common / Rare / Epic) and hands out random materials from that tier.
	 * @param opener the player who opened the chest
	 */
	private void giveMaterials(Creature opener)
	{
		final Player player = opener == null ? null : opener.asPlayer();
		if (player == null)
		{
			return;
		}
		
		final String tierName;
		final List<ChestMaterial> pool;
		final int minItems;
		final int maxItems;
		final double roll = Rnd.get(TreasureChestConfig.COMMON_CHANCE + TreasureChestConfig.RARE_CHANCE + TreasureChestConfig.EPIC_CHANCE);
		if (roll < TreasureChestConfig.EPIC_CHANCE)
		{
			tierName = "Epic";
			pool = TreasureChestConfig.EPIC_MATERIALS;
			minItems = TreasureChestConfig.EPIC_MIN_ITEMS;
			maxItems = TreasureChestConfig.EPIC_MAX_ITEMS;
		}
		else if (roll < (TreasureChestConfig.EPIC_CHANCE + TreasureChestConfig.RARE_CHANCE))
		{
			tierName = "Rare";
			pool = TreasureChestConfig.RARE_MATERIALS;
			minItems = TreasureChestConfig.RARE_MIN_ITEMS;
			maxItems = TreasureChestConfig.RARE_MAX_ITEMS;
		}
		else
		{
			tierName = "Common";
			pool = TreasureChestConfig.COMMON_MATERIALS;
			minItems = TreasureChestConfig.COMMON_MIN_ITEMS;
			maxItems = TreasureChestConfig.COMMON_MAX_ITEMS;
		}
		
		if (pool.isEmpty())
		{
			return;
		}
		
		// Pick different materials, each with the same chance.
		final List<ChestMaterial> materials = new ArrayList<>(pool);
		Collections.shuffle(materials);
		final int count = Math.min(materials.size(), Rnd.get(minItems, maxItems));
		for (int i = 0; i < count; i++)
		{
			final ChestMaterial material = materials.get(i);
			dropOrAutoLoot(player, new ItemHolder(material.itemId, Rnd.get(material.min, material.max)));
		}
		
		if (TreasureChestConfig.MESSAGES)
		{
			player.sendMessage("You opened the treasure chest and found " + tierName + " materials!");
		}
	}
	
	/**
	 * Real chests never move. Mimics can walk and chase the player who woke them (with {@link TreasureChestConfig#MIMIC_CAN_MOVE}), but they never walk around on their own.
	 */
	@Override
	public boolean isMovementDisabled()
	{
		if (TreasureChestConfig.ENABLED && TreasureChestConfig.MIMIC_CAN_MOVE && isMimic())
		{
			return super.isMovementDisabled();
		}
		return true;
	}
	
	/**
	 * Called while this chest is fighting. A mimic that has chased someone further than {@link TreasureChestConfig#MIMIC_CHASE_RANGE} from its spawn point gives up: it forgets its attackers, heals up and walks back.
	 * @return {@code true} if the mimic gave up the chase
	 */
	public boolean checkMimicLeash()
	{
		if (!TreasureChestConfig.ENABLED || !TreasureChestConfig.MIMIC_CAN_MOVE || (TreasureChestConfig.MIMIC_CHASE_RANGE <= 0) || !isMimic() || isDead() || (getSpawn() == null))
		{
			return false;
		}
		
		if (calculateDistance2D(getSpawn()) <= TreasureChestConfig.MIMIC_CHASE_RANGE)
		{
			return false;
		}
		
		abortAttack();
		abortCast();
		getAttackByList().clear();
		setCurrentHp(getMaxHp());
		setCurrentMp(getMaxMp());
		setWalking();
		returnHome();
		return true;
	}
	
	@Override
	public boolean hasRandomAnimation()
	{
		return false;
	}
}
