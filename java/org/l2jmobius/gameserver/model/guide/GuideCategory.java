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
package org.l2jmobius.gameserver.model.guide;

/**
 * The chapters of the Adventurer's Guidebook, in the order it shows them.
 */
public enum GuideCategory
{
	BASICS("Getting Started"),
	CLASS("Class Transfers"),
	QUESTS("Quests"),
	MONSTERS("Monsters"),
	REWARDS("Buffs and Rewards"),
	WORLD("The World");

	private final String _title;

	GuideCategory(String title)
	{
		_title = title;
	}

	public String getTitle()
	{
		return _title;
	}
}
