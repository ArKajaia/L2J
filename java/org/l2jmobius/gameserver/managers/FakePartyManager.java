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
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.FakePlayerPvpAI;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.custom.FakePartyConfig;
import org.l2jmobius.gameserver.config.custom.FakePlayerPvpConfig;
import org.l2jmobius.gameserver.config.custom.FakePlayersConfig;
import org.l2jmobius.gameserver.data.xml.FakePlayerPvpData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerHolder;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerParty;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild.Role;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.groups.Party;
import org.l2jmobius.gameserver.model.groups.PartyDistributionType;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;
import org.l2jmobius.gameserver.network.serverpackets.ExPartyPetWindowAdd;
import org.l2jmobius.gameserver.network.serverpackets.FakePartySmallWindowAll;
import org.l2jmobius.gameserver.network.serverpackets.FakePartySmallWindowDelete;
import org.l2jmobius.gameserver.network.serverpackets.FakePartySmallWindowUpdate;
import org.l2jmobius.gameserver.network.serverpackets.JoinParty;
import org.l2jmobius.gameserver.network.serverpackets.PartySmallWindowDeleteAll;
import org.l2jmobius.gameserver.network.serverpackets.SystemMessage;

/**
 * Fake party members (see {@link FakePartyConfig}).
 * <ul>
 * <li>A player writes "lf &lt;class&gt;" in chat ({@link #onPlayerChat}): a fake player of that class and of the player's level answers in a whisper and walks over (a free one hunting close by, or one that comes from out of sight).</li>
 * <li>The player invites it ({@link #onInvite}, called from {@code RequestJoinParty}): it accepts and joins the party ({@link FakePlayerParty}). Fake players are npcs, so they are kept next to the player's {@link Party}, and the party window is sent with them
 * ({@link #refreshWindows}).</li>
 * <li>In the party its AI ({@link FakePlayerPvpAI}) follows the leader, fights what the party fights and, for a healer or buffer, looks after the party. Its damage and kills count for the party ({@link #getRewardPlayer}, {@link #getExpShare}), party skills reach it
 * and its area skills spare the party ({@link #isSameGroup}).</li>
 * <li>Dismissed, or when the player leaves the party or logs off, it goes back to hunting where it is. Killed, it comes back to the party from town a while after its body is gone, unless someone resurrects it first.</li>
 * <li>Roaming fake players sometimes come with friends ({@link #onRoamingSpawn}): a party of fake players only, which hunts together.</li>
 * </ul>
 */
public class FakePartyManager
{
	private static final Logger LOGGER = Logger.getLogger(FakePartyManager.class.getName());
	
	/** Party window HP/MP of the fake players, and the waiting ones, are checked this often. */
	private static final long UPDATE_INTERVAL = 1000;
	/** A party holds this many members, players and fake players. */
	private static final int MAX_PARTY_SIZE = 9;
	/** An answering fake player comes from out of sight, or from about this far when every spot around is in sight. */
	private static final int ARRIVAL_DISTANCE = 1300;
	/** The party leader got this far (or into another instance): its fake players get there after {@link FakePartyConfig#TELEPORT_DELAY}, like players taking the same gatekeeper. */
	private static final int TELEPORT_FOLLOW_DISTANCE = 4000;
	/** A fake player this far from the player it goes to (or its party leader), standing still, is sent over (see {@link #walkTo}). */
	private static final int WALK_OVER_DISTANCE = 600;
	/** A fake player that wasn't asked for only joins a player this close. */
	private static final int INVITE_RANGE = 2000;
	/** How long a fake player sticks to its answer to someone's invites. */
	private static final long INVITE_MEMORY = 300000;
	
	/** Words that start a request: "lf ss", "lfm healer", "lf2 dd", "need bp", "looking for ee". */
	private static final Set<String> TRIGGERS = Set.of("lf", "lfm", "lfp", "lfg", "need", "needs");
	
	/** Short names players use for the classes. */
	private static final String[][] SHORT_NAMES =
	{
		// @formatter:off
		{"SPELLSINGER", "ss", "sps", "singer"},
		{"SPELLHOWLER", "sh", "howler"},
		{"SORCERER", "sorc", "sorcer", "sorce"},
		{"NECROMANCER", "necro"},
		{"BISHOP", "bp", "bish", "bishy"},
		{"PROPHET", "pp", "proph", "prof"},
		{"ELDER", "ee", "elvenelder"},
		{"SHILLIEN_ELDER", "se", "selder"},
		{"WARCRYER", "wc", "warcry"},
		{"OVERLORD", "ol"},
		{"BLADEDANCER", "bd", "dancer"},
		{"SWORDSINGER", "sws", "swordsinger"},
		{"TREASURE_HUNTER", "th"},
		{"PLAINS_WALKER", "pw", "plainswalker"},
		{"ABYSS_WALKER", "aw"},
		{"HAWKEYE", "he", "hawk"},
		{"SILVER_RANGER", "sr"},
		{"PHANTOM_RANGER", "pr"},
		{"TEMPLE_KNIGHT", "tk"},
		{"SHILLIEN_KNIGHT", "sk"},
		{"DARK_AVENGER", "da"},
		{"PALADIN", "pal", "pala", "pally"},
		{"GLADIATOR", "glad", "gladi"},
		{"WARLORD", "wl"},
		{"DESTROYER", "destro"},
		{"TYRANT", "tyr"},
		{"MYSTIC_MUSE", "mm"},
		{"CARDINAL", "cardi"},
		{"HIEROPHANT", "hiero"},
		{"EVA_SAINT", "evas", "evassaint"},
		{"EVA_TEMPLAR", "evastemplar"},
		{"SHILLIEN_SAINT", "ssaint"},
		{"DOOMCRYER", "dc"},
		{"SWORD_MUSE", "swm"},
		{"SPECTRAL_DANCER", "sd"},
		{"ARCHMAGE", "am"},
		{"MALE_SOUL_HOUND", "soulhound", "hound"},
		{"FEMALE_SOUL_HOUND", "soulhound", "hound"},
		{"MALE_SOULBREAKER", "soulbreaker", "sb"},
		{"FEMALE_SOULBREAKER", "soulbreaker", "sb"},
		// @formatter:on
	};
	
	/** The healers ("lf healer"). */
	private static final Set<PlayerClass> HEALERS = EnumSet.of(PlayerClass.CARDINAL, PlayerClass.EVA_SAINT, PlayerClass.SHILLIEN_SAINT);
	/** The buffers ("lf buffer"). */
	private static final Set<PlayerClass> BUFFERS = EnumSet.of(PlayerClass.HIEROPHANT, PlayerClass.DOOMCRYER, PlayerClass.DOMINATOR, PlayerClass.SWORD_MUSE, PlayerClass.SPECTRAL_DANCER, PlayerClass.EVA_SAINT, PlayerClass.SHILLIEN_SAINT);
	
	// What fake players say.
	private static final String[] ANSWERS =
	{
		"%c %l here, inv pls",
		"me! %c %l",
		"%l %c, can come",
		"hey, %c %l free, omw",
		"inv me, %c %l",
		"%c %l, where r u?",
		"i can come, %c %l",
	};
	private static final String[] JOIN =
	{
		"ty",
		"thx for inv",
		"hi all",
		"hey",
		"o/",
		"hi :)",
		"hello",
	};
	private static final String[] JOIN_SUPPORT =
	{
		"buffing in a sec",
		"stay close for heals",
		"hi, buffs incoming",
		"tell me if u need something",
	};
	private static final String[] DECLINE =
	{
		"no thx",
		"sry, solo",
		"nah",
		"sry, already have a pt",
	};
	private static final String[] NEVERMIND =
	{
		"nvm then",
		"found a pt, gl",
		"nvm, gl",
	};
	private static final String[] DEATH =
	{
		"dead :(",
		"argh",
		"res pls",
		"wtf",
		"lag...",
	};
	private static final String[] TOWN =
	{
		"brb, going back to town",
		"coming back from town",
		"omw back",
	};
	private static final String[] BACK =
	{
		"back",
		"here again",
		"re",
	};
	private static final String[] RESURRECTED =
	{
		"ty for res",
		"thx",
		"ty",
	};
	private static final String[] BYE =
	{
		"ok gl",
		"bb",
		"cya",
		"gl hf",
	};
	
	/** Parties of fake players with a player, by the object id of that player (the host). */
	private final Map<Integer, FakePlayerParty> _parties = new ConcurrentHashMap<>();
	/** Parties of fake players only. */
	private final Set<FakePlayerParty> _fakeParties = ConcurrentHashMap.newKeySet();
	/** Fake players walking over to a player that asked for their class, and to whom (object id). */
	private final Map<Npc, Integer> _responders = new ConcurrentHashMap<>();
	/** When a player's next request gets an answer (object id -> time). */
	private final Map<Integer, Long> _nextRequest = new ConcurrentHashMap<>();
	/** The answer of a fake player to a player's invites: (fake object id &lt;&lt; 32 | player object id) -> until when, positive for yes, negative for no. */
	private final Map<Long, Long> _decisions = new ConcurrentHashMap<>();
	/** HP/MP of the party fake players last sent to the party window (object id -> HP &lt;&lt; 32 | MP). */
	private final Map<Integer, Long> _lastStatus = new ConcurrentHashMap<>();
	/** Party fake players whose leader is far away (it teleported), and since when (object id -> time). */
	private final Map<Integer, Long> _farSince = new ConcurrentHashMap<>();
	/** Dead party fake players on their way back from town, by name. */
	private final Map<String, ScheduledFuture<?>> _returns = new ConcurrentHashMap<>();
	/** Class names and short names players write, with the builds they ask for. */
	private volatile Map<String, List<FakePlayerPvpBuild>> _names;
	
	protected FakePartyManager()
	{
		ThreadPool.scheduleAtFixedRate(this::update, UPDATE_INTERVAL, UPDATE_INTERVAL);
	}
	
	/**
	 * @return {@code true} if players can ask for fake party members
	 */
	public boolean isEnabled()
	{
		return FakePartyConfig.ENABLED && FakePlayersConfig.FAKE_PLAYERS_ENABLED && !FakePlayerPvpData.getInstance().getBuilds().isEmpty();
	}
	
	// ---------------------------------------------------------------------------------------------
	// Asking for a class in chat
	// ---------------------------------------------------------------------------------------------
	
	/**
	 * Called for every chat message of a player: "lf &lt;class&gt;" brings a fake player of that class (see {@link FakePartyConfig#LF_CHANNELS}).
	 * @param player the player
	 * @param type the chat channel
	 * @param text the message
	 */
	public void onPlayerChat(Player player, ChatType type, String text)
	{
		if ((player == null) || (text == null) || !FakePartyConfig.LF_CHANNELS.contains(type) || !isEnabled())
		{
			return;
		}
		
		final List<Request> requests = parse(text);
		if (requests.isEmpty())
		{
			return;
		}
		
		final long now = System.currentTimeMillis();
		final Long next = _nextRequest.get(player.getObjectId());
		if ((next != null) && (now < next))
		{
			return;
		}
		
		if (!canHaveFakes(player))
		{
			return;
		}
		
		_nextRequest.put(player.getObjectId(), now + (FakePartyConfig.LF_COOLDOWN * 1000L));
		
		// Typing takes a while, and they don't all answer at once.
		long delay = Rnd.get(FakePartyConfig.LF_DELAY_MIN, FakePartyConfig.LF_DELAY_MAX) * 1000L;
		for (Request request : requests)
		{
			ThreadPool.schedule(() -> answer(player, request), delay);
			delay += Rnd.get(1000, 4000);
		}
	}
	
	/**
	 * What a player asked for: the word used and the builds it means.
	 */
	private record Request(String word, List<FakePlayerPvpBuild> builds, boolean role)
	{
	}
	
	/**
	 * @param text a chat message
	 * @return the classes it asks for, none if it isn't a request ("lf ...")
	 */
	private List<Request> parse(String text)
	{
		final String[] words = text.toLowerCase().replace("'", "").replaceAll("[^a-z0-9]+", " ").trim().split(" ");
		if ((words.length < 2) || words[0].isEmpty())
		{
			return Collections.emptyList();
		}
		
		int start;
		if (TRIGGERS.contains(words[0]) || words[0].matches("lf[mp]?\\d+"))
		{
			start = 1;
		}
		else if ((words.length > 2) && words[0].equals("looking") && words[1].equals("for"))
		{
			start = 2;
		}
		else
		{
			return Collections.emptyList();
		}
		
		final Map<String, List<FakePlayerPvpBuild>> names = getNames();
		final List<Request> requests = new ArrayList<>();
		final Set<String> seen = new HashSet<>();
		for (int i = start; (i < words.length) && (requests.size() < FakePartyConfig.LF_MAX_PER_MESSAGE);)
		{
			boolean found = false;
			
			// Class names of up to three words ("mystic muse", "eva s saint").
			for (int count = Math.min(3, words.length - i); count > 0; count--)
			{
				final StringBuilder sb = new StringBuilder();
				for (int j = i; j < (i + count); j++)
				{
					sb.append(words[j]);
				}
				
				String key = sb.toString();
				List<FakePlayerPvpBuild> builds = names.get(key);
				if ((builds == null) && (key.length() > 3) && key.endsWith("s")) // "healers", "bishops"
				{
					key = key.substring(0, key.length() - 1);
					builds = names.get(key);
				}
				
				if (builds != null)
				{
					if (seen.add(key))
					{
						requests.add(new Request(key, builds, isRoleWord(key)));
					}
					i += count;
					found = true;
					break;
				}
			}
			
			if (!found)
			{
				i++;
			}
		}
		
		return requests;
	}
	
	/**
	 * @return the words players use for the classes, built once the builds are loaded
	 */
	private Map<String, List<FakePlayerPvpBuild>> getNames()
	{
		Map<String, List<FakePlayerPvpBuild>> names = _names;
		if (names == null)
		{
			synchronized (this)
			{
				names = _names;
				if (names == null)
				{
					names = createNames();
					_names = names;
				}
			}
		}
		
		return names;
	}
	
	private static Map<String, List<FakePlayerPvpBuild>> createNames()
	{
		final Map<String, List<FakePlayerPvpBuild>> names = new HashMap<>();
		final List<FakePlayerPvpBuild> builds = FakePlayerPvpData.getInstance().getBuilds();
		
		// Every class of a build's class line, from the 1st class on (base classes are shared by many lines): "lf spellsinger" brings a Mystic Muse build, which is a Spellsinger below 76.
		for (FakePlayerPvpBuild build : builds)
		{
			for (PlayerClass playerClass = build.getPlayerClass(); (playerClass != null) && (playerClass.level() >= 1); playerClass = playerClass.getParent())
			{
				addName(names, normalize(playerClass.name()), build);
			}
		}
		
		// Short names.
		for (String[] shortNames : SHORT_NAMES)
		{
			final List<FakePlayerPvpBuild> classBuilds = names.get(normalize(shortNames[0]));
			if (classBuilds == null)
			{
				continue;
			}
			
			for (int i = 1; i < shortNames.length; i++)
			{
				for (FakePlayerPvpBuild build : new ArrayList<>(classBuilds))
				{
					addName(names, shortNames[i], build);
				}
			}
		}
		
		// Roles.
		addRole(names, builds, b -> HEALERS.contains(b.getPlayerClass()), "healer", "heal", "heals", "healz", "heala", "healr");
		addRole(names, builds, b -> BUFFERS.contains(b.getPlayerClass()), "buffer", "buff", "buffs", "buffz", "bufer");
		addRole(names, builds, FakePlayerPvpBuild::isSupport, "support", "supp", "sup");
		addRole(names, builds, b -> !b.isSupport() && (b.getRole() == Role.TANK), "tank", "tanker");
		addRole(names, builds, b -> !b.isSupport() && (b.getRole() == Role.MAGE), "nuker", "nuke", "mage", "mystic", "caster");
		addRole(names, builds, b -> !b.isSupport() && (b.getRole() == Role.ARCHER), "archer", "bow", "bowman", "ranger");
		addRole(names, builds, b -> !b.isSupport() && (b.getRole() == Role.DAGGER), "dagger", "dag", "rogue");
		addRole(names, builds, b -> !b.isSupport() && (b.getRole() == Role.FIGHTER), "warrior", "melee", "fighter");
		addRole(names, builds, b -> !b.isSupport() && (b.getRole() != Role.TANK), "dd", "dps", "damage");
		return names;
	}
	
	private static void addName(Map<String, List<FakePlayerPvpBuild>> names, String name, FakePlayerPvpBuild build)
	{
		final List<FakePlayerPvpBuild> list = names.computeIfAbsent(name, _ -> new ArrayList<>());
		if (!list.contains(build))
		{
			list.add(build);
		}
	}
	
	private static void addRole(Map<String, List<FakePlayerPvpBuild>> names, List<FakePlayerPvpBuild> builds, Predicate<FakePlayerPvpBuild> filter, String... words)
	{
		final List<FakePlayerPvpBuild> matching = new ArrayList<>();
		for (FakePlayerPvpBuild build : builds)
		{
			if (filter.test(build))
			{
				matching.add(build);
			}
		}
		
		if (matching.isEmpty())
		{
			return;
		}
		
		for (String word : words)
		{
			// A class name wins over a role ("ranger" isn't a class, "dagger" isn't either).
			names.putIfAbsent(word, matching);
		}
	}
	
	private static boolean isRoleWord(String word)
	{
		return switch (word)
		{
			case "healer", "heal", "heals", "healz", "heala", "healr", "buffer", "buff", "buffs", "buffz", "bufer", "support", "supp", "sup", "tank", "tanker", "nuker", "nuke", "mage", "mystic", "caster", "archer", "bow", "bowman", "ranger", "dagger", "dag", "rogue", "warrior", "melee", "fighter", "dd", "dps", "damage" -> true;
			default -> false;
		};
	}
	
	/**
	 * @param name a class name (enum name, or what a player wrote)
	 * @return the name in lower case, without spaces, underscores and quotes
	 */
	private static String normalize(String name)
	{
		return name.toLowerCase().replaceAll("[^a-z0-9]", "");
	}
	
	/**
	 * @param playerClass a class
	 * @return its name as players write it ("Spellsinger", "Eva's Saint")
	 */
	public static String getClassName(PlayerClass playerClass)
	{
		switch (playerClass)
		{
			case ELDER:
				return "Elven Elder";
			case ORACLE:
				return "Elven Oracle";
			case EVA_SAINT:
				return "Eva's Saint";
			case EVA_TEMPLAR:
				return "Eva's Templar";
			case MALE_SOUL_HOUND:
			case FEMALE_SOUL_HOUND:
				return "Soul Hound";
			case MALE_SOULBREAKER:
			case FEMALE_SOULBREAKER:
				return "Soul Breaker";
			default:
			{
				final StringBuilder sb = new StringBuilder();
				for (String word : playerClass.name().toLowerCase().split("_"))
				{
					if (!sb.isEmpty())
					{
						sb.append(' ');
					}
					sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
				}
				return sb.toString();
			}
		}
	}
	
	/**
	 * A fake player answers a player's request: a free one of the class hunting close by, or one that comes over from out of sight.
	 * @param player the player that asked
	 * @param request what it asked for
	 */
	private void answer(Player player, Request request)
	{
		try
		{
			if (!player.isOnline() || !canHaveFakes(player) || (getMemberCount(player) >= MAX_PARTY_SIZE) || (getFakeCount(player) >= FakePartyConfig.MAX_FAKES))
			{
				return;
			}
			
			final long now = System.currentTimeMillis();
			Npc fake = findNearbyFake(player, request.builds(), now);
			if (fake == null)
			{
				final FakePlayerPvpBuild build = request.builds().get(Rnd.get(request.builds().size()));
				final int spread = FakePartyConfig.LF_LEVEL_SPREAD;
				final int level = Math.max(1, Math.min(85, player.getLevel() + Rnd.get(-spread, spread)));
				final Location arrival = findArrival(player);
				if (arrival == null)
				{
					return;
				}
				
				fake = FakePlayerPvpManager.getInstance().spawnFakePlayer(build, level, arrival.getX(), arrival.getY(), arrival.getZ(), player.getInstanceId(), null, null);
				if (fake == null)
				{
					return;
				}
				
				// Not of a clan at war with the player's.
				FakeClanManager.getInstance().avoidWar(fake, player);
			}
			
			final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
			profile.setLfTarget(player.getObjectId(), now + (FakePartyConfig.LF_WAIT_TIME * 1000L));
			_responders.put(fake, player.getObjectId());
			if (fake.hasAI() && (fake.getAI() instanceof FakePlayerPvpAI ai))
			{
				ai.onAnswer();
			}
			
			// Its class the way the player wrote it, or its real name for a role ("lf healer").
			final String className = request.role() ? getClassName(profile.getPlayerClass()) : (Rnd.nextBoolean() ? request.word() : getClassName(profile.getPlayerClass()));
			whisper(fake, player, pick(ANSWERS).replace("%c", Rnd.get(3) == 0 ? className.toLowerCase() : className).replace("%l", String.valueOf(fake.getLevel())));
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not answer " + player.getName() + ".", e);
		}
	}
	
	/**
	 * @param player the player that asked
	 * @param builds the builds it asked for
	 * @param now the current time
	 * @return the closest free fake player of one of those classes hunting close by, about the player's level, {@code null} if none
	 */
	private static Npc findNearbyFake(Player player, List<FakePlayerPvpBuild> builds, long now)
	{
		if (FakePartyConfig.LF_NEARBY_RANGE <= 0)
		{
			return null;
		}
		
		final int levelDifference = Math.max(FakePartyConfig.LF_LEVEL_SPREAD, 2);
		Npc closest = null;
		double closestDistance = FakePartyConfig.LF_NEARBY_RANGE;
		for (Npc fake : FakePlayerPvpManager.getInstance().getFakePlayers())
		{
			if (fake.isDead() || !fake.isSpawned() || fake.isTrialDuelist() || (fake.getInstanceId() != player.getInstanceId()) || (Math.abs(fake.getLevel() - player.getLevel()) > levelDifference))
			{
				continue;
			}
			
			final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
			if ((profile == null) || (profile.getParty() != null) || (profile.getLfTarget(now) != 0) || FakePlayerPvpManager.isInPvp(fake) || !isOfClass(profile, builds) || FakeClanManager.getInstance().isWarEnemy(fake, player))
			{
				continue;
			}
			
			final double distance = fake.calculateDistance2D(player);
			if (distance < closestDistance)
			{
				closest = fake;
				closestDistance = distance;
			}
		}
		
		return closest;
	}
	
	private static boolean isOfClass(FakePlayerPvpProfile profile, List<FakePlayerPvpBuild> builds)
	{
		for (FakePlayerPvpBuild build : builds)
		{
			if (build.getPlayerClass() == profile.getBuild().getPlayerClass())
			{
				return true;
			}
		}
		
		return false;
	}
	
	/**
	 * @param player a player
	 * @return where a fake player coming to {@code player} appears: a walkable point no player sees (just out of their sight if there is one, else {@link #ARRIVAL_DISTANCE} away behind a wall or a hill), else the farthest point about {@link #ARRIVAL_DISTANCE} away,
	 *         {@code null} if there is none
	 */
	private static Location findArrival(Creature player)
	{
		Location farthest = null;
		for (int distance : new int[]
		{
			FakePlayerPvpConfig.UNSEEN_RANGE + 300,
			ARRIVAL_DISTANCE
		})
		{
			farthest = null;
			double farthestDistance = distance / 3;
			final double start = Rnd.nextDouble() * 2 * Math.PI;
			for (int i = 0; i < 12; i++)
			{
				final double angle = start + ((i * Math.PI) / 6);
				final Location point = GeoEngine.getInstance().getValidLocation(player.getX(), player.getY(), player.getZ(), player.getX() + (int) (Math.cos(angle) * distance), player.getY() + (int) (Math.sin(angle) * distance), player.getZ(), player.getInstanceId());
				final double dx = point.getX() - player.getX();
				final double dy = point.getY() - player.getY();
				final double pointDistance = Math.sqrt((dx * dx) + (dy * dy));
				if ((pointDistance >= (distance / 2)) && !FakePlayerPvpManager.isSeenByPlayer(point.getX(), point.getY(), point.getZ(), player.getInstanceId()))
				{
					return point;
				}
				
				if (pointDistance > farthestDistance)
				{
					farthest = point;
					farthestDistance = pointDistance;
				}
			}
		}
		
		return farthest;
	}
	
	/**
	 * @param player a player
	 * @return {@code true} if fake players may join {@code player} where it is now
	 */
	private static boolean canHaveFakes(Player player)
	{
		return player.isOnline() && !player.isInOlympiadMode() && !player.inObserverMode() && !player.isJailed() && !player.isOnEvent() && !player.isInsideZone(ZoneId.SIEGE) && (player.getUCState() == Player.UC_STATE_NONE);
	}
	
	// ---------------------------------------------------------------------------------------------
	// Invites
	// ---------------------------------------------------------------------------------------------
	
	/**
	 * Called from {@code RequestJoinParty} when no player has the invited name: an invited fake player answers like a player would. One that answered the inviter's request always accepts; another one may, if it hunts close by and isn't busy.
	 * @param requestor the player that invites
	 * @param name the name it invited
	 * @param distributionType the loot rule the requestor chose for a new party, {@code null} for none
	 * @return {@code true} if {@code name} is a fake player (the invite is handled)
	 */
	public boolean onInvite(Player requestor, String name, PartyDistributionType distributionType)
	{
		if ((name == null) || !isEnabled())
		{
			return false;
		}
		
		final Npc fake = findFake(name);
		if (fake == null)
		{
			return false;
		}
		
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		final Party party = requestor.getParty();
		if ((party != null) && !party.isLeader(requestor))
		{
			requestor.sendPacket(SystemMessageId.ONLY_THE_LEADER_CAN_GIVE_OUT_INVITATIONS);
			return true;
		}
		
		if (fake.isDead() || fake.isTrialDuelist() || !canHaveFakes(requestor) || (fake.getInstanceId() != requestor.getInstanceId()))
		{
			requestor.sendPacket(SystemMessageId.THAT_IS_AN_INCORRECT_TARGET);
			return true;
		}
		
		if (profile.getParty() != null)
		{
			final SystemMessage sm = new SystemMessage(SystemMessageId.C1_IS_A_MEMBER_OF_ANOTHER_PARTY_AND_CANNOT_BE_INVITED);
			sm.addString(fake.getName());
			requestor.sendPacket(sm);
			return true;
		}
		
		if (requestor.isProcessingRequest())
		{
			requestor.sendPacket(SystemMessageId.WAITING_FOR_ANOTHER_REPLY);
			return true;
		}
		
		// It answered the inviter, or a player of the inviter's party.
		final long now = System.currentTimeMillis();
		final int askedBy = profile.getLfTarget(now);
		final boolean asked = (askedBy != 0) && ((askedBy == requestor.getObjectId()) || ((party != null) && party.getMembers().stream().anyMatch(member -> member.getObjectId() == askedBy)));
		if (((getMemberCount(requestor) - (askedBy == requestor.getObjectId() ? 1 : 0)) >= MAX_PARTY_SIZE) || (getFakeCount(requestor) >= FakePartyConfig.MAX_FAKES))
		{
			requestor.sendPacket(SystemMessageId.THE_PARTY_IS_FULL);
			return true;
		}
		
		final SystemMessage sm = new SystemMessage(SystemMessageId.C1_HAS_BEEN_INVITED_TO_THE_PARTY);
		sm.addString(fake.getName());
		requestor.sendPacket(sm);
		
		// The loot rule of a new party, like a party with a player.
		if ((party == null) && (distributionType != null))
		{
			requestor.setPartyDistributionType(distributionType);
		}
		
		final boolean accept = asked || wantsToJoin(fake, requestor, now);
		ThreadPool.schedule(() -> answerInvite(requestor, fake, accept), Rnd.get(800, asked ? 2500 : 5000));
		return true;
	}
	
	/**
	 * @param fake a fake player that didn't ask to join
	 * @param requestor the player that invites it
	 * @param now the current time
	 * @return {@code true} if it accepts (it keeps to its answer for a while)
	 */
	private boolean wantsToJoin(Npc fake, Player requestor, long now)
	{
		final long key = ((long) fake.getObjectId() << 32) | (requestor.getObjectId() & 0xFFFFFFFFL);
		final Long decision = _decisions.get(key);
		if ((decision != null) && (Math.abs(decision) > now))
		{
			return decision > 0;
		}
		
		final boolean accept = !FakePlayerPvpManager.isInPvp(fake) && !FakeClanManager.getInstance().isWarEnemy(fake, requestor) && (fake.calculateDistance2D(requestor) <= INVITE_RANGE) && (Math.abs(fake.getLevel() - requestor.getLevel()) <= FakePartyConfig.INVITE_MAX_LEVEL_DIFFERENCE) && (Rnd.get(100) < FakePartyConfig.INVITE_ACCEPT_CHANCE);
		_decisions.put(key, accept ? (now + INVITE_MEMORY) : -(now + INVITE_MEMORY));
		return accept;
	}
	
	private void answerInvite(Player requestor, Npc fake, boolean accept)
	{
		if (!requestor.isOnline())
		{
			return;
		}
		
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		final Party party = requestor.getParty();
		final boolean valid = !fake.isDead() && fake.isSpawned() && (profile.getParty() == null) && ((party == null) || party.isLeader(requestor)) && canHaveFakes(requestor) && (getFakeCount(requestor) < FakePartyConfig.MAX_FAKES);
		if (accept && valid)
		{
			requestor.sendPacket(new JoinParty(1));
			join(requestor, fake);
			return;
		}
		
		requestor.sendPacket(new JoinParty(0));
		requestor.sendPacket(SystemMessageId.THE_PLAYER_DECLINED_TO_JOIN_YOUR_PARTY);
		if (!accept && !fake.isDead() && (Rnd.get(100) < 60))
		{
			whisper(fake, requestor, pick(DECLINE));
		}
	}
	
	/**
	 * @param name a character name
	 * @return the roaming fake player of that name, {@code null} if none
	 */
	private static Npc findFake(String name)
	{
		for (Npc fake : FakePlayerPvpManager.getInstance().getFakePlayers())
		{
			if (fake.getName().equalsIgnoreCase(name) && fake.isSpawned())
			{
				return fake;
			}
		}
		
		return null;
	}
	
	// ---------------------------------------------------------------------------------------------
	// Joining and leaving
	// ---------------------------------------------------------------------------------------------
	
	/**
	 * {@code fake} joins the party of {@code host}.
	 * @param host the player that invited it
	 * @param fake the fake player
	 */
	private void join(Player host, Npc fake)
	{
		FakePlayerParty party = getParty(host);
		if ((party == null) || party.isFakeOnly())
		{
			party = _parties.computeIfAbsent(host.getObjectId(), _ -> new FakePlayerParty(host));
		}
		
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		profile.setLfTarget(0, 0);
		profile.setParty(party);
		_responders.remove(fake);
		party.addFake(fake);
		
		FakePlayerPvpManager.getInstance().onJoinParty(fake);
		if (fake.hasAI() && (fake.getAI() instanceof FakePlayerPvpAI ai))
		{
			ai.onJoinParty();
		}
		
		final SystemMessage sm = new SystemMessage(SystemMessageId.C1_HAS_JOINED_THE_PARTY);
		sm.addString(fake.getName());
		for (Player player : party.getPlayers())
		{
			player.sendPacket(sm);
		}
		
		refreshWindows(party);
		
		if (FakePartyConfig.CHAT && (Rnd.get(100) < 70))
		{
			final String text = (profile.getBuild().isSupport() && Rnd.nextBoolean()) ? pick(JOIN_SUPPORT) : pick(JOIN);
			ThreadPool.schedule(() -> partyChat(fake, text), Rnd.get(1500, 4000));
		}
	}
	
	/**
	 * {@code fake} leaves its party and goes back to hunting where it is.
	 * @param fake the fake player
	 * @param expelled {@code true} if the party leader dismissed it
	 * @param silent {@code true} for no system message
	 */
	public void leave(Npc fake, boolean expelled, boolean silent)
	{
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		final FakePlayerParty party = profile != null ? profile.getParty() : null;
		if (party == null)
		{
			return;
		}
		
		removeFromParty(fake, party, silent ? null : expelled ? SystemMessageId.C1_WAS_EXPELLED_FROM_THE_PARTY : SystemMessageId.C1_HAS_LEFT_THE_PARTY);
		if (fake.isSpawned() && !fake.isDead())
		{
			FakePlayerPvpManager.getInstance().onLeaveParty(fake);
			if (fake.hasAI() && (fake.getAI() instanceof FakePlayerPvpAI ai))
			{
				ai.onLeaveParty();
			}
		}
	}
	
	private void removeFromParty(Npc fake, FakePlayerParty party, SystemMessageId message)
	{
		if (!party.removeFake(fake))
		{
			return;
		}
		
		fake.getTemplate().getFakePlayerPvpProfile().setParty(null);
		_lastStatus.remove(fake.getObjectId());
		
		if (party.isFakeOnly())
		{
			checkFakeOnly(party);
			return;
		}
		
		final FakePartySmallWindowDelete delete = new FakePartySmallWindowDelete(fake.getObjectId(), fake.getName());
		SystemMessage sm = null;
		if (message != null)
		{
			sm = new SystemMessage(message);
			sm.addString(fake.getName());
		}
		
		for (Player player : party.getPlayers())
		{
			player.sendPacket(delete);
			if (sm != null)
			{
				player.sendPacket(sm);
			}
		}
		
		// The last one left a player without a party: its party window closes.
		final Player host = party.getHost();
		if (party.getFakes().isEmpty())
		{
			_parties.remove(host.getObjectId(), party);
			if (!host.isInParty())
			{
				host.sendPacket(PartySmallWindowDeleteAll.STATIC_PACKET);
			}
		}
	}
	
	/**
	 * All fake players of {@code party} leave it.
	 * @param party the party
	 * @param silent {@code true} for no system messages and no goodbye
	 */
	private void disband(FakePlayerParty party, boolean silent)
	{
		for (Npc fake : party.getFakes())
		{
			if (!silent && FakePartyConfig.CHAT && !fake.isDead() && (Rnd.get(100) < 50))
			{
				partyChat(fake, pick(BYE));
			}
			leave(fake, false, silent);
		}
		
		final Player host = party.getHost();
		if (host != null)
		{
			_parties.remove(host.getObjectId(), party);
		}
		else
		{
			_fakeParties.remove(party);
		}
	}
	
	/**
	 * Called from {@code RequestOustPartyMember}: the party leader dismisses a fake player.
	 * @param player the player that dismisses
	 * @param name the name it dismisses
	 * @return {@code true} if {@code name} is a fake player of its party (handled)
	 */
	public boolean onDismiss(Player player, String name)
	{
		final FakePlayerParty party = getParty(player);
		if ((party == null) || party.isFakeOnly())
		{
			return false;
		}
		
		for (Npc fake : party.getFakes())
		{
			if (fake.getName().equalsIgnoreCase(name))
			{
				final Party realParty = player.getParty();
				if ((realParty != null) ? realParty.isLeader(player) : (party.getHost() == player))
				{
					if (FakePartyConfig.CHAT && !fake.isDead() && (Rnd.get(100) < 50))
					{
						partyChat(fake, pick(BYE));
					}
					leave(fake, true, false);
				}
				return true;
			}
		}
		
		return false;
	}
	
	/**
	 * Called from {@code RequestWithDrawalParty}: a player that invited fake players leaves the party, they go too.
	 * @param player the player that leaves
	 * @return {@code true} if the player has no other party to leave (handled)
	 */
	public boolean onWithdraw(Player player)
	{
		final FakePlayerParty party = _parties.get(player.getObjectId());
		if (party == null)
		{
			return false;
		}
		
		disband(party, false);
		if (!player.isInParty())
		{
			player.sendPacket(SystemMessageId.YOU_HAVE_WITHDRAWN_FROM_THE_PARTY);
			player.sendPacket(PartySmallWindowDeleteAll.STATIC_PACKET);
			return true;
		}
		
		return false;
	}
	
	/**
	 * Called by {@link Party} when a player joins or leaves it or its leader changes: the party windows of the players are sent again with the fake players.
	 * @param realParty the party
	 * @param player the player that joined or left (the new leader)
	 * @param joined {@code false} if {@code player} left
	 */
	public void onPartyChanged(Party realParty, Player player, boolean joined)
	{
		if (_parties.isEmpty() || (player == null))
		{
			return;
		}
		
		// After the party's own window packets.
		final List<Player> members = new ArrayList<>(realParty.getMembers());
		ThreadPool.schedule(() ->
		{
			// A player with fake players left: they go with it, out of the windows of the others.
			final FakePlayerParty own = _parties.get(player.getObjectId());
			if (!joined && (own != null))
			{
				for (Player member : members)
				{
					if (member == player)
					{
						continue;
					}
					
					for (Npc fake : own.getFakes())
					{
						member.sendPacket(new FakePartySmallWindowDelete(fake.getObjectId(), fake.getName()));
					}
				}
			}
			
			final Set<FakePlayerParty> refreshed = new HashSet<>();
			for (Player member : realParty.getMembers())
			{
				final FakePlayerParty party = _parties.get(member.getObjectId());
				if ((party != null) && refreshed.add(party))
				{
					refreshWindows(party);
				}
			}
			
			if ((own != null) && refreshed.add(own))
			{
				refreshWindows(own);
			}
		}, 100);
	}
	
	/**
	 * Sends the whole party window again, with the fake players, to every player of {@code party}.
	 * @param party the party
	 */
	public void refreshWindows(FakePlayerParty party)
	{
		if (party.isFakeOnly() || party.getFakes().isEmpty())
		{
			return;
		}
		
		final List<Player> players = party.getPlayers();
		for (Player player : players)
		{
			if (!player.isOnline())
			{
				continue;
			}
			
			player.sendPacket(PartySmallWindowDeleteAll.STATIC_PACKET);
			player.sendPacket(new FakePartySmallWindowAll(player, party));
			for (Player member : players)
			{
				if ((member != player) && member.hasSummon())
				{
					player.sendPacket(new ExPartyPetWindowAdd(member.getSummon()));
				}
			}
		}
		
		for (Npc fake : party.getFakes())
		{
			_lastStatus.remove(fake.getObjectId());
		}
	}
	
	// ---------------------------------------------------------------------------------------------
	// Who is in which party
	// ---------------------------------------------------------------------------------------------
	
	/**
	 * @param creature a player (or its summon) or a fake player
	 * @return its party of fake players, {@code null} if none
	 */
	public FakePlayerParty getParty(Creature creature)
	{
		if (creature == null)
		{
			return null;
		}
		
		if (creature.isNpc())
		{
			if (!creature.isFakePlayer())
			{
				return null;
			}
			
			final FakePlayerPvpProfile profile = creature.asNpc().getTemplate().getFakePlayerPvpProfile();
			return profile != null ? profile.getParty() : null;
		}
		
		if (_parties.isEmpty())
		{
			return null;
		}
		
		final Player player = creature.asPlayer();
		if (player == null)
		{
			return null;
		}
		
		final FakePlayerParty party = _parties.get(player.getObjectId());
		if (party != null)
		{
			return party;
		}
		
		final Party realParty = player.getParty();
		if (realParty != null)
		{
			for (Player member : realParty.getMembers())
			{
				final FakePlayerParty memberParty = _parties.get(member.getObjectId());
				if (memberParty != null)
				{
					return memberParty;
				}
			}
		}
		
		return null;
	}
	
	/**
	 * @param creature a creature
	 * @param other another creature
	 * @return {@code true} if both are members (or summons of members) of the same party of fake players: they don't hit each other, and party skills reach both
	 */
	public boolean isSameGroup(Creature creature, Creature other)
	{
		if ((creature == null) || (other == null) || (creature == other))
		{
			return false;
		}
		
		// Cheap way out for the common case: no fake player is in a party.
		if (_parties.isEmpty() && _fakeParties.isEmpty())
		{
			return false;
		}
		
		if (!creature.isFakePlayer() && !other.isFakePlayer())
		{
			return false;
		}
		
		final FakePlayerParty party = getParty(creature);
		return (party != null) && (party == getParty(other));
	}
	
	/**
	 * @param creature a fake player
	 * @return {@code true} if it is in a party with players
	 */
	public boolean isInPlayerParty(Creature creature)
	{
		final FakePlayerParty party = ((creature != null) && creature.isFakePlayer()) ? getParty(creature) : null;
		return (party != null) && !party.isFakeOnly();
	}
	
	/**
	 * @param player a player
	 * @return how many fake players its party has
	 */
	public int getFakeCount(Player player)
	{
		final FakePlayerParty party = getParty(player);
		return (party != null) && !party.isFakeOnly() ? party.getFakes().size() : 0;
	}
	
	/**
	 * @param player a player
	 * @return how many members its party has, with the fake players and the ones on their way to join it
	 */
	private int getMemberCount(Player player)
	{
		final FakePlayerParty party = getParty(player);
		int count = (party != null) && !party.isFakeOnly() ? party.size() : (player.isInParty() ? player.getParty().getMemberCount() : 1);
		final long now = System.currentTimeMillis();
		for (Map.Entry<Npc, Integer> entry : _responders.entrySet())
		{
			if ((entry.getValue() == player.getObjectId()) && (entry.getKey().getTemplate().getFakePlayerPvpProfile().getLfTarget(now) != 0))
			{
				count++;
			}
		}
		
		return count;
	}
	
	// ---------------------------------------------------------------------------------------------
	// Rewards
	// ---------------------------------------------------------------------------------------------
	
	/**
	 * Damage and kills of a party fake player count for its party, like a member's: they are credited to a player of the party close to the monster.
	 * @param creature the attacker (or killer)
	 * @param target the monster
	 * @return the player of its party that gets them, {@code null} if {@code creature} isn't a party fake player (or no player of its party is around)
	 */
	public Player getRewardPlayer(Creature creature, Attackable target)
	{
		if ((creature == null) || !creature.isFakePlayer() || _parties.isEmpty())
		{
			return null;
		}
		
		final FakePlayerParty party = getParty(creature);
		if ((party == null) || party.isFakeOnly())
		{
			return null;
		}
		
		final Player host = party.getHost();
		if (isRewardable(host, target))
		{
			return host;
		}
		
		for (Player player : party.getPlayers())
		{
			if (isRewardable(player, target))
			{
				return player;
			}
		}
		
		return null;
	}
	
	private static boolean isRewardable(Player player, Attackable target)
	{
		return (player != null) && player.isOnline() && !player.isDead() && (player.getInstanceId() == target.getInstanceId()) && (target.calculateDistance3D(player) <= PlayerConfig.ALT_PARTY_RANGE);
	}
	
	/**
	 * Fake players take their part of the party exp like members (see {@link FakePartyConfig#EXP_SHARE}): the party bonus grows with them and each one takes a share by level, like a player.
	 * @param player a player rewarded for a kill
	 * @param players the players of its party rewarded for it (only {@code player} without a party)
	 * @param target the monster
	 * @return what the players' exp and sp are multiplied by
	 */
	public double getExpShare(Player player, List<Player> players, Attackable target)
	{
		if (!FakePartyConfig.EXP_SHARE || _parties.isEmpty())
		{
			return 1;
		}
		
		final FakePlayerParty party = getParty(player);
		if ((party == null) || party.isFakeOnly())
		{
			return 1;
		}
		
		double fakeLevels = 0;
		int fakes = 0;
		for (Npc fake : party.getFakes())
		{
			if (!fake.isDead() && fake.isSpawned() && (fake.getInstanceId() == target.getInstanceId()) && (target.calculateDistance3D(fake) <= PlayerConfig.ALT_PARTY_RANGE))
			{
				fakeLevels += fake.getLevel() * fake.getLevel();
				fakes++;
			}
		}
		
		if (fakes == 0)
		{
			return 1;
		}
		
		double playerLevels = 0;
		for (Player member : players)
		{
			playerLevels += member.getLevel() * member.getLevel();
		}
		
		final int count = Math.max(1, players.size());
		final double bonus = Party.getBaseExpSpBonusFor(count + fakes) / Party.getBaseExpSpBonusFor(count);
		return (bonus * playerLevels) / Math.max(1, playerLevels + fakeLevels);
	}
	
	// ---------------------------------------------------------------------------------------------
	// Death and coming back
	// ---------------------------------------------------------------------------------------------
	
	/**
	 * @param fake a fake player hit by a monster
	 * @return {@code true} if monsters can kill it (a party fake player, see {@link FakePartyConfig#CAN_DIE})
	 */
	public boolean canBeKilledByMonsters(Creature fake)
	{
		return FakePartyConfig.CAN_DIE && isInPlayerParty(fake);
	}
	
	/**
	 * Called by its AI when a fake player dies.
	 * @param fake the fake player
	 */
	public void onFakeDeath(Npc fake)
	{
		_responders.remove(fake);
		final FakePlayerParty party = getParty(fake);
		if ((party == null) || party.isFakeOnly())
		{
			return;
		}
		
		_lastStatus.remove(fake.getObjectId());
		if (FakePartyConfig.CHAT && (Rnd.get(100) < 60))
		{
			ThreadPool.schedule(() -> partyChat(fake, pick(DEATH)), Rnd.get(1000, 4000));
		}
	}
	
	/**
	 * Called when a dead fake player is resurrected.
	 * @param fake the fake player
	 */
	public void onRevived(Npc fake)
	{
		FakePlayerPvpManager.getInstance().onRevived(fake);
		if (fake.getCurrentHp() < 1)
		{
			fake.setCurrentHp(fake.getMaxHp() * 0.3);
		}
		
		if (fake.isAttackable())
		{
			fake.asAttackable().clearAggroList();
		}
		
		fake.broadcastInfo();
		if (fake.hasAI())
		{
			fake.getAI().setIntention(Intention.ACTIVE);
		}
		
		final FakePlayerParty party = getParty(fake);
		if ((party != null) && !party.isFakeOnly())
		{
			_lastStatus.remove(fake.getObjectId());
			if (FakePartyConfig.CHAT && (Rnd.get(100) < 70))
			{
				ThreadPool.schedule(() -> partyChat(fake, pick(RESURRECTED)), Rnd.get(1000, 3000));
			}
		}
	}
	
	/**
	 * Called when a fake player leaves the world (body gone, deleted): it leaves its party. One that died in a party with players comes back from town a while later ({@link FakePartyConfig#RETURN_DELAY}).
	 * @param fake the fake player
	 * @return {@code true} if it comes back to its party (its name stays taken)
	 */
	public boolean onFakeDecay(Npc fake)
	{
		_responders.remove(fake);
		final FakePlayerParty party = getParty(fake);
		if (party == null)
		{
			return false;
		}
		
		final boolean died = fake.isDead();
		removeFromParty(fake, party, null);
		final Player host = party.getHost();
		if (!died || party.isFakeOnly() || (host == null) || !host.isOnline() || !isEnabled())
		{
			return false;
		}
		
		if (FakePartyConfig.CHAT && (Rnd.get(100) < 50))
		{
			partyChat(fake, party, pick(TOWN));
		}
		
		final NpcTemplate template = fake.getTemplate();
		final String name = template.getName();
		final int hostId = host.getObjectId();
		_returns.put(name, ThreadPool.schedule(() -> comeBack(template, hostId), FakePartyConfig.RETURN_DELAY * 1000L));
		return true;
	}
	
	/**
	 * A party fake player that died comes back from town: it appears out of sight and runs to the party leader.
	 * @param template its template
	 * @param hostId the object id of the player whose party it was in
	 */
	private void comeBack(NpcTemplate template, int hostId)
	{
		final String name = template.getName();
		if (_returns.remove(name) == null)
		{
			return;
		}
		
		final Player host = World.getInstance().getPlayer(hostId);
		final Location arrival = (host != null) && host.isOnline() && canHaveFakes(host) && isEnabled() ? findArrival(host) : null;
		if ((arrival == null) || (getFakeCount(host) >= FakePartyConfig.MAX_FAKES) || (getMemberCount(host) >= MAX_PARTY_SIZE))
		{
			FakePlayerPvpManager.getInstance().releaseName(name);
			return;
		}
		
		final Npc fake = FakePlayerPvpManager.getInstance().respawnFromTemplate(template, arrival.getX(), arrival.getY(), arrival.getZ(), host.getInstanceId());
		if (fake == null)
		{
			return;
		}
		
		join(host, fake);
		if (FakePartyConfig.CHAT && (Rnd.get(100) < 60))
		{
			ThreadPool.schedule(() -> partyChat(fake, pick(BACK)), Rnd.get(2000, 5000));
		}
	}
	
	/**
	 * Cancels the party fake players on their way back from town.
	 * @return how many were cancelled
	 */
	public int clearReturns()
	{
		int count = 0;
		for (Map.Entry<String, ScheduledFuture<?>> entry : _returns.entrySet())
		{
			if (_returns.remove(entry.getKey()) != null)
			{
				entry.getValue().cancel(false);
				FakePlayerPvpManager.getInstance().releaseName(entry.getKey());
				count++;
			}
		}
		
		return count;
	}
	
	/**
	 * A party fake player follows its leader to where it teleported: it gets there too, like a player taking the same gatekeeper.
	 * @param fake the fake player
	 * @param leader the party leader
	 */
	private void teleportToLeader(Npc fake, Creature leader)
	{
		final Location point = GeoEngine.getInstance().getValidLocation(leader.getX(), leader.getY(), leader.getZ(), leader.getX() + Rnd.get(-120, 120), leader.getY() + Rnd.get(-120, 120), leader.getZ(), leader.getInstanceId());
		fake.abortAttack();
		fake.abortCast();
		if (fake.isAttackable())
		{
			fake.asAttackable().clearAggroList();
		}
		final FakePlayerHolder holder = fake.getTemplate().getFakePlayerInfo();
		if (holder != null)
		{
			holder.setSitting(false);
		}
		
		fake.teleToLocation(point.getX(), point.getY(), point.getZ(), fake.getHeading(), leader.getInstanceId());
		if (fake.getSpawn() != null)
		{
			fake.getSpawn().setXYZ(point.getX(), point.getY(), point.getZ());
		}
		
		// Its AI went idle with nobody around.
		fake.getAI().setIntention(Intention.ACTIVE);
	}
	
	/**
	 * Sends a fake player that stands still over to {@code target} (a fake player whose AI went idle with no player around doesn't think until a player comes close).
	 * @param fake the fake player
	 * @param target the player (or party leader) it goes to
	 */
	private static void walkTo(Npc fake, Creature target)
	{
		if (fake.isDead() || fake.isMoving() || fake.isCastingNow() || fake.isAttackingNow() || fake.isMovementDisabled() || fake.isInCombat())
		{
			return;
		}
		
		final FakePlayerHolder holder = fake.getTemplate().getFakePlayerInfo();
		if ((holder != null) && holder.isSitting())
		{
			return;
		}
		
		final Intention intention = fake.getAI().getIntention();
		if ((intention == Intention.ATTACK) || (intention == Intention.CAST))
		{
			return;
		}
		
		final Location point = GeoEngine.getInstance().getValidLocation(fake.getX(), fake.getY(), fake.getZ(), target.getX() + Rnd.get(-80, 80), target.getY() + Rnd.get(-80, 80), target.getZ(), fake.getInstanceId());
		fake.setRunning();
		fake.getAI().setIntention(Intention.MOVE_TO, point);
	}
	
	/**
	 * @param fake a party fake player
	 * @param leader its party leader
	 * @return {@code true} if the leader is too far to follow on foot (it teleported, or went into an instance)
	 */
	public static boolean isFarFrom(Creature fake, Creature leader)
	{
		return (leader.getInstanceId() != fake.getInstanceId()) || (fake.calculateDistance2D(leader) > TELEPORT_FOLLOW_DISTANCE) || (Math.abs(fake.getZ() - leader.getZ()) > 1500);
	}
	
	// ---------------------------------------------------------------------------------------------
	// Fake players hunting together
	// ---------------------------------------------------------------------------------------------
	
	/**
	 * Called when a roaming fake player spawns: sometimes it comes with friends (see {@link FakePartyConfig#ROAMING_CHANCE}), a party of fake players that hunts together around it.
	 * @param leader the fake player that spawned
	 */
	public void onRoamingSpawn(Npc leader)
	{
		if ((leader == null) || !isEnabled() || (Rnd.get(100) >= FakePartyConfig.ROAMING_CHANCE))
		{
			return;
		}
		
		final FakePlayerPvpProfile leaderProfile = leader.getTemplate().getFakePlayerPvpProfile();
		if ((leaderProfile == null) || (leaderProfile.getParty() != null) || leader.isTrialDuelist())
		{
			return;
		}
		
		final List<FakePlayerPvpBuild> supports = new ArrayList<>();
		for (FakePlayerPvpBuild build : FakePlayerPvpData.getInstance().getBuilds())
		{
			if (build.isSupport())
			{
				supports.add(build);
			}
		}
		
		final FakePlayerParty party = new FakePlayerParty(null);
		party.addFake(leader);
		leaderProfile.setParty(party);
		_fakeParties.add(party);
		
		final int size = Rnd.get(2, FakePartyConfig.ROAMING_MAX_SIZE);
		for (int i = 1; i < size; i++)
		{
			if ((FakePlayerPvpConfig.MAX_ALIVE > 0) && (FakePlayerPvpManager.getInstance().getFakePlayers().size() >= FakePlayerPvpConfig.MAX_ALIVE))
			{
				break;
			}
			
			final FakePlayerPvpBuild build = ((i == 1) && !supports.isEmpty() && (Rnd.get(100) < FakePartyConfig.ROAMING_SUPPORT_CHANCE)) ? supports.get(Rnd.get(supports.size())) : FakePlayerPvpData.getInstance().getRandomBuild();
			final int level = Math.max(1, Math.min(85, leader.getLevel() + Rnd.get(-2, 2)));
			final Location point = GeoEngine.getInstance().getValidLocation(leader.getX(), leader.getY(), leader.getZ(), leader.getX() + Rnd.get(-150, 150), leader.getY() + Rnd.get(-150, 150), leader.getZ(), leader.getInstanceId());
			final Npc friend = FakePlayerPvpManager.getInstance().spawnFakePlayer(build, level, point.getX(), point.getY(), point.getZ(), leader.getInstanceId(), null, null);
			if (friend == null)
			{
				continue;
			}
			
			friend.getTemplate().getFakePlayerPvpProfile().setParty(party);
			party.addFake(friend);
			
			// Friends that hunt together are often of the same clan.
			FakeClanManager.getInstance().shareClan(friend, leader);
		}
		
		checkFakeOnly(party);
	}
	
	/**
	 * A party of fake players only breaks up when one member is left.
	 * @param party the party
	 */
	private void checkFakeOnly(FakePlayerParty party)
	{
		int alive = 0;
		for (Npc fake : party.getFakes())
		{
			if (fake.isSpawned() && !fake.isDead())
			{
				alive++;
			}
		}
		
		if (alive > 1)
		{
			return;
		}
		
		_fakeParties.remove(party);
		for (Npc fake : party.getFakes())
		{
			party.removeFake(fake);
			fake.getTemplate().getFakePlayerPvpProfile().setParty(null);
		}
	}
	
	/**
	 * @param fake a fake player of a party of fake players only
	 * @return {@code true} if it leads its party: the others follow it, and it logs off for all of them
	 */
	public boolean isFakeLeader(Npc fake)
	{
		final FakePlayerParty party = getParty(fake);
		return (party == null) || (party.getLeader() == fake);
	}
	
	/**
	 * Called when a fake player of a party is attacked by a player (or another fake player): the others of its party fight them too.
	 * @param fake the fake player attacked
	 * @param enemy who attacks it
	 */
	public void onFakeAttacked(Attackable fake, Creature enemy)
	{
		final FakePlayerParty party = getParty(fake);
		if ((party == null) || (enemy == null) || party.isMember(enemy))
		{
			return;
		}
		
		for (Npc other : party.getFakes())
		{
			if ((other != fake) && !other.isDead() && other.isAttackable() && (other.calculateDistance2D(fake) < 1500) && !other.asAttackable().getAggroList().containsKey(enemy))
			{
				FakePlayerPvpManager.getInstance().assistFight(other.asAttackable(), enemy);
			}
		}
	}
	
	// ---------------------------------------------------------------------------------------------
	// Chat
	// ---------------------------------------------------------------------------------------------
	
	private void partyChat(Npc fake, String text)
	{
		final FakePlayerParty party = getParty(fake);
		if (party != null)
		{
			partyChat(fake, party, text);
		}
	}
	
	private static void partyChat(Npc fake, FakePlayerParty party, String text)
	{
		final CreatureSay say = new CreatureSay(fake, ChatType.PARTY, fake.getName(), text);
		for (Player player : party.getPlayers())
		{
			if (player.isOnline())
			{
				player.sendPacket(say);
			}
		}
	}
	
	private static void whisper(Npc fake, Player player, String text)
	{
		if (player.isOnline())
		{
			player.sendPacket(new CreatureSay(fake, ChatType.WHISPER, fake.getName(), text));
		}
	}
	
	private static String pick(String[] texts)
	{
		return texts[Rnd.get(texts.length)];
	}
	
	// ---------------------------------------------------------------------------------------------
	// Every second
	// ---------------------------------------------------------------------------------------------
	
	private void update()
	{
		try
		{
			final long now = System.currentTimeMillis();
			
			// Fake players that waited long enough for an invite go back to hunting.
			for (Map.Entry<Npc, Integer> entry : _responders.entrySet())
			{
				final Npc fake = entry.getKey();
				final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
				if (fake.isDead() || !fake.isSpawned() || (profile.getParty() != null))
				{
					_responders.remove(fake);
					continue;
				}
				
				final int lfTarget = profile.getLfTarget(now);
				if (lfTarget != 0)
				{
					// On its way: away from players its AI may not think yet, so it is sent over.
					final Player player = World.getInstance().getPlayer(lfTarget);
					if ((player != null) && (player.getInstanceId() == fake.getInstanceId()) && (fake.calculateDistance2D(player) > WALK_OVER_DISTANCE))
					{
						walkTo(fake, player);
					}
				}
				else
				{
					_responders.remove(fake);
					final Player player = World.getInstance().getPlayer(entry.getValue());
					if ((player != null) && FakePartyConfig.CHAT && (profile.getLfUntil() <= now) && (Rnd.get(100) < 60))
					{
						whisper(fake, player, pick(NEVERMIND));
					}
					FakePlayerPvpManager.getInstance().onLeaveParty(fake);
				}
			}
			
			for (FakePlayerParty party : _parties.values())
			{
				// The player logged off: its fake players go on without it.
				final Player host = party.getHost();
				if (!host.isOnline() || (host.getClient() == null) || host.getClient().isDetached())
				{
					disband(party, true);
					continue;
				}
				
				final Creature leader = party.getLeader();
				for (Npc fake : party.getFakes())
				{
					// Deleted without going through its decay (should not happen).
					if (!fake.isSpawned() && !fake.isDead())
					{
						removeFromParty(fake, party, null);
						continue;
					}
					
					if (!fake.isDead())
					{
						// Its hunting ground is where its party is: nothing sends it back somewhere else.
						if (fake.getSpawn() != null)
						{
							fake.getSpawn().setXYZ(fake.getX(), fake.getY(), fake.getZ());
						}
						
						// The leader teleported: it gets there a while later (its AI may have gone idle with nobody around). Left behind on foot, it runs after it.
						if ((leader != null) && !isFarFrom(fake, leader) && (fake.calculateDistance2D(leader) > WALK_OVER_DISTANCE))
						{
							_farSince.remove(fake.getObjectId());
							walkTo(fake, leader);
						}
						else if ((leader != null) && !leader.isDead() && isFarFrom(fake, leader))
						{
							final Long since = _farSince.putIfAbsent(fake.getObjectId(), now);
							if ((since != null) && ((now - since) > (FakePartyConfig.TELEPORT_DELAY * 1000L)) && !fake.isCastingNow())
							{
								_farSince.remove(fake.getObjectId());
								teleportToLeader(fake, leader);
							}
						}
						else
						{
							_farSince.remove(fake.getObjectId());
						}
					}
					
					final long status = ((long) (fake.isDead() ? 0 : (int) fake.getCurrentHp()) << 32) | ((int) fake.getCurrentMp() & 0xFFFFFFFFL);
					final Long last = _lastStatus.put(fake.getObjectId(), status);
					if ((last == null) || (last != status))
					{
						final FakePartySmallWindowUpdate packet = new FakePartySmallWindowUpdate(fake);
						for (Player player : party.getPlayers())
						{
							player.sendPacket(packet);
						}
					}
				}
			}
			
			for (FakePlayerParty party : _fakeParties)
			{
				checkFakeOnly(party);
			}
			
			_decisions.entrySet().removeIf(entry -> Math.abs(entry.getValue()) < now);
			_nextRequest.entrySet().removeIf(entry -> entry.getValue() < now);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Problem while updating the fake parties.", e);
		}
	}
	
	public static FakePartyManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final FakePartyManager INSTANCE = new FakePartyManager();
	}
}
