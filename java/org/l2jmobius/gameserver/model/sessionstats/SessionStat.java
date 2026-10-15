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
package org.l2jmobius.gameserver.model.sessionstats;

/**
 * What the session log counts. The key names the value in the saved history, so it must never change.
 */
public enum SessionStat
{
	EXP_GAINED("xp"),
	EXP_LOST("xpl"),
	SP_GAINED("sp"),
	KILLS("k"),
	PARTY_KILLS("pt"),
	RAID_KILLS("rb"),
	CHAMPION_KILLS("ch"),
	DEATHS("d"),
	PVP_KILLS("pvp"),
	PK_KILLS("pkk"),
	ADENA_EARNED("ae"),
	ADENA_SPENT("as"),
	ADENA_START("a0"),
	ADENA_END("a1"),
	FAME("fame"),
	QUESTS("q"),
	ITEMS_LOOTED("loot"),
	SPOILS("sw"),
	ITEMS_CRAFTED("cr");
	
	private final String _key;
	
	SessionStat(String key)
	{
		_key = key;
	}
	
	public String getKey()
	{
		return _key;
	}
	
	public static SessionStat fromKey(String key)
	{
		for (SessionStat stat : values())
		{
			if (stat._key.equals(key))
			{
				return stat;
			}
		}
		return null;
	}
}
