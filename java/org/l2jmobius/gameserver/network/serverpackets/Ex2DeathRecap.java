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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.l2jmobius.commons.network.WritableBuffer;
import org.l2jmobius.gameserver.model.actor.holders.player.CombatLog;
import org.l2jmobius.gameserver.model.actor.holders.player.CombatLog.Entry;
import org.l2jmobius.gameserver.model.actor.holders.player.CombatLog.Report;
import org.l2jmobius.gameserver.model.actor.holders.player.CombatLog.SkillUse;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;

/**
 * The PvP fight a player just died in, for the client's death recap window: the killer, the damage taken by attack and skill, and the last hits.<br>
 * The layout is in {@code client/DeathRecap/PROTOCOL.txt}. Made from the frozen {@link Report} of {@link org.l2jmobius.gameserver.managers.CombatLogManager#onDeath}.
 */
public class Ex2DeathRecap extends ServerPacket
{
	public static final int VERSION = 1;

	public static final int FLAG_KILLER_PVP = 1;
	public static final int FLAG_KILLER_SUMMON = 2;
	public static final int FLAG_NO_KILLER = 4;

	public static final int HIT_CRIT = 1;
	public static final int HIT_KILLING_BLOW = 2;

	/** The client's limits. */
	public static final int MAX_ROWS = 16;
	public static final int MAX_HITS = 48;
	public static final int MAX_NAME = 32;
	public static final int MAX_ATTACKER = 48;
	/** A hit of a skill that is not among the rows. */
	public static final int NO_ROW = 255;

	private final Report _report;
	private final List<SkillUse> _rows;
	private final List<Entry> _hits = new ArrayList<>();
	private final Map<Long, Integer> _rowOf = new HashMap<>();
	private final Entry _killingBlow;

	/**
	 * @param report the fight, with its killer filled in
	 */
	public Ex2DeathRecap(Report report)
	{
		_report = report;
		// Most damage first already.
		_rows = report.skills.subList(0, Math.min(MAX_ROWS, report.skills.size()));
		for (int i = 0; i < _rows.size(); i++)
		{
			_rowOf.put(_rows.get(i).key, i);
		}

		Entry last = null;
		for (Entry e : report.entries)
		{
			if ((e.type == CombatLog.HIT) || (e.type == CombatLog.DOT))
			{
				_hits.add(e);
				last = e;
			}
		}
		_killingBlow = last;
		// The newest ones.
		while (_hits.size() > MAX_HITS)
		{
			_hits.remove(0);
		}
	}

	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		ServerPackets.EX2_DEATH_RECAP.writeId(this, buffer);
		final Report r = _report;
		buffer.writeByte(VERSION);
		buffer.writeByte(r.killerName == null ? FLAG_NO_KILLER : (r.killerPvp ? FLAG_KILLER_PVP : 0) | (r.killerSummon ? FLAG_KILLER_SUMMON : 0));
		buffer.writeInt(r.killerObjectId);
		buffer.writeString(cut(r.killerName, MAX_NAME));
		buffer.writeString(cut(r.killerOwner, MAX_NAME));
		buffer.writeString(cut(r.killerClass, MAX_NAME));
		buffer.writeShort(clamp(r.killerLevel, 32767));
		buffer.writeInt(r.killerWeaponItemId);
		buffer.writeShort(clamp(r.killerWeaponEnchant, 32767));
		buffer.writeByte(r.killerWeaponType);
		buffer.writeByte(r.killerElement);
		buffer.writeShort(clamp(r.killerElementValue, 32767));
		buffer.writeInt(Math.max(0, r.killerHp));
		buffer.writeInt(Math.max(0, r.killerMaxHp));
		buffer.writeInt(clamp(r.deathTime - r.fightStart, Integer.MAX_VALUE));
		buffer.writeLong(r.damageTaken);
		buffer.writeLong(r.cpTaken);
		buffer.writeLong(r.dealtToKiller);

		buffer.writeByte(_rows.size());
		for (SkillUse use : _rows)
		{
			buffer.writeByte(use.category);
			buffer.writeInt(use.skill == null ? 0 : use.skill.getDisplayId());
			buffer.writeShort(use.skill == null ? 0 : clamp(use.skill.getDisplayLevel(), 32767));
			buffer.writeByte(use.weaponType);
			// The killer's own rows need no name.
			buffer.writeString(use.attackerId == r.killerObjectId ? "" : cut(use.source, MAX_ATTACKER));
			buffer.writeLong(use.damage);
			buffer.writeShort(clamp(use.hits, 32767));
			buffer.writeShort(clamp(use.crits, 32767));
			buffer.writeInt(use.biggest);
			buffer.writeByte(use.biggestCrit ? 1 : 0);
			buffer.writeInt(use.effectMillis);
		}

		buffer.writeByte(_hits.size());
		for (Entry e : _hits)
		{
			final Integer row = _rowOf.get(e.useKey);
			buffer.writeInt(clamp(r.deathTime - e.time, Integer.MAX_VALUE));
			buffer.writeByte(row == null ? NO_ROW : row);
			buffer.writeInt(e.getTotal());
			buffer.writeByte((e.crit ? HIT_CRIT : 0) | (e == _killingBlow ? HIT_KILLING_BLOW : 0));
		}
	}

	private static String cut(String text, int length)
	{
		if (text == null)
		{
			return "";
		}
		return text.length() > length ? text.substring(0, length) : text;
	}

	private static int clamp(long value, int max)
	{
		return (int) Math.max(0, Math.min(max, value));
	}
}
