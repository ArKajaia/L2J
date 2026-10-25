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
package org.l2jmobius.gameserver.managers.simulation;

import java.util.List;

/**
 * The slot a member fills in a simulation team (see {@link SimulationComposer}), with the builds that can fill it.
 */
public enum SimRole
{
	TANK("tank", List.of("Phoenix Knight", "Hell Knight", "Eva's Templar", "Shillien Templar")),
	MELEE("melee", List.of("Adventurer", "Wind Rider", "Ghost Hunter", "Duelist", "Grand Khavatari", "Titan", "Doombringer", "Judicator", "Dreadnought", "Fortune Seeker", "Maestro", "War Dancer")),
	ARCHER("ranged", List.of("Sagittarius", "Moonlight Sentinel", "Ghost Sentinel", "Trickster")),
	NUKER("ranged", List.of("Archmage", "Mystic Muse", "Storm Screamer", "Soultaker", "Male Soul Hound", "Female Soul Hound", "Flame Warchief", "Doom Oracle")),
	HEALER("healer", List.of("Cardinal", "Eva's Saint", "Shillien Saint")),
	BUFFER("buffer", List.of("Sword Muse", "Spectral Dancer", "Doomcryer", "Hierophant", "Battle Bard"));

	private final String _group;
	private final List<String> _builds;

	SimRole(String group, List<String> builds)
	{
		_group = group;
		_builds = builds;
	}

	/**
	 * @return what an admin types to pick this slot (//JoinParty 1 ranged): tank, melee, ranged, healer or buffer
	 */
	public String getGroup()
	{
		return _group;
	}

	/**
	 * @return the names of the builds (data/FakePlayerPvp.xml) that can fill this slot
	 */
	public List<String> getBuilds()
	{
		return _builds;
	}

	/**
	 * @return {@code true} for a member that fights from range
	 */
	public boolean isRanged()
	{
		return (this == ARCHER) || (this == NUKER);
	}
}
