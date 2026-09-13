package org.l2jmobius.gameserver.model.actor;

import org.l2jmobius.gameserver.model.item.ItemTemplate;

public class AutoLootFilterUtil
{
	public static boolean shouldAutoLoot(Player player, ItemTemplate item)
	{
		if ((player == null) || (item == null))
		{
			return true; // Failsafe
		}
		
		// 1. Check Adena
		if (item.getId() == 57)
		{
			return player.getVariables().getBoolean("Filter_Adena", true);
		}
		
		// 2. Check Gear (Weapons, Armor, Jewelry)
		if (item.isWeapon() || item.isArmor())
		{
			return player.getVariables().getBoolean("Filter_Gear", true);
		}
		
		// 3. Check EtcItem (Materials, Recipes, Potions, Scrolls, Arrows, etc.)
		return player.getVariables().getBoolean("Filter_EtcItem", true);
	}
}