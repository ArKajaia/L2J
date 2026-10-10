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
package handlers.chat.commands.admin;

import java.util.StringTokenizer;

import org.l2jmobius.gameserver.config.custom.InfusedMonsterConfig;
import org.l2jmobius.gameserver.config.custom.ResonantMonsterConfig;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.managers.InfusedMonsterManager;
import org.l2jmobius.gameserver.managers.ResonantMonsterManager;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.item.holders.Elementals;

/**
 * GM tools of the Infused and Resonant monsters (see {@link InfusedMonsterManager}, {@link ResonantMonsterManager}). They work on the targeted monster and skip the spawn chance and the eligibility rules, for testing.
 * <ul>
 * <li>{@code //monstermod} - what the targeted monster is.</li>
 * <li>{@code //monstermod infused [fire|water|wind|earth|holy|dark]} - infuses it, with a random element if none is given.</li>
 * <li>{@code //monstermod resonant} - makes it Resonant.</li>
 * <li>{@code //monstermod clear} - makes it an ordinary monster again.</li>
 * </ul>
 */
public class AdminMonsterMod implements IAdminCommandHandler
{
	private static final String[] ADMIN_COMMANDS =
	{
		"admin_monstermod"
	};

	@Override
	public boolean onCommand(String command, Player activeChar)
	{
		final StringTokenizer st = new StringTokenizer(command);
		st.nextToken();
		final String action = st.hasMoreTokens() ? st.nextToken().toLowerCase() : "status";

		final WorldObject target = activeChar.getTarget();
		if ((target == null) || !target.isMonster() || target.asMonster().isFakePlayer())
		{
			activeChar.sendSysMessage("Target a monster first.");
			return false;
		}

		final Monster monster = target.asMonster();
		if (monster.isDead())
		{
			activeChar.sendSysMessage(monster.getName() + " is dead.");
			return false;
		}

		switch (action)
		{
			case "status":
			{
				showStatus(activeChar, monster);
				return true;
			}
			case "infused":
			{
				if (!InfusedMonsterConfig.ENABLED)
				{
					activeChar.sendSysMessage("Infused monsters are disabled (InfusedEnabled in InfusedMonsters.ini).");
					return false;
				}

				byte element = Elementals.NONE;
				if (st.hasMoreTokens())
				{
					final String name = st.nextToken();
					element = Elementals.getElementId(name);
					if ((element < 0) || (element >= InfusedMonsterConfig.ELEMENT_COUNT))
					{
						activeChar.sendSysMessage("Unknown element " + name + ": fire, water, wind, earth, holy or dark.");
						return false;
					}
				}
				else
				{
					element = InfusedMonsterManager.pickElement(monster);
					if (element == Elementals.NONE)
					{
						element = Elementals.FIRE;
					}
				}

				monster.stopResonant();
				monster.startInfused(element);
				monster.setCurrentHp(monster.getMaxHp());
				monster.broadcastInfo();
				activeChar.sendSysMessage(monster.getName() + " is infused with " + Elementals.getElementName(element) + ".");
				return true;
			}
			case "resonant":
			{
				if (!ResonantMonsterConfig.ENABLED)
				{
					activeChar.sendSysMessage("Resonant monsters are disabled (ResonantEnabled in ResonantMonsters.ini).");
					return false;
				}

				monster.stopInfused();
				monster.startResonant();
				monster.setCurrentHp(monster.getMaxHp());
				monster.broadcastInfo();
				final int cap = ResonantMonsterManager.getStageCap(monster.getLevel());
				activeChar.sendSysMessage(monster.getName() + " is Resonant: " + (cap >= 0 ? "soul crystals of stage " + cap + " or lower grow." : "no soul crystal grows at its level."));
				return true;
			}
			case "clear":
			{
				monster.stopInfused();
				monster.stopResonant();
				monster.setCurrentHp(Math.min(monster.getCurrentHp(), monster.getMaxHp()));
				monster.broadcastInfo();
				activeChar.sendSysMessage(monster.getName() + " is an ordinary monster again.");
				return true;
			}
			default:
			{
				activeChar.sendSysMessage("Usage: //monstermod [status|infused [fire|water|wind|earth|holy|dark]|resonant|clear]");
				return false;
			}
		}
	}

	private void showStatus(Player activeChar, Monster monster)
	{
		final StringBuilder sb = new StringBuilder(monster.getName()).append(" (level ").append(monster.getLevel()).append("):");
		if (monster.isInfused())
		{
			sb.append(" Infused with ").append(Elementals.getElementName(monster.getInfusedElement())).append('.');
		}
		if (monster.isResonant())
		{
			final int cap = ResonantMonsterManager.getStageCap(monster.getLevel());
			sb.append(" Resonant, soul crystals of stage ").append(cap).append(" or lower grow.");
		}
		if (!monster.isInfused() && !monster.isResonant())
		{
			sb.append(" neither Infused nor Resonant.");
		}
		activeChar.sendSysMessage(sb.toString());
	}

	@Override
	public String[] getCommandList()
	{
		return ADMIN_COMMANDS;
	}
}
