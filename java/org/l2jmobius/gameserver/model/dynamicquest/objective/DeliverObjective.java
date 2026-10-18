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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.l2jmobius.gameserver.data.xml.DynamicQuestData;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.dynamicquest.ObjectiveTemplate;
import org.l2jmobius.gameserver.model.dynamicquest.QuestInstance;
import org.l2jmobius.gameserver.model.dynamicquest.RewardEntry;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;

/**
 * DELIVER: hand real items over at a Notice Board. They come from the item table {@code table} ({@code kinds} of its entries for the quest's level, their counts scaled by the tier) or from {@code items} ("id:count,id:count"). The items are taken from the inventory.
 * @author Mobius
 */
public class DeliverObjective extends AbstractObjective
{
	private final Map<Integer, Long> _items = new LinkedHashMap<>();

	public DeliverObjective(QuestInstance quest, ObjectiveTemplate template, int index)
	{
		super(quest, template, index);
	}

	@Override
	public String start(boolean restored)
	{
		final String list = _params.getString("items", null);
		if (list != null)
		{
			for (String pair : list.split(","))
			{
				final String[] split = pair.trim().split(":");
				if (split.length == 2)
				{
					_items.put(parseInt(split[0], 0), (long) parseInt(split[1], 1));
				}
			}
		}
		else
		{
			final List<RewardEntry> eligible = new ArrayList<>();
			final List<RewardEntry> table = DynamicQuestData.getInstance().getItemTable(_params.getString("table", ""));
			if (table != null)
			{
				for (RewardEntry entry : table)
				{
					if (entry.isFor(_quest.getDifficulty().getQuestLevel()))
					{
						eligible.add(entry);
					}
				}
			}
			final Random random = random("deliver");
			final int kinds = Math.min(eligible.size(), _params.getInt("kinds", 2));
			for (int i = 0; i < kinds; i++)
			{
				final RewardEntry entry = eligible.remove(random.nextInt(eligible.size()));
				_items.put(entry.getId(), Math.max(1, Math.round(entry.getCount() * _quest.getDifficulty().getTier().getCountMultiplier())));
			}
		}
		_items.keySet().removeIf(id -> ItemData.getInstance().getTemplate(id) == null);
		if (_items.isEmpty())
		{
			return "nothing to deliver";
		}
		_quest.putContext("goods", describeItems(null));
		return null;
	}

	private String describeItems(Player player)
	{
		final StringBuilder sb = new StringBuilder();
		for (Map.Entry<Integer, Long> entry : _items.entrySet())
		{
			final ItemTemplate item = ItemData.getInstance().getTemplate(entry.getKey());
			if (sb.length() > 0)
			{
				sb.append(", ");
			}
			if (player != null)
			{
				sb.append(item.getName()).append(' ').append(Math.min(entry.getValue(), player.getInventory().getInventoryItemCount(entry.getKey(), -1))).append('/').append(entry.getValue());
			}
			else
			{
				sb.append(entry.getValue()).append(' ').append(item.getName());
			}
		}
		return sb.toString();
	}

	/**
	 * @param player a player
	 * @return {@code true} if the player carries everything
	 */
	public boolean hasAll(Player player)
	{
		for (Map.Entry<Integer, Long> entry : _items.entrySet())
		{
			if (player.getInventory().getInventoryItemCount(entry.getKey(), -1) < entry.getValue())
			{
				return false;
			}
		}
		return true;
	}

	@Override
	public String onAction(Player player, Npc npc, String action)
	{
		if (!action.equals("deliver"))
		{
			return null;
		}
		if (player.getObjectId() != _quest.getOwnerId())
		{
			return "Only " + _quest.getOwnerName() + " can hand this over.";
		}
		if ((npc == null) || !DynamicQuestData.getInstance().isNoticeBoard(npc.getId()))
		{
			return "Bring it to a Notice Board.";
		}
		if (!hasAll(player))
		{
			return "You are still missing some of it: " + describeItems(player) + ".";
		}
		for (Map.Entry<Integer, Long> entry : _items.entrySet())
		{
			if (!player.destroyItemByItemId(ItemProcessType.QUEST, entry.getKey(), entry.getValue(), npc, true))
			{
				return "You are still missing some of it.";
			}
		}
		complete();
		return null;
	}

	/**
	 * @return {@code true} if it waits for a hand-over at a board
	 */
	public boolean isDeliverable()
	{
		return !isDone();
	}

	@Override
	public String getProgress()
	{
		final Player owner = _quest.getOwner();
		return "Bring: " + describeItems(owner);
	}

	@Override
	protected String defaultDescription()
	{
		return "Bring {goods} to a Notice Board.";
	}
}
