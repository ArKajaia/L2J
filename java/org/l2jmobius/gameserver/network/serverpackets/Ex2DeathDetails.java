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
import org.l2jmobius.gameserver.model.actor.holders.player.CombatLog;
import org.l2jmobius.gameserver.model.actor.holders.player.CombatLog.Entry;
import org.l2jmobius.gameserver.model.actor.holders.player.CombatLog.Equipped;
import org.l2jmobius.gameserver.model.actor.holders.player.CombatLog.Report;
import org.l2jmobius.gameserver.model.actor.holders.player.CombatLog.Side;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;

/**
 * The rest of the PvP fight a player last died in, for the client's death details window: what {@link Ex2DeathRecap} left out. The fight's totals, the attackers, the player's targets, the debuffs they had, the whole timeline, and the killer's equipment and passive tree.<br>
 * The layout is in {@code client/DeathRecap/PROTOCOL.txt}. Sent when the client asks (RequestEx2CombatReport).
 */
public class Ex2DeathDetails extends ServerPacket
{
	public static final int VERSION = 1;

	/** The client's limits. */
	public static final int MAX_ATTACKERS = 16;
	public static final int MAX_TARGETS = 8;
	public static final int MAX_DEBUFFS = 24;
	public static final int MAX_ENTRIES = 100;
	public static final int MAX_EQUIPMENT = 32;
	public static final int MAX_TREE_NODES = 2048;
	public static final int MAX_NAME = 32;
	public static final int MAX_LONG_NAME = 64;
	/** An augmentation's effect lines, and the length of one. */
	public static final int MAX_EFFECTS = 6;
	public static final int MAX_EFFECT_TEXT = 80;

	public static final int ENTRY_PVP = 1;
	public static final int ENTRY_CRIT = 2;

	private final Report _report;

	/**
	 * @param report the fight, with its killer filled in
	 */
	public Ex2DeathDetails(Report report)
	{
		_report = report;
	}

	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		ServerPackets.EX2_DEATH_DETAILS.writeId(this, buffer);
		final Report r = _report;
		buffer.writeByte(VERSION);
		// The killer, beyond the recap.
		buffer.writeString(cut(r.killerClan, MAX_NAME));
		buffer.writeInt(Math.max(0, r.killerDistance));
		buffer.writeInt(Math.max(0, r.killerCp));
		buffer.writeInt(Math.max(0, r.killerMaxCp));
		// The player and the fight.
		buffer.writeInt(Math.max(0, r.maxHp));
		buffer.writeInt(Math.max(0, r.maxCp));
		buffer.writeLong(r.healedHp);
		buffer.writeLong(r.healedCp);
		buffer.writeInt(r.heals);
		buffer.writeInt(r.debuffsTaken);
		buffer.writeInt(r.resists);
		buffer.writeInt(r.cancelled);
		buffer.writeLong(r.damageDealt);
		buffer.writeInt(r.hitsDealt);
		buffer.writeInt(r.critsDealt);
		buffer.writeInt(r.missesDealt);

		final int attackers = Math.min(MAX_ATTACKERS, r.attackers.size());
		buffer.writeByte(attackers);
		for (int i = 0; i < attackers; i++)
		{
			final Side side = r.attackers.get(i);
			buffer.writeString(cut(side.name, MAX_LONG_NAME));
			buffer.writeByte(side.pvp ? 1 : 0);
			buffer.writeLong(side.damage);
			buffer.writeLong(side.cpDamage);
			buffer.writeShort(clamp(side.hits + side.dots, 32767));
			buffer.writeShort(clamp(side.crits, 32767));
			buffer.writeInt(side.biggest);
			writeSkill(buffer, side.biggestSkill);
			buffer.writeShort(clamp(side.evaded, 32767));
			buffer.writeShort(clamp(side.debuffs, 32767));
			buffer.writeShort(clamp(side.resisted, 32767));
			buffer.writeShort(clamp(side.cancels, 32767));
		}

		final int targets = Math.min(MAX_TARGETS, r.targets.size());
		buffer.writeByte(targets);
		for (int i = 0; i < targets; i++)
		{
			final Side side = r.targets.get(i);
			buffer.writeString(cut(side.name, MAX_LONG_NAME));
			buffer.writeLong(side.damage);
			buffer.writeShort(clamp(side.hits + side.dots, 32767));
			buffer.writeShort(clamp(side.crits, 32767));
		}

		// "name", "icon", "seconds left", "caster", "skill id", "skill level" (CombatLogManager.getDebuffs).
		final List<String[]> debuffs = r.activeDebuffs;
		final int debuffCount = Math.min(MAX_DEBUFFS, debuffs.size());
		buffer.writeByte(debuffCount);
		for (int i = 0; i < debuffCount; i++)
		{
			final String[] debuff = debuffs.get(i);
			buffer.writeInt(debuff.length > 4 ? number(debuff[4]) : 0);
			buffer.writeShort(debuff.length > 5 ? clamp(number(debuff[5]), 32767) : 0);
			buffer.writeInt(number(debuff[2]));
			buffer.writeString(cut(debuff[3], MAX_LONG_NAME));
		}

		// The newest events, oldest first.
		final List<Entry> entries = r.entries;
		final int first = Math.max(0, entries.size() - MAX_ENTRIES);
		buffer.writeByte(entries.size() - first);
		for (int i = first; i < entries.size(); i++)
		{
			final Entry e = entries.get(i);
			buffer.writeByte(e.type);
			buffer.writeInt(clamp(r.deathTime - e.time, Integer.MAX_VALUE));
			buffer.writeString(cut(e.name, MAX_LONG_NAME));
			buffer.writeByte((e.pvp ? ENTRY_PVP : 0) | (e.crit ? ENTRY_CRIT : 0));
			writeSkill(buffer, e.skill);
			buffer.writeInt(Math.max(0, e.hp));
			buffer.writeInt(Math.max(0, e.cp));
			// -1: unknown.
			buffer.writeInt(Math.max(-1, e.hpAfter));
			buffer.writeInt(Math.max(-1, e.cpAfter));
			buffer.writeShort(clamp(e.count, 32767));
		}
		buffer.writeInt(r.droppedEntries + first);

		final List<Equipped> equipment = r.killerEquipment;
		final int items = Math.min(MAX_EQUIPMENT, equipment.size());
		buffer.writeByte(items);
		for (int i = 0; i < items; i++)
		{
			final Equipped item = equipment.get(i);
			buffer.writeByte(item.slot);
			buffer.writeInt(item.itemId);
			buffer.writeShort(clamp(item.enchant, 32767));
			buffer.writeInt(item.augmentation);
			final int effects = Math.min(MAX_EFFECTS, item.augmentEffects.size());
			buffer.writeByte(effects);
			for (int k = 0; k < effects; k++)
			{
				buffer.writeString(cut(item.augmentEffects.get(k), MAX_EFFECT_TEXT));
			}
			buffer.writeByte(item.attackElement);
			buffer.writeShort(clamp(item.attackElementValue, 32767));
			for (int value : item.defence)
			{
				buffer.writeShort(clamp(value, 32767));
			}
		}

		final int[] nodes = r.killerTreeNodes;
		buffer.writeByte(nodes == null ? 0 : 1);
		if (nodes != null)
		{
			final int count = Math.min(MAX_TREE_NODES, nodes.length);
			buffer.writeInt(Math.max(0, r.killerTreeEarned));
			buffer.writeInt(Math.max(0, r.killerTreeSpent));
			buffer.writeShort(count);
			for (int i = 0; i < count; i++)
			{
				buffer.writeInt(nodes[i]);
			}
		}
	}

	private static void writeSkill(WritableBuffer buffer, Skill skill)
	{
		buffer.writeInt(skill == null ? 0 : skill.getDisplayId());
		buffer.writeShort(skill == null ? 0 : clamp(skill.getDisplayLevel(), 32767));
	}

	private static int number(String text)
	{
		try
		{
			return Math.max(0, Integer.parseInt(text));
		}
		catch (NumberFormatException e)
		{
			return 0;
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

	/** Entry types, as {@link CombatLog}'s. */
	public static final int TYPES = CombatLog.YOU_MISSED + 1;
}
