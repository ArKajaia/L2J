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
package org.l2jmobius.gameserver.model.actor.instance;

import java.util.StringTokenizer;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;

public class UCHelper extends Folk
{
	private static final Location[][] LOCATIONS =
	{
		{
			new Location(-84451, -45452, -10728),
			new Location(-84580, -45587, -10728)
		},
		{
			new Location(-86154, -50429, -10728),
			new Location(-86118, -50624, -10728)
		},
		{
			new Location(-82009, -53652, -10728),
			new Location(-81802, -53665, -10728)
		},
		{
			new Location(-77603, -50673, -10728),
			new Location(-77586, -50503, -10728)
		},
		{
			new Location(-79186, -45644, -10728),
			new Location(-79309, -45561, -10728)
		}
	};
	
	public UCHelper(NpcTemplate template)
	{
		super(template);
	}
	
	@Override
	public void onBypassFeedback(Player player, String command)
	{
		final StringTokenizer token = new StringTokenizer(command, " ");
		final String actualCommand = token.nextToken();
		if (actualCommand.startsWith("coliseum"))
		{
			final int index = Integer.parseInt(token.nextToken());
			final Location[] locs = LOCATIONS[index];
			player.teleToLocation(locs[Rnd.get(locs.length)], true);
		}
		else
		{
			super.onBypassFeedback(player, command);
		}
	}
	
	@Override
	public String getHtmlPath(int npcId, int val)
	{
		String pom = "";
		
		if (val == 0)
		{
			pom = "" + npcId;
		}
		else
		{
			pom = npcId + "-" + val;
		}
		
		return "data/html/undergroundColiseum/" + pom + ".htm";
	}
}
