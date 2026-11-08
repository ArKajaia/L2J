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
package org.l2jmobius.gameserver.ai;

import java.util.Locale;
import java.util.Map;

import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;

/**
 * How easy each class is to kill in a Team vs Team fight, for {@link FakeTvtTactics#ease}: its deaths per appearance in about 230 simulated 30v30 matches (2026-11-07/08, about 12,000 kills,
 * mostly with the tactics of {@code best}), relative to the average fighter (1.0). A class is drawn evenly within its role's slots, so its expected appearances are its role's slots a team over the classes
 * of the role. See docs/tvt-tactics.md, round 7.
 */
public final class FakeTvtKillEase
{
	/** Deaths per appearance, relative to the average fighter, by class (letters only, upper case: "EVASTEMPLAR"). */
	private static final Map<String, Double> RATES = Map.ofEntries(
		// @formatter:off
		Map.entry("GHOSTHUNTER", 1.94), Map.entry("ADVENTURER", 1.94), Map.entry("WINDRIDER", 1.88), Map.entry("TRICKSTER", 1.52),
		Map.entry("DOOMBRINGER", 1.42), Map.entry("MOONLIGHTSENTINEL", 1.34), Map.entry("GHOSTSENTINEL", 1.32), Map.entry("DOOMORACLE", 1.30),
		Map.entry("WARDANCER", 1.28), Map.entry("ARCHMAGE", 1.28), Map.entry("DOOMCRYER", 1.27), Map.entry("GRANDKHAVATARI", 1.24),
		Map.entry("MALESOULHOUND", 1.24), Map.entry("TITAN", 1.23), Map.entry("FLAMEWARCHIEF", 1.21), Map.entry("STORMSCREAMER", 1.19),
		Map.entry("JUDICATOR", 1.18), Map.entry("FEMALESOULHOUND", 1.17), Map.entry("SOULTAKER", 1.11), Map.entry("SPECTRALDANCER", 1.09),
		Map.entry("MYSTICMUSE", 1.08), Map.entry("DREADNOUGHT", 1.06), Map.entry("SAGITTARIUS", 1.03), Map.entry("FORTUNESEEKER", 1.01),
		Map.entry("MAESTRO", 0.97), Map.entry("DUELIST", 0.96), Map.entry("SHILLIENSAINT", 0.79), Map.entry("SWORDMUSE", 0.77),
		Map.entry("EVASSAINT", 0.72), Map.entry("HIEROPHANT", 0.70), Map.entry("CARDINAL", 0.66), Map.entry("HELLKNIGHT", 0.40),
		Map.entry("PHOENIXKNIGHT", 0.36), Map.entry("SHILLIENTEMPLAR", 0.35), Map.entry("BATTLEBARD", 0.33), Map.entry("EVASTEMPLAR", 0.29));
		// @formatter:on
	private static final double MIN = 0.29;
	private static final double MAX = 1.94;

	private FakeTvtKillEase()
	{
	}

	/**
	 * @param creature an enemy (a fake player by its build, a player by its class)
	 * @return how easy it is to kill, from 0 (the hardest class, a tank) to 1 (the easiest, a dagger); 0.5 for a class not in the table
	 */
	public static double of(Creature creature)
	{
		final String name;
		if (creature.isPlayer())
		{
			name = creature.asPlayer().getPlayerClass().name();
		}
		else
		{
			final FakePlayerPvpProfile profile = creature.isNpc() ? creature.asNpc().getTemplate().getFakePlayerPvpProfile() : null;
			if ((profile == null) || (profile.getBuild() == null))
			{
				return 0.5;
			}
			name = profile.getBuild().getName();
		}
		final Double rate = RATES.get(name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", ""));
		return rate == null ? 0.5 : (rate - MIN) / (MAX - MIN);
	}
}
