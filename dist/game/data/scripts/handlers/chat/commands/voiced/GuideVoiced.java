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
package handlers.chat.commands.voiced;

import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.managers.AdventurersGuideManager;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.guide.GuideLesson;

/**
 * .guide: the Adventurer's Guidebook in the tutorial window, every lesson the character has unlocked. {@code .guide <lesson>} opens one lesson (for example {@code .guide arena}), {@code .guide off} and {@code .guide on} turn the tutorial pop-ups off and on.
 */
public class GuideVoiced implements IVoicedCommandHandler
{
	private static final String[] COMMANDS =
	{
		"guide"
	};

	@Override
	public boolean useVoicedCommand(String command, Player player, String params)
	{
		if (player == null)
		{
			return false;
		}

		final AdventurersGuideManager manager = AdventurersGuideManager.getInstance();
		if ((params != null) && (params.trim().equalsIgnoreCase("on") || params.trim().equalsIgnoreCase("off")))
		{
			manager.setTipsEnabled(player, params.trim().equalsIgnoreCase("on"));
			return true;
		}

		final GuideLesson lesson = (params != null) && !params.isEmpty() ? GuideLesson.find(params.trim().replace(' ', '_')) : null;
		if ((lesson != null) && lesson.isEnabled() && manager.isUnlocked(player, lesson))
		{
			manager.showLesson(player, lesson);
		}
		else
		{
			manager.showBook(player);
		}
		return true;
	}

	@Override
	public String[] getVoicedCommandList()
	{
		return COMMANDS;
	}

	@Override
	public boolean onCommand(String command, Player player, String params)
	{
		return useVoicedCommand(command, player, params);
	}

	@Override
	public String[] getCommandList()
	{
		return COMMANDS;
	}
}
