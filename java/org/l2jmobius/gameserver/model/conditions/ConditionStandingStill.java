package org.l2jmobius.gameserver.model.conditions;

import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.skill.Skill;

/**
 * Whether the creature has stood still for a while: not moving, and its last step at least the given time ago (passive tree Ley Anchor, the STILL and MOBILE conditions).
 */
public class ConditionStandingStill extends Condition
{
	private final long _time;
	private final boolean _still;
	
	/**
	 * @param time how long the creature must have stood still, in milliseconds
	 * @param still {@code true} to pass once it has, {@code false} to pass until it has
	 */
	public ConditionStandingStill(long time, boolean still)
	{
		_time = time;
		_still = still;
	}
	
	@Override
	public boolean testImpl(Creature effector, Creature effected, Skill skill, ItemTemplate item)
	{
		if (effector == null)
		{
			return false;
		}
		
		final boolean still = !effector.isMoving() && ((System.currentTimeMillis() - effector.getLastMoveTime()) >= _time);
		return still == _still;
	}
}
