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
package handlers.skill.effects;

import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.custom.TreasureChestConfig;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.Chest;
import org.l2jmobius.gameserver.model.conditions.Condition;
import org.l2jmobius.gameserver.model.effects.AbstractEffect;
import org.l2jmobius.gameserver.model.skill.Skill;

/**
 * Open Chest effect implementation.
 * @author Adry_85
 */
public class OpenChest extends AbstractEffect
{
	public OpenChest(Condition attachCond, Condition applyCond, StatSet set, StatSet params)
	{
		super(attachCond, applyCond, set, params);
	}
	
	@Override
	public boolean isInstant()
	{
		return true;
	}
	
	@Override
	public void onStart(Creature effector, Creature effected, Skill skill)
	{
		if (!(effected instanceof Chest))
		{
			return;
		}
		
		final Player player = effector.asPlayer();
		final Chest chest = (Chest) effected;
		if (chest.isDead() || (player.getInstanceId() != chest.getInstanceId()))
		{
			return;
		}
		
		if (TreasureChestConfig.ENABLED && (chest.isRealTreasureChest() || chest.isMimic()))
		{
			openTreasureChest(player, chest, skill);
			return;
		}
		
		if (isLevelInRange(player, chest))
		{
			player.broadcastSocialAction(3);
			chest.setSpecialDrop();
			chest.setMustRewardExpSp(false);
			chest.reduceCurrentHp(chest.getMaxHp(), player, skill);
		}
		else
		{
			player.broadcastSocialAction(13);
			chest.addDamageHate(player, 0, 1);
			chest.getAI().setIntention(Intention.ATTACK, player);
		}
	}
	
	/**
	 * Retail-like treasure chests: the level check comes first and looks the same for both kinds, then a mimic attacks the player and a real chest opens and pays out materials.
	 * @param player the player using the key
	 * @param chest the targeted real treasure chest or mimic
	 * @param skill the key skill
	 */
	private void openTreasureChest(Player player, Chest chest, Skill skill)
	{
		if (TreasureChestConfig.LEVEL_CHECK && !isLevelInRange(player, chest))
		{
			player.broadcastSocialAction(13);
			if (TreasureChestConfig.MESSAGES)
			{
				player.sendMessage("Your key doesn't fit this chest's lock. It only opens chests close to your level.");
			}
			return;
		}
		
		if (chest.isMimic())
		{
			player.broadcastSocialAction(13);
			if (TreasureChestConfig.MESSAGES)
			{
				player.sendMessage("It's a Mimic!");
			}
			chest.addDamageHate(player, 0, 999);
			chest.getAI().setIntention(Intention.ATTACK, player);
			return;
		}
		
		player.broadcastSocialAction(3);
		chest.setSpecialDrop();
		chest.setMustRewardExpSp(false);
		chest.reduceCurrentHp(chest.getMaxHp(), player, skill);
	}
	
	private static boolean isLevelInRange(Player player, Chest chest)
	{
		return ((player.getLevel() <= 77) && (Math.abs(chest.getLevel() - player.getLevel()) <= 6)) || ((player.getLevel() >= 78) && (Math.abs(chest.getLevel() - player.getLevel()) <= 5));
	}
}
