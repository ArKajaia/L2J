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
package org.l2jmobius.gameserver.model.mapdevice;

import java.util.List;

/**
 * A unique map (data/MapDevice.xml {@code <uniqueMap>}): a map item that always opens the same map, with modifiers chosen for it instead of rolled, a twist of its own while it is cleared, a final encounter once it is, and drops of its own for the whole map and for its boss.
 * The twist and the final encounter are {@link Type}'s, worked by {@code UniqueMapManager}; the numbers and the drops are the XML's.
 * @param type which unique map it is (its twist and final encounter)
 * @param name its name
 * @param itemId the item that opens it
 * @param tier the tier whose monsters fill it (their levels and strength, and the tier's multipliers)
 * @param monsterCount how many monsters it holds before its final encounter
 * @param arena the name of the arena of data/MapDevice.xml (or the id of an event map) it opens in, {@code null} or unknown for any of the tier's
 * @param modifiers its modifiers, always the same
 * @param reward multiplier on the experience, SP and drop chance of its monsters, on top of the tier's
 * @param bossNpcId the npc its boss is made from (a plain {@code type="Monster"} npc)
 * @param bossName the boss's name, shown as its title
 * @param bossHp multiplier on the boss's HP, on top of the map's
 * @param bossAttack multiplier on the boss's P. Atk. and M. Atk., on top of the map's
 * @param bossDefence multiplier on the boss's P. Def. and M. Def., on top of the map's
 * @param description what the map is
 * @param twist what its twist does
 * @param finale what its final encounter is
 * @param drops what every monster of the map may drop (not the boss)
 * @param bossDrops what the boss drops
 * @param specialDrops what the twist's own monsters drop (the Ossuary's Echoes, the Vault's spilled coins...)
 */
public record UniqueMap(Type type, String name, int itemId, int tier, int monsterCount, String arena, List<MapModifier> modifiers, double reward, int bossNpcId, String bossName, double bossHp, double bossAttack, double bossDefence, String description, String twist, String finale, List<Drop> drops, List<Drop> bossDrops, List<Drop> specialDrops)
{
	/** The unique maps: each one's twist and final encounter. */
	public enum Type
	{
		/** The Crimson Coliseum: Deathmatch: Mayhem against monsters, in three waves, then the Crimson Champion. */
		COLISEUM,
		/** The Gilded Vault: five minutes on the clock, every kill buys time and greed, then the Gilded Mimic that runs with the hoard. */
		VAULT,
		/** The Ossuary of Echoes: the slain leave Echoes behind, then the Hollow King behind his phylacteries. */
		OSSUARY
	}

	/**
	 * One drop of a unique map.
	 * @param itemId the item
	 * @param min the least of it
	 * @param max the most of it
	 * @param chance its chance in percent (0-100)
	 */
	public record Drop(int itemId, long min, long max, double chance)
	{
	}
}
