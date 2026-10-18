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

import java.util.List;
import java.util.Random;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.dynamicquest.ObjectiveTemplate;
import org.l2jmobius.gameserver.model.dynamicquest.QuestInstance;
import org.l2jmobius.gameserver.util.QuestSpawnUtil;

/**
 * INVESTIGATE: find {@code clues} clues ({@code clueNpc}) one after the other. The first is {@code firstMin}-{@code firstMax} from {@code at}, each next one {@code spacingMin}-{@code spacingMax} from the last.
 * A clue is searched by standing (or sitting) next to it for {@code searchTime} seconds; what is found is a line of the text pool {@code lines}, and with {@code ambushChance} percent {@code ambushCount} monsters jump the searcher. Where the last clue lay becomes the quest's LAST place.
 * With {@code stealth="true"} it fails as soon as a participant is drawn into a fight ({@code seenText}).
 * @author Mobius
 */
public class InvestigateObjective extends AbstractObjective
{
	private static final int SEARCH_RANGE = 200;

	private Npc _clue;
	private int _found;
	private int _progress;
	private int _total;

	public InvestigateObjective(QuestInstance quest, ObjectiveTemplate template, int index)
	{
		super(quest, template, index);
	}

	@Override
	public String start(boolean restored)
	{
		_total = Math.max(1, _params.getInt("clues", 2));
		_quest.putContext("clues" + _index, String.valueOf(_total));
		final Location saved = restored ? _quest.getLocation("clue" + _index) : null;
		if (!spawnClue(saved))
		{
			return "the trail could not be found";
		}
		return null;
	}

	private boolean spawnClue(Location saved)
	{
		Location location = saved;
		if (location == null)
		{
			if (_found == 0)
			{
				location = point(resolve(_params.getString("at", "AREA")), _params.getInt("firstMin", 0), _params.getInt("firstMax", 600));
			}
			else
			{
				final Location last = _quest.getLocation("clue" + _index);
				location = point(last != null ? last : resolve("AREA"), _params.getInt("spacingMin", 1000), _params.getInt("spacingMax", 1800));
			}
		}
		_quest.putLocation("clue" + _index, location);
		_clue = _quest.spawn(this, _params.getInt("clueNpc", 0), location, 0);
		if (_clue == null)
		{
			return false;
		}
		_progress = 0;
		_quest.setMarker(location);
		return true;
	}

	@Override
	public String onTalk(Player player, Npc npc)
	{
		if (npc != _clue)
		{
			return null;
		}
		return "<font color=\"LEVEL\">" + npc.getName() + "</font><br>" + text("clueText", "Something happened here. Stay close and look around - sit down if you like.") + "<br><br>Clue " + (_found + 1) + " of " + _total + ".";
	}

	@Override
	public void onTick(int second, List<Player> near)
	{
		if (_params.getBoolean("stealth", false))
		{
			for (Player player : near)
			{
				if (player.isInCombat())
				{
					fail(text("seenText", "You were seen. The scouting is ruined."));
					return;
				}
			}
		}
		if (!QuestSpawnUtil.isAlive(_clue))
		{
			return;
		}

		Player searcher = null;
		for (Player player : near)
		{
			if ((player.calculateDistance2D(_clue) <= SEARCH_RANGE) && !player.isDead() && !player.isInCombat())
			{
				searcher = player;
				break;
			}
		}
		if (searcher == null)
		{
			return;
		}

		// Sitting down searches twice as fast.
		_progress += searcher.isSitting() ? 2 : 1;
		final int searchTime = _params.getInt("searchTime", 8);
		if (_progress < searchTime)
		{
			if ((second % 2) == 0)
			{
				QuestSpawnUtil.screen(searcher, "Searching... " + Math.min(100, (_progress * 100) / searchTime) + "%");
			}
			return;
		}

		final Location where = _clue.getLocation();
		_quest.despawn(_clue);
		_clue = null;
		_found++;
		_quest.setDirty(true);
		_quest.putLocation("last", where);

		final Random random = random("line" + _found);
		final String line = _quest.format(pickText(_params.getString("lines", null), random, "You find a trace and follow it."));
		searcher.sendMessage(_quest.getName() + ": " + line);
		_quest.screen(line);

		if (Rnd.get(100) < _params.getInt("ambushChance", 30))
		{
			final int amount = count("ambushCount", 2);
			for (int i = 0; i < amount; i++)
			{
				_quest.spawnMonster(this, pickMonsterNear(where, random), point(where, 300, 500), 0, title("ambushTitle"), searcher);
			}
			_quest.announce(text("ambushText", "It was a trap!"));
		}

		if (_found >= _total)
		{
			_quest.setMarker(null);
			complete();
			return;
		}
		if (!spawnClue(null))
		{
			fail("The trail went cold.");
		}
	}

	@Override
	public void cleanup()
	{
		// Ambushers stay and fight; only the clue goes.
		_quest.despawn(_clue);
		_clue = null;
	}

	@Override
	public String getProgress()
	{
		return "Clues: " + Math.min(_found, _total) + "/" + _total;
	}

	@Override
	protected String defaultDescription()
	{
		return "Follow the trail near {near}: search the places on your radar.";
	}

	@Override
	public String saveState()
	{
		return String.valueOf(_found);
	}

	@Override
	public void loadState(String state)
	{
		_found = parseInt(state, 0);
	}
}
