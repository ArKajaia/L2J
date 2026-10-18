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
package org.l2jmobius.gameserver.model.dynamicquest.objective;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.l2jmobius.gameserver.data.xml.DynamicQuestData;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.dynamicquest.NoticeBoardSite;
import org.l2jmobius.gameserver.model.dynamicquest.ObjectiveTemplate;
import org.l2jmobius.gameserver.model.dynamicquest.QuestInstance;
import org.l2jmobius.gameserver.util.QuestSpawnUtil;

/**
 * REACH: get within {@code radius} of a place. {@code dest="TOWN_FAR"}: the Notice Board of another town {@code minDistance}-{@code maxDistance} away; {@code "TOWN_NEAR"}: the nearest other town's; {@code "HOME"}: the board the quest was taken at;
 * {@code "AREA"} or {@code "LAST"}: the quest's places.
 * <p>
 * With {@code noTeleport="true"} the owner must travel on foot: a teleport (more than 1500 in one second) fails it. Every {@code ambushEvery} seconds outside towns, {@code ambushCount} monsters of the land around jump the owner.
 * </p>
 * @author Mobius
 */
public class ReachObjective extends AbstractObjective
{
	private static final int TELEPORT_DISTANCE = 1500;

	private Location _destination;
	private Location _lastPosition;
	private int _nextAmbush;

	public ReachObjective(QuestInstance quest, ObjectiveTemplate template, int index)
	{
		super(quest, template, index);
	}

	@Override
	public String start(boolean restored)
	{
		final Player owner = _quest.getOwner();
		if (owner == null)
		{
			return "nobody to travel";
		}

		final String dest = _params.getString("dest", "TOWN_FAR").toUpperCase();
		switch (dest)
		{
			case "TOWN_FAR":
			case "TOWN_NEAR":
			{
				final NoticeBoardSite site = pickTown(owner, dest.equals("TOWN_FAR"));
				if (site == null)
				{
					return "there is nowhere to go";
				}
				_destination = site.getLocation();
				_quest.putContext("dest", site.getTown());
				break;
			}
			case "HOME":
			{
				final NoticeBoardSite site = DynamicQuestData.getInstance().getBoard(_quest.getContext("town", ""));
				if ((site == null) || (site.getLocation() == null))
				{
					return "there is no home to go back to";
				}
				_destination = site.getLocation();
				_quest.putContext("home", site.getTown());
				break;
			}
			default:
			{
				_destination = resolve(dest);
				break;
			}
		}
		_quest.putLocation("dest" + _index, _destination);
		_lastPosition = owner.getLocation();
		_nextAmbush = _params.getInt("ambushEvery", 0);
		_quest.setMarker(_destination);
		return null;
	}

	private NoticeBoardSite pickTown(Player owner, boolean far)
	{
		final String home = _quest.getContext("town", null);
		if (!far)
		{
			return DynamicQuestData.getInstance().getNearestBoard(owner, 2000);
		}

		final int minDistance = _params.getInt("minDistance", 8000);
		final int maxDistance = _params.getInt("maxDistance", 40000);
		final List<NoticeBoardSite> candidates = new ArrayList<>();
		NoticeBoardSite nearestAboveMin = null;
		for (NoticeBoardSite site : DynamicQuestData.getInstance().getBoards())
		{
			if ((site.getLocation() == null) || site.getTown().equals(home))
			{
				continue;
			}
			final double distance = owner.calculateDistance2D(site.getLocation());
			if ((distance >= minDistance) && (distance <= maxDistance))
			{
				candidates.add(site);
			}
			if ((distance >= minDistance) && ((nearestAboveMin == null) || (distance < owner.calculateDistance2D(nearestAboveMin.getLocation()))))
			{
				nearestAboveMin = site;
			}
		}
		if (candidates.isEmpty())
		{
			return nearestAboveMin;
		}
		final Random random = random("town");
		return candidates.get(random.nextInt(candidates.size()));
	}

	@Override
	public void onTick(int second, List<Player> near)
	{
		final Player owner = _quest.getOwner();
		if ((owner == null) || (_destination == null))
		{
			return;
		}

		if (_params.getBoolean("noTeleport", false))
		{
			if ((owner.getInstanceId() != 0) || ((_lastPosition != null) && (owner.calculateDistance2D(_lastPosition) > TELEPORT_DISTANCE)))
			{
				fail(text("teleportText", "You took a shortcut through the ether - the seal is broken."));
				return;
			}
			_lastPosition = owner.getLocation();
		}

		if (owner.calculateDistance2D(_destination) <= _params.getInt("radius", 400))
		{
			_quest.setMarker(null);
			complete();
			return;
		}

		final int every = _params.getInt("ambushEvery", 0);
		if ((every > 0) && (--_nextAmbush <= 0))
		{
			_nextAmbush = every;
			if (!QuestSpawnUtil.isTown(owner.getX(), owner.getY(), owner.getZ()) && !owner.isDead())
			{
				final Random random = random("ambush" + second);
				final int amount = count("ambushCount", 2);
				for (int i = 0; i < amount; i++)
				{
					_quest.spawnMonster(this, pickMonsterNear(owner, random), point(owner, 300, 500), _params.getInt("tierBonus", 0), title("ambushTitle"), owner);
				}
				_quest.announce(text("ambushText", "Ambush!"));
			}
		}
	}

	@Override
	public void cleanup()
	{
		// Ambushers already fighting stay.
	}

	@Override
	public String getProgress()
	{
		final Player owner = _quest.getOwner();
		if ((owner == null) || (_destination == null))
		{
			return "Travel";
		}
		return "Distance left: " + (int) owner.calculateDistance2D(_destination);
	}

	@Override
	protected String defaultDescription()
	{
		final String dest = _params.getString("dest", "TOWN_FAR").toUpperCase();
		if (dest.equals("HOME"))
		{
			return "Go back to the Notice Board of {town}.";
		}
		return dest.startsWith("TOWN") ? "Travel to {dest}" + (_params.getBoolean("noTeleport", false) ? " on foot - no teleports." : ".") : "Reach the place on your radar.";
	}
}
