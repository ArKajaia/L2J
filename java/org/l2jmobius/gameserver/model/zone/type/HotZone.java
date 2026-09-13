package org.l2jmobius.gameserver.model.zone.type;

import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.model.zone.ZoneType;

public class HotZone extends ZoneType
{
	public HotZone(int id)
	{
		super(id);
	}
	
	@Override
	protected void onEnter(Creature creature)
	{
		// Tells the game this creature is currently in a hotzone
		creature.setInsideZone(ZoneId.HOTZONE, true);
	}
	
	@Override
	protected void onExit(Creature creature)
	{
		// Tells the game this creature left the hotzone
		creature.setInsideZone(ZoneId.HOTZONE, false);
	}
}