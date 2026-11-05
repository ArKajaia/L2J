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
package handlers.chat.commands.voiced;

import org.l2jmobius.gameserver.config.custom.MapDeviceConfig;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.managers.MapDeviceManager;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.mapdevice.MapRun;

import custom.MapDevice.MapDeviceWindow;

/**
 * {@code .hideout}: the player's hideout and its Map Device (see {@link MapDeviceManager}).
 * <ul>
 * <li>{@code .hideout} - go to your hideout from a peaceful place; in a hideout or a map, open the Map Device window.</li>
 * <li>{@code .hideout party} - visit the hideout of your party (its leader's first).</li>
 * <li>{@code .hideout back} - from a map, back to the hideout.</li>
 * <li>{@code .hideout leave} - from a hideout, back to where you came from.</li>
 * </ul>
 * The device window's buttons come here too ({@code bypass voiced_hideout ...}), so they work wherever the window was opened: {@code device}, {@code insert <tier>}, {@code medals <tier> <count>}, {@code open <tier> <count>}, {@code close [confirm]}. The manager checks every action.
 * @author Mobius
 */
public class HideoutVoiced implements IVoicedCommandHandler
{
	private static final String[] COMMANDS =
	{
		"hideout"
	};

	@Override
	public boolean onCommand(String command, Player player, String params)
	{
		if (player == null)
		{
			return false;
		}
		if (!MapDeviceConfig.ENABLED)
		{
			player.sendMessage("Hideouts and maps are disabled.");
			return true;
		}

		final MapDeviceManager manager = MapDeviceManager.getInstance();
		final String[] args = (params == null) || params.trim().isEmpty() ? new String[0] : params.trim().toLowerCase().split("\\s+");
		final String action = args.length > 0 ? args[0] : "";
		switch (action)
		{
			case "":
			{
				if (MapDeviceManager.isHideoutInstance(player.getInstanceId()) || MapDeviceManager.isMapInstance(player.getInstanceId()))
				{
					MapDeviceWindow.showDevice(player);
				}
				else
				{
					report(player, manager.enterHideout(player));
				}
				break;
			}
			case "party":
			{
				report(player, manager.visitPartyHideout(player));
				break;
			}
			case "back":
			{
				report(player, manager.returnToHideout(player));
				break;
			}
			case "leave":
			{
				report(player, manager.leaveHideout(player));
				break;
			}
			case "device":
			case "status":
			{
				MapDeviceWindow.showDevice(player);
				break;
			}
			case "insert":
			{
				final int tier = parse(args, 1);
				if (!checkOwner(player, manager))
				{
					break;
				}
				if (player.getInventory().getInventoryItemCount(tierItem(tier), -1) < 1)
				{
					player.sendMessage("You don't have that map.");
					MapDeviceWindow.showDevice(player);
					break;
				}
				MapDeviceWindow.showInsert(player, tier);
				break;
			}
			case "medals":
			{
				if (checkOwner(player, manager))
				{
					MapDeviceWindow.showConfirm(player, parse(args, 1), parse(args, 2));
				}
				break;
			}
			case "open":
			{
				final String denied = manager.openMap(player, parse(args, 1), parse(args, 2));
				if (denied != null)
				{
					player.sendMessage(denied);
				}
				MapDeviceWindow.showDevice(player);
				break;
			}
			case "close":
			{
				if (!checkOwner(player, manager))
				{
					break;
				}
				if ((args.length > 1) && args[1].equals("confirm"))
				{
					report(player, manager.abandonMap(player));
					MapDeviceWindow.showDevice(player);
				}
				else
				{
					final MapRun run = manager.getCurrentRun(player);
					if ((run == null) || run.isClosed())
					{
						player.sendMessage("No map is open.");
						MapDeviceWindow.showDevice(player);
					}
					else
					{
						MapDeviceWindow.showCloseConfirm(player);
					}
				}
				break;
			}
			default:
			{
				player.sendMessage("Usage: .hideout | .hideout party | .hideout back | .hideout leave");
				break;
			}
		}
		return true;
	}

	/**
	 * @return {@code true} if the player stands in their own hideout; tells them otherwise
	 */
	private static boolean checkOwner(Player player, MapDeviceManager manager)
	{
		if (manager.isDeviceOwner(player))
		{
			return true;
		}
		player.sendMessage("Only the owner of a hideout can work its Map Device, in the hideout.");
		return false;
	}

	private static int tierItem(int tier)
	{
		final org.l2jmobius.gameserver.model.mapdevice.MapTier mapTier = org.l2jmobius.gameserver.data.xml.MapDeviceData.getInstance().getTier(tier);
		return mapTier != null ? mapTier.itemId() : -1;
	}

	private static int parse(String[] args, int index)
	{
		if (args.length <= index)
		{
			return -1;
		}
		try
		{
			return Integer.parseInt(args[index]);
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	private static void report(Player player, String denied)
	{
		if (denied != null)
		{
			player.sendMessage(denied);
		}
	}

	@Override
	public String[] getCommandList()
	{
		return COMMANDS;
	}

	@Override
	public boolean useVoicedCommand(String command, Player player, String params)
	{
		return onCommand(command, player, params);
	}

	@Override
	public String[] getVoicedCommandList()
	{
		return COMMANDS;
	}
}
