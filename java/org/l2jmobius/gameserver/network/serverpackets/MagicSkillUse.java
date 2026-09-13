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
package org.l2jmobius.gameserver.network.serverpackets;

import org.l2jmobius.commons.network.WritableBuffer;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;

/**
 * MagicSkillUse server packet implementation.
 * @author Mobius
 */
public class MagicSkillUse extends ServerPacket
{
	private final int _skillId;
	private final int _skillLevel;
	private final int _hitTime;
	private final int _reuseDelay;
	private final Creature _creature;
	private final Creature _target;
	private final Location _groundLocation;
	
	public MagicSkillUse(Creature creature, Creature target, int skillId, int skillLevel, int hitTime, int reuseDelay)
	{
		_creature = creature;
		_target = target;
		_skillId = skillId;
		_skillLevel = skillLevel;
		_hitTime = hitTime;
		_reuseDelay = reuseDelay;
		_groundLocation = creature.isPlayer() ? creature.asPlayer().getCurrentSkillWorldPosition() : null;
	}
	
	public MagicSkillUse(Creature creature, int skillId, int skillLevel, int hitTime, int reuseDelay)
	{
		this(creature, creature, skillId, skillLevel, hitTime, reuseDelay);
	}
	
	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		ServerPackets.MAGIC_SKILL_USE.writeId(this, buffer);
		buffer.writeInt(_creature.getObjectId());
		buffer.writeInt(_target.getObjectId());
		buffer.writeInt(_skillId);
		buffer.writeInt(_skillLevel);
		buffer.writeInt(_hitTime);
		buffer.writeInt(_reuseDelay);
		buffer.writeInt(_creature.getX());
		buffer.writeInt(_creature.getY());
		buffer.writeInt(_creature.getZ());
		buffer.writeShort(0); // isGroundTargetSkill ? 65535 : 0
		if (_groundLocation == null)
		{
			buffer.writeShort(0);
		}
		else
		{
			buffer.writeShort(1);
			buffer.writeInt(_groundLocation.getX());
			buffer.writeInt(_groundLocation.getY());
			buffer.writeInt(_groundLocation.getZ());
		}
		
		buffer.writeInt(_target.getX());
		buffer.writeInt(_target.getY());
		buffer.writeInt(_target.getZ());
	}
}
