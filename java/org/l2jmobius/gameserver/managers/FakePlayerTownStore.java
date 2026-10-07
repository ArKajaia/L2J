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
package org.l2jmobius.gameserver.managers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.npc.DropType;
import org.l2jmobius.gameserver.model.actor.holders.npc.DropGroupHolder;
import org.l2jmobius.gameserver.model.actor.holders.npc.DropHolder;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.itemcontainer.Inventory;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.holders.RequestTrade;
import org.l2jmobius.gameserver.network.holders.TradeItem;

/**
 * The private store of a town fake player (see {@link FakePlayerTownVisitor}): either the loot of a hunt (the best of what a number of kills of normal monsters of its level dropped, rolled with their drop lists) or 1 to 3 kinds of crafting materials, 1 to 50 of each, the better ones the higher its level, and now and then a rare find, each at 1.5 to 5 times its reference price (a rare find at least at its own price range, for the ones whose reference price is low).<br>
 * Players buy from it like from a player's store; the items are created when bought. Used from the network threads (players buying) and the town manager's thread, so everything is synchronized.
 */
final class FakePlayerTownStore
{
	/** A material: the levels of the sellers that have it (the materials of their hunting grounds) and how many they put up at most. */
	private record Goods(int itemId, int minLevel, int maxLevel, int maxCount, String label)
	{
	}

	/** A rare find: the levels of the sellers that have it, how many, the least it goes for and how players call it. */
	private record Rare(int itemId, int minLevel, int maxLevel, int minCount, int maxCount, long minPrice, long maxPrice, String label)
	{
	}

	/** Max length of a private store message (SetPrivateStoreMsgSell). */
	private static final int MAX_MESSAGE = 29;
	/** How far apart (spawn to spawn) the monsters of one hunting ground are at most. */
	private static final int HUNTING_GROUND_RANGE = 6000;

	// @formatter:off
	private static final Goods[] GOODS =
	{
		// Basic materials, from the first hunting grounds on.
		new Goods(1864, 1, 30, 50, "stem"),
		new Goods(1865, 1, 30, 50, "varnish"),
		new Goods(1866, 1, 30, 50, "suede"),
		new Goods(1867, 1, 30, 50, "animal skin"),
		new Goods(1868, 1, 30, 50, "thread"),
		new Goods(1869, 1, 30, 50, "iron ore"),
		new Goods(1870, 1, 30, 50, "coal"),
		new Goods(1871, 1, 30, 50, "charcoal"),
		new Goods(1872, 1, 30, 50, "animal bone"),
		new Goods(1873, 5, 35, 50, "silver nugget"),
		new Goods(1785, 1, 40, 50, "soul ore"),
		new Goods(3031, 1, 40, 50, "spirit ore"),
		// D grade.
		new Goods(1878, 18, 45, 50, "braided hemp"),
		new Goods(1879, 18, 45, 50, "cokes"),
		new Goods(1880, 18, 45, 50, "steel"),
		new Goods(1881, 18, 45, 50, "cbp"),
		new Goods(1882, 18, 45, 50, "leather"),
		new Goods(1884, 18, 45, 50, "cord"),
		new Goods(1895, 18, 45, 50, "metallic fiber"),
		new Goods(1876, 20, 50, 50, "mithril ore"),
		new Goods(1875, 20, 55, 50, "stone of purity"),
		new Goods(1885, 20, 50, 40, "hg suede"),
		new Goods(1883, 22, 50, 20, "steel mold"),
		new Goods(1458, 20, 45, 50, "crystal d"),
		new Goods(2130, 20, 45, 50, "gemstone d"),
		// C and B grade.
		new Goods(1874, 38, 65, 50, "ori ore"),
		new Goods(1877, 38, 65, 50, "adamantite"),
		new Goods(1888, 38, 62, 40, "synth cokes"),
		new Goods(1889, 38, 62, 40, "compound braid"),
		new Goods(1887, 38, 62, 30, "varnish of purity"),
		new Goods(1894, 38, 62, 40, "crafted leather"),
		new Goods(5549, 40, 65, 40, "metallic thread"),
		new Goods(4039, 40, 70, 40, "mold glue"),
		new Goods(4042, 40, 80, 40, "enria"),
		new Goods(4043, 40, 80, 50, "asofe"),
		new Goods(4044, 40, 80, 50, "thons"),
		new Goods(1886, 40, 65, 20, "silver mold"),
		new Goods(1890, 40, 65, 20, "mithril alloy"),
		new Goods(1459, 40, 60, 50, "crystal c"),
		new Goods(2131, 40, 60, 50, "gemstone c"),
		// B and A grade.
		new Goods(1893, 55, 80, 30, "oriharukon"),
		new Goods(4040, 55, 80, 30, "mold lubricant"),
		new Goods(4041, 58, 85, 30, "mold hardener"),
		new Goods(5550, 58, 85, 30, "durable metal plate"),
		new Goods(1460, 52, 70, 50, "crystal b"),
		new Goods(1461, 61, 78, 50, "crystal a"),
		new Goods(2132, 52, 70, 40, "gemstone b"),
		new Goods(2133, 61, 80, 30, "gemstone a"),
		new Goods(1891, 58, 80, 10, "artisan frame"),
		new Goods(1892, 58, 80, 10, "blacksmith frame"),
		new Goods(4046, 61, 85, 5, "maestro anvil lock"),
		new Goods(4048, 61, 85, 5, "maestro mold"),
		// S grade.
		new Goods(9630, 74, 85, 30, "orichalcum"),
		new Goods(9629, 74, 85, 30, "adamantine"),
		new Goods(9628, 74, 85, 30, "leonard"),
		new Goods(1462, 74, 85, 50, "crystal s"),
		new Goods(2134, 74, 85, 20, "gemstone s"),
		new Goods(4045, 72, 85, 5, "maestro holder"),
		new Goods(4047, 74, 85, 3, "craftsman mold"),
		new Goods(5551, 74, 85, 5, "reorin mold"),
		new Goods(5552, 76, 85, 5, "warsmith mold"),
		new Goods(5553, 76, 85, 3, "arcsmith anvil"),
		new Goods(5554, 76, 85, 3, "warsmith holder"),
	};

	private static final Rare[] RARE =
	{
		new Rare(955, 1, 39, 1, 3, 80_000, 150_000, "ewd"),
		new Rare(6575, 15, 45, 1, 1, 1_200_000, 2_000_000, "bewd"),
		new Rare(951, 20, 51, 1, 3, 180_000, 300_000, "ewc"),
		new Rare(6573, 30, 60, 1, 1, 4_000_000, 6_000_000, "bewc"),
		new Rare(947, 40, 75, 1, 2, 800_000, 1_200_000, "ewb"),
		new Rare(6571, 45, 75, 1, 1, 9_000_000, 14_000_000, "bewb"),
		new Rare(729, 55, 85, 1, 2, 2_500_000, 4_000_000, "ewa"),
		new Rare(6569, 60, 85, 1, 1, 20_000_000, 30_000_000, "bewa"),
		new Rare(959, 76, 85, 1, 2, 7_000_000, 10_000_000, "ews"),
		new Rare(6577, 76, 85, 1, 1, 40_000_000, 60_000_000, "bews"),
		new Rare(960, 76, 85, 1, 3, 700_000, 1_000_000, "eas"),
		new Rare(6578, 76, 85, 1, 1, 6_000_000, 9_000_000, "beas"),
		new Rare(8745, 45, 65, 1, 1, 400_000, 600_000, "high ls 52"),
		new Rare(8758, 55, 75, 1, 1, 3_000_000, 4_500_000, "top ls 61"),
		new Rare(8752, 70, 85, 1, 1, 1_300_000, 2_000_000, "high ls 76"),
		new Rare(8762, 72, 85, 1, 1, 6_500_000, 9_000_000, "top ls 76"),
		new Rare(9576, 78, 85, 1, 1, 8_000_000, 11_000_000, "top ls 80"),
		new Rare(10486, 80, 85, 1, 1, 9_500_000, 13_000_000, "top ls 82"),
		new Rare(9546, 76, 85, 1, 10, 50_000, 120_000, "fire stone"),
		new Rare(9547, 76, 85, 1, 10, 50_000, 120_000, "water stone"),
		new Rare(9548, 76, 85, 1, 10, 50_000, 120_000, "earth stone"),
		new Rare(9549, 76, 85, 1, 10, 50_000, 120_000, "wind stone"),
		new Rare(9550, 76, 85, 1, 10, 50_000, 120_000, "dark stone"),
		new Rare(9551, 76, 85, 1, 10, 50_000, 120_000, "holy stone"),
		new Rare(9552, 80, 85, 1, 2, 800_000, 1_500_000, "fire crystal"),
		new Rare(9555, 80, 85, 1, 2, 800_000, 1_500_000, "wind crystal"),
		new Rare(9556, 80, 85, 1, 2, 800_000, 1_500_000, "dark crystal"),
		new Rare(6622, 70, 85, 1, 3, 2_000_000, 3_500_000, "codex"),
		new Rare(9627, 76, 85, 1, 1, 9_000_000, 14_000_000, "codex mastery"),
	};
	// @formatter:on

	private static final String[] MATERIAL_MESSAGES =
	{
		"wts mats",
		"mats",
		"cheap mats",
		"materials",
		"selling mats",
		"S> {a}",
		"{a}",
		"{a} cheap",
		"wts {a}",
		"{a} / {b}",
		"S> {a}, {b}",
		"{a} {b} {c}",
		"mats for craft",
		"selling stuff",
		"spoil sale",
		"wh cleaning",
		"everything must go"
	};
	/**
	 * What a black market seller fences at night (see {@link #createBlackMarket(int)}): Sealed Caches and the night lures fishermen don't sell.<br>
	 * A cache always costs more than what is inside sells to a shop for on average (Common about 306,000, Rare 977,000, Epic 3,150,000 Adena: its Gold Dragons at 100 each plus its materials), so buying caches to sell their contents never pays.
	 */
	private static final Rare[] FENCED =
	{
		new Rare(6492, 20, 85, 1, 5, 350_000, 500_000, "sealed cache"),
		new Rare(6499, 30, 85, 1, 3, 1_100_000, 1_600_000, "rare cache"),
		new Rare(6509, 52, 85, 1, 1, 3_500_000, 5_000_000, "epic cache"),
		new Rare(8505, 1, 85, 20, 100, 300, 600, "night lure"),
		new Rare(8508, 1, 85, 20, 100, 300, 600, "night lure"),
		new Rare(8511, 1, 85, 20, 100, 300, 600, "night lure"),
		new Rare(8507, 20, 85, 10, 50, 900, 1_600, "hg night lure"),
		new Rare(8510, 20, 85, 10, 50, 1_000, 1_800, "hg night lure"),
		new Rare(8513, 20, 85, 10, 50, 1_000, 1_800, "hg night lure"),
	};

	private static final String[] BLACK_MARKET_MESSAGES =
	{
		"psst.. {r}",
		"{r} no questions",
		"{r} - dont ask",
		"night deals",
		"{r} cheap tonight",
		"fell off a cart",
		"before dawn: {r}",
		"shh.. {r}",
		"{r} / {f}",
		"black market",
		"{f}, {r}",
		"moonlight sale"
	};

	private static final String[] RARE_MESSAGES =
	{
		"!!! {r} !!!",
		"{r}",
		"S> {r}",
		"wts {r}",
		"{r} + mats",
		"{r} inside",
		"rare stuff",
		"{r} / {a}",
		"--- {r} ---"
	};
	private static final String[] LOOT_MESSAGES =
	{
		"wts drops",
		"drops",
		"farm loot",
		"selling my loot",
		"after farm",
		"{a}",
		"S> {a}",
		"wts {a}",
		"{a} cheap",
		"{a} / {b}",
		"S> {a}, {b}",
		"{m} drops",
		"loot from {m}",
		"{m} farm"
	};

	private final List<TradeItem> _items = new ArrayList<>();
	private final String _message;
	private final String _headline;
	private final boolean _rare;
	private final boolean _blackMarket;
	private boolean _released;

	private FakePlayerTownStore(List<TradeItem> items, String message, String headline, boolean rare)
	{
		this(items, message, headline, rare, false);
	}

	private FakePlayerTownStore(List<TradeItem> items, String message, String headline, boolean rare, boolean blackMarket)
	{
		_items.addAll(items);
		_message = message;
		_headline = headline;
		_rare = rare;
		_blackMarket = blackMarket;
	}

	/**
	 * Puts a store together for a seller of that level.
	 * @param level the seller's level
	 * @param rareChance the chance (in %) a materials store also sells a rare find
	 * @param lootChance the chance (in %) it sells the loot of a hunt instead of materials
	 * @param kills how many monsters that hunt was
	 * @return the store, {@code null} if nothing could be put in it
	 */
	static FakePlayerTownStore create(int level, int rareChance, int lootChance, int kills)
	{
		if (Rnd.get(100) < lootChance)
		{
			final FakePlayerTownStore loot = createLoot(level, kills);
			if (loot != null)
			{
				return loot;
			}
		}
		
		return createMaterials(level, rareChance);
	}
	
	/**
	 * A black market store, open at night only (see {@link NightCycleManager#getBlackMarketStores()}): a rare find of the seller's level well below its usual price - nobody asks where it came from - and fenced goods (Sealed Caches, night lures).
	 * @param level the seller's level
	 * @return the store, {@code null} if nothing could be put in it
	 */
	static FakePlayerTownStore createBlackMarket(int level)
	{
		final List<TradeItem> items = new ArrayList<>();

		// The rare find, at 55-85% of the least it usually goes for.
		String rareLabel = null;
		final List<Rare> rares = new ArrayList<>();
		for (Rare rare : RARE)
		{
			if ((level >= rare.minLevel()) && (level <= rare.maxLevel()))
			{
				rares.add(rare);
			}
		}
		if (!rares.isEmpty())
		{
			final Rare rare = rares.get(Rnd.get(rares.size()));
			final ItemTemplate template = ItemData.getInstance().getTemplate(rare.itemId());
			if (template != null)
			{
				items.add(newItem(template, Rnd.get(rare.minCount(), rare.maxCount()), roundPrice(rare.minPrice() * (0.55 + (Rnd.nextDouble() * 0.3)))));
				rareLabel = rare.label();
			}
		}

		// One or two kinds of fenced goods.
		final List<Rare> fenced = new ArrayList<>();
		for (Rare goods : FENCED)
		{
			if ((level >= goods.minLevel()) && (level <= goods.maxLevel()))
			{
				fenced.add(goods);
			}
		}
		Collections.shuffle(fenced);
		String fencedLabel = null;
		final int kinds = Rnd.get(100) < 60 ? 1 : 2;
		for (int i = 0; (i < kinds) && (i < fenced.size()); i++)
		{
			final Rare goods = fenced.get(i);
			final ItemTemplate template = ItemData.getInstance().getTemplate(goods.itemId());
			if (template != null)
			{
				items.add(newItem(template, Rnd.get(goods.minCount(), goods.maxCount()), roundPrice(goods.minPrice() + (Rnd.nextDouble() * (goods.maxPrice() - goods.minPrice())))));
				if (fencedLabel == null)
				{
					fencedLabel = goods.label();
				}
			}
		}

		if (items.isEmpty())
		{
			return null;
		}

		final String headline = rareLabel != null ? rareLabel : fencedLabel;
		return new FakePlayerTownStore(items, blackMarketMessage(rareLabel != null ? rareLabel : fencedLabel, fencedLabel != null ? fencedLabel : rareLabel), headline, rareLabel != null, true);
	}

	/**
	 * A store of materials, now and then with a rare find.
	 * @param level the seller's level
	 * @param rareChance the chance (in %) it also sells a rare find
	 * @return the store, {@code null} if nothing could be put in it
	 */
	private static FakePlayerTownStore createMaterials(int level, int rareChance)
	{
		// Materials of its own hunting grounds mostly, sometimes leftovers of the ones it outgrew.
		final List<Goods> fitting = new ArrayList<>();
		final List<Goods> lower = new ArrayList<>();
		for (Goods goods : GOODS)
		{
			if ((level >= goods.minLevel()) && (level <= goods.maxLevel()))
			{
				fitting.add(goods);
			}
			else if (goods.maxLevel() < level)
			{
				lower.add(goods);
			}
		}

		final List<TradeItem> items = new ArrayList<>();
		final List<String> labels = new ArrayList<>();
		final int kinds = Rnd.get(100) < 45 ? 1 : Rnd.get(100) < 70 ? 2 : 3;
		Collections.shuffle(fitting);
		Collections.shuffle(lower);
		for (int i = 0; i < kinds; i++)
		{
			final List<Goods> from = (!lower.isEmpty() && ((fitting.isEmpty()) || (Rnd.get(100) < 25))) ? lower : fitting;
			if (from.isEmpty())
			{
				break;
			}

			final Goods goods = from.remove(0);
			final ItemTemplate template = ItemData.getInstance().getTemplate(goods.itemId());
			if (template == null)
			{
				continue;
			}

			// A few of the expensive ones, a stack of the cheap ones.
			final int count = Math.max(1, Math.min(goods.maxCount(), (int) Math.round(Rnd.get(1, goods.maxCount()) * (0.4 + (Rnd.nextDouble() * 0.6)))));
			final long price = Math.max(price(template), roundPrice(Math.max(10, template.getReferencePrice()) * (0.7 + (Rnd.nextDouble() * 0.9))));
			items.add(newItem(template, count, price));
			labels.add(goods.label());
		}

		// A rare find.
		String rareLabel = null;
		if (Rnd.get(100) < rareChance)
		{
			final List<Rare> rares = new ArrayList<>();
			for (Rare rare : RARE)
			{
				if ((level >= rare.minLevel()) && (level <= rare.maxLevel()))
				{
					rares.add(rare);
				}
			}

			if (!rares.isEmpty())
			{
				final Rare rare = rares.get(Rnd.get(rares.size()));
				final ItemTemplate template = ItemData.getInstance().getTemplate(rare.itemId());
				if (template != null)
				{
					items.add(Rnd.get(items.size() + 1), newItem(template, Rnd.get(rare.minCount(), rare.maxCount()), Math.max(price(template), roundPrice(rare.minPrice() + (Rnd.nextDouble() * (rare.maxPrice() - rare.minPrice()))))));
					rareLabel = rare.label();
				}
			}
		}

		if (items.isEmpty())
		{
			return null;
		}
		final String headline = rareLabel != null ? rareLabel : labels.isEmpty() ? "mats" : labels.size() == 1 ? labels.get(0) : labels.get(0) + " and " + labels.get(1);
		return new FakePlayerTownStore(items, message(labels, rareLabel), headline, rareLabel != null);
	}

	/**
	 * A store of the loot of a hunt: the seller killed that many normal monsters of its level, of 1 to 3 kinds that live close to each other, and puts up the best of what they dropped.
	 * @param level the seller's level
	 * @param kills how many monsters it killed
	 * @return the store, {@code null} if there is no hunting ground for that level or nothing worth selling dropped
	 */
	private static FakePlayerTownStore createLoot(int level, int kills)
	{
		final List<NpcTemplate> ground = huntingGround(level);
		if (ground.isEmpty())
		{
			return null;
		}
		
		final Map<Integer, Long> loot = new HashMap<>();
		for (int i = 0; i < kills; i++)
		{
			rollDrops(ground.get(Rnd.get(ground.size())), loot);
		}
		
		// What is worth selling, the most valuable first.
		final List<ItemTemplate> drops = new ArrayList<>();
		for (int itemId : loot.keySet())
		{
			final ItemTemplate template = ItemData.getInstance().getTemplate(itemId);
			if ((template != null) && (itemId != Inventory.ADENA_ID) && !template.hasExImmediateEffect() && !template.isQuestItem() && template.isTradeable() && (template.getReferencePrice() > 0))
			{
				drops.add(template);
			}
		}
		if (drops.isEmpty())
		{
			return null;
		}
		drops.sort((a, b) -> Double.compare((double) b.getReferencePrice() * loot.get(b.getId()), (double) a.getReferencePrice() * loot.get(a.getId())));
		
		// The best few: a stack each, an entry each for what doesn't stack (like a player's store).
		final int kinds = Rnd.get(2, 4);
		final List<TradeItem> items = new ArrayList<>();
		final List<String> labels = new ArrayList<>();
		boolean rare = false;
		for (ItemTemplate template : drops)
		{
			if (items.size() >= kinds)
			{
				break;
			}
			
			final long count = loot.get(template.getId());
			if (template.isStackable())
			{
				items.add(newItem(template, count, price(template)));
			}
			else
			{
				for (long i = Math.min(count, kinds - items.size()); i > 0; i--)
				{
					items.add(newItem(template, 1, price(template)));
				}
				rare = true;
			}
			labels.add(template.getName().toLowerCase());
		}
		
		final String mob = ground.get(0).getName().toLowerCase();
		return new FakePlayerTownStore(items, lootMessage(labels, mob), labels.get(0), rare);
	}
	
	/**
	 * @param level the seller's level
	 * @return 1 to 3 kinds of normal monsters of about that level, with drops, spawned close to each other; empty if there are none
	 */
	private static List<NpcTemplate> huntingGround(int level)
	{
		List<NpcTemplate> monsters = Collections.emptyList();
		for (int gap = 1; (gap <= 5) && monsters.isEmpty(); gap += 2)
		{
			final int range = gap;
			monsters = NpcData.getInstance().getTemplates(template -> template.isType("Monster") && (Math.abs(template.getLevel() - level) <= range) && ((template.getDropGroups() != null) || (template.getDropList() != null)) && (SpawnTable.getInstance().getSpawnCount(template.getId()) > 0));
		}
		if (monsters.isEmpty())
		{
			return monsters;
		}
		
		Collections.shuffle(monsters);
		final List<NpcTemplate> ground = new ArrayList<>();
		final NpcTemplate first = monsters.get(0);
		ground.add(first);
		
		// Its neighbours (spawns in a territory have no fixed point: then it hunts that one alone).
		final Spawn spawn = SpawnTable.getInstance().getAnySpawn(first.getId());
		if ((spawn != null) && ((spawn.getX() != 0) || (spawn.getY() != 0)))
		{
			final int wanted = Rnd.get(1, 3);
			for (int i = 1; (i < monsters.size()) && (ground.size() < wanted); i++)
			{
				final Spawn other = SpawnTable.getInstance().getAnySpawn(monsters.get(i).getId());
				if ((other != null) && ((other.getX() != 0) || (other.getY() != 0)) && (Math.hypot(other.getX() - spawn.getX(), other.getY() - spawn.getY()) < HUNTING_GROUND_RANGE))
				{
					ground.add(monsters.get(i));
				}
			}
		}
		return ground;
	}
	
	/**
	 * Rolls one kill of that monster, the way {@link NpcTemplate#calculateDrops} does for a killer of its level (server drop rates, no champion, premium or other bonus).
	 * @param template the monster
	 * @param loot what dropped so far, by item id, added to
	 */
	private static void rollDrops(NpcTemplate template, Map<Integer, Long> loot)
	{
		final List<DropGroupHolder> groups = template.getDropGroups();
		if (groups != null)
		{
			for (DropGroupHolder group : groups)
			{
				// On x1 one item at most per group (the chances add up to 100), with other rates each item on its own.
				double totalChance = 0;
				for (DropHolder drop : group.getDropList())
				{
					final double rate = chanceRate(drop.getItemId());
					totalChance = rate == 1 ? totalChance + drop.getChance() : drop.getChance();
					if ((Rnd.nextDouble() * 100) < (totalChance * (group.getChance() / 100) * rate))
					{
						addDrop(drop, loot);
						if (rate == 1)
						{
							break;
						}
					}
				}
			}
		}
		
		final List<DropHolder> drops = template.getDropList();
		if (drops != null)
		{
			for (DropHolder drop : drops)
			{
				if ((drop.getDropType() == DropType.DROP) && ((Rnd.nextDouble() * 100) < (drop.getChance() * chanceRate(drop.getItemId()))))
				{
					addDrop(drop, loot);
				}
			}
		}
	}
	
	private static void addDrop(DropHolder drop, Map<Integer, Long> loot)
	{
		final long count = (long) (Rnd.get(drop.getMin(), drop.getMax()) * amountRate(drop.getItemId()));
		if (count > 0)
		{
			loot.merge(drop.getItemId(), count, Long::sum);
		}
	}
	
	/**
	 * @param itemId an item
	 * @return the server's drop chance multiplier for it
	 */
	private static double chanceRate(int itemId)
	{
		final Float byId = RatesConfig.RATE_DROP_CHANCE_BY_ID.get(itemId);
		if (byId != null)
		{
			return byId;
		}
		
		final ItemTemplate template = ItemData.getInstance().getTemplate(itemId);
		return (template != null) && template.hasExImmediateEffect() ? RatesConfig.RATE_HERB_DROP_CHANCE_MULTIPLIER : RatesConfig.RATE_DEATH_DROP_CHANCE_MULTIPLIER;
	}
	
	/**
	 * @param itemId an item
	 * @return the server's drop amount multiplier for it
	 */
	private static double amountRate(int itemId)
	{
		final Float byId = RatesConfig.RATE_DROP_AMOUNT_BY_ID.get(itemId);
		if (byId != null)
		{
			return byId;
		}
		
		final ItemTemplate template = ItemData.getInstance().getTemplate(itemId);
		return (template != null) && template.hasExImmediateEffect() ? RatesConfig.RATE_HERB_DROP_AMOUNT_MULTIPLIER : RatesConfig.RATE_DEATH_DROP_AMOUNT_MULTIPLIER;
	}

	private static TradeItem newItem(ItemTemplate template, long count, long price)
	{
		final TradeItem item = new TradeItem(template, count, price);
		item.setObjectId(IdManager.getInstance().getNextId());
		return item;
	}

	/**
	 * @param template an item
	 * @return what a seller asks for it: 1.5 to 5 times its reference price
	 */
	private static long price(ItemTemplate template)
	{
		return roundPrice(Math.max(10, template.getReferencePrice()) * (1.5 + (Rnd.nextDouble() * 3.5)));
	}
	
	/**
	 * @param price a price
	 * @return the price the way players set it: two significant digits (1.2k, 35k, 4.5kk...)
	 */
	private static long roundPrice(double price)
	{
		if (price < 100)
		{
			return Math.max(1, Math.round(price));
		}

		final long step = (long) Math.pow(10, Math.floor(Math.log10(price)) - 1);
		return Math.max(step, Math.round(price / step) * step);
	}

	private static String message(List<String> labels, String rareLabel)
	{
		final String a = labels.isEmpty() ? "mats" : labels.get(0);
		final String b = labels.size() > 1 ? labels.get(1) : a;
		final String c = labels.size() > 2 ? labels.get(2) : "";
		for (int attempt = 0; attempt < 6; attempt++)
		{
			String text;
			if (rareLabel != null)
			{
				text = RARE_MESSAGES[Rnd.get(RARE_MESSAGES.length)].replace("{r}", rareLabel);
			}
			else
			{
				text = MATERIAL_MESSAGES[Rnd.get(MATERIAL_MESSAGES.length)];
				if ((text.contains("{b}") && (labels.size() < 2)) || (text.contains("{c}") && (labels.size() < 3)))
				{
					continue;
				}
			}

			text = text.replace("{a}", a).replace("{b}", b).replace("{c}", c).trim();
			if (Rnd.get(100) < 35)
			{
				text = text.toUpperCase();
			}
			if (text.length() <= MAX_MESSAGE)
			{
				return text;
			}
		}
		return rareLabel != null ? "rare stuff" : "mats";
	}

	private static String blackMarketMessage(String rareLabel, String fencedLabel)
	{
		for (int attempt = 0; attempt < 6; attempt++)
		{
			String text = BLACK_MARKET_MESSAGES[Rnd.get(BLACK_MARKET_MESSAGES.length)].replace("{r}", rareLabel).replace("{f}", fencedLabel).trim();
			if (Rnd.get(100) < 25)
			{
				text = text.toUpperCase();
			}
			if (text.length() <= MAX_MESSAGE)
			{
				return text;
			}
		}
		return "night deals";
	}

	private static String lootMessage(List<String> labels, String mob)
	{
		final String a = labels.get(0);
		final String b = labels.size() > 1 ? labels.get(1) : a;
		for (int attempt = 0; attempt < 6; attempt++)
		{
			String text = LOOT_MESSAGES[Rnd.get(LOOT_MESSAGES.length)];
			if (text.contains("{b}") && (labels.size() < 2))
			{
				continue;
			}
			
			text = text.replace("{a}", a).replace("{b}", b).replace("{m}", mob).trim();
			if (Rnd.get(100) < 35)
			{
				text = text.toUpperCase();
			}
			if (text.length() <= MAX_MESSAGE)
			{
				return text;
			}
		}
		return "drops";
	}

	/**
	 * @return the store message
	 */
	String getMessage()
	{
		return _message;
	}

	/**
	 * @return what it mainly sells, the way players call it (to advertise it)
	 */
	String getHeadline()
	{
		return _headline;
	}
	
	/**
	 * @return {@code true} if it sells a rare find
	 */
	boolean hasRare()
	{
		return _rare;
	}

	/**
	 * @return {@code true} for a black market store, which packs up at dawn
	 */
	boolean isBlackMarket()
	{
		return _blackMarket;
	}

	/**
	 * @return what is left to buy, for the sell list
	 */
	synchronized List<TradeItem> getItems()
	{
		return new ArrayList<>(_items);
	}

	/**
	 * @return {@code true} if everything was sold
	 */
	synchronized boolean isEmpty()
	{
		return _items.isEmpty();
	}

	/**
	 * @return a few words about the stock, for //faketown
	 */
	synchronized String describe()
	{
		final StringBuilder sb = new StringBuilder();
		for (TradeItem item : _items)
		{
			if (sb.length() > 0)
			{
				sb.append(", ");
			}
			sb.append(item.getCount()).append(' ').append(item.getItem().getName()).append(" at ").append(item.getPrice());
		}
		return sb.toString();
	}

	/**
	 * A player buys from the store, like {@code TradeList.privateStoreBuy}.
	 * @param player the buyer
	 * @param requests what it buys (object id, count, price as shown)
	 * @param seller the fake player
	 * @return {@code true} if it bought something
	 */
	synchronized boolean buy(Player player, Set<RequestTrade> requests, WorldObject seller)
	{
		if (_released)
		{
			return false;
		}

		long total = 0;
		long weight = 0;
		int slots = 0;
		final List<long[]> bought = new ArrayList<>(); // Index in the list, count.
		for (RequestTrade request : requests)
		{
			int index = -1;
			for (int i = 0; i < _items.size(); i++)
			{
				if (_items.get(i).getObjectId() == request.getObjectId())
				{
					index = i;
					break;
				}
			}

			// Sold out in the meantime, or the price shown isn't the price.
			final TradeItem item = index >= 0 ? _items.get(index) : null;
			if ((item == null) || (item.getPrice() != request.getPrice()))
			{
				continue;
			}

			final long count = Math.min(item.getCount(), request.getCount());
			if ((count <= 0) || ((PlayerConfig.MAX_ADENA / count) < item.getPrice()))
			{
				return false;
			}

			total += count * item.getPrice();
			if ((total > PlayerConfig.MAX_ADENA) || (total < 0))
			{
				return false;
			}

			weight += count * item.getItem().getWeight();
			if (!item.getItem().isStackable())
			{
				slots += (int) count;
			}
			else if (player.getInventory().getItemByItemId(item.getItem().getId()) == null)
			{
				slots++;
			}
			bought.add(new long[]
			{
				index,
				count
			});
		}

		if (bought.isEmpty())
		{
			return false;
		}

		if (total > player.getAdena())
		{
			player.sendPacket(SystemMessageId.YOU_DO_NOT_HAVE_ENOUGH_ADENA);
			return false;
		}

		if (!player.getInventory().validateWeight(weight))
		{
			player.sendPacket(SystemMessageId.YOU_HAVE_EXCEEDED_THE_WEIGHT_LIMIT);
			return false;
		}

		if (!player.getInventory().validateCapacity(slots))
		{
			player.sendPacket(SystemMessageId.YOUR_INVENTORY_IS_FULL);
			return false;
		}

		if (!player.reduceAdena(ItemProcessType.BUY, total, seller, true))
		{
			return false;
		}

		final List<TradeItem> soldOut = new ArrayList<>();
		for (long[] entry : bought)
		{
			final TradeItem item = _items.get((int) entry[0]);
			player.addItem(ItemProcessType.BUY, item.getItem().getId(), entry[1], seller, true);
			item.setCount(item.getCount() - entry[1]);
			if (item.getCount() <= 0)
			{
				soldOut.add(item);
			}
		}

		for (TradeItem item : soldOut)
		{
			_items.remove(item);
			IdManager.getInstance().releaseId(item.getObjectId());
		}
		return true;
	}

	/**
	 * Another fake player buys a bit.
	 * @return {@code true} if it bought something
	 */
	synchronized boolean sellToFake()
	{
		if (_released || _items.isEmpty())
		{
			return false;
		}

		final TradeItem item = _items.get(Rnd.get(_items.size()));
		final long count = Math.max(1, Math.min(item.getCount(), Rnd.get(1, (int) Math.max(1, item.getCount() / 2))));
		item.setCount(item.getCount() - count);
		if (item.getCount() <= 0)
		{
			_items.remove(item);
			IdManager.getInstance().releaseId(item.getObjectId());
		}
		return true;
	}

	/**
	 * The store is closed for good: frees the object ids of what is left.
	 */
	synchronized void release()
	{
		if (_released)
		{
			return;
		}

		_released = true;
		for (TradeItem item : _items)
		{
			IdManager.getInstance().releaseId(item.getObjectId());
		}
		_items.clear();
	}
}
