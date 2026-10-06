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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
import org.l2jmobius.gameserver.data.xml.FakePlayerPvpData;
import org.l2jmobius.gameserver.managers.FakePlayerPvpFactory.Looks;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.enums.player.Sex;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerHolder;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;
import org.l2jmobius.gameserver.model.actor.instance.FakePlayerPvpServitor;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.clan.Clan;
import org.l2jmobius.gameserver.model.clan.ClanAccess;
import org.l2jmobius.gameserver.model.clan.ClanMember;
import org.l2jmobius.gameserver.model.clan.Crest;
import org.l2jmobius.gameserver.model.clan.enums.CrestType;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;
import org.l2jmobius.gameserver.network.serverpackets.PledgeShowMemberListAdd;
import org.l2jmobius.gameserver.network.serverpackets.PledgeShowMemberListDelete;
import org.l2jmobius.gameserver.network.serverpackets.PledgeShowMemberListUpdate;
import org.l2jmobius.gameserver.network.serverpackets.RelationChanged;
import org.l2jmobius.gameserver.network.serverpackets.SystemMessage;

/**
 * Clans run by fake players (FakeClans.ini). They are real clans: in the clan table and the database, with a level, a crest, reputation, wars and alliances, so players see their crest and name over their members, can declare war on them and invite them into
 * their alliance. Their leader and members are no characters: the leader is kept here (its id is the clan id, which marks such a clan in the database) and the members are the fake players wearing the clan.
 * <ul>
 * <li>A new fake player (town, farming or party) is sometimes a member of one of them, with a random title; the friends it comes with are often of the same clan.</li>
 * <li>Members attack the players (and fake players) of the clans their clan is at war with, both sides having declared. Kills in such a war give no karma and move clan reputation, like a war between players.</li>
 * <li>A clan that a players' clan declares war on declares war back after a while, and stops when the players stop. Killing its members often enough makes it declare war on the killers' clan.</li>
 * <li>An alliance leader invites one of them into the alliance by inviting one of its members.</li>
 * <li>A clan invite to a fake player that isn't in a clan brings it into the inviter's clan for good ({@link Member}, kept in the database): it keeps its name, class line and looks, logs off and on again (it shows offline in the clan window meanwhile), may leave or gain a
 * level every day cycle, and its gear and passive tree are rolled again only at some levels ({@link FakeClanConfig#MEMBER_REROLL_LEVELS}).</li>
 * <li>One invited into the clan academy (level 40 or below, before its 2nd class) gains a level every day cycle and never leaves on its own; at its 2nd class (level 40) it graduates: it leaves the clan, which earns reputation like for a player graduate.</li>
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
	/** Global variable with the start of the current day cycle of the clan members (see {@link #checkCycle}). */
	private static final String CYCLE_VARIABLE = "FAKE_CLAN_MEMBER_CYCLE";
	/** The most day cycles rolled at once, after the server was down for a long time. */
	private static final int MAX_MISSED_CYCLES = 30;
	/** How often the day cycle is checked. */
	private static final long CYCLE_CHECK_INTERVAL = 60000;
	/** The highest level a clan member reaches. */
	private static final int MAX_LEVEL = 85;
	
	private static final String CREATE_MEMBERS = "CREATE TABLE IF NOT EXISTS `fake_clan_members` (`name` VARCHAR(35) NOT NULL, `clan_id` INT UNSIGNED NOT NULL, `build` VARCHAR(64) NOT NULL, `class_id` SMALLINT UNSIGNED NOT NULL, `level` TINYINT UNSIGNED NOT NULL, `female` TINYINT UNSIGNED NOT NULL DEFAULT 0, `hair` TINYINT UNSIGNED NOT NULL DEFAULT 0, `hair_color` TINYINT UNSIGNED NOT NULL DEFAULT 0, `face` TINYINT UNSIGNED NOT NULL DEFAULT 0, `title` VARCHAR(16) NOT NULL DEFAULT '', `seed` BIGINT NOT NULL DEFAULT 0, `joined` BIGINT UNSIGNED NOT NULL DEFAULT 0, `academy_level` TINYINT UNSIGNED NOT NULL DEFAULT 0, PRIMARY KEY (`name`), KEY `clan_id` (`clan_id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8";
	/** The academy column, missing in a table made before there were academy members. */
	private static final String FIND_ACADEMY_COLUMN = "SHOW COLUMNS FROM fake_clan_members LIKE 'academy_level'";
	private static final String ADD_ACADEMY_COLUMN = "ALTER TABLE fake_clan_members ADD COLUMN `academy_level` TINYINT UNSIGNED NOT NULL DEFAULT 0";
	private static final String SELECT_MEMBERS = "SELECT * FROM fake_clan_members";
	private static final String INSERT_MEMBER = "REPLACE INTO fake_clan_members (name, clan_id, build, class_id, level, female, hair, hair_color, face, title, seed, joined, academy_level) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
	private static final String UPDATE_MEMBER = "UPDATE fake_clan_members SET level=?, title=?, seed=? WHERE name=?";
	private static final String DELETE_MEMBER = "DELETE FROM fake_clan_members WHERE name=?";
	
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
	private static final String[] LEVEL_UP =
	{
		"ding %l",
		"%l!! :D",
		"finally %l",
		"lvl %l, gratz me",
		"ding! %l",
	};
	private static final String[] LEVEL_UP_REROLL =
	{
		"%l, time for new gear",
		"ding %l, new grade!",
		"%l finally, can wear new stuff",
	};
	private static final String[] GRADUATE =
	{
		"%l, graduated! ty all",
		"ding %l, academy done :D",
		"2nd class! thx for everything guys",
		"graduated, cya around",
	};
	private static final String[] FAREWELL =
	{
		"gl all, im out",
		"sry guys, leaving the clan",
		"cya all, it was fun",
		"bye, joining my friends' clan",
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
	/** Fake members of players' clans, by name in lower case. */
	private final Map<String, Member> _members = new ConcurrentHashMap<>();
	
	protected FakeClanManager()
	{
		load();
		loadMembers();
		
		// Declaring a war calls back here, so not before this manager is made.
		ThreadPool.execute(this::startWars);
		ThreadPool.scheduleAtFixedRate(this::refreshLooks, REFRESH_INTERVAL, REFRESH_INTERVAL);
		ThreadPool.scheduleAtFixedRate(this::checkCycle, CYCLE_CHECK_INTERVAL, CYCLE_CHECK_INTERVAL);
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
	 * @return the clans new fake players join (FakeClanNames), in their order
	 */
	public List<Clan> getActiveClans()
	{
		return Collections.unmodifiableList(_activeClans);
	}
	
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
		shareClan(fake, friend, false);
	}
	
	/**
	 * A fake player that comes with another one joins its clan.
	 * @param fake the fake player
	 * @param friend the one it comes with
	 * @param always {@code true} for a group that came to fight together (see {@link PvpSpotManager}), {@code false} to join with {@link FakeClanConfig#SAME_CLAN_CHANCE}
	 * @return {@code true} if they are in the same clan now
	 */
	public boolean shareClan(Npc fake, Npc friend, boolean always)
	{
		final FakePlayerHolder info = getInfo(fake);
		final FakePlayerHolder friendInfo = getInfo(friend);
		if ((info == null) || (friendInfo == null) || !isEnabled() || (friendInfo.getClanId() == 0) || !_fakeClans.containsKey(friendInfo.getClanId()))
		{
			return false;
		}
		
		if (friendInfo.getClanId() == info.getClanId())
		{
			return true;
		}
		
		if (!always && (Rnd.get(100) >= FakeClanConfig.SAME_CLAN_CHANCE))
		{
			return false;
		}
		
		info.setClan(friendInfo.getClanId(), randomTitle());
		fake.broadcastInfo();
		return true;
	}
	
	/**
	 * Makes a fake player that isn't in a clan a member of one of the clans, for a group that came to fight together (see {@link PvpSpotManager}).
	 * @param fake the fake player
	 * @return {@code true} if it is in a clan now
	 */
	public boolean joinAnyClan(Npc fake)
	{
		final FakePlayerHolder info = getInfo(fake);
		if ((info == null) || !isEnabled())
		{
			return false;
		}
		
		if (info.getClanId() != 0)
		{
			return true;
		}
		
		if (_activeClans.isEmpty())
		{
			return false;
		}
		
		info.setClan(_activeClans.get(Rnd.get(_activeClans.size())).getId(), randomTitle());
		fake.broadcastInfo();
		return true;
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
	 * @param creature a player, a summon or a fake player
	 * @return the clan whose wars it fights: its clan, {@code null} for a fake player in a players' clan academy (like a player there, see {@link #getClan})
	 */
	private Clan getWarClan(Creature creature)
	{
		final Clan clan = getClan(creature);
		if ((clan == null) || isFakeClan(clan))
		{
			return clan;
		}
		
		final Npc fake = creature instanceof FakePlayerPvpServitor ? ((FakePlayerPvpServitor) creature).getOwner() : creature.isFakePlayer() ? creature.asNpc() : null;
		final Member member = fake != null ? _members.get(key(fake.getName())) : null;
		return (member != null) && (member.clanId == clan.getId()) && member.isAcademy() ? null : clan;
	}
	
	/**
	 * @param creature a fake player, player or summon
	 * @param other another one
	 * @return {@code true} if their clans are at war, both having declared it
	 */
	public boolean isWarEnemy(Creature creature, Creature other)
	{
		final Clan clan = getWarClan(creature);
		final Clan otherClan = getWarClan(other);
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
		if (!player.isAcademyMember() && (getWarClan(fake) != null) && clan.isAtWarWith(fakeClan.getId()))
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
	 * Every {@link #REFRESH_INTERVAL} ms: members of a clan whose crest or alliance changed are shown again, those of a clan that is gone leave it, and the fake members of players' clans are online or not (see {@link #updateMembers}).
	 */
	private void refreshLooks()
	{
		try
		{
			final List<Npc> fakes = getFakes();
			final Map<Integer, Integer> looks = new HashMap<>();
			for (Npc fake : fakes)
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
			}
			
			updateMembers(fakes);
			
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
	
	// ---------------------------------------------------------------------------------------------
	// Members of players' clans
	// ---------------------------------------------------------------------------------------------
	
	/**
	 * A fake player that joined a players' clan, kept in the database ({@code fake_clan_members}). Its name, class line and looks never change; it logs off and on again, gains levels and may leave (see {@link #rollCycle}).
	 */
	private static class Member
	{
		final String name;
		final int clanId;
		final String buildName;
		/** The last class of its class line (the one of its build), to find a build again if its own is gone. */
		final int classId;
		final Looks looks;
		final long joined;
		volatile int level;
		volatile String title;
		/** The seed its gear, passive tree and personality are made from: they stay the same until it is rolled again (see {@link FakeClanConfig#MEMBER_REROLL_LEVELS}). */
		volatile long seed;
		/** Its fake player while it is online (or dead and coming back), {@code null} while it is offline. */
		volatile Npc npc;
		/** When it may log in again. */
		volatile long nextLogin;
		/** The level it joined the clan academy at, 0 for a member of the main clan. */
		final int academyLevel;
		
		Member(String name, int clanId, String buildName, int classId, Looks looks, long joined, int level, String title, long seed, int academyLevel)
		{
			this.name = name;
			this.clanId = clanId;
			this.buildName = buildName;
			this.classId = classId;
			this.looks = looks;
			this.joined = joined;
			this.level = level;
			this.title = title;
			this.seed = seed;
			this.academyLevel = academyLevel;
		}
		
		/**
		 * @return its build, or one of its class line if its own was removed from the data, {@code null} if there is none
		 */
		FakePlayerPvpBuild getBuild()
		{
			final FakePlayerPvpBuild build = FakePlayerPvpData.getInstance().getBuild(buildName);
			if (build != null)
			{
				return build;
			}
			
			final PlayerClass lastClass = PlayerClass.getPlayerClass(classId);
			return lastClass != null ? findBuild(lastClass, 85) : null;
		}
		
		/**
		 * @return its class at its level
		 */
		PlayerClass getPlayerClass()
		{
			final FakePlayerPvpBuild build = getBuild();
			if (build != null)
			{
				return build.getPlayerClass(level);
			}
			
			// Like FakePlayerPvpBuild.getPlayerClass(int).
			final int classLevel = level >= 76 ? 3 : level >= 40 ? 2 : level >= 20 ? 1 : 0;
			PlayerClass playerClass = PlayerClass.getPlayerClass(classId);
			while ((playerClass.level() > classLevel) && (playerClass.getParent() != null))
			{
				playerClass = playerClass.getParent();
			}
			return playerClass;
		}
		
		/**
		 * @return {@code true} if it is in the clan academy
		 */
		boolean isAcademy()
		{
			return academyLevel > 0;
		}
		
		/**
		 * @return its place in the clan: 0 for the main clan, {@link Clan#SUBUNIT_ACADEMY} for the academy
		 */
		int getPledgeType()
		{
			return isAcademy() ? Clan.SUBUNIT_ACADEMY : 0;
		}
		
		/**
		 * @return the object id it shows in the clan window: its fake player's while it is online, 0 while it is offline
		 */
		int getObjectId()
		{
			final Npc fake = npc;
			return fake != null ? fake.getObjectId() : 0;
		}
	}
	
	/**
	 * @param name a character name
	 * @return the key of its member in {@link #_members}
	 */
	private static String key(String name)
	{
		return name.toLowerCase();
	}
	
	/**
	 * @param playerClass a class
	 * @param level a level
	 * @return a build that has {@code playerClass} at {@code level}, else one whose class line has it, {@code null} if none
	 */
	private static FakePlayerPvpBuild findBuild(PlayerClass playerClass, int level)
	{
		final List<FakePlayerPvpBuild> exact = new ArrayList<>();
		final List<FakePlayerPvpBuild> line = new ArrayList<>();
		for (FakePlayerPvpBuild build : FakePlayerPvpData.getInstance().getBuilds())
		{
			if (build.getPlayerClass(level) == playerClass)
			{
				exact.add(build);
			}
			
			for (PlayerClass lineClass = build.getPlayerClass(); lineClass != null; lineClass = lineClass.getParent())
			{
				if (lineClass == playerClass)
				{
					line.add(build);
					break;
				}
			}
		}
		
		final List<FakePlayerPvpBuild> builds = !exact.isEmpty() ? exact : line;
		return builds.isEmpty() ? null : builds.get(Rnd.get(builds.size()));
	}
	
	/**
	 * @param fake a fake player
	 * @return the build it becomes a clan member with: its own (a roaming one), or one of its class (a town one), {@code null} if there is none
	 */
	private static FakePlayerPvpBuild findBuild(Npc fake)
	{
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		if (profile != null)
		{
			return profile.getBuild();
		}
		
		final FakePlayerHolder info = getInfo(fake);
		return info != null ? findBuild(info.getPlayerClass(), fake.getLevel()) : null;
	}
	
	/**
	 * Loads the fake members of players' clans. Those of a clan that is gone are deleted.
	 */
	private void loadMembers()
	{
		final long now = System.currentTimeMillis();
		final List<String> gone = new ArrayList<>();
		try (Connection con = DatabaseFactory.getConnection();
			Statement statement = con.createStatement())
		{
			statement.execute(CREATE_MEMBERS);
			boolean hasAcademyColumn;
			try (ResultSet rs = statement.executeQuery(FIND_ACADEMY_COLUMN))
			{
				hasAcademyColumn = rs.next();
			}
			if (!hasAcademyColumn)
			{
				statement.execute(ADD_ACADEMY_COLUMN);
			}
			
			try (ResultSet rs = statement.executeQuery(SELECT_MEMBERS))
			{
				while (rs.next())
				{
					final String name = rs.getString("name");
					final Clan clan = ClanTable.getInstance().getClan(rs.getInt("clan_id"));
					if ((clan == null) || _fakeClans.containsKey(clan.getId()) || (PlayerClass.getPlayerClass(rs.getInt("class_id")) == null))
					{
						gone.add(name);
						continue;
					}
					
					final Looks looks = new Looks(rs.getInt("female") == 1, rs.getInt("hair"), rs.getInt("hair_color"), rs.getInt("face"));
					final Member member = new Member(name, clan.getId(), rs.getString("build"), rs.getInt("class_id"), looks, rs.getLong("joined"), rs.getInt("level"), rs.getString("title"), rs.getLong("seed"), rs.getInt("academy_level"));
					
					// They log in one after another, not all at once.
					member.nextLogin = now + (Rnd.get(0, FakeClanConfig.MEMBER_OFFLINE_MAX) * 60000L);
					_members.put(key(name), member);
					FakePlayerPvpManager.getInstance().keepName(name);
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not load the fake members of players' clans.", e);
		}
		
		for (String name : gone)
		{
			deleteMember(name);
		}
		
		if (!_members.isEmpty())
		{
			LOGGER.info(getClass().getSimpleName() + ": " + _members.size() + " fake members of players' clans.");
		}
	}
	
	private static void storeMember(Member member)
	{
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(INSERT_MEMBER))
		{
			ps.setString(1, member.name);
			ps.setInt(2, member.clanId);
			ps.setString(3, member.buildName);
			ps.setInt(4, member.classId);
			ps.setInt(5, member.level);
			ps.setInt(6, member.looks.female() ? 1 : 0);
			ps.setInt(7, member.looks.hair());
			ps.setInt(8, member.looks.hairColor());
			ps.setInt(9, member.looks.face());
			ps.setString(10, member.title);
			ps.setLong(11, member.seed);
			ps.setLong(12, member.joined);
			ps.setInt(13, member.academyLevel);
			ps.execute();
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, FakeClanManager.class.getSimpleName() + ": Could not store clan member " + member.name + ".", e);
		}
	}
	
	private static void updateMember(Member member)
	{
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(UPDATE_MEMBER))
		{
			ps.setInt(1, member.level);
			ps.setString(2, member.title);
			ps.setLong(3, member.seed);
			ps.setString(4, member.name);
			ps.execute();
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, FakeClanManager.class.getSimpleName() + ": Could not update clan member " + member.name + ".", e);
		}
	}
	
	private static void deleteMember(String name)
	{
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(DELETE_MEMBER))
		{
			ps.setString(1, name);
			ps.execute();
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, FakeClanManager.class.getSimpleName() + ": Could not delete clan member " + name + ".", e);
		}
	}
	
	/**
	 * @param name a character name
	 * @return {@code true} if it is the name of a fake member of a players' clan (online or not)
	 */
	public boolean isMemberName(String name)
	{
		return (name != null) && _members.containsKey(key(name));
	}
	
	/**
	 * @param clanId a clan id
	 * @return how many fake members that clan has, online or not
	 */
	private int countClanMembers(int clanId)
	{
		int count = 0;
		for (Member member : _members.values())
		{
			if (member.clanId == clanId)
			{
				count++;
			}
		}
		return count;
	}
	
	/**
	 * @param clanId a clan id
	 * @param pledgeType 0 for the main clan, {@link Clan#SUBUNIT_ACADEMY} for the academy
	 * @return how many fake members that clan has there, online or not
	 */
	private int countClanMembers(int clanId, int pledgeType)
	{
		int count = 0;
		for (Member member : _members.values())
		{
			if ((member.clanId == clanId) && (member.getPledgeType() == pledgeType))
			{
				count++;
			}
		}
		return count;
	}
	
	/**
	 * Sends a player the fake members of its clan, online and offline, for its clan window.
	 * @param clan a clan of players
	 * @param player a player of that clan
	 */
	public void sendMembers(Clan clan, Player player)
	{
		if ((clan == null) || isFakeClan(clan))
		{
			return;
		}
		
		for (Member member : _members.values())
		{
			if (member.clanId == clan.getId())
			{
				player.sendPacket(new PledgeShowMemberListAdd(member.name, member.level, member.getPlayerClass().getId(), member.getObjectId(), member.getPledgeType()));
			}
		}
	}
	
	/**
	 * @param member a member
	 * @return its line in the clan window, as it is now
	 */
	private static PledgeShowMemberListUpdate memberUpdate(Member member)
	{
		final PlayerClass playerClass = member.getPlayerClass();
		return new PledgeShowMemberListUpdate(member.name, member.level, playerClass.getId(), member.looks.female(), playerClass.getRace().ordinal(), member.getObjectId(), member.getPledgeType());
	}
	
	/**
	 * Called with a fake player that takes a monster's place (see {@link FakePlayerPvpManager}): a fake member of a players' clan of about that level, offline long enough, logs in there instead of a new fake player.
	 * @param level the level of the fake player that would come
	 * @param x the x
	 * @param y the y
	 * @param z the z
	 * @param instanceId the instance
	 * @param monster the monster it replaces
	 * @param spawn the spawn of {@code monster}
	 * @return the member that logged in, {@code null} if none did
	 */
	public Npc logIn(int level, int x, int y, int z, int instanceId, Npc monster, Spawn spawn)
	{
		if (_members.isEmpty() || !isEnabled())
		{
			return null;
		}
		
		final long now = System.currentTimeMillis();
		final List<Member> ready = new ArrayList<>();
		for (Member member : _members.values())
		{
			if ((member.npc == null) && (now >= member.nextLogin) && (Math.abs(member.level - level) <= FakeClanConfig.MEMBER_LOGIN_LEVEL_RANGE))
			{
				ready.add(member);
			}
		}
		
		if (ready.isEmpty())
		{
			return null;
		}
		
		final Member member = ready.get(Rnd.get(ready.size()));
		final FakePlayerPvpBuild build = member.getBuild();
		final Clan clan = ClanTable.getInstance().getClan(member.clanId);
		synchronized (member)
		{
			if ((member.npc != null) || (now < member.nextLogin) || !_members.containsKey(key(member.name)) || FakePlayerPvpManager.getInstance().isComingBack(member.name))
			{
				return null;
			}
			
			if ((build == null) || (clan == null))
			{
				member.nextLogin = now + (FakeClanConfig.MEMBER_OFFLINE_MAX * 60000L);
				return null;
			}
			
			final Npc fake = FakePlayerPvpManager.getInstance().spawnClanMember(build, member.level, member.name, member.looks, member.seed, member.clanId, member.title, x, y, z, instanceId, monster, spawn);
			if (fake == null)
			{
				member.nextLogin = now + (FakeClanConfig.MEMBER_OFFLINE_MIN * 60000L);
				return null;
			}
			
			setOnline(member, fake);
			return fake;
		}
	}
	
	/**
	 * Its fake player is (still) in the world: the clan sees it log in if it was offline.
	 * @param member the member
	 * @param fake its fake player
	 */
	private static void setOnline(Member member, Npc fake)
	{
		final boolean wasOffline = member.npc == null;
		member.npc = fake;
		if (!wasOffline)
		{
			return;
		}
		
		final Clan clan = ClanTable.getInstance().getClan(member.clanId);
		if (clan != null)
		{
			clan.broadcastToOnlineMembers(new SystemMessage(SystemMessageId.CLAN_MEMBER_S1_HAS_LOGGED_INTO_GAME).addString(member.name));
			clan.broadcastToOnlineMembers(memberUpdate(member));
		}
	}
	
	/**
	 * Its fake player left the world: it is offline for a while.
	 * @param member the member
	 * @param now the current time
	 */
	private static void setOffline(Member member, long now)
	{
		member.npc = null;
		member.nextLogin = now + (Rnd.get(FakeClanConfig.MEMBER_OFFLINE_MIN, FakeClanConfig.MEMBER_OFFLINE_MAX) * 60000L);
		final Clan clan = ClanTable.getInstance().getClan(member.clanId);
		if (clan != null)
		{
			clan.broadcastToOnlineMembers(memberUpdate(member));
		}
	}
	
	/**
	 * Every {@link #REFRESH_INTERVAL} ms: which fake members of players' clans are online (their fake player in the world, or dead and coming back). A fake player that wears a players' clan it is no member of anymore (it left while it was dead) takes it off.
	 * @param fakes the fake players in the world
	 */
	private void updateMembers(List<Npc> fakes)
	{
		final Map<Member, Npc> online = new HashMap<>();
		for (Npc fake : fakes)
		{
			final FakePlayerHolder info = getInfo(fake);
			if ((info == null) || (info.getClanId() == 0) || _fakeClans.containsKey(info.getClanId()))
			{
				continue;
			}
			
			final Member member = _members.get(key(fake.getName()));
			if ((member == null) || (member.clanId != info.getClanId()))
			{
				info.setClan(0, "");
				if (fake.isSpawned())
				{
					fake.broadcastInfo();
				}
				continue;
			}
			
			if (fake.isSpawned())
			{
				online.put(member, fake);
			}
		}
		
		final long now = System.currentTimeMillis();
		for (Member member : _members.values())
		{
			// Its clan was dissolved.
			if (ClanTable.getInstance().getClan(member.clanId) == null)
			{
				removeMember(member, false);
				continue;
			}
			
			final Npc fake = online.get(member);
			synchronized (member)
			{
				if (fake != null)
				{
					if (member.npc != fake)
					{
						setOnline(member, fake);
					}
				}
				else if ((member.npc != null) && !member.npc.isSpawned() && !FakePlayerPvpManager.getInstance().isComingBack(member.name))
				{
					setOffline(member, now);
				}
			}
		}
	}
	
	/**
	 * Every minute: once a day cycle ({@link FakeClanConfig#MEMBER_CYCLE_HOURS}) went by, the fake members of players' clans roll to leave and to level up ({@link #rollCycle}). The cycles missed while the server was down are rolled too, up to
	 * {@link #MAX_MISSED_CYCLES}.
	 */
	private void checkCycle()
	{
		try
		{
			final long now = System.currentTimeMillis();
			final long period = FakeClanConfig.MEMBER_CYCLE_HOURS * 3600000L;
			final long last = GlobalVariablesManager.getInstance().getLong(CYCLE_VARIABLE, 0);
			if ((last <= 0) || (last > now))
			{
				GlobalVariablesManager.getInstance().set(CYCLE_VARIABLE, now);
				GlobalVariablesManager.getInstance().storeMe();
				return;
			}
			
			final long cycles = (now - last) / period;
			if (cycles <= 0)
			{
				return;
			}
			
			// Moved on first: a cycle is never rolled twice.
			GlobalVariablesManager.getInstance().set(CYCLE_VARIABLE, last + (cycles * period));
			GlobalVariablesManager.getInstance().storeMe();
			if (isEnabled())
			{
				for (long i = Math.min(cycles, MAX_MISSED_CYCLES); i > 0; i--)
				{
					rollCycle();
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not roll the day cycle of the clan members.", e);
		}
	}
	
	/**
	 * A day cycle: every fake member of a players' clan may leave it ({@link FakeClanConfig#MEMBER_LEAVE_CHANCE}), and one that stays may gain a level ({@link FakeClanConfig#MEMBER_LEVEL_UP_CHANCE}). One in the academy stays and gains a level every
	 * cycle, until it graduates.
	 */
	private void rollCycle()
	{
		for (Member member : _members.values())
		{
			if (member.isAcademy())
			{
				levelUp(member);
			}
			else if (Rnd.get(100) < FakeClanConfig.MEMBER_LEAVE_CHANCE)
			{
				removeMember(member, true);
			}
			else if ((member.level < MAX_LEVEL) && (Rnd.get(100) < FakeClanConfig.MEMBER_LEVEL_UP_CHANCE))
			{
				levelUp(member);
			}
		}
	}
	
	/**
	 * A fake member gains a level. At the levels of {@link FakeClanConfig#MEMBER_REROLL_LEVELS} its gear and passive tree are rolled again. Its fake player in the world keeps its level until it logs in again. One in the academy that reaches its 2nd class
	 * graduates ({@link #graduate}).
	 * @param member the member
	 */
	private void levelUp(Member member)
	{
		member.level++;
		if (member.isAcademy() && (member.getPlayerClass().level() >= 2))
		{
			graduate(member);
			return;
		}
		
		final boolean reroll = FakeClanConfig.MEMBER_REROLL_LEVELS.contains(member.level);
		if (reroll)
		{
			member.seed = Rnd.nextLong();
		}
		updateMember(member);
		
		final Clan clan = ClanTable.getInstance().getClan(member.clanId);
		if (clan == null)
		{
			return;
		}
		
		clan.broadcastToOnlineMembers(memberUpdate(member));
		final Npc fake = member.npc;
		if ((fake != null) && fake.isSpawned() && !fake.isDead() && (Rnd.get(100) < 60))
		{
			final String text = (reroll && Rnd.nextBoolean() ? LEVEL_UP_REROLL[Rnd.get(LEVEL_UP_REROLL.length)] : LEVEL_UP[Rnd.get(LEVEL_UP.length)]).replace("%l", String.valueOf(member.level));
			clanChat(fake, clan, text);
		}
	}
	
	/**
	 * A fake member of the academy took its 2nd class: like a player, it graduates and leaves the clan, which earns reputation (the more, the lower the level it joined the academy at).
	 * @param member the member
	 */
	private void graduate(Member member)
	{
		final Clan clan = ClanTable.getInstance().getClan(member.clanId);
		if (clan == null)
		{
			removeMember(member, false);
			return;
		}
		
		// Like Player.setPlayerClass for a player graduate.
		final int reputation;
		if (member.academyLevel <= 16)
		{
			reputation = FeatureConfig.JOIN_ACADEMY_MAX_REP_SCORE;
		}
		else if (member.academyLevel >= 39)
		{
			reputation = FeatureConfig.JOIN_ACADEMY_MIN_REP_SCORE;
		}
		else
		{
			reputation = FeatureConfig.JOIN_ACADEMY_MAX_REP_SCORE - ((member.academyLevel - 16) * 20);
		}
		
		final Npc fake = member.npc;
		if ((fake != null) && fake.isSpawned() && !fake.isDead() && (Rnd.get(100) < 70))
		{
			clanChat(fake, clan, GRADUATE[Rnd.get(GRADUATE.length)].replace("%l", String.valueOf(member.level)));
		}
		
		removeMember(member, false);
		clan.addReputationScore(reputation);
		clan.broadcastToOnlineMembers(new SystemMessage(SystemMessageId.CLAN_MEMBER_S1_HAS_BEEN_EXPELLED).addString(member.name));
		clan.broadcastToOnlineMembers(new SystemMessage(SystemMessageId.SINCE_THE_CLAN_HAS_RECEIVED_A_GRADUATE_OF_THE_CLAN_ACADEMY_IT_HAS_EARNED_S1_POINTS_TOWARD_ITS_REPUTATION_SCORE).addInt(reputation));
	}
	
	/**
	 * A fake member leaves its clan (it left, it was dismissed, or the clan is gone): it is deleted, and its fake player in the world takes the clan off.
	 * @param member the member
	 * @param withdrew {@code true} if it left on its own (the clan is told, and it says goodbye if it is online)
	 */
	private void removeMember(Member member, boolean withdrew)
	{
		if (!_members.remove(key(member.name), member))
		{
			return;
		}
		
		deleteMember(member.name);
		
		final Npc fake;
		synchronized (member)
		{
			fake = member.npc;
			member.npc = null;
		}
		
		final boolean inWorld = (fake != null) && fake.isSpawned();
		FakePlayerPvpManager.getInstance().unkeepName(member.name, inWorld || FakePlayerPvpManager.getInstance().isComingBack(member.name));
		
		final Clan clan = ClanTable.getInstance().getClan(member.clanId);
		if (withdrew && (clan != null) && inWorld && !fake.isDead() && (Rnd.get(100) < 60))
		{
			clanChat(fake, clan, FAREWELL[Rnd.get(FAREWELL.length)]);
		}
		
		// The dead one coming back shares this template: it comes back without the clan too.
		final FakePlayerHolder info = getInfo(fake);
		if ((info != null) && (info.getClanId() == member.clanId))
		{
			info.setClan(0, "");
			if (inWorld)
			{
				fake.broadcastInfo();
			}
		}
		
		if (clan != null)
		{
			if (withdrew)
			{
				clan.broadcastToOnlineMembers(new SystemMessage(SystemMessageId.S1_HAS_WITHDRAWN_FROM_THE_CLAN).addString(member.name));
			}
			clan.broadcastToOnlineMembers(new PledgeShowMemberListDelete(member.name));
		}
	}
	
	private static void clanChat(Npc fake, Clan clan, String text)
	{
		ThreadPool.schedule(() ->
		{
			if (fake.isSpawned() && !fake.isDead())
			{
				clan.broadcastToOnlineMembers(new CreatureSay(fake, ChatType.CLAN, fake.getName(), text));
			}
		}, Rnd.get(1500, 4000));
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
	 * Called from {@code RequestJoinPledge} when the invited one isn't a player: a fake player that isn't in a clan answers like a player would and, if it accepts, becomes a member of the inviter's clan for good (with a random title), see {@link Member}.
	 * @param player the player that invites
	 * @param objectId the invited object
	 * @param pledgeType where it is invited: {@link Clan#SUBUNIT_ACADEMY} for the academy, the main clan otherwise
	 * @return {@code true} if it is a fake player (the invite is handled)
	 */
	public boolean onClanInvite(Player player, int objectId, int pledgeType)
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
		
		// Only the academy is told apart: other units keep it in the main clan.
		final boolean academy = pledgeType == Clan.SUBUNIT_ACADEMY;
		if (!canInvite(player, fake, academy, true))
		{
			return true;
		}
		
		final boolean accept = decide("clan:" + fake.getObjectId() + ":" + clan.getId(), FakeClanConfig.INVITE_ACCEPT_CHANCE) && !FakePlayerPvpManager.isInPvp(fake);
		ThreadPool.schedule(() -> answerClanInvite(player, fake, clan, academy, accept), Rnd.get(1500, 5000));
		return true;
	}
	
	/**
	 * @param player the player that invites
	 * @param fake the invited fake player
	 * @param academy {@code true} for the clan academy, {@code false} for the main clan
	 * @param tell {@code true} to tell the player why not
	 * @return {@code true} if {@code fake} may join the clan of {@code player} (like {@link Clan#checkClanJoinCondition} for a player)
	 */
	private boolean canInvite(Player player, Npc fake, boolean academy, boolean tell)
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
		else if (isMemberName(fake.getName()) || (findBuild(fake) == null))
		{
			// Still the member of a clan it left a moment ago, or a class it can't come back as.
			if (tell)
			{
				player.sendPacket(new SystemMessage(SystemMessageId.S1_DECLINED_YOUR_CLAN_INVITATION).addString(fake.getName()));
			}
			return false;
		}
		else if (academy && (clan.getSubPledge(Clan.SUBUNIT_ACADEMY) == null))
		{
			message = SystemMessageId.YOU_HAVE_INVITED_THE_WRONG_TARGET;
		}
		else if (academy && ((fake.getLevel() > 40) || (info.getPlayerClass().level() >= 2)))
		{
			if (tell)
			{
				player.sendPacket(new SystemMessage(SystemMessageId.S1_DOES_NOT_MEET_THE_REQUIREMENTS_TO_JOIN_A_CLAN_ACADEMY).addString(fake.getName()));
				player.sendPacket(SystemMessageId.TO_JOIN_A_CLAN_ACADEMY_CHARACTERS_MUST_BE_LEVEL_40_OR_BELOW_NOT_BELONG_ANOTHER_CLAN_AND_NOT_YET_COMPLETED_THEIR_2ND_CLASS_TRANSFER);
			}
			return false;
		}
		else if ((FakeClanConfig.MAX_RECRUITS > 0) && (countClanMembers(clan.getId()) >= FakeClanConfig.MAX_RECRUITS))
		{
			if (tell)
			{
				player.sendPacket(academy ? new SystemMessage(SystemMessageId.THE_ACADEMY_ROYAL_GUARD_ORDER_OF_KNIGHTS_IS_FULL_AND_CANNOT_ACCEPT_NEW_MEMBERS_AT_THIS_TIME) : new SystemMessage(SystemMessageId.S1_IS_FULL_AND_CANNOT_ACCEPT_ADDITIONAL_CLAN_MEMBERS_AT_THIS_TIME).addString(clan.getName()));
			}
			return false;
		}
		else if (academy && ((clan.getSubPledgeMembersCount(Clan.SUBUNIT_ACADEMY) + countClanMembers(clan.getId(), Clan.SUBUNIT_ACADEMY)) >= clan.getMaxNrOfMembers(Clan.SUBUNIT_ACADEMY)))
		{
			message = SystemMessageId.THE_ACADEMY_ROYAL_GUARD_ORDER_OF_KNIGHTS_IS_FULL_AND_CANNOT_ACCEPT_NEW_MEMBERS_AT_THIS_TIME;
		}
		else if (!academy && ((clan.getSubPledgeMembersCount(0) + countClanMembers(clan.getId(), 0)) >= clan.getMaxNrOfMembers(0)))
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
	
	private void answerClanInvite(Player player, Npc fake, Clan clan, boolean academy, boolean accept)
	{
		if (!player.isOnline() || (player.getClan() != clan) || !fake.isSpawned() || fake.isDead())
		{
			return;
		}
		
		if (!accept || !canInvite(player, fake, academy, false))
		{
			player.sendPacket(new SystemMessage(SystemMessageId.S1_DECLINED_YOUR_CLAN_INVITATION).addString(fake.getName()));
			if (Rnd.get(100) < 60)
			{
				whisper(fake, player, DECLINE[Rnd.get(DECLINE.length)]);
			}
			return;
		}
		
		// A member for good: its name, class line and looks are kept.
		final FakePlayerPvpBuild build = findBuild(fake);
		final FakePlayerHolder info = getInfo(fake);
		final String title = randomTitle();
		final Looks looks = new Looks(fake.getTemplate().getSex() == Sex.FEMALE, info.getHair(), info.getHairColor(), info.getFace());
		final Member member = new Member(fake.getName(), clan.getId(), build.getName(), build.getPlayerClass().getId(), looks, System.currentTimeMillis(), fake.getLevel(), title, Rnd.nextLong(), academy ? fake.getLevel() : 0);
		member.npc = fake;
		if (_members.putIfAbsent(key(member.name), member) != null)
		{
			return;
		}
		FakePlayerPvpManager.getInstance().keepName(member.name);
		storeMember(member);
		
		info.setClan(clan.getId(), title);
		fake.broadcastInfo();
		clan.broadcastToOnlineMembers(new SystemMessage(SystemMessageId.S1_HAS_JOINED_THE_CLAN).addString(fake.getName()));
		clan.broadcastToOnlineMembers(new PledgeShowMemberListAdd(member.name, member.level, member.getPlayerClass().getId(), fake.getObjectId(), member.getPledgeType()));
		if (Rnd.get(100) < 70)
		{
			clanChat(fake, clan, JOIN[Rnd.get(JOIN.length)]);
		}
	}
	
	/**
	 * Called from {@code RequestOustPledgeMember}: a fake member of the clan of {@code player} is dismissed, online or not.
	 * @param player the player that dismisses
	 * @param name the name to dismiss
	 * @return {@code true} if {@code name} is a fake member of the clan (it is handled)
	 */
	public boolean onDismiss(Player player, String name)
	{
		final Clan clan = player.getClan();
		if ((clan == null) || (name == null) || isFakeClan(clan))
		{
			return false;
		}
		
		final Member member = _members.get(key(name));
		if ((member == null) || (member.clanId != clan.getId()))
		{
			return false;
		}
		
		if (!player.hasAccess(ClanAccess.REMOVE_MEMBER))
		{
			player.sendPacket(SystemMessageId.YOU_ARE_NOT_AUTHORIZED_TO_DO_THAT);
			return true;
		}
		
		final Npc fake = member.npc;
		if ((fake != null) && fake.isSpawned() && fake.isInCombat())
		{
			player.sendPacket(SystemMessageId.A_CLAN_MEMBER_MAY_NOT_BE_DISMISSED_DURING_COMBAT);
			return true;
		}
		
		removeMember(member, false);
		clan.broadcastToOnlineMembers(new SystemMessage(SystemMessageId.CLAN_MEMBER_S1_HAS_BEEN_EXPELLED).addString(member.name));
		player.sendPacket(SystemMessageId.YOU_HAVE_SUCCEEDED_IN_EXPELLING_THE_CLAN_MEMBER);
		return true;
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
		final long online = _members.values().stream().filter(member -> member.npc != null).count();
		final long academy = _members.values().stream().filter(Member::isAcademy).count();
		lines.add("Fake members of players' clans: " + _members.size() + " (" + academy + " in an academy), " + online + " online.");
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
