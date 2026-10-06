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
package org.l2jmobius.gameserver.model.hiddenquest.task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.hiddenquest.HiddenQuestSession;

/**
 * PILGRIMAGE: walk to a chain of shrines in the wild and pray (sit) at each. A shrine may be guarded (role {@code site}): its guardians rise when the player comes near and must be laid to rest before the prayer counts.
 * <ul>
 * <li>{@code sites} - number of shrines (3)</li>
 * <li>{@code siteNpcId} - the shrine NPC</li>
 * <li>{@code siteMin}/{@code siteMax} - distance between shrines (1200/2400)</li>
 * <li>{@code prayTime} - seconds of prayer per shrine (30)</li>
 * <li>{@code vowNoPvp} - fails if the player flags or gains karma (false)</li>
 * </ul>
 * @author Mobius
 */
public class PilgrimageTask extends AbstractHiddenTask
{
	private static final int PRAY_RADIUS = 200;
	private static final int GUARD_RADIUS = 600;

	private final List<Location> _sites = new ArrayList<>();
	private List<Npc> _guardians = Collections.emptyList();
	private Npc _shrine;
	private int _index;
	private int _prayed;
	private boolean _guarded;
	private int _startKarma;

	public PilgrimageTask(HiddenQuestSession session)
	{
		super(session);
	}

	@Override
	public String start(Player player)
	{
		final int count = Math.max(1, _params.getInt("sites", 3));
		Location previous = player.getLocation();
		for (int i = 0; i < count; i++)
		{
			previous = randomPoint(previous, _params.getInt("siteMin", 1200), _params.getInt("siteMax", 2400), false);
			_sites.add(previous);
		}

		_startKarma = player.getKarma();
		if (!spawnShrine(player))
		{
			return "the shrine could not be raised";
		}
		announce(player, text("start", "Find the first shrine. Your radar shows the way."));
		return null;
	}

	private boolean spawnShrine(Player player)
	{
		final Location site = _sites.get(_index);
		_shrine = spawnNpc(_params.getInt("siteNpcId", 0), site, 0);
		_guardians = Collections.emptyList();
		_guarded = false;
		_prayed = 0;
		setMarker(player, site);
		return _shrine != null;
	}

	@Override
	protected void onTick(Player player, int second)
	{
		if (_params.getBoolean("vowNoPvp", false) && ((player.getPvpFlag() != 0) || (player.getKarma() > _startKarma)))
		{
			fail(text("failVow", "You raised your hand against another. The vow is broken."));
			return;
		}

		final Location site = _sites.get(_index);
		final double distance = player.calculateDistance2D(site);
		if (!_guarded && (distance <= GUARD_RADIUS) && (_definition.getRole("site") != null))
		{
			_guarded = true;
			_guardians = spawnRole("site", site, 150, 350, player, 0);
			say(_shrine, text("siteGuarded", "Something stirs around the shrine..."));
		}

		if (distance > PRAY_RADIUS)
		{
			_prayed = 0;
			return;
		}

		if (countAlive(_guardians) > 0)
		{
			_prayed = 0;
			if ((second % 5) == 0)
			{
				screen(player, text("siteBusy", "No prayer is heard while they stir. Lay them to rest."));
			}
			return;
		}

		if (!player.isSitting())
		{
			if (_prayed > 0)
			{
				screen(player, text("prayerBroken", "Your prayer is broken. Sit down to begin again."));
				_prayed = 0;
			}
			else if ((second % 10) == 0)
			{
				screen(player, text("sitDown", "Sit down at the shrine to pray."));
			}
			return;
		}

		_prayed++;
		final int prayTime = Math.max(5, _params.getInt("prayTime", 30));
		if (_prayed == 1)
		{
			screen(player, text("praying", "You begin to pray..."));
		}
		else if ((_prayed % 10) == 0)
		{
			screen(player, "Praying... " + _prayed + " / " + prayTime);
		}

		if (_prayed >= prayTime)
		{
			say(_shrine, text("siteDone", "The flame takes your prayer."));
			_shrine.deleteMe();
			_index++;
			if (_index >= _sites.size())
			{
				complete();
				return;
			}

			spawnShrine(player);
			announce(player, text("next", "Another shrine waits. Follow your radar.") + " (" + (_index + 1) + "/" + _sites.size() + ")");
		}
	}

	@Override
	public String getProgress()
	{
		return "Shrine " + Math.min(_index + 1, _sites.size()) + "/" + _sites.size() + (_prayed > 0 ? ", praying " + _prayed + "s" : "");
	}
}
