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

import java.io.File;
import java.text.SimpleDateFormat;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.IXmlReader;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.NightCycleConfig;
import org.l2jmobius.gameserver.config.custom.TownLifeConfig;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.data.xml.MapRegionData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.TownLifeResident.Kind;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.clan.Clan;
import org.l2jmobius.gameserver.model.events.Containers;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.actor.creature.OnCreatureDeath;
import org.l2jmobius.gameserver.model.events.holders.actor.npc.OnNpcSpawn;
import org.l2jmobius.gameserver.model.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.model.hotzone.HotzoneModifier;
import org.l2jmobius.gameserver.model.nightcycle.NightPhase;
import org.l2jmobius.gameserver.model.olympiad.Hero;
import org.l2jmobius.gameserver.model.olympiad.Olympiad;
import org.l2jmobius.gameserver.model.siege.Castle;
import org.l2jmobius.gameserver.taskmanagers.GameTimeTaskManager;

/**
 * Town Life (TownLife.ini, data/TownLife.xml): makes the towns feel lived in.<br>
 * By day townsfolk stroll the streets and stop for a chat, porters carry between the warehouse and the shops, guards walk patrols, children play tag in the square and run up to players for a sweet, a crier shouts the news of the server, fishermen
 * and dock workers keep the harbors busy. At dusk ({@link NightCycleManager}) the children are called in first, then everyone walks into a house and is gone for the night, some retail npcs with nothing to sell included; the lamplighter makes his
 * round. At night watchmen call the hour and a crowd gathers at the tavern until the Witching Hour. At dawn they all come back out. One day a week is festival day: banners, a bonfire, more people, fireworks at dusk.<br>
 * The streets and npcs of each town come from the town itself (the geodata and the spawns, like the town fake players: {@link FakePlayerTown}), so a town needs no coordinates. Npcs only think while a player is around.
 */
public class TownLifeManager implements IXmlReader
{
	private static final Logger LOGGER = Logger.getLogger(TownLifeManager.class.getName());
	
	private static final int TICK = 500;
	/** Raid news is told for this long. */
	private static final long NEWS_LIFETIME = 3_600_000;
	
	/**
	 * The time of day as the town life sees it.
	 * @param phase the phase of the day/night cycle
	 * @param duskProgress how far into the dusk, 0-1 (1 outside of it)
	 * @param dawnProgress how far into the dawn, 0-1 (1 outside of it)
	 * @param gameHour the game hour, 0-23
	 */
	public record Clock(NightPhase phase, double duskProgress, double dawnProgress, int gameHour)
	{
	}
	
	/** A piece of news for the criers. */
	private record News(String text, long time)
	{
	}
	
	private final List<TownLifeTown> _towns = new ArrayList<>();
	private final Deque<News> _news = new ArrayDeque<>();
	/** What each town's crier last cried: the time of the newest news it told, and which of the other kinds of news is next. */
	private final Map<TownLifeTown, Long> _criedNews = new HashMap<>();
	private final Map<TownLifeTown, Integer> _cryTurn = new HashMap<>();
	private volatile Clock _clock = new Clock(NightPhase.DAY, 1, 1, 12);
	/** The phase the last clock reading was in, and since when (real time). */
	private NightPhase _phase;
	private long _phaseSince;
	/** A GM's festival day: {@code null} to follow the calendar. */
	private volatile Boolean _festivalOverride;
	
	protected TownLifeManager()
	{
		if (!TownLifeConfig.ENABLED)
		{
			return;
		}
		
		load();
		if (_towns.isEmpty())
		{
			return;
		}
		
		_clock = readClock();
		Containers.Monsters().addListener(new ConsumerEventListener(Containers.Monsters(), EventType.ON_NPC_SPAWN, (OnNpcSpawn event) -> onRaidSpawn(event.getNpc()), this));
		Containers.Monsters().addListener(new ConsumerEventListener(Containers.Monsters(), EventType.ON_CREATURE_DEATH, (OnCreatureDeath event) -> onRaidDeath(event), this));
		ThreadPool.scheduleAtFixedRate(this::tick, TICK, TICK);
		
		// The geodata work of every town, once, away from the server start (a town's npcs come out once it is done).
		ThreadPool.execute(() ->
		{
			final long start = System.currentTimeMillis();
			int residents = 0;
			for (TownLifeTown town : _towns)
			{
				try
				{
					town.prepare();
					residents += town.residents.size();
				}
				catch (Exception e)
				{
					LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not prepare " + town.name + ".", e);
				}
			}
			LOGGER.info(getClass().getSimpleName() + ": Prepared " + _towns.size() + " towns and harbors (" + residents + " npcs) in " + ((System.currentTimeMillis() - start) / 1000) + " seconds.");
		});
	}
	
	@Override
	public void load()
	{
		_towns.clear();
		parseDatapackFile("data/TownLife.xml");
		LOGGER.info(getClass().getSimpleName() + ": " + _towns.size() + " towns and harbors, their npcs come out once the town is prepared.");
	}
	
	@Override
	public void parseDocument(Document document, File file)
	{
		forEach(document, "list", listNode ->
		{
			forEach(listNode, "town", townNode -> parseTown(townNode));
			forEach(listNode, "harbor", harborNode ->
			{
				final NamedNodeMap attrs = harborNode.getAttributes();
				final TownLifeTown harbor = new TownLifeTown(parseString(attrs, "name"), null, parseInteger(attrs, "wharf"), 1, false, null, 0, null, Collections.emptyList());
				if (harbor.discover(null))
				{
					_towns.add(harbor);
				}
				else
				{
					LOGGER.warning(getClass().getSimpleName() + ": No wharf manager " + harbor.wharfId + " for " + harbor.name + ".");
				}
			});
		});
	}
	
	private void parseTown(Node townNode)
	{
		final NamedNodeMap attrs = townNode.getAttributes();
		final String region = parseString(attrs, "region");
		final List<Location> points = MapRegionData.getInstance().getSpawnLocsByRegionName(region);
		if (points.isEmpty())
		{
			LOGGER.warning(getClass().getSimpleName() + ": Unknown map region " + region + ".");
			return;
		}
		
		final String size = parseString(attrs, "size", "medium");
		final double sizeFactor = "small".equals(size) ? 0.5 : "large".equals(size) ? 1.5 : 1;
		final Location[] square = new Location[1];
		final Location[] tavern = new Location[1];
		final int[] tavernNpc = new int[1];
		final List<Integer> homebound = new ArrayList<>();
		forEach(townNode, "square", node -> square[0] = parseLocation(node));
		forEach(townNode, "tavern", node ->
		{
			final NamedNodeMap tavernAttrs = node.getAttributes();
			tavernNpc[0] = parseInteger(tavernAttrs, "npc", 0);
			if (tavernAttrs.getNamedItem("x") != null)
			{
				tavern[0] = parseLocation(node);
			}
		});
		forEach(townNode, "homebound", node -> homebound.add(parseInteger(node.getAttributes(), "npc")));
		
		final String name = parseString(attrs, "name", region);
		final TownLifeTown town = new TownLifeTown(name, region, 0, sizeFactor, parseBoolean(attrs, "children", false), square[0], tavernNpc[0], tavern[0], homebound);
		if (town.discover(points))
		{
			_towns.add(town);
		}
	}
	
	private Location parseLocation(Node node)
	{
		final NamedNodeMap attrs = node.getAttributes();
		return new Location(parseInteger(attrs, "x"), parseInteger(attrs, "y"), parseInteger(attrs, "z"));
	}
	
	private void tick()
	{
		try
		{
			final Clock clock = readClock();
			_clock = clock;
			final boolean festival = isFestival();
			final long now = System.currentTimeMillis();
			for (TownLifeTown town : _towns)
			{
				if (town.isReady())
				{
					town.update(clock, festival, now);
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": " + e.getMessage(), e);
		}
	}
	
	/**
	 * @return the time of day: the Night Cycle's phase (a GM-forced one too), or the clock's own if the Night Cycle is off
	 */
	private Clock readClock()
	{
		final int gameTime = Math.floorMod(GameTimeTaskManager.getInstance().getGameTime(), NightPhase.MINUTES_PER_DAY);
		final boolean nightCycle = NightCycleConfig.ENABLED;
		final NightPhase phase = nightCycle ? NightCycleManager.getInstance().getPhase() : NightPhase.at(gameTime);
		final long now = System.currentTimeMillis();
		if (phase != _phase)
		{
			_phase = phase;
			_phaseSince = now;
		}
		
		// How far into the dusk or the dawn: by the game clock, or for a phase a GM forced, by the real time since (a game minute is 10 real seconds).
		final boolean forced = nightCycle && NightCycleManager.getInstance().isForced();
		double dusk = 1;
		double dawn = 1;
		if ((phase == NightPhase.DUSK) && (NightCycleConfig.DUSK_MINUTES > 0))
		{
			dusk = forced ? (now - _phaseSince) / (NightCycleConfig.DUSK_MINUTES * 10_000.0) : (gameTime - (NightPhase.MINUTES_PER_DAY - NightCycleConfig.DUSK_MINUTES)) / (double) NightCycleConfig.DUSK_MINUTES;
		}
		else if ((phase == NightPhase.DAWN) && (NightCycleConfig.DAWN_MINUTES > 0))
		{
			dawn = forced ? (now - _phaseSince) / (NightCycleConfig.DAWN_MINUTES * 10_000.0) : (gameTime - NightPhase.NIGHT_END) / (double) NightCycleConfig.DAWN_MINUTES;
		}
		return new Clock(phase, Math.max(0, Math.min(1, dusk)), Math.max(0, Math.min(1, dawn)), gameTime / 60);
	}
	
	public Clock getClock()
	{
		return _clock;
	}
	
	/**
	 * @return {@code true} on festival day (the day of TownLifeFestivalDay, or as a GM set it)
	 */
	public boolean isFestival()
	{
		final Boolean override = _festivalOverride;
		if (override != null)
		{
			return override;
		}
		return TownLifeConfig.FESTIVAL && (LocalDate.now().getDayOfWeek() == TownLifeConfig.FESTIVAL_DAY);
	}
	
	/**
	 * @param festival {@code true} or {@code false} to make it festival day or not, {@code null} to follow the calendar again
	 */
	public void setFestivalOverride(Boolean festival)
	{
		_festivalOverride = festival;
	}
	
	// ------------------------------------------------------------------
	// News for the criers
	// ------------------------------------------------------------------
	
	private void onRaidSpawn(Npc npc)
	{
		if (npc.isRaid() && !npc.isRaidMinion() && !npc.isMinion() && (npc.getInstanceId() == 0))
		{
			addNews("Hear ye, hear ye! " + npc.getName() + " has risen again! Adventurers, beware!");
		}
	}
	
	private void onRaidDeath(OnCreatureDeath event)
	{
		final Creature target = event.getTarget();
		if ((target == null) || !target.isRaid() || target.isRaidMinion() || target.isMinion() || (target.getInstanceId() != 0))
		{
			return;
		}
		
		final Creature attacker = event.getAttacker();
		final Player killer = attacker != null ? attacker.asPlayer() : null;
		if ((killer != null) && !killer.isFakePlayer())
		{
			final String who = killer.isInParty() ? killer.getParty().getLeader().getName() + "'s party" : killer.getName();
			addNews("Hear ye, hear ye! " + target.getName() + " has been slain by " + who + "! Glory to the brave!");
		}
		else
		{
			addNews("Hear ye, hear ye! " + target.getName() + " has fallen!");
		}
	}
	
	private void addNews(String text)
	{
		synchronized (_news)
		{
			_news.addLast(new News(text, System.currentTimeMillis()));
			while (_news.size() > 20)
			{
				_news.removeFirst();
			}
		}
	}
	
	/**
	 * @param town a town
	 * @param clock the time of day
	 * @param festival {@code true} on festival day
	 * @return what its crier shouts next: news it hasn't told yet first, otherwise the next of the sieges, tonight's Omen, the heroes, the festival or a town notice
	 */
	String nextCry(TownLifeTown town, Clock clock, boolean festival)
	{
		final long now = System.currentTimeMillis();
		final long told = _criedNews.getOrDefault(town, 0L);
		synchronized (_news)
		{
			for (News news : _news)
			{
				if ((news.time() > told) && ((now - news.time()) < NEWS_LIFETIME))
				{
					_criedNews.put(town, news.time());
					return news.text();
				}
			}
		}
		
		final int turn = _cryTurn.merge(town, 1, Integer::sum);
		for (int i = 0; i < 5; i++)
		{
			final String line;
			switch ((turn + i) % 5)
			{
				case 0:
				{
					line = siegeNews();
					break;
				}
				case 1:
				{
					line = omenNews(clock);
					break;
				}
				case 2:
				{
					line = heroNews();
					break;
				}
				case 3:
				{
					line = festivalNews(town, festival);
					break;
				}
				default:
				{
					line = null;
					break;
				}
			}
			if (line != null)
			{
				return line;
			}
		}
		return TownLifeLines.fill(TownLifeLines.random(TownLifeLines.CRIER_FILLER), town.name, null);
	}
	
	/**
	 * @param town a town
	 * @return everything its crier could tell right now, for its dialog
	 */
	private List<String> currentNews(TownLifeTown town)
	{
		final List<String> lines = new ArrayList<>();
		final long now = System.currentTimeMillis();
		synchronized (_news)
		{
			for (News news : _news)
			{
				if ((now - news.time()) < NEWS_LIFETIME)
				{
					lines.add(news.text());
				}
			}
		}
		Collections.reverse(lines);
		for (String line : new String[]
		{
			siegeNews(),
			omenNews(_clock),
			heroNews(),
			festivalNews(town, isFestival())
		})
		{
			if (line != null)
			{
				lines.add(line);
			}
		}
		if (lines.isEmpty())
		{
			lines.add(TownLifeLines.fill(TownLifeLines.random(TownLifeLines.CRIER_FILLER), town.name, null));
		}
		return lines;
	}
	
	private static String siegeNews()
	{
		Castle next = null;
		final long now = System.currentTimeMillis();
		for (Castle castle : CastleManager.getInstance().getCastles())
		{
			final Calendar date = castle.getSiegeDate();
			if ((date != null) && (date.getTimeInMillis() > now) && ((date.getTimeInMillis() - now) < (7L * 24 * 3600 * 1000)) && ((next == null) || (date.getTimeInMillis() < next.getSiegeDate().getTimeInMillis())))
			{
				next = castle;
			}
		}
		if (next == null)
		{
			return null;
		}
		
		final String when = new SimpleDateFormat("EEEE 'at' HH:mm", Locale.ENGLISH).format(next.getSiegeDate().getTime());
		final Clan owner = next.getOwnerId() > 0 ? ClanTable.getInstance().getClan(next.getOwnerId()) : null;
		return "Hear ye! The siege of " + next.getName() + " Castle begins on " + when + "!" + (owner != null ? " Will clan " + owner.getName() + " hold it?" : " Who will claim it?");
	}
	
	private static String omenNews(Clock clock)
	{
		if (!NightCycleConfig.ENABLED || ((clock.phase() != NightPhase.DUSK) && !clock.phase().isNight()))
		{
			return null;
		}
		
		final HotzoneModifier omen = NightCycleManager.getInstance().getOmen();
		if (omen == null)
		{
			return clock.phase() == NightPhase.DUSK ? "Hear ye! Night is falling. Finish your business beyond the walls!" : null;
		}
		return "Hear ye! Tonight's omen is " + NightCycleManager.getOmenName(omen) + ". Take care beyond the walls!";
	}
	
	private static String heroNews()
	{
		final Map<Integer, StatSet> heroes = Hero.getInstance().getHeroes();
		if ((heroes == null) || heroes.isEmpty())
		{
			return null;
		}
		
		final List<StatSet> list = new ArrayList<>(heroes.values());
		final String hero = list.get(Rnd.get(list.size())).getString(Olympiad.CHAR_NAME, null);
		return hero != null ? "All hail " + hero + ", hero of the Grand Olympiad!" : null;
	}
	
	private static String festivalNews(TownLifeTown town, boolean festival)
	{
		if (!TownLifeConfig.FESTIVAL)
		{
			return null;
		}
		if (festival)
		{
			return "Hear ye, hear ye! Today is festival day in " + town.name + "! Fireworks over the square at dusk!";
		}
		final DayOfWeek tomorrow = LocalDate.now().getDayOfWeek().plus(1);
		return tomorrow == TownLifeConfig.FESTIVAL_DAY ? "Hear ye! Tomorrow is festival day in " + town.name + "! Don't miss the fireworks!" : null;
	}
	
	// ------------------------------------------------------------------
	// For the town life script (dialogs and sweets)
	// ------------------------------------------------------------------
	
	private TownLifeResident find(Npc npc)
	{
		if (npc == null)
		{
			return null;
		}
		
		for (TownLifeTown town : _towns)
		{
			if (!town.isReady())
			{
				continue;
			}
			for (TownLifeResident resident : town.residents)
			{
				if (resident.getNpc() == npc)
				{
					return resident;
				}
			}
		}
		return null;
	}
	
	/**
	 * @param npc an npc
	 * @return {@code true} if it is one of the town life children
	 */
	public boolean isChild(Npc npc)
	{
		final TownLifeResident resident = find(npc);
		return (resident != null) && (resident.kind == Kind.CHILD);
	}
	
	/**
	 * @param npc an npc
	 * @return {@code true} if it is a town crier
	 */
	public boolean isCrier(Npc npc)
	{
		final TownLifeResident resident = find(npc);
		return (resident != null) && (resident.kind == Kind.CRIER);
	}
	
	/**
	 * @param npc a child
	 * @param player a player
	 * @return {@code true} if the child ran up to that player for a sweet
	 */
	public boolean hasAsked(Npc npc, Player player)
	{
		final TownLifeResident resident = find(npc);
		return (resident != null) && (resident.askedPlayerId == player.getObjectId());
	}
	
	/**
	 * @param npc a town crier
	 * @return the news it tells
	 */
	public List<String> getNews(Npc npc)
	{
		final TownLifeResident resident = find(npc);
		return resident != null ? currentNews(resident.town) : Collections.emptyList();
	}
	
	/**
	 * @param npc a town life npc
	 * @param player the player talking to it
	 * @return what it says when talked to, {@code null} if it isn't a town life npc
	 */
	public String getTalk(Npc npc, Player player)
	{
		final TownLifeResident resident = find(npc);
		if (resident == null)
		{
			return null;
		}
		
		final String[] lines;
		switch (resident.kind)
		{
			case WANDERER:
			{
				lines = TownLifeLines.MUSINGS;
				break;
			}
			case PORTER:
			case ERRAND_RUNNER:
			{
				lines = TownLifeLines.WORKER;
				break;
			}
			case SWEEPER:
			{
				lines = TownLifeLines.SWEEPER;
				break;
			}
			case CHILD:
			{
				lines = TownLifeLines.CHILD_PLAY;
				break;
			}
			case PATROL:
			{
				lines = TownLifeLines.PATROL;
				break;
			}
			case LAMPLIGHTER:
			{
				lines = TownLifeLines.LAMPLIGHTER;
				break;
			}
			case NIGHT_WATCH:
			{
				lines = TownLifeLines.NIGHT_WATCH_WITCHING;
				break;
			}
			case TAVERN:
			{
				lines = TownLifeLines.TAVERN;
				break;
			}
			case FISHERMAN:
			{
				lines = TownLifeLines.FISHERMAN;
				break;
			}
			case DOCK_WORKER:
			{
				lines = TownLifeLines.DOCK_WORKER;
				break;
			}
			default:
			{
				lines = TownLifeLines.GREETINGS;
				break;
			}
		}
		return TownLifeLines.fill(TownLifeLines.random(lines), resident.town.name, player.getName());
	}
	
	/**
	 * A player gave a child a sweet: it thanks them and runs back to play.
	 * @param npc the child
	 * @param player the player
	 */
	public void onTreat(Npc npc, Player player)
	{
		final TownLifeResident resident = find(npc);
		if ((resident == null) || (resident.kind != Kind.CHILD))
		{
			return;
		}
		
		ThreadPool.execute(() ->
		{
			TownLifeResident.face(npc, player);
			npc.onRandomAnimation(Rnd.get(1, 3));
			resident.say(npc, "Yay! Thank you, %name%! You're the best!", player.getName());
			resident.town.onTreat(resident);
		});
	}
	
	/**
	 * @return what every town does right now, for //townlife
	 */
	public List<String> describe()
	{
		final List<String> lines = new ArrayList<>();
		final Clock clock = _clock;
		lines.add("Town Life: " + (TownLifeConfig.ENABLED ? "on" : "off") + ", " + clock.phase().getDisplayName() + (clock.phase() == NightPhase.DUSK ? String.format(" %.0f%%", clock.duskProgress() * 100) : clock.phase() == NightPhase.DAWN ? String.format(" %.0f%%", clock.dawnProgress() * 100) : "") + (isFestival() ? ", festival day" : "") + (_festivalOverride != null ? " (set by a GM)" : ""));
		for (TownLifeTown town : _towns)
		{
			lines.addAll(town.describe());
		}
		return lines;
	}
	
	/**
	 * @param npc a town life npc
	 * @return what it is doing, for //townlife on a target, {@code null} if it isn't one
	 */
	public String describe(Npc npc)
	{
		final TownLifeResident resident = find(npc);
		return resident != null ? resident.town.name + " " + resident.describe() : null;
	}
	
	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------
	
	/**
	 * @param npc an npc
	 * @return {@code true} if a player is around (movement uses the geodata only then, and nothing needs to look right when nobody watches)
	 */
	static boolean isWatched(Npc npc)
	{
		return (npc != null) && FakePlayerTown.isWatched(npc);
	}
	
	/**
	 * @return the point {@code from} can walk to in a straight line towards x, y (on the ground)
	 */
	static Location groundAt(int x, int y, int z, WorldObject from)
	{
		return GeoEngine.getInstance().getValidLocation(from.getX(), from.getY(), from.getZ(), x, y, z, 0);
	}
	
	public static TownLifeManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final TownLifeManager INSTANCE = new TownLifeManager();
	}
}
