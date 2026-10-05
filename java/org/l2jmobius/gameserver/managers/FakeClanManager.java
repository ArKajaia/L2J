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

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.FeatureConfig;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.ServerConfig;
import org.l2jmobius.gameserver.config.custom.FakeClanConfig;
import org.l2jmobius.gameserver.config.custom.FakePlayersConfig;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.data.sql.CrestTable;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerHolder;
import org.l2jmobius.gameserver.model.actor.instance.FakePlayerPvpServitor;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.clan.Clan;
import org.l2jmobius.gameserver.model.clan.ClanAccess;
import org.l2jmobius.gameserver.model.clan.ClanMember;
import org.l2jmobius.gameserver.model.clan.Crest;
import org.l2jmobius.gameserver.model.clan.enums.CrestType;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;
import org.l2jmobius.gameserver.network.serverpackets.PledgeShowMemberListAdd;
import org.l2jmobius.gameserver.network.serverpackets.PledgeShowMemberListDelete;
import org.l2jmobius.gameserver.network.serverpackets.RelationChanged;
import org.l2jmobius.gameserver.network.serverpackets.SystemMessage;

/**
 * Clans run by fake players (FakeClans.ini). They are real clans: in the clan table and the database, with a level, a crest, reputation, wars and alliances, so players see their crest and name over their members, can declare war on them and invite them into
 * their alliance. Their leader and members are no characters: the leader is kept here (its id is the clan id, which marks such a clan in the database) and the members are the fake players wearing the clan.
 * <ul>
 * <li>A new fake player (town, farming or party) is sometimes a member of one of them, with a random title; the friends it comes with are often of the same clan.</li>
 * <li>Members attack the players (and fake players) of the clans their clan is at war with, both sides having declared. Kills in such a war give no karma and move clan reputation, like a war between players.</li>
 * <li>A clan that a players' clan declares war on declares war back after a while, and stops when the players stop. Killing its members often enough makes it declare war on the killers' clan.</li>
 * <li>An alliance leader invites one of them into the alliance by inviting one of its members; a clan invite to a fake player that isn't in a clan brings it into the inviter's clan.</li>
 * </ul>
 */
public class FakeClanManager
{
	private static final Logger LOGGER = Logger.getLogger(FakeClanManager.class.getName());
	
	/** Where the leader of a clan is kept: global variable {@code FAKE_CLAN_<clan id>} = "name;class id;level;female;member count". */
	private static final String VARIABLE_PREFIX = "FAKE_CLAN_";
	/** How often the members of a clan whose crest or alliance changed are shown again. */
	private static final long REFRESH_INTERVAL = 5000;
	/** How long a fake player keeps to its answer to a clan or alliance invite. */
	private static final long DECISION_MEMORY = 600000;
	
	private static final String[] JOIN =
	{
		"ty for the invite!",
		"sure, why not",
		"ok im in",
		"cool, thx",
		"yay a clan :)",
		"thx! glad to be here",
		"ok, hi all",
		"np, joined",
	};
	private static final String[] DECLINE =
	{
		"no thx",
		"sry, not looking for a clan",
		"nah im good",
		"maybe later",
		"i like it solo, ty",
		"no ty",
	};
	private static final String[] ALLY_ACCEPT =
	{
		"leader says ok, welcome allies",
		"we're in, gl all",
		"ok, our leader accepted",
		"allies it is then",
	};
	private static final String[] ALLY_DECLINE =
	{
		"leader said no, sry",
		"we stay on our own, ty",
		"not interested in an ally rn",
		"maybe some other time",
	};
	
	/** The clans run by fake players (configured or not anymore), by id. */
	private final Map<Integer, Clan> _fakeClans = new ConcurrentHashMap<>();
	/** The ones new fake players join (FakeClanNames), in their order. */
	private final List<Clan> _activeClans = new CopyOnWriteArrayList<>();
	/** What the members of a clan show over their heads (crest, alliance), to show them again when it changes. */
	private final Map<Integer, Integer> _looks = new ConcurrentHashMap<>();
	/** Answers to clan and alliance invites (who and what -> until when, negative for no). */
	private final Map<String, Long> _decisions = new ConcurrentHashMap<>();
	/** When members of a fake clan were killed by a players' clan (fake clan id and players' clan id -> kill times). */
	private final Map<Long, Deque<Long>> _grudges = new ConcurrentHashMap<>();
	/** War replies on their way (fake clan id and other clan id). */
	private final Set<Long> _pendingWars = ConcurrentHashMap.newKeySet();
	/** Fake players in clans of players, by object id, to keep the clan windows up to date. */
	private final Map<Integer, Recruit> _recruits = new ConcurrentHashMap<>();
	
	private record Recruit(int clanId, String name)
	{
	}
	
	protected FakeClanManager()
	{
		load();
		
		// Declaring a war calls back here, so not before this manager is made.
		ThreadPool.execute(this::startWars);
		ThreadPool.scheduleAtFixedRate(this::refreshLooks, REFRESH_INTERVAL, REFRESH_INTERVAL);
	}
	
	/**
	 * @return {@code true} if fake players join the clans
	 */
	public boolean isEnabled()
	{
		return FakeClanConfig.ENABLED && FakePlayersConfig.FAKE_PLAYERS_ENABLED;
	}
	
	// ---------------------------------------------------------------------------------------------
	// Loading
	// ---------------------------------------------------------------------------------------------
	
	private void load()
	{
		// The clans already made: their leader is kept here, not in the characters.
		final List<Integer> ids = new ArrayList<>();
		try (Connection con = DatabaseFactory.getConnection();
			Statement statement = con.createStatement();
			ResultSet rs = statement.executeQuery("SELECT clan_id FROM clan_data WHERE leader_id = clan_id"))
		{
			while (rs.next())
			{
				ids.add(rs.getInt("clan_id"));
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not load the clans of fake players.", e);
		}
		
		for (int id : ids)
		{
			final Clan clan = ClanTable.getInstance().getClan(id);
			if (clan != null)
			{
				restoreLeader(clan);
				_fakeClans.put(id, clan);
			}
		}
		
		if (!isEnabled())
		{
			LOGGER.info(getClass().getSimpleName() + ": Disabled" + (_fakeClans.isEmpty() ? "." : ", " + _fakeClans.size() + " clans kept as they are."));
			return;
		}
		
		// The configured ones, made the first time.
		int created = 0;
		for (int i = 0; i < FakeClanConfig.NAMES.size(); i++)
		{
			final String name = FakeClanConfig.NAMES.get(i);
			Clan clan = ClanTable.getInstance().getClanByName(name);
			if (clan == null)
			{
				clan = create(name);
				if (clan == null)
				{
					continue;
				}
				created++;
			}
			else if (!_fakeClans.containsKey(clan.getId()))
			{
				LOGGER.warning(getClass().getSimpleName() + ": A clan of players is named " + name + ", it isn't made a clan of fake players.");
				continue;
			}
			
			updateCrest(clan, i);
			_activeClans.add(clan);
		}
		
		LOGGER.info(getClass().getSimpleName() + ": " + _activeClans.size() + " clans of fake players" + (created > 0 ? " (" + created + " new)." : "."));
	}
	
	/**
	 * Puts the clans of FakeClanWars at war with each other, both declaring it.
	 */
	private void startWars()
	{
		if (!isEnabled())
		{
			return;
		}
		
		for (String[] pair : FakeClanConfig.WARS)
		{
			final Clan clan1 = getActiveClan(pair[0]);
			final Clan clan2 = getActiveClan(pair[1]);
			if ((clan1 == null) || (clan2 == null) || (clan1 == clan2))
			{
				LOGGER.warning(getClass().getSimpleName() + ": FakeClanWars " + pair[0] + ":" + pair[1] + " are not two clans of FakeClanNames.");
				continue;
			}
			
			if ((clan1.getAllyId() != 0) && (clan1.getAllyId() == clan2.getAllyId()))
			{
				LOGGER.warning(getClass().getSimpleName() + ": " + clan1.getName() + " and " + clan2.getName() + " are in the same alliance, no war between them.");
				continue;
			}
			
			if (!clan1.isAtWarWith(clan2.getId()))
			{
				ClanTable.getInstance().storeClanWars(clan1.getId(), clan2.getId());
			}
			if (!clan2.isAtWarWith(clan1.getId()))
			{
				ClanTable.getInstance().storeClanWars(clan2.getId(), clan1.getId());
			}
		}
	}
	
	private Clan getActiveClan(String name)
	{
		for (Clan clan : _activeClans)
		{
			if (clan.getName().equalsIgnoreCase(name.trim()))
			{
				return clan;
			}
		}
		return null;
	}
	
	/**
	 * Makes a new clan of fake players: a leader of a third class, a level, some reputation, stored like any clan.
	 * @param name the clan name
	 * @return the clan, {@code null} if it couldn't be made
	 */
	private Clan create(String name)
	{
		try
		{
			final int clanId = IdManager.getInstance().getNextId();
			final Clan clan = new Clan(clanId, name);
			final String leader = newLeader();
			clan.setLeader(toLeader(clan, leader));
			clan.setFakeMemberCount(getMemberCount(leader));
			clan.store();
			ClanTable.getInstance().addClan(clan);
			clan.changeLevel(Rnd.get(FakeClanConfig.LEVEL_MIN, FakeClanConfig.LEVEL_MAX));
			clan.addReputationScore(clan.getLevel() >= 5 ? Rnd.get(500, 2000 * (clan.getLevel() - 3)) : 0);
			clan.updateClanInDB();
			
			GlobalVariablesManager.getInstance().set(VARIABLE_PREFIX + clanId, leader);
			GlobalVariablesManager.getInstance().storeMe();
			_fakeClans.put(clanId, clan);
			return clan;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not make the clan " + name + ".", e);
			return null;
		}
	}
	
	/**
	 * Gives back a clan of fake players its leader, kept in the global variables (a new one if it was lost).
	 * @param clan the clan
	 */
	private void restoreLeader(Clan clan)
	{
		String leader = GlobalVariablesManager.getInstance().getString(VARIABLE_PREFIX + clan.getId(), null);
		ClanMember member = leader != null ? toLeader(clan, leader) : null;
		if (member == null)
		{
			leader = newLeader();
			member = toLeader(clan, leader);
			GlobalVariablesManager.getInstance().set(VARIABLE_PREFIX + clan.getId(), leader);
		}
		else
		{
			FakePlayerPvpManager.getInstance().reserveName(member.getName());
		}
		
		clan.setLeader(member);
		clan.setFakeMemberCount(getMemberCount(leader));
	}
	
	/**
	 * @return a new leader: "name;class id;level;female;member count", a third class of level 76 to 85
	 */
	private static String newLeader()
	{
		final List<PlayerClass> classes = new ArrayList<>();
		for (PlayerClass playerClass : PlayerClass.values())
		{
			if ((playerClass.level() == 3) && (playerClass.getRace() != null))
			{
				classes.add(playerClass);
			}
		}
		
		final PlayerClass playerClass = classes.get(Rnd.get(classes.size()));
		final String name = FakePlayerPvpManager.getInstance().generateName();
		return name + ";" + playerClass.getId() + ";" + Rnd.get(76, 85) + ";" + (Rnd.nextBoolean() ? 1 : 0) + ";" + Rnd.get(FakeClanConfig.MEMBERS_MIN, FakeClanConfig.MEMBERS_MAX);
	}
	
	/**
	 * @param clan the clan
	 * @param leader the leader, see {@link #newLeader()}
	 * @return the clan member of the leader, its id being the clan id; {@code null} if {@code leader} can't be read
	 */
	private static ClanMember toLeader(Clan clan, String leader)
	{
		try
		{
			final String[] parts = leader.split(";");
			final PlayerClass playerClass = PlayerClass.getPlayerClass(Integer.parseInt(parts[1]));
			return new ClanMember(clan, clan.getId(), parts[0], Integer.parseInt(parts[2]), playerClass.getId(), "1".equals(parts[3]), playerClass.getRace().ordinal());
		}
		catch (Exception e)
		{
			return null;
		}
	}
	
	private static int getMemberCount(String leader)
	{
		try
		{
			return Math.max(1, Integer.parseInt(leader.split(";")[4]));
		}
		catch (Exception e)
		{
			return FakeClanConfig.MEMBERS_MIN;
		}
	}
	
	/**
	 * Gives a clan its crest: the one in {@code data/fakeclans} if the operator put one there, otherwise a design of its own.
	 * @param clan the clan
	 * @param index its place in FakeClanNames
	 */
	private static void updateCrest(Clan clan, int index)
	{
		final byte[] data = FakeClanCrest.create(clan.getName(), index, ServerConfig.DATAPACK_ROOT);
		if (data == null)
		{
			return;
		}
		
		final Crest current = clan.getCrestId() != 0 ? CrestTable.getInstance().getCrest(clan.getCrestId()) : null;
		if ((current != null) && Arrays.equals(current.getData(), data))
		{
			return;
		}
		
		final Crest crest = CrestTable.getInstance().createCrest(data, CrestType.PLEDGE);
		if (crest != null)
		{
			clan.changeClanCrest(crest.getId());
		}
	}
	
	// ---------------------------------------------------------------------------------------------
	// Members
	// ---------------------------------------------------------------------------------------------
	
	/**
	 * @param clan a clan
	 * @return {@code true} if fake players run it
	 */
	public boolean isFakeClan(Clan clan)
	{
		return (clan != null) && _fakeClans.containsKey(clan.getId());
	}
	
	/**
	 * A new fake player (town, farming or party): sometimes it is a member of one of the clans, with a random title.
	 * @param template its template
	 */
	public void assignClan(NpcTemplate template)
	{
		final FakePlayerHolder info = template.getFakePlayerInfo();
		if ((info == null) || !isEnabled() || _activeClans.isEmpty() || (Rnd.get(100) >= FakeClanConfig.MEMBER_CHANCE))
		{
			return;
		}
		
		info.setClan(_activeClans.get(Rnd.get(_activeClans.size())).getId(), randomTitle());
	}
	
	/**
	 * A fake player that comes with another one (a party back from a hunt, friends hunting together) is often in its clan.
	 * @param fake the fake player
	 * @param friend the one it comes with
	 */
	public void shareClan(Npc fake, Npc friend)
	{
		final FakePlayerHolder info = getInfo(fake);
		final FakePlayerHolder friendInfo = getInfo(friend);
		if ((info == null) || (friendInfo == null) || !isEnabled() || (friendInfo.getClanId() == 0) || (friendInfo.getClanId() == info.getClanId()) || !_fakeClans.containsKey(friendInfo.getClanId()) || (Rnd.get(100) >= FakeClanConfig.SAME_CLAN_CHANCE))
		{
			return;
		}
		
		info.setClan(friendInfo.getClanId(), randomTitle());
		fake.broadcastInfo();
	}
	
	/**
	 * A fake player coming over to a player (who asked for its class) isn't of a clan at war with the player's clan: it leaves it.
	 * @param fake the fake player
	 * @param player the player
	 */
	public void avoidWar(Npc fake, Player player)
	{
		final FakePlayerHolder info = getInfo(fake);
		if ((info != null) && isWarEnemy(fake, player))
		{
			info.setClan(0, "");
			fake.broadcastInfo();
		}
	}
	
	/**
	 * @return a title from FakeClanTitles (FakeClanTitleChance), empty for none
	 */
	private static String randomTitle()
	{
		final List<String> titles = FakeClanConfig.TITLES;
		return !titles.isEmpty() && (Rnd.get(100) < FakeClanConfig.TITLE_CHANCE) ? titles.get(Rnd.get(titles.size())) : "";
	}
	
	private static FakePlayerHolder getInfo(Creature creature)
	{
		return (creature != null) && creature.isFakePlayer() ? creature.asNpc().getTemplate().getFakePlayerInfo() : null;
	}
	
	/**
	 * @return the fake players the clans can have: roaming (farming and party) and town ones
	 */
	private static List<Npc> getFakes()
	{
		final List<Npc> fakes = new ArrayList<>(FakePlayerPvpManager.getInstance().getFakePlayers());
		if (FakePlayerTownManager.isStarted())
		{
			fakes.addAll(FakePlayerTownManager.getInstance().getVisitorNpcs());
		}
		return fakes;
	}
	
	/**
	 * @param clanId a clan id
	 * @return how many fake players of that clan are in the world
	 */
	private static int countMembers(int clanId)
	{
		int count = 0;
		for (Npc fake : getFakes())
		{
			final FakePlayerHolder info = getInfo(fake);
			if ((info != null) && (info.getClanId() == clanId) && fake.isSpawned())
			{
				count++;
			}
		}
		return count;
	}
	
	/**
	 * @param clan a clan of fake players
	 * @return how many of its members are online: its fake players in the world
	 */
	public int getOnlineCount(Clan clan)
	{
		return Math.min(countMembers(clan.getId()), clan.getMembersCount());
	}
	
	/**
	 * @param clan a clan of players
	 * @return the fake players that joined it and are in the world (they show in its clan window)
	 */
	public List<Npc> getRecruits(Clan clan)
	{
		final List<Npc> recruits = new ArrayList<>();
		if ((clan == null) || isFakeClan(clan))
		{
			return recruits;
		}
		
		for (Npc fake : getFakes())
		{
			final FakePlayerHolder info = getInfo(fake);
			if ((info != null) && (info.getClanId() == clan.getId()) && fake.isSpawned())
			{
				recruits.add(fake);
			}
		}
		return recruits;
	}
	
	/**
	 * @param creature a player, a summon or a fake player
	 * @return its clan (a player's in the academy has none here), {@code null} if none
	 */
	public static Clan getClan(Creature creature)
	{
		if (creature == null)
		{
			return null;
		}
		
		if (creature instanceof FakePlayerPvpServitor)
		{
			return getClan(((FakePlayerPvpServitor) creature).getOwner());
		}
		
		final FakePlayerHolder info = getInfo(creature);
		if (info != null)
		{
			return info.getClanId() != 0 ? ClanTable.getInstance().getClan(info.getClanId()) : null;
		}
		
		final Player player = creature.asPlayer();
		return (player != null) && !player.isAcademyMember() ? player.getClan() : null;
	}
	
	/**
	 * @param creature a fake player, player or summon
	 * @param other another one
	 * @return {@code true} if their clans are at war, both having declared it
	 */
	public boolean isWarEnemy(Creature creature, Creature other)
	{
		final Clan clan = getClan(creature);
		final Clan otherClan = getClan(other);
		return (clan != null) && (otherClan != null) && (clan != otherClan) && clan.isAtWarWith(otherClan.getId()) && otherClan.isAtWarWith(clan.getId());
	}
	
	/**
	 * @param creature a fake player, player or summon
	 * @param other another one
	 * @return {@code true} if they are of the same clan or alliance
	 */
	public boolean isFriend(Creature creature, Creature other)
	{
		final Clan clan = getClan(creature);
		final Clan otherClan = getClan(other);
		return (clan != null) && (otherClan != null) && ((clan == otherClan) || ((clan.getAllyId() != 0) && (clan.getAllyId() == otherClan.getAllyId())));
	}
	
	/**
	 * Tells {@code player} how it stands with the clan of a fake player it sees (clan war icons, attackable without Ctrl), like for a player.
	 * @param fake the fake player
	 * @param player the player who sees it
	 */
	public void sendRelation(Npc fake, Player player)
	{
		final Clan fakeClan = getClan(fake);
		final Clan clan = player.getClan();
		if ((fakeClan == null) || (clan == null))
		{
			return;
		}
		
		int relation = RelationChanged.RELATION_CLAN_MEMBER;
		if (fakeClan == clan)
		{
			relation |= RelationChanged.RELATION_CLAN_MATE;
		}
		if (fakeClan.getAllyId() != 0)
		{
			relation |= RelationChanged.RELATION_ALLY_MEMBER;
		}
		if (!player.isAcademyMember() && clan.isAtWarWith(fakeClan.getId()))
		{
			relation |= RelationChanged.RELATION_1SIDED_WAR;
			if (fakeClan.isAtWarWith(clan.getId()))
			{
				relation |= RelationChanged.RELATION_MUTUAL_WAR;
			}
		}
		player.sendPacket(new RelationChanged(fake, relation, fake.isAutoAttackable(player)));
	}
	
	/**
	 * Shows the members of a clan again (crest, alliance, title, war relation) to the players around them.
	 * @param clanId the clan id
	 */
	private static void refreshMembers(int clanId)
	{
		for (Npc fake : getFakes())
		{
			final FakePlayerHolder info = getInfo(fake);
			if ((info != null) && (info.getClanId() == clanId) && fake.isSpawned())
			{
				fake.broadcastInfo();
			}
		}
	}
	
	/**
	 * Every {@link #REFRESH_INTERVAL} ms: members of a clan whose crest or alliance changed are shown again, those of a clan that is gone leave it, and the clan windows of players show the fake players that joined their clan and are in the world.
	 */
	private void refreshLooks()
	{
		try
		{
			final Map<Integer, Integer> looks = new HashMap<>();
			final Map<Integer, Npc> recruits = new HashMap<>();
			for (Npc fake : getFakes())
			{
				final FakePlayerHolder info = getInfo(fake);
				if ((info == null) || (info.getClanId() == 0))
				{
					continue;
				}
				
				final Clan clan = ClanTable.getInstance().getClan(info.getClanId());
				if (clan == null)
				{
					info.setClan(0, "");
					if (fake.isSpawned())
					{
						fake.broadcastInfo();
					}
					continue;
				}
				
				looks.put(clan.getId(), Objects.hash(clan.getCrestId(), clan.getAllyId(), clan.getAllyCrestId(), clan.getAllyName()));
				if (!isFakeClan(clan) && fake.isSpawned())
				{
					recruits.put(fake.getObjectId(), fake);
				}
			}
			
			// Gone (logged off, left, dismissed) or back.
			for (Map.Entry<Integer, Recruit> entry : _recruits.entrySet())
			{
				final FakePlayerHolder info = getInfo(recruits.get(entry.getKey()));
				if ((info == null) || (info.getClanId() != entry.getValue().clanId()))
				{
					_recruits.remove(entry.getKey());
					final Clan clan = ClanTable.getInstance().getClan(entry.getValue().clanId());
					if (clan != null)
					{
						clan.broadcastToOnlineMembers(new PledgeShowMemberListDelete(entry.getValue().name()));
					}
				}
			}
			for (Npc fake : recruits.values())
			{
				addRecruit(fake);
			}
			
			for (Map.Entry<Integer, Integer> entry : looks.entrySet())
			{
				final Integer previous = _looks.put(entry.getKey(), entry.getValue());
				if ((previous != null) && !previous.equals(entry.getValue()))
				{
					refreshMembers(entry.getKey());
				}
			}
			_looks.keySet().retainAll(looks.keySet());
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not refresh the clans of fake players.", e);
		}
	}
	
	/**
	 * Shows a fake player that joined a players' clan in the clan window of its online members.
	 * @param fake the fake player
	 */
	private void addRecruit(Npc fake)
	{
		final FakePlayerHolder info = getInfo(fake);
		final Clan clan = info != null ? ClanTable.getInstance().getClan(info.getClanId()) : null;
		if ((clan != null) && (_recruits.putIfAbsent(fake.getObjectId(), new Recruit(clan.getId(), fake.getName())) == null))
		{
			clan.broadcastToOnlineMembers(new PledgeShowMemberListAdd(fake));
		}
	}
	
	// ---------------------------------------------------------------------------------------------
	// Wars
	// ---------------------------------------------------------------------------------------------
	
	/**
	 * Called by {@link ClanTable#storeClanWars} when {@code clan1} declared war on {@code clan2}: their fake players show it, and a clan of fake players declares war back after a while.
	 * @param clan1 the clan that declared war
	 * @param clan2 the clan it declared war on
	 */
	public void onWarStarted(Clan clan1, Clan clan2)
	{
		ThreadPool.schedule(() ->
		{
			refreshMembers(clan1.getId());
			refreshMembers(clan2.getId());
		}, 500);
		
		if (!isEnabled() || !isFakeClan(clan2) || isFakeClan(clan1) || clan2.isAtWarWith(clan1.getId()) || (Rnd.get(100) >= FakeClanConfig.WAR_REPLY_CHANCE))
		{
			return;
		}
		
		final long key = key(clan2.getId(), clan1.getId());
		if (!_pendingWars.add(key))
		{
			return;
		}
		
		ThreadPool.schedule(() ->
		{
			_pendingWars.remove(key);
			if ((ClanTable.getInstance().getClan(clan1.getId()) != null) && clan1.isAtWarWith(clan2.getId()) && !clan2.isAtWarWith(clan1.getId()))
			{
				ClanTable.getInstance().storeClanWars(clan2.getId(), clan1.getId());
			}
		}, Rnd.get(FakeClanConfig.WAR_REPLY_DELAY_MIN, FakeClanConfig.WAR_REPLY_DELAY_MAX) * 1000L);
	}
	
	/**
	 * Called by {@link ClanTable#deleteClanWars} when {@code clan1} stopped its war on {@code clan2}: their fake players show it, and a clan of fake players stops its war on players that stopped theirs.
	 * @param clan1 the clan that stopped the war
	 * @param clan2 the clan it was at war with
	 */
	public void onWarStopped(Clan clan1, Clan clan2)
	{
		ThreadPool.schedule(() ->
		{
			refreshMembers(clan1.getId());
			refreshMembers(clan2.getId());
		}, 500);
		
		if (!isFakeClan(clan2) || isFakeClan(clan1) || !clan2.isAtWarWith(clan1.getId()))
		{
			return;
		}
		
		ThreadPool.schedule(() ->
		{
			if (clan2.isAtWarWith(clan1.getId()) && !clan1.isAtWarWith(clan2.getId()) && (ClanTable.getInstance().getClan(clan1.getId()) != null))
			{
				ClanTable.getInstance().deleteClanWars(clan2.getId(), clan1.getId());
			}
		}, Rnd.get(FakeClanConfig.WAR_REPLY_DELAY_MIN, FakeClanConfig.WAR_REPLY_DELAY_MAX) * 1000L);
	}
	
	/**
	 * A kill in a clan war (both sides declared): the killer's clan takes reputation from the victim's, like in a war between players.
	 * @param killer the killer (a player or a fake player)
	 * @param victim the victim (a player or a fake player)
	 * @return {@code true} if it was a clan war kill
	 */
	public boolean onWarKill(Creature killer, Creature victim)
	{
		if (!isWarEnemy(killer, victim))
		{
			return false;
		}
		
		final Clan killerClan = getClan(killer);
		final Clan victimClan = getClan(victim);
		if (victimClan.getReputationScore() > 0)
		{
			killerClan.addReputationScore(FeatureConfig.REPUTATION_SCORE_PER_KILL);
		}
		if (killerClan.getReputationScore() > 0)
		{
			victimClan.takeReputationScore(FeatureConfig.REPUTATION_SCORE_PER_KILL);
		}
		return true;
	}
	
	/**
	 * Called when a player kills a fake player.
	 * @param fake the fake player
	 * @param killer the player
	 * @return {@code true} if it was a clan war kill (no karma), see {@link #onWarKill}; otherwise a clan of fake players remembers it, and after FakeClanWarGrudgeKills of its members it declares war on the killers' clan
	 */
	public boolean onFakeKilled(Npc fake, Player killer)
	{
		if (onWarKill(killer, fake))
		{
			return true;
		}
		
		final Clan fakeClan = getClan(fake);
		final Clan killerClan = getClan(killer);
		if (!isEnabled() || (FakeClanConfig.WAR_GRUDGE_KILLS <= 0) || !isFakeClan(fakeClan) || (killerClan == null) || isFakeClan(killerClan) || isFriend(fake, killer) || fakeClan.isAtWarWith(killerClan.getId()))
		{
			return false;
		}
		
		// A clan that can't be at war (like players can't declare war on it).
		if ((killerClan.getLevel() < 3) || (killerClan.getMembersCount() < PlayerConfig.ALT_CLAN_MEMBERS_FOR_WAR))
		{
			return false;
		}
		
		final long now = System.currentTimeMillis();
		final Deque<Long> kills = _grudges.computeIfAbsent(key(fakeClan.getId(), killerClan.getId()), _ -> new ArrayDeque<>());
		synchronized (kills)
		{
			kills.addLast(now);
			while (!kills.isEmpty() && ((now - kills.peekFirst()) > (FakeClanConfig.WAR_GRUDGE_TIME * 60000L)))
			{
				kills.pollFirst();
			}
			if (kills.size() < FakeClanConfig.WAR_GRUDGE_KILLS)
			{
				return false;
			}
			kills.clear();
		}
		
		ClanTable.getInstance().storeClanWars(fakeClan.getId(), killerClan.getId());
		return false;
	}
	
	private static long key(int id1, int id2)
	{
		return ((long) id1 << 32) | (id2 & 0xFFFFFFFFL);
	}
	
	// ---------------------------------------------------------------------------------------------
	// Invites
	// ---------------------------------------------------------------------------------------------
	
	/**
	 * @param objectId an object id a player invites
	 * @return the fake player of that id that can be invited into a clan or alliance (a farming, party or town one), {@code null} if it isn't one
	 */
	private static Npc findFake(int objectId)
	{
		final WorldObject object = World.getInstance().findObject(objectId);
		if ((object == null) || !object.isNpc())
		{
			return null;
		}
		
		final Npc npc = object.asNpc();
		if (!npc.isFakePlayer() || npc.isTrialDuelist())
		{
			return null;
		}
		
		return npc.isPvpFakePlayer() || (FakePlayerTownManager.isStarted() && (FakePlayerTownManager.getInstance().getVisitor(npc) != null)) ? npc : null;
	}
	
	/**
	 * Called from {@code RequestJoinPledge} when the invited one isn't a player: a fake player that isn't in a clan answers like a player would and, if it accepts, wears the inviter's clan (with a random title) while it is in the world.
	 * @param player the player that invites
	 * @param objectId the invited object
	 * @return {@code true} if it is a fake player (the invite is handled)
	 */
	public boolean onClanInvite(Player player, int objectId)
	{
		final WorldObject object = World.getInstance().findObject(objectId);
		if ((object == null) || !object.isNpc() || !object.asNpc().isFakePlayer())
		{
			return false;
		}
		
		final Npc fake = findFake(objectId);
		final Clan clan = player.getClan();
		if ((fake == null) || !isEnabled() || fake.isDead() || (clan == null))
		{
			player.sendPacket(SystemMessageId.YOU_HAVE_INVITED_THE_WRONG_TARGET);
			return true;
		}
		
		if (!canInvite(player, fake, true))
		{
			return true;
		}
		
		final boolean accept = decide("clan:" + fake.getObjectId() + ":" + clan.getId(), FakeClanConfig.INVITE_ACCEPT_CHANCE) && !FakePlayerPvpManager.isInPvp(fake);
		ThreadPool.schedule(() -> answerClanInvite(player, fake, clan, accept), Rnd.get(1500, 5000));
		return true;
	}
	
	/**
	 * @param player the player that invites
	 * @param fake the invited fake player
	 * @param tell {@code true} to tell the player why not
	 * @return {@code true} if {@code fake} may join the clan of {@code player} (like {@link Clan#checkClanJoinCondition} for a player)
	 */
	private boolean canInvite(Player player, Npc fake, boolean tell)
	{
		final Clan clan = player.getClan();
		final FakePlayerHolder info = getInfo(fake);
		final SystemMessageId message;
		if ((clan == null) || (info == null))
		{
			message = SystemMessageId.YOU_HAVE_INVITED_THE_WRONG_TARGET;
		}
		else if (!player.hasAccess(ClanAccess.INVITE_MEMBER))
		{
			message = SystemMessageId.YOU_ARE_NOT_AUTHORIZED_TO_DO_THAT;
		}
		else if (clan.getCharPenaltyExpiryTime() > System.currentTimeMillis())
		{
			message = SystemMessageId.AFTER_A_CLAN_MEMBER_IS_DISMISSED_FROM_A_CLAN_THE_CLAN_MUST_WAIT_AT_LEAST_A_DAY_BEFORE_ACCEPTING_A_NEW_MEMBER;
		}
		else if (info.getClanId() != 0)
		{
			if (tell)
			{
				player.sendPacket(new SystemMessage(SystemMessageId.S1_IS_ALREADY_A_MEMBER_OF_ANOTHER_CLAN).addString(fake.getName()));
			}
			return false;
		}
		else if ((FakeClanConfig.MAX_RECRUITS > 0) && (countMembers(clan.getId()) >= FakeClanConfig.MAX_RECRUITS))
		{
			if (tell)
			{
				player.sendPacket(new SystemMessage(SystemMessageId.S1_IS_FULL_AND_CANNOT_ACCEPT_ADDITIONAL_CLAN_MEMBERS_AT_THIS_TIME).addString(clan.getName()));
			}
			return false;
		}
		else
		{
			return true;
		}
		
		if (tell)
		{
			player.sendPacket(message);
		}
		return false;
	}
	
	private void answerClanInvite(Player player, Npc fake, Clan clan, boolean accept)
	{
		if (!player.isOnline() || (player.getClan() != clan) || !fake.isSpawned() || fake.isDead())
		{
			return;
		}
		
		if (!accept || !canInvite(player, fake, false))
		{
			player.sendPacket(new SystemMessage(SystemMessageId.S1_DECLINED_YOUR_CLAN_INVITATION).addString(fake.getName()));
			if (Rnd.get(100) < 60)
			{
				whisper(fake, player, DECLINE[Rnd.get(DECLINE.length)]);
			}
			return;
		}
		
		getInfo(fake).setClan(clan.getId(), randomTitle());
		fake.broadcastInfo();
		clan.broadcastToOnlineMembers(new SystemMessage(SystemMessageId.S1_HAS_JOINED_THE_CLAN).addString(fake.getName()));
		addRecruit(fake);
		if (Rnd.get(100) < 70)
		{
			final String text = JOIN[Rnd.get(JOIN.length)];
			ThreadPool.schedule(() ->
			{
				if (fake.isSpawned() && !fake.isDead())
				{
					clan.broadcastToOnlineMembers(new CreatureSay(fake, ChatType.CLAN, fake.getName(), text));
				}
			}, Rnd.get(1500, 4000));
		}
	}
	
	/**
	 * Called from {@code RequestOustPledgeMember}: a fake player that joined the clan of {@code player} is dismissed.
	 * @param player the player that dismisses
	 * @param name the name to dismiss
	 * @return {@code true} if {@code name} is a fake player of the clan (it is handled)
	 */
	public boolean onDismiss(Player player, String name)
	{
		final Clan clan = player.getClan();
		if ((clan == null) || (name == null) || isFakeClan(clan))
		{
			return false;
		}
		
		for (Npc fake : getFakes())
		{
			final FakePlayerHolder info = getInfo(fake);
			if ((info == null) || (info.getClanId() != clan.getId()) || !fake.getName().equalsIgnoreCase(name))
			{
				continue;
			}
			
			if (!player.hasAccess(ClanAccess.REMOVE_MEMBER))
			{
				player.sendPacket(SystemMessageId.YOU_ARE_NOT_AUTHORIZED_TO_DO_THAT);
				return true;
			}
			
			if (fake.isInCombat())
			{
				player.sendPacket(SystemMessageId.A_CLAN_MEMBER_MAY_NOT_BE_DISMISSED_DURING_COMBAT);
				return true;
			}
			
			info.setClan(0, "");
			fake.broadcastInfo();
			_recruits.remove(fake.getObjectId());
			clan.broadcastToOnlineMembers(new SystemMessage(SystemMessageId.CLAN_MEMBER_S1_HAS_BEEN_EXPELLED).addString(fake.getName()));
			clan.broadcastToOnlineMembers(new PledgeShowMemberListDelete(fake.getName()));
			player.sendPacket(SystemMessageId.YOU_HAVE_SUCCEEDED_IN_EXPELLING_THE_CLAN_MEMBER);
			return true;
		}
		return false;
	}
	
	/**
	 * Called from {@code RequestJoinAlly} when the invited one isn't a player: an alliance leader invites a member of a clan of fake players, which takes it to its leader. The clan answers after a while, like a clan leader would (see
	 * {@link Clan#checkAllyJoinCondition}).
	 * @param player the alliance leader
	 * @param objectId the invited object
	 * @return {@code true} if it is a fake player (the invite is handled)
	 */
	public boolean onAllyInvite(Player player, int objectId)
	{
		final WorldObject object = World.getInstance().findObject(objectId);
		if ((object == null) || !object.isNpc() || !object.asNpc().isFakePlayer())
		{
			return false;
		}
		
		final Npc fake = findFake(objectId);
		if ((fake == null) || fake.isDead())
		{
			player.sendPacket(SystemMessageId.YOU_HAVE_INVITED_THE_WRONG_TARGET);
			return true;
		}
		
		if (player.getClan() == null)
		{
			player.sendPacket(SystemMessageId.YOU_ARE_NOT_A_CLAN_MEMBER_AND_CANNOT_PERFORM_THIS_ACTION);
			return true;
		}
		
		final Clan clan = getClan(fake);
		if (!canAlly(player, fake, clan, true))
		{
			return true;
		}
		
		player.sendPacket(SystemMessageId.YOU_HAVE_INVITED_SOMEONE_TO_YOUR_ALLIANCE);
		final boolean accept = decide("ally:" + clan.getId() + ":" + player.getAllyId(), FakeClanConfig.ALLY_ACCEPT_CHANCE);
		ThreadPool.schedule(() -> answerAllyInvite(player, fake, clan, accept), Rnd.get(4000, 10000));
		return true;
	}
	
	/**
	 * @param player the alliance leader
	 * @param fake the invited fake player
	 * @param clan its clan
	 * @param tell {@code true} to tell the player why not
	 * @return {@code true} if {@code clan} may join the alliance of {@code player}
	 */
	private boolean canAlly(Player player, Npc fake, Clan clan, boolean tell)
	{
		final Clan leaderClan = player.getClan();
		SystemMessage message = null;
		if ((leaderClan == null) || (player.getAllyId() == 0) || !player.isClanLeader() || (player.getClanId() != player.getAllyId()))
		{
			message = new SystemMessage(SystemMessageId.THIS_FEATURE_IS_ONLY_AVAILABLE_TO_ALLIANCE_LEADERS);
		}
		else if ((leaderClan.getAllyPenaltyExpiryTime() > System.currentTimeMillis()) && (leaderClan.getAllyPenaltyType() == Clan.PENALTY_TYPE_DISMISS_CLAN))
		{
			message = new SystemMessage(SystemMessageId.YOU_MAY_NOT_ACCEPT_ANY_CLAN_WITHIN_A_DAY_AFTER_EXPELLING_ANOTHER_CLAN);
		}
		else if (clan == null)
		{
			message = new SystemMessage(SystemMessageId.THE_TARGET_MUST_BE_A_CLAN_MEMBER);
		}
		else if (!isFakeClan(clan))
		{
			// It joined a clan of players: its leader is a player.
			message = new SystemMessage(SystemMessageId.S1_IS_NOT_A_CLAN_LEADER).addString(fake.getName());
		}
		else if (clan.getAllyId() != 0)
		{
			message = new SystemMessage(SystemMessageId.S1_CLAN_IS_ALREADY_A_MEMBER_OF_S2_ALLIANCE).addString(clan.getName()).addString(clan.getAllyName());
		}
		else if ((clan.getAllyPenaltyExpiryTime() > System.currentTimeMillis()) && (clan.getAllyPenaltyType() == Clan.PENALTY_TYPE_CLAN_LEAVED))
		{
			message = new SystemMessage(SystemMessageId.S1_CLAN_CANNOT_JOIN_THE_ALLIANCE_BECAUSE_ONE_DAY_HAS_NOT_YET_PASSED_SINCE_THEY_LEFT_ANOTHER_ALLIANCE).addString(clan.getName()).addString(leaderClan.getAllyName());
		}
		else if ((clan.getAllyPenaltyExpiryTime() > System.currentTimeMillis()) && (clan.getAllyPenaltyType() == Clan.PENALTY_TYPE_CLAN_DISMISSED))
		{
			message = new SystemMessage(SystemMessageId.A_CLAN_THAT_HAS_WITHDRAWN_OR_BEEN_EXPELLED_CANNOT_ENTER_INTO_AN_ALLIANCE_WITHIN_ONE_DAY_OF_WITHDRAWAL_OR_EXPULSION);
		}
		else if (leaderClan.isAtWarWith(clan.getId()) || clan.isAtWarWith(leaderClan.getId()))
		{
			message = new SystemMessage(SystemMessageId.YOU_MAY_NOT_ALLY_WITH_A_CLAN_YOU_ARE_CURRENTLY_AT_WAR_WITH_THAT_WOULD_BE_DIABOLICAL_AND_TREACHEROUS);
		}
		else if (ClanTable.getInstance().getClanAllies(player.getAllyId()).size() >= PlayerConfig.ALT_MAX_NUM_OF_CLANS_IN_ALLY)
		{
			message = new SystemMessage(SystemMessageId.YOU_HAVE_EXCEEDED_THE_LIMIT);
		}
		
		if ((message != null) && tell)
		{
			player.sendPacket(message);
		}
		return message == null;
	}
	
	private void answerAllyInvite(Player player, Npc fake, Clan clan, boolean accept)
	{
		if (!player.isOnline())
		{
			return;
		}
		
		final boolean speak = fake.isSpawned() && !fake.isDead();
		if (!accept || !canAlly(player, fake, clan, false))
		{
			player.sendPacket(SystemMessageId.YOU_HAVE_FAILED_TO_INVITE_A_CLAN_INTO_THE_ALLIANCE);
			if (speak)
			{
				whisper(fake, player, ALLY_DECLINE[Rnd.get(ALLY_DECLINE.length)]);
			}
			return;
		}
		
		final Clan leaderClan = player.getClan();
		clan.setAllyId(leaderClan.getAllyId());
		clan.setAllyName(leaderClan.getAllyName());
		clan.setAllyPenaltyExpiryTime(0, 0);
		clan.changeAllyCrest(leaderClan.getAllyCrestId(), true);
		clan.updateClanInDB();
		refreshMembers(clan.getId());
		
		leaderClan.broadcastToOnlineAllyMembers(new SystemMessage(SystemMessageId.S1).addString(clan.getName() + " has joined the alliance " + leaderClan.getAllyName() + "."));
		if (speak)
		{
			whisper(fake, player, ALLY_ACCEPT[Rnd.get(ALLY_ACCEPT.length)]);
		}
	}
	
	/**
	 * @param key who answers what
	 * @param chance the chance (in %) of a yes
	 * @return the answer, the same for {@link #DECISION_MEMORY} ms
	 */
	private boolean decide(String key, int chance)
	{
		final long now = System.currentTimeMillis();
		_decisions.values().removeIf(until -> Math.abs(until) < now);
		final Long decision = _decisions.get(key);
		if (decision != null)
		{
			return decision > 0;
		}
		
		final boolean yes = Rnd.get(100) < chance;
		_decisions.put(key, yes ? now + DECISION_MEMORY : -(now + DECISION_MEMORY));
		return yes;
	}
	
	private static void whisper(Npc fake, Player player, String text)
	{
		ThreadPool.schedule(() ->
		{
			if (player.isOnline())
			{
				player.sendPacket(new CreatureSay(fake, ChatType.WHISPER, fake.getName(), text));
			}
		}, Rnd.get(800, 2500));
	}
	
	// ---------------------------------------------------------------------------------------------
	// Admin
	// ---------------------------------------------------------------------------------------------
	
	/**
	 * @return a few lines about the clans (for //fakeclans)
	 */
	public List<String> getInfo()
	{
		final List<String> lines = new ArrayList<>();
		lines.add("Clans of fake players: " + _activeClans.size() + (isEnabled() ? "" : " (disabled)") + ".");
		for (Clan clan : _fakeClans.values())
		{
			final StringBuilder wars = new StringBuilder();
			for (int id : clan.getWarList())
			{
				final Clan enemy = ClanTable.getInstance().getClan(id);
				if (enemy != null)
				{
					wars.append(wars.length() > 0 ? ", " : "").append(enemy.getName()).append(enemy.isAtWarWith(clan.getId()) ? "" : " (one-sided)");
				}
			}
			lines.add(clan.getName() + " lv " + clan.getLevel() + ", leader " + clan.getLeaderName() + ", " + getOnlineCount(clan) + "/" + clan.getMembersCount() + " online, rep " + clan.getReputationScore() + (clan.getAllyId() != 0 ? ", ally " + clan.getAllyName() : "") + (wars.length() > 0 ? ", wars: " + wars : "") + (_activeClans.contains(clan) ? "" : " (not in FakeClanNames)"));
		}
		return lines;
	}
	
	public static FakeClanManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final FakeClanManager INSTANCE = new FakeClanManager();
	}
}
