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

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.dynamicquest.ObjectiveTemplate;
import org.l2jmobius.gameserver.model.dynamicquest.QuestInstance;

/**
 * INTERACT: talk to {@code npc}, spawned {@code spawnMin}-{@code spawnMax} from {@code at}. Its dialog is {@code dialog} and its button {@code button}; it says {@code line} when the talk is done. Where it stands becomes the quest's LAST place.
 * @author Mobius
 */
public class InteractObjective extends AbstractObjective
{
	private Npc _npc;

	public InteractObjective(QuestInstance quest, ObjectiveTemplate template, int index)
	{
		super(quest, template, index);
	}

	@Override
	public String start(boolean restored)
	{
		Location location = restored ? _quest.getLocation("talk" + _index) : null;
		if (location == null)
		{
			location = point(resolve(_params.getString("at", "AREA")), _params.getInt("spawnMin", 0), _params.getInt("spawnMax", 200));
		}
		_npc = _quest.spawn(this, _params.getInt("npc", 0), location, 0);
		if (_npc == null)
		{
			return "nobody to talk to";
		}
		_quest.putLocation("talk" + _index, location);
		_quest.putLocation("last", location);
		_quest.putContext("npc" + _index, _npc.getName());
		_quest.setMarker(location);
		return null;
	}

	@Override
	public String onTalk(Player player, Npc npc)
	{
		if (npc != _npc)
		{
			return null;
		}
		return "<font color=\"LEVEL\">" + npc.getName() + "</font><br>" + text("dialog", "Ah, you came. Good.") + "<br><br><center>" + button("talk", text("button", "I am ready")) + "</center>";
	}

	@Override
	public String onAction(Player player, Npc npc, String action)
	{
		if (action.equals("talk") && (npc == _npc) && !isDone())
		{
			_quest.say(_npc, text("line", "Then let us begin."));
			complete();
		}
		return null;
	}

	@Override
	public void cleanup()
	{
		// The npc stays a little, so it doesn't vanish mid-sentence.
		final Npc npc = _npc;
		_npc = null;
		if (npc != null)
		{
			ThreadPool.schedule(() -> _quest.despawn(npc), 20000);
		}
	}

	@Override
	public String getProgress()
	{
		return isDone() ? "Spoken to {npc" + _index + "}" : "Talk to {npc" + _index + "}";
	}

	@Override
	protected String defaultDescription()
	{
		return "Find {npc" + _index + "} at the place on your radar.";
	}
}
