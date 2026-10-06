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

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.cache.HtmCache;
import org.l2jmobius.gameserver.managers.HiddenQuestManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.hiddenquest.HiddenQuestSession;
import org.l2jmobius.gameserver.model.hiddenquest.Landmark;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;
import org.l2jmobius.gameserver.network.serverpackets.TutorialShowQuestionMark;

/**
 * RIDDLE: solve {@code riddles} riddles picked from the quest's landmarks, each pointing to a famous place. Standing within {@code radius} of the place solves it and an apparition ({@code apparitionId}) appears. After {@code hintAfter} seconds on one riddle the radar marks the place. The question mark shows the current riddle again.
 * @author Mobius
 */
public class RiddleTask extends AbstractHiddenTask
{
	private static final String HTML = "data/scripts/custom/HiddenQuests/riddle.html";

	private final List<Landmark> _riddles = new ArrayList<>();
	private int _index;
	private int _riddleStart;
	private boolean _hinted;

	public RiddleTask(HiddenQuestSession session)
	{
		super(session);
	}

	@Override
	public String start(Player player)
	{
		final List<Landmark> pool = new ArrayList<>(_definition.getLandmarks());
		if (pool.isEmpty())
		{
			return "the map is blank";
		}
		Collections.shuffle(pool);
		_riddles.addAll(pool.subList(0, Math.min(pool.size(), Math.max(1, _params.getInt("riddles", 3)))));
		showRiddle(player);
		return null;
	}

	private void showRiddle(Player player)
	{
		final Landmark landmark = _riddles.get(_index);
		String html = HtmCache.getInstance().getHtm(player, HTML);
		if (html == null)
		{
			html = "<html><body>%riddle%<br>%hint%</body></html>";
		}
		final NpcHtmlMessage message = new NpcHtmlMessage(0, html);
		message.replace("%quest%", _definition.getName());
		message.replace("%number%", String.valueOf(_index + 1));
		message.replace("%total%", String.valueOf(_riddles.size()));
		message.replace("%riddle%", landmark.getRiddle());
		message.replace("%hint%", _hinted ? landmark.getHint() : "");
		player.sendPacket(message);
		player.sendPacket(new TutorialShowQuestionMark(HiddenQuestManager.QUESTION_MARK_ID));
		screen(player, "Riddle " + (_index + 1) + "/" + _riddles.size() + " - " + text("reread", "click the question mark to read it again."));
	}

	@Override
	protected void onTick(Player player, int second)
	{
		final Landmark landmark = _riddles.get(_index);
		final Location location = landmark.getLocation();
		if ((player.calculateDistance2D(location) <= _params.getInt("radius", 700)) && (Math.abs(player.getZ() - location.getZ()) < 1000))
		{
			solved(player, second);
			return;
		}

		if (!_hinted && ((second - _riddleStart) >= _params.getInt("hintAfter", 600)))
		{
			_hinted = true;
			setMarker(player, location);
			announce(player, text("hint", "A faint mark appears on your map...") + " " + landmark.getHint());
		}
	}

	private void solved(Player player, int second)
	{
		setMarker(player, null);
		final Npc apparition = spawnNpc(_params.getInt("apparitionId", 0), randomPoint(player, 60, 120, true), 0);
		if (apparition != null)
		{
			say(apparition, text("solved", "You found it. Just as I drew it, long ago."));
			ThreadPool.schedule(() ->
			{
				if (apparition.isSpawned())
				{
					apparition.deleteMe();
				}
			}, 20000);
		}

		_index++;
		_hinted = false;
		_riddleStart = second;
		if (_index >= _riddles.size())
		{
			complete();
			return;
		}
		announce(player, text("next", "The map shifts. Another riddle appears."));
		showRiddle(player);
	}

	@Override
	protected void onQuestionMark(Player player)
	{
		showRiddle(player);
	}

	@Override
	public String getProgress()
	{
		return "Riddle " + Math.min(_index + 1, _riddles.size()) + "/" + _riddles.size() + (_riddles.isEmpty() ? "" : " (" + _riddles.get(Math.min(_index, _riddles.size() - 1)).getName() + ")");
	}
}
