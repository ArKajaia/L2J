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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
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
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerHolder;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerParty;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild;
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
 * <li>A player who meets a roaming fake player invites it ({@link #onInvite}, called from {@code RequestJoinParty}). It may accept, more likely the closer their levels are ({@link #getAcceptChance}), and joins the party ({@link FakePlayerParty}). Fake players are npcs, so they
 * are kept next to the player's {@link Party}, and the party window is sent with them ({@link #refreshWindows}).</li>
 * <li>In the party its AI ({@link FakePlayerPvpAI}) follows the leader, fights what the party fights and, for a healer or buffer, looks after the party. Its damage and kills count for the party ({@link #getRewardPlayer}, {@link #getExpShare}), party skills reach it
 * and its area skills spare the party ({@link #isSameGroup}).</li>
 * <li>Dismissed, or when the player leaves the party or logs off, it goes back to hunting where it is. Killed, it comes back to the party from town a while after its body is gone, unless someone resurrects it first.</li>
 * <li>Roaming fake players sometimes come with friends ({@link #onRoamingSpawn}): a party of fake players only, which hunts together.</li>
 * </ul>
 */
public class FakePartyManager
{
	private static final Logger LOGGER = Logger.getLogger(FakePartyManager.class.getName());
	
	/** Party window HP/MP of the fake players are checked this often. */
	private static final long UPDATE_INTERVAL = 1000;
	/** A party holds this many members, players and fake players. */
	private static final int MAX_PARTY_SIZE = 9;
	/** A fake player coming back from town appears out of sight, or about this far when every spot around is in sight. */
	private static final int ARRIVAL_DISTANCE = 1300;
	/** The party leader got this far (or into another instance): its fake players get there after {@link FakePartyConfig#TELEPORT_DELAY}, like players taking the same gatekeeper. */
	private static final int TELEPORT_FOLLOW_DISTANCE = 4000;
	/** A fake player this far from its party leader, standing still, is sent over (see {@link #walkTo}). */
	private static final int WALK_OVER_DISTANCE = 600;
	/** A fake player only joins a player this close. */
	private static final int INVITE_RANGE = 2000;
	/** How long a fake player sticks to its answer to someone's invites. */
	private static final long INVITE_MEMORY = 300000;
	
	// What fake players say.
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
	private static final String[] DECLINE_LOW =
	{
		"ur too low for me sry",
		"no ty, too low lvl",
		"lvl gap too big",
		"sry, i'd get no exp with u",
	};
	private static final String[] DECLINE_HIGH =
	{
		"ur way too high for me",
		"lol no, i'd die here",
		"too high lvl for me sry",
		"cant keep up with u, sry",
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
	/** The answer of a fake player to a player's invites: (fake object id &lt;&lt; 32 | player object id) -> until when, positive for yes, negative for no. */
	private final Map<Long, Long> _decisions = new ConcurrentHashMap<>();
	/** HP/MP of the party fake players last sent to the party window (object id -> HP &lt;&lt; 32 | MP). */
	private final Map<Integer, Long> _lastStatus = new ConcurrentHashMap<>();
	/** Party fake players whose leader is far away (it teleported), and since when (object id -> time). */
	private final Map<Integer, Long> _farSince = new ConcurrentHashMap<>();
	/** Dead party fake players on their way back from town, by name. */
	private final Map<String, ScheduledFuture<?>> _returns = new ConcurrentHashMap<>();
	
	protected FakePartyManager()
	{
		ThreadPool.scheduleAtFixedRate(this::update, UPDATE_INTERVAL, UPDATE_INTERVAL);
	}
	
	/**
	 * @return {@code true} if players can party with fake players
	 */
	public boolean isEnabled()
	{
		return FakePartyConfig.ENABLED && FakePlayersConfig.FAKE_PLAYERS_ENABLED && !FakePlayerPvpData.getInstance().getBuilds().isEmpty();
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
	 * Called from {@code RequestJoinParty} when no player has the invited name: an invited fake player answers like a player would. It may accept if it hunts close by and isn't busy, see {@link #getAcceptChance}.
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
		
		if ((getMemberCount(requestor) >= MAX_PARTY_SIZE) || (getFakeCount(requestor) >= FakePartyConfig.MAX_FAKES))
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
		
		final boolean accept = wantsToJoin(fake, requestor, System.currentTimeMillis());
		ThreadPool.schedule(() -> answerInvite(requestor, fake, accept), Rnd.get(800, 5000));
		return true;
	}
	
	/**
	 * @param fake a fake player
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
		
		final boolean accept = !FakePlayerPvpManager.isInPvp(fake) && !FakeClanManager.getInstance().isWarEnemy(fake, requestor) && (fake.calculateDistance2D(requestor) <= INVITE_RANGE) && (Rnd.get(100) < getAcceptChance(fake, requestor));
		_decisions.put(key, accept ? (now + INVITE_MEMORY) : -(now + INVITE_MEMORY));
		return accept;
	}
	
	/**
	 * The chance (in %) a fake player accepts a party invite: {@link FakePartyConfig#INVITE_ACCEPT_CHANCE} at the same level, less for each level the inviter is below it ({@link FakePartyConfig#INVITE_CHANCE_PER_LEVEL_BELOW}, it would have to carry them) or above it
	 * ({@link FakePartyConfig#INVITE_CHANCE_PER_LEVEL_ABOVE}, it can't keep up), none past {@link FakePartyConfig#INVITE_MAX_LEVEL_DIFFERENCE}. Members of the inviter's clan or alliance are keener ({@link FakePartyConfig#INVITE_CLAN_BONUS}).
	 * @param fake the fake player
	 * @param requestor the player that invites it
	 * @return the chance, 0-100
	 */
	public static int getAcceptChance(Npc fake, Player requestor)
	{
		final int difference = requestor.getLevel() - fake.getLevel();
		if (Math.abs(difference) > FakePartyConfig.INVITE_MAX_LEVEL_DIFFERENCE)
		{
			return 0;
		}
		
		int chance = FakePartyConfig.INVITE_ACCEPT_CHANCE - (difference < 0 ? -difference * FakePartyConfig.INVITE_CHANCE_PER_LEVEL_BELOW : difference * FakePartyConfig.INVITE_CHANCE_PER_LEVEL_ABOVE);
		if (FakeClanManager.getInstance().isFriend(fake, requestor))
		{
			chance += FakePartyConfig.INVITE_CLAN_BONUS;
		}
		return Math.max(0, Math.min(100, chance));
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
			// Says why when the levels are far apart.
			final int difference = requestor.getLevel() - fake.getLevel();
			whisper(fake, requestor, pick(difference <= -5 ? DECLINE_LOW : difference >= 5 ? DECLINE_HIGH : DECLINE));
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
		profile.setParty(party);
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
	 * @return how many members its party has, with the fake players
	 */
	private int getMemberCount(Player player)
	{
		final FakePlayerParty party = getParty(player);
		return (party != null) && !party.isFakeOnly() ? party.size() : (player.isInParty() ? player.getParty().getMemberCount() : 1);
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
	 * @param name a fake player name
	 * @return {@code true} if that party fake player died and comes back to its party from town
	 */
	public boolean isComingBack(String name)
	{
		return _returns.containsKey(name);
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
