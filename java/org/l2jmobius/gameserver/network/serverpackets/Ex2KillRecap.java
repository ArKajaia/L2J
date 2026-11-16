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
package org.l2jmobius.gameserver.network.serverpackets;

import java.util.List;

import org.l2jmobius.commons.network.WritableBuffer;
import org.l2jmobius.gameserver.model.actor.holders.player.CombatLog.KillSummary;
import org.l2jmobius.gameserver.model.actor.holders.player.CombatLog.SkillUse;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;

/**
 * What a player did to the player they just killed, for the client's kill recap toast: the damage dealt by attack and skill, the damage taken from the victim, and the best hit.<br>
 * The layout is in {@code client/DeathRecap/PROTOCOL.txt}. Made by {@link org.l2jmobius.gameserver.managers.CombatLogManager#onDeath}.
 */
public class Ex2KillRecap extends ServerPacket
{
	public static final int VERSION = 1;

	/** The client's limits. */
	public static final int MAX_ROWS = 8;
	public static final int MAX_NAME = 32;

	private final String _victimName;
	private final String _className;
	private final int _level;
	private final int _weaponItemId;
	private final KillSummary _summary;

	/**
	 * @param victimName who died
	 * @param className the name of their class, {@code null} for none
	 * @param level their level
	 * @param weaponItemId their weapon's item id, 0 for none
	 * @param summary what the recipient did to them
	 */
	public Ex2KillRecap(String victimName, String className, int level, int weaponItemId, KillSummary summary)
	{
		_victimName = victimName;
		_className = className;
		_level = level;
		_weaponItemId = weaponItemId;
		_summary = summary;
	}

	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		ServerPackets.EX2_KILL_RECAP.writeId(this, buffer);
		buffer.writeByte(VERSION);
		buffer.writeString(cut(_victimName));
		buffer.writeString(cut(_className));
		buffer.writeShort(clamp(_level, 32767));
		buffer.writeInt(_weaponItemId);
		buffer.writeInt(clamp(_summary.fightMillis, Integer.MAX_VALUE));
		buffer.writeLong(_summary.dealt);
		buffer.writeLong(Math.max(0, _summary.taken));

		final List<SkillUse> rows = _summary.rows;
		final int count = Math.min(MAX_ROWS, rows.size());
		buffer.writeByte(count);
		for (int i = 0; i < count; i++)
		{
			final SkillUse use = rows.get(i);
			buffer.writeByte(use.category);
			buffer.writeInt(use.skill == null ? 0 : use.skill.getDisplayId());
			buffer.writeShort(use.skill == null ? 0 : clamp(use.skill.getDisplayLevel(), 32767));
			buffer.writeByte(use.weaponType);
			buffer.writeLong(use.damage);
			buffer.writeShort(clamp(use.hits, 32767));
			buffer.writeShort(clamp(use.crits, 32767));
			buffer.writeInt(use.biggest);
			buffer.writeByte(use.biggestCrit ? 1 : 0);
		}

		buffer.writeInt(_summary.bestHit);
		buffer.writeInt(_summary.bestHitSkill == null ? 0 : _summary.bestHitSkill.getDisplayId());
		buffer.writeShort(_summary.bestHitSkill == null ? 0 : clamp(_summary.bestHitSkill.getDisplayLevel(), 32767));
	}

	private static String cut(String text)
	{
		if (text == null)
		{
			return "";
		}
		return text.length() > MAX_NAME ? text.substring(0, MAX_NAME) : text;
	}

	private static int clamp(long value, int max)
	{
		return (int) Math.max(0, Math.min(max, value));
	}
}
