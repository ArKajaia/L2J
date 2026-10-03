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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.custom.CommunityBoardConfig;
import org.l2jmobius.gameserver.config.custom.FakePlayersConfig;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.FakePlayerTown.Role;
import org.l2jmobius.gameserver.managers.FakePlayerTown.TownNpc;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerHolder;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.ChangeWaitType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillLaunched;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillUse;
import org.l2jmobius.gameserver.network.serverpackets.SocialAction;
import org.l2jmobius.gameserver.network.serverpackets.StopRotation;
import org.l2jmobius.gameserver.util.LocationUtil;

/**
 * One town fake player (see {@link FakePlayerTownManager}) from the moment it arrives in town until it leaves.<br>
 * It comes with a plan, like a player that comes back from a hunt, logs in, or only drops by for buffs: errands (warehouse, grocer, blacksmith, shops, masters, a quest npc...), buffs before going out again, some time with others, sitting or standing around, and a way
 * out (a gatekeeper, the community board teleport, or logging off). Newbies sometimes walk up to someone to ask for a little adena, and a few come only to sit in a private store ({@link FakePlayerTownStore}) that players can buy from. The plan, the npc it picks, where it stands, how long everything takes and how it types all come from dice and from its own temper, so no two of them do the same thing the same way.
 */
final class FakePlayerTownVisitor
{
	/** What an errand is. */
	enum Kind
	{
		/** Talks to a town npc (warehouse, grocer, shop, blacksmith, master, quest npc...). */
		NPC,
		/** Gets the newbie support magic from a Newbie Guide or an Adventurers' Guide. */
		GUIDE_BUFF,
		/** Asks a buffer for buffs. */
		ASK_BUFFER,
		/** Casts its own class buffs. */
		SELF_BUFF,
		/** Goes to someone to talk, or joins a few talking. */
		SOCIALIZE,
		IDLE,
		SIT,
		/** Walks somewhere close, for no reason. */
		WANDER,
		/** Walks across town to another street, sometimes on to one more. */
		STROLL,
		/** A newbie walks up to someone and asks for a little adena. */
		BEG,
		/** Sits in its private store. */
		STORE,
		/** Looks at a player's private store. */
		BROWSE_STORE,
		/** A buffer waiting for people to buff. */
		BUFFER_POST,
		LEAVE_GATEKEEPER,
		/** Teleports away with the community board (Alt+B), from wherever it stands. */
		LEAVE_BOARD,
		LOGOUT;
		
		boolean isLeave()
		{
			return (this == LEAVE_GATEKEEPER) || (this == LEAVE_BOARD) || (this == LOGOUT);
		}
	}
	
	private enum Phase
	{
		START,
		TRAVEL,
		WORK
	}
	
	/** Why it is in town, which decides its plan. */
	private enum Visit
	{
		/** Back from a hunt: warehouse, grocer, maybe more, buffs, back out. */
		RESTOCK,
		/** Only here for buffs. */
		QUICK_BUFF,
		SHOPPING,
		/** Hangs around and talks. */
		SOCIAL,
		/** Sits or stands for a long time. */
		AFK,
		/** Learns skills, quests. */
		TRAINING,
		/** A low level running between the npcs of its village. */
		NEWBIE,
		/** Sells in a private store. */
		VENDOR
	}
	
	static final class Errand
	{
		final Kind kind;
		final Role role;
		/** The npc to go to, {@code null} to pick one. */
		TownNpc presetNpc;
		/** Extra time it takes (leaving behind a party member...). */
		long extraDelay;
		/** A long version (an AFK sitter...). */
		boolean longVersion;
		
		Errand(Kind kind, Role role)
		{
			this.kind = kind;
			this.role = role;
		}
		
		@Override
		public String toString()
		{
			return role != null ? kind + "(" + role + ")" : kind.toString();
		}
	}
	
	/** Newbie support magic, like data/scripts/handlers/bypass/npc/SupportMagic. */
	private static final int[] GUIDE_FIGHTER_BUFFS =
	{
		4322, // Wind Walk
		4323, // Shield
		5637, // Magic Barrier
		4324, // Bless the Body
		4325, // Vampiric Rage
		4326, // Regeneration
	};
	private static final int[] GUIDE_MAGE_BUFFS =
	{
		4322, // Wind Walk
		4323, // Shield
		5637, // Magic Barrier
		4328, // Bless the Soul
		4329, // Acumen
		4330, // Concentration
		4331, // Empower
	};
	private static final int GUIDE_HASTE_LOW = 4327;
	private static final int GUIDE_HASTE_HIGH = 5632;
	/** Buffs that make it run faster once it got them, like a buffed player (Wind Walk, Adventurer's Wind Walk, Song of Wind, Chant of Movement, Improved Movement). */
	private static final int[] SPEED_BUFFS =
	{
		1204,
		4322,
		268,
		1535,
		1504
	};
	
	// Social actions (see RequestActionUse).
	static final int SOCIAL_GREETING = 2;
	static final int SOCIAL_VICTORY = 3;
	static final int SOCIAL_NO = 5;
	static final int SOCIAL_YES = 6;
	static final int SOCIAL_BOW = 7;
	static final int SOCIAL_UNAWARE = 8;
	static final int SOCIAL_WAITING = 9;
	static final int SOCIAL_LAUGH = 10;
	static final int SOCIAL_APPLAUSE = 11;
	static final int SOCIAL_DANCE = 12;
	static final int SOCIAL_SORROW = 13;
	
	private final FakePlayerTownManager _manager;
	final FakePlayerTown town;
	final Spawn spawn;
	final Npc npc;
	final int level;
	final PlayerClass playerClass;
	final boolean mage;
	/** The buff line of a resident buffer, {@code null} for everyone else. */
	final FakePlayerTownManager.BufferLine bufferLine;
	/** What a seller sells, {@code null} for everyone else. */
	final FakePlayerTownStore store;
	/** Its store is open (sitting at its spot). */
	private volatile boolean _storeOpen;
	private final List<Skill> _selfBuffs;
	
	// Temper.
	final double patience = 0.6 + (Rnd.nextDouble() * 1.1);
	final double chatty = Rnd.nextDouble();
	final double gestures = Rnd.nextDouble() * Rnd.nextDouble();
	final double sitter = Rnd.nextDouble() * Rnd.nextDouble();
	final boolean loner = Rnd.get(100) < 22;
	final boolean walker = Rnd.get(100) < 3;
	final int typingMs = Rnd.get(110, 260);
	final int reactionMs = Rnd.get(1500, 6500);
	final FakePlayerTownChat.Style style = new FakePlayerTownChat.Style();
	final long arrivedAt = System.currentTimeMillis();
	long leaveBy;
	private Visit _visit;
	/** Once its errands are done it hangs around (stands, sits, talks, walks a bit) until {@link #leaveBy}, instead of leaving right away. */
	private boolean _lingering;
	
	// What it does.
	private final Deque<Errand> _plan = new ArrayDeque<>();
	private Errand _errand;
	private Phase _phase = Phase.START;
	private long _nextAction;
	private Location _destination;
	private Location _leg;
	private long _legDeadline;
	private int _travelFails;
	/** How many errands it gave up for lack of a way there (for //faketown). */
	private int _aborts;
	private boolean _legWatched;
	private TownNpc _npcTarget;
	private Location _spot;
	/** The buffer it asks, the one it walks up to for a talk, the store it looks at. */
	private WorldObject _other;
	private FakePlayerTownCircle _joining;
	FakePlayerTownCircle circle;
	/** Driven by someone else (a conversation, a buff exchange, waiting for someone coming to talk) until released, or until this time at the latest. */
	private long _heldUntil;
	private boolean _held;
	boolean gone;
	
	/** Players it said hello to (object ids), so it doesn't greet the same one twice. */
	private final Set<Integer> _greeted = new HashSet<>();
	/** How many walks across town it chained. */
	private int _strolls;
	
	// Resident buffer.
	private final Deque<FakePlayerTownVisitor> _requests = new ArrayDeque<>();
	private boolean _serving;
	
	FakePlayerTownVisitor(FakePlayerTownManager manager, FakePlayerTown town, Spawn spawn, Npc npc, int level, PlayerClass playerClass, FakePlayerTownManager.BufferLine bufferLine, FakePlayerTownStore store, List<Skill> selfBuffs)
	{
		_manager = manager;
		this.town = town;
		this.spawn = spawn;
		this.npc = npc;
		this.level = level;
		this.playerClass = playerClass;
		mage = playerClass.isMage();
		this.bufferLine = bufferLine;
		this.store = store;
		_selfBuffs = selfBuffs;
	}
	
	boolean isBuffer()
	{
		return bufferLine != null;
	}
	
	/**
	 * @return {@code true} if it came to town to sell in a private store
	 */
	boolean isVendor()
	{
		return store != null;
	}
	
	/**
	 * @return {@code true} if its private store is open
	 */
	boolean isStoreOpen()
	{
		return _storeOpen && !gone;
	}
	
	/**
	 * Makes its plan for this visit.
	 * @param midSession {@code true} if it was already in town (server start): it is somewhere in the middle of its plan
	 */
	void makePlan(boolean midSession)
	{
		final long now = System.currentTimeMillis();
		_plan.clear();
		final double stayScale = FakePlayersConfig.FAKE_TOWN_PLAYERS_STAY_SCALE / 100.0;
		if (isBuffer())
		{
			_visit = Visit.AFK;
			_plan.add(new Errand(Kind.BUFFER_POST, null));
			_plan.add(leaveErrand(Rnd.get(100) < 70 ? Kind.LOGOUT : Kind.LEAVE_BOARD));
			leaveBy = now + (long) (Rnd.get(25, 120) * 60000L * stayScale);
			return;
		}
		
		if (isVendor())
		{
			// Gets its things out of the warehouse first now and then, then sits down in its store until it is sold out or tired of it.
			_visit = Visit.VENDOR;
			if (!midSession && (Rnd.get(100) < 40))
			{
				_plan.add(npcErrand(Role.WAREHOUSE));
			}
			_plan.add(new Errand(Kind.STORE, null));
			_plan.add(leaveErrand(Rnd.get(100) < 70 ? Kind.LOGOUT : null));
			leaveBy = now + (long) (Rnd.get(20, 90) * 60000L * stayScale);
			return;
		}
		
		_visit = pickVisit();
		final List<Errand> errands = new ArrayList<>();
		switch (_visit)
		{
			case RESTOCK:
			{
				if (Rnd.get(100) < 65)
				{
					errands.add(npcErrand(Role.WAREHOUSE));
				}
				if (Rnd.get(100) < 80)
				{
					errands.add(npcErrand(Role.GROCER));
				}
				if (Rnd.get(100) < 30)
				{
					errands.add(npcErrand(Role.MERCHANT));
				}
				if (Rnd.get(100) < 25)
				{
					errands.add(npcErrand(Role.BLACKSMITH));
				}
				if (Rnd.get(100) < 10)
				{
					errands.add(npcErrand(Role.MASTER));
				}
				if (Rnd.get(100) < 15)
				{
					errands.add(npcErrand(Role.OTHER));
				}
				Collections.shuffle(errands);
				addBuffStep(errands, 80);
				errands.add(leaveErrand(null));
				break;
			}
			case QUICK_BUFF:
			{
				if (Rnd.get(100) < 30)
				{
					errands.add(new Errand(Kind.IDLE, null));
				}
				addBuffStep(errands, 100);
				errands.add(leaveErrand(Rnd.get(100) < 50 ? Kind.LEAVE_GATEKEEPER : Kind.LEAVE_BOARD));
				break;
			}
			case SHOPPING:
			{
				final Role[] shops =
				{
					Role.MERCHANT,
					Role.MERCHANT,
					Role.BLACKSMITH,
					Role.GROCER,
					Role.WAREHOUSE,
					Role.OTHER
				};
				final int count = Rnd.get(2, 4);
				for (int i = 0; i < count; i++)
				{
					errands.add(npcErrand(shops[Rnd.get(shops.length)]));
				}
				if (Rnd.get(100) < 30)
				{
					errands.add(Rnd.get(errands.size()), new Errand(Kind.BROWSE_STORE, null));
				}
				if (Rnd.get(100) < 40)
				{
					errands.add(Rnd.get(errands.size() + 1), new Errand(Kind.SOCIALIZE, null));
				}
				addBuffStep(errands, 35);
				errands.add(leaveErrand(null));
				break;
			}
			case SOCIAL:
			{
				errands.add(new Errand(Rnd.get(100) < (30 + (sitter * 50)) ? Kind.SIT : Kind.IDLE, null));
				errands.add(new Errand(Kind.SOCIALIZE, null));
				if (Rnd.get(100) < 60)
				{
					errands.add(new Errand(Kind.WANDER, null));
				}
				if (Rnd.get(100) < 45)
				{
					errands.add(new Errand(Kind.STROLL, null));
				}
				if (Rnd.get(100) < 50)
				{
					errands.add(npcErrand(randomRole()));
				}
				if (Rnd.get(100) < 45)
				{
					errands.add(new Errand(Kind.SOCIALIZE, null));
				}
				if (Rnd.get(100) < 35)
				{
					errands.add(new Errand(Kind.BROWSE_STORE, null));
				}
				if (canBeg() && (Rnd.get(100) < 20))
				{
					errands.add(Rnd.get(errands.size() + 1), new Errand(Kind.BEG, null));
				}
				if (Rnd.get(100) < 40)
				{
					errands.add(new Errand(Kind.IDLE, null));
				}
				addBuffStep(errands, 30);
				errands.add(leaveErrand(Rnd.get(100) < 35 ? Kind.LOGOUT : null));
				leaveBy = now + (long) (Rnd.get(10, 45) * 60000L * stayScale);
				_lingering = true;
				break;
			}
			case AFK:
			{
				if (Rnd.get(100) < 50)
				{
					errands.add(new Errand(Kind.STROLL, null));
				}
				else if (Rnd.get(100) < 60)
				{
					errands.add(new Errand(Kind.WANDER, null));
				}
				final Errand afk = new Errand(Rnd.get(100) < (40 + (sitter * 60)) ? Kind.SIT : Kind.IDLE, null);
				afk.longVersion = true;
				errands.add(afk);
				if (Rnd.get(100) < 30)
				{
					errands.add(new Errand(Kind.SOCIALIZE, null));
				}
				if (Rnd.get(100) < 40)
				{
					errands.add(new Errand(Kind.WANDER, null));
				}
				errands.add(leaveErrand(Rnd.get(100) < 65 ? Kind.LOGOUT : null));
				leaveBy = now + (long) (Rnd.get(15, 50) * 60000L * stayScale);
				_lingering = true;
				break;
			}
			case TRAINING:
			{
				final int count = Rnd.get(1, 3);
				for (int i = 0; i < count; i++)
				{
					errands.add(npcErrand(Rnd.nextBoolean() ? Role.MASTER : Role.OTHER));
				}
				if (Rnd.get(100) < 40)
				{
					errands.add(npcErrand(Role.GROCER));
				}
				Collections.shuffle(errands);
				addBuffStep(errands, 50);
				errands.add(leaveErrand(null));
				break;
			}
			case NEWBIE:
			{
				if ((level >= 6) && (Rnd.get(100) < 60))
				{
					errands.add(new Errand(Kind.GUIDE_BUFF, Role.GUIDE));
				}
				final int count = Rnd.get(1, 3);
				for (int i = 0; i < count; i++)
				{
					errands.add(npcErrand(Role.OTHER));
				}
				if (Rnd.get(100) < 40)
				{
					errands.add(npcErrand(Role.MASTER));
				}
				if (Rnd.get(100) < 50)
				{
					errands.add(npcErrand(Role.GROCER));
				}
				if (Rnd.get(100) < 40)
				{
					errands.add(new Errand(Kind.WANDER, null));
				}
				if (canBeg() && (Rnd.get(100) < 35))
				{
					errands.add(new Errand(Kind.BEG, null));
				}
				Collections.shuffle(errands);
				addBuffStep(errands, 30);
				errands.add(leaveErrand(null));
				break;
			}
		}
		
		// Now and then something in between: a pause, a sit, a chat, a little walk.
		final List<Errand> full = new ArrayList<>();
		for (int i = 0; i < errands.size(); i++)
		{
			full.add(errands.get(i));
			if (i == (errands.size() - 1))
			{
				break;
			}
			
			final int roll = Rnd.get(100);
			if (roll < 7)
			{
				full.add(new Errand(Kind.IDLE, null));
			}
			else if (roll < (7 + (sitter * 5)))
			{
				full.add(new Errand(Kind.SIT, null));
			}
			else if ((roll < 26) && !loner && (Rnd.nextDouble() < (chatty * chatRate())))
			{
				full.add(new Errand(Kind.SOCIALIZE, null));
			}
			else if (roll < 36)
			{
				full.add(new Errand(Kind.WANDER, null));
			}
			else if (roll < 43)
			{
				full.add(new Errand(Kind.STROLL, null));
			}
			else if (roll < 49)
			{
				full.add(new Errand(Kind.BROWSE_STORE, null));
			}
			else if ((roll < 52) && canBeg())
			{
				full.add(new Errand(Kind.BEG, null));
			}
		}
		
		// Already in town for a while: somewhere in the middle of it.
		if (midSession && (full.size() > 1))
		{
			final int skip = Rnd.get(full.size() - 1);
			for (int i = 0; i < skip; i++)
			{
				full.remove(0);
			}
		}
		
		_plan.addAll(full);
		if (leaveBy == 0)
		{
			// Some hang around for a while once their errands are done, the others leave as soon as they are.
			if ((_visit != Visit.QUICK_BUFF) && (Rnd.get(100) < 25))
			{
				leaveBy = now + (long) (Rnd.get(6, 25) * 60000L * stayScale);
				_lingering = true;
			}
			else
			{
				leaveBy = now + (long) (60 * 60000L * stayScale); // Errands decide how long it stays, this is only a limit.
			}
		}
	}
	
	/**
	 * Hanging around once the errands are done: a few things to do before the way out.
	 */
	private void addFillers()
	{
		final Errand leave = _plan.pollLast();
		final int count = Rnd.get(1, 3);
		for (int i = 0; i < count; i++)
		{
			final double socialWeight = loner ? 4 : chatty * 35 * chatRate();
			final double sitWeight = 5 + (sitter * 20) + (_visit == Visit.AFK ? 15 : 0);
			final double[] weights =
			{
				16, // Idle.
				sitWeight,
				socialWeight,
				24, // Wander.
				10, // An npc.
				9, // A private store.
				14, // Across town.
				canBeg() ? 9 : 0 // Asking for adena.
			};
			double total = 0;
			for (double weight : weights)
			{
				total += weight;
			}
			
			double roll = Rnd.nextDouble() * total;
			int pick = 0;
			while ((pick < (weights.length - 1)) && ((roll -= weights[pick]) > 0))
			{
				pick++;
			}
			
			final Errand filler;
			switch (pick)
			{
				case 0:
				{
					filler = new Errand(Kind.IDLE, null);
					filler.longVersion = (_visit == Visit.AFK) && (Rnd.get(100) < 40);
					break;
				}
				case 1:
				{
					filler = new Errand(Kind.SIT, null);
					filler.longVersion = (_visit == Visit.AFK) && (Rnd.get(100) < 50);
					break;
				}
				case 2:
				{
					filler = new Errand(Kind.SOCIALIZE, null);
					break;
				}
				case 3:
				{
					filler = new Errand(Kind.WANDER, null);
					break;
				}
				case 4:
				{
					filler = npcErrand(randomRole());
					break;
				}
				case 5:
				{
					filler = new Errand(Kind.BROWSE_STORE, null);
					break;
				}
				case 6:
				{
					filler = new Errand(Kind.STROLL, null);
					break;
				}
				default:
				{
					filler = new Errand(Kind.BEG, null);
					break;
				}
			}
			_plan.addLast(filler);
		}
		
		if (leave != null)
		{
			_plan.addLast(leave);
		}
	}
	
	private Visit pickVisit()
	{
		final int roll = Rnd.get(100);
		if (level < 20)
		{
			return roll < 45 ? Visit.NEWBIE : roll < 61 ? Visit.RESTOCK : roll < 69 ? Visit.QUICK_BUFF : roll < 85 ? Visit.SOCIAL : roll < 89 ? Visit.AFK : Visit.SHOPPING;
		}
		if (level < 76)
		{
			return roll < 29 ? Visit.RESTOCK : roll < 40 ? Visit.QUICK_BUFF : roll < 56 ? Visit.SHOPPING : roll < 76 ? Visit.SOCIAL : roll < 83 ? Visit.AFK : roll < 93 ? Visit.TRAINING : Visit.NEWBIE;
		}
		return roll < 31 ? Visit.RESTOCK : roll < 42 ? Visit.QUICK_BUFF : roll < 59 ? Visit.SHOPPING : roll < 81 ? Visit.SOCIAL : roll < 90 ? Visit.AFK : Visit.TRAINING;
	}
	
	private static Errand npcErrand(Role role)
	{
		return new Errand(Kind.NPC, role);
	}
	
	private static Role randomRole()
	{
		final Role[] roles =
		{
			Role.WAREHOUSE,
			Role.GROCER,
			Role.MERCHANT,
			Role.BLACKSMITH,
			Role.MASTER,
			Role.OTHER,
			Role.OTHER
		};
		return roles[Rnd.get(roles.length)];
	}
	
	/**
	 * @return {@code true} if it is a newbie that may ask others for adena
	 */
	private boolean canBeg()
	{
		return FakePlayersConfig.FAKE_TOWN_PLAYERS_BEGGARS && isChatEnabled() && (level <= 25) && !isBuffer() && !isVendor();
	}
	
	private static double chatRate()
	{
		return FakePlayersConfig.FAKE_TOWN_PLAYERS_CHAT ? FakePlayersConfig.FAKE_TOWN_PLAYERS_CHAT_RATE / 100.0 : 0;
	}
	
	/**
	 * @return {@code true} if town fake players talk at all
	 */
	static boolean isChatEnabled()
	{
		return FakePlayersConfig.FAKE_TOWN_PLAYERS_CHAT && (FakePlayersConfig.FAKE_TOWN_PLAYERS_CHAT_RATE > 0);
	}
	
	/**
	 * Adds getting buffed before going out: from the guide (up to level 75, before the third class), from a buffer of the town, or its own class buffs.
	 * @param errands the plan
	 * @param chance the chance (in %) it gets buffs at all
	 */
	private void addBuffStep(List<Errand> errands, int chance)
	{
		if (Rnd.get(100) >= chance)
		{
			return;
		}
		
		final boolean guide = (level >= 6) && (level <= 75) && town.has(Role.GUIDE);
		final boolean buffers = town.countBuffers() > 0;
		final int guideWeight = guide ? 60 : 0;
		final int bufferWeight = buffers ? (level >= 76 ? 70 : 35) : 0;
		final int selfWeight = !_selfBuffs.isEmpty() ? 18 : 0;
		final int total = guideWeight + bufferWeight + selfWeight;
		if (total <= 0)
		{
			return;
		}
		
		final int roll = Rnd.get(total);
		if (roll < guideWeight)
		{
			errands.add(new Errand(Kind.GUIDE_BUFF, Role.GUIDE));
		}
		else if (roll < (guideWeight + bufferWeight))
		{
			errands.add(new Errand(Kind.ASK_BUFFER, null));
		}
		else
		{
			errands.add(new Errand(Kind.SELF_BUFF, null));
		}
	}
	
	/**
	 * @param kind the way out, {@code null} for any
	 * @return the last errand of a plan
	 */
	private Errand leaveErrand(Kind kind)
	{
		final boolean board = CommunityBoardConfig.CUSTOM_CB_ENABLED && CommunityBoardConfig.COMMUNITYBOARD_ENABLE_TELEPORTS;
		Kind way = kind;
		if (way == null)
		{
			final int roll = Rnd.get(100);
			way = roll < 55 ? Kind.LEAVE_GATEKEEPER : roll < 90 ? Kind.LEAVE_BOARD : Kind.LOGOUT;
		}
		if ((way == Kind.LEAVE_BOARD) && !board)
		{
			way = Kind.LEAVE_GATEKEEPER;
		}
		if ((way == Kind.LEAVE_GATEKEEPER) && !town.has(Role.GATEKEEPER))
		{
			way = board ? Kind.LEAVE_BOARD : Kind.LOGOUT;
		}
		return new Errand(way, way == Kind.LEAVE_GATEKEEPER ? Role.GATEKEEPER : null);
	}
	
	/**
	 * Starts in the middle of an errand at an npc (server start).
	 * @param townNpc the npc
	 * @param spot the spot it stands at, taken
	 */
	void startAtNpc(TownNpc townNpc, Location spot)
	{
		_errand = npcErrand(townNpc.role);
		_npcTarget = townNpc;
		_spot = spot;
		_phase = Phase.WORK;
		_nextAction = System.currentTimeMillis() + Rnd.get(1000, 30000);
		final Npc target = townNpc.getNpc();
		if (target != null)
		{
			npc.setHeading(LocationUtil.calculateHeadingFrom(npc, target));
		}
	}
	
	/**
	 * Starts sitting or standing around (server start, or a resident buffer at its post).
	 * @param sitting {@code true} if it sits
	 */
	void startIdle(boolean sitting)
	{
		_errand = new Errand(sitting ? Kind.SIT : Kind.IDLE, null);
		_phase = Phase.WORK;
		_nextAction = System.currentTimeMillis() + Rnd.get(5000, 90000);
	}
	
	/**
	 * Waits a moment before doing anything (the loading screen after a teleport, a party saying goodbye...).
	 * @param delay the time in milliseconds
	 */
	void delay(long delay)
	{
		_nextAction = Math.max(_nextAction, System.currentTimeMillis() + delay);
	}
	
	/**
	 * Called by the manager every tick.
	 * @param now the current time
	 */
	void update(long now)
	{
		if (gone)
		{
			return;
		}
		
		if (!npc.isSpawned() || npc.isDead())
		{
			_manager.leave(this);
			return;
		}
		
		if (_held)
		{
			// Nobody released it in time (should not happen): it goes on by itself.
			if (now > _heldUntil)
			{
				if (circle != null)
				{
					circle.leave(this, now);
				}
				release(now, 1000);
			}
			return;
		}
		
		switch (_phase)
		{
			case START:
			{
				if (now >= _nextAction)
				{
					startNextErrand(now);
				}
				break;
			}
			case TRAVEL:
			{
				updateTravel(now);
				break;
			}
			case WORK:
			{
				if (now >= _nextAction)
				{
					finishErrand(now);
				}
				break;
			}
		}
	}
	
	private void startNextErrand(long now)
	{
		// Time to go: whatever was left to do waits for another day.
		final Errand first = _plan.peekFirst();
		if ((now > leaveBy) && ((first == null) || !first.kind.isLeave()))
		{
			Errand leave = null;
			for (Errand errand : _plan)
			{
				if (errand.kind.isLeave())
				{
					leave = errand;
				}
			}
			_plan.clear();
			_plan.add(leave != null ? leave : leaveErrand(null));
		}
		
		// Errands done but in no hurry: it hangs around a bit longer.
		final Errand upcoming = _plan.peekFirst();
		if (_lingering && (now < (leaveBy - 60000)) && ((upcoming == null) || upcoming.kind.isLeave()))
		{
			if (upcoming == null)
			{
				_plan.add(leaveErrand(null));
			}
			addFillers();
		}
		
		final Errand next = _plan.pollFirst();
		_errand = next != null ? next : leaveErrand(null);
		
		// A sitting player gets up before walking anywhere.
		if (isSitting() && needsToStand(_errand.kind))
		{
			standUp();
			_plan.addFirst(_errand);
			_errand = null;
			_nextAction = now + Rnd.get(1200, 2200);
			return;
		}
		
		if (!begin(now))
		{
			clearTarget();
			_errand = null;
			_phase = Phase.START;
			_nextAction = now + Rnd.get(400, 2500);
		}
	}
	
	private static boolean needsToStand(Kind kind)
	{
		return (kind != Kind.SIT) && (kind != Kind.IDLE) && (kind != Kind.LOGOUT) && (kind != Kind.BUFFER_POST);
	}
	
	/**
	 * Starts the current errand.
	 * @param now the current time
	 * @return {@code false} if it can't be done (no such npc, nobody to talk to...)
	 */
	private boolean begin(long now)
	{
		switch (_errand.kind)
		{
			case NPC:
			case GUIDE_BUFF:
			case LEAVE_GATEKEEPER:
			{
				// Server start: the town isn't worked out yet, it stands around a bit first.
				if (!town.isPrepared())
				{
					_plan.addFirst(_errand);
					_errand = new Errand(Kind.IDLE, null);
					_phase = Phase.WORK;
					_nextAction = now + Rnd.get(5000, 20000);
					return true;
				}
				
				_npcTarget = _errand.presetNpc != null ? _errand.presetNpc : town.pickNpc(_errand.role, npc);
				if (_npcTarget == null)
				{
					return false;
				}
				
				_spot = town.takeSpot(_npcTarget, this);
				return (_spot != null) && travelTo(jitter(_spot, 12), now);
			}
			case ASK_BUFFER:
			{
				final FakePlayerTownVisitor buffer = _manager.pickBuffer(town, this);
				if (buffer == null)
				{
					return fallbackBuff(now);
				}
				
				final Location near = pointNear(buffer.npc, 90, 170);
				if (near == null)
				{
					return fallbackBuff(now);
				}
				
				_other = buffer.npc;
				return travelTo(near, now);
			}
			case SELF_BUFF:
			{
				if (_selfBuffs.isEmpty())
				{
					return false;
				}
				
				final List<Skill> buffs = new ArrayList<>(_selfBuffs);
				Collections.shuffle(buffs);
				final long time = _manager.castBuffs(npc, npc, buffs.subList(0, Math.min(buffs.size(), Rnd.get(1, 3))), 0.5 + (Rnd.nextDouble() * 0.4), null);
				_phase = Phase.WORK;
				_nextAction = now + time + Rnd.get(500, 2500);
				return true;
			}
			case SOCIALIZE:
			{
				if (!isChatEnabled())
				{
					return false;
				}
				
				// Joins a few already talking, or walks up to someone standing around (when the town isn't talking enough already).
				final boolean canStart = _manager.canStartCircle(town, now);
				final FakePlayerTownCircle existing = (Rnd.get(100) < (canStart ? 25 : 45)) ? _manager.pickCircle(town, this) : null;
				if (existing != null)
				{
					final Location near = pointNear(existing.getCenter(), 70, 110);
					if (near != null)
					{
						_joining = existing;
						return travelTo(near, now);
					}
				}
				
				final FakePlayerTownVisitor partner = canStart ? _manager.pickPartner(town, this) : null;
				if (partner == null)
				{
					_errand = new Errand(Kind.IDLE, null);
					return begin(now);
				}
				
				final Location near = pointNear(partner.npc, 55, 95);
				if (near == null)
				{
					return false;
				}
				
				_other = partner.npc;
				if (!travelTo(near, now))
				{
					_other = null;
					return false;
				}
				partner.hold(now, 60000); // It waits for this one to come over.
				return true;
			}
			case IDLE:
			{
				_phase = Phase.WORK;
				_nextAction = now + (_errand.longVersion ? duration(240, 90, 1200) : duration(10, 3, 80));
				return true;
			}
			case SIT:
			{
				sitDown();
				_phase = Phase.WORK;
				_nextAction = now + (_errand.longVersion ? duration(420, 150, 1800) : duration(40, 12, 240));
				return true;
			}
			case WANDER:
			{
				final Location place = wanderPlace();
				return (place != null) && travelTo(place, now);
			}
			case STROLL:
			{
				// Server start: the streets aren't worked out yet.
				if (!town.isPrepared())
				{
					_errand = new Errand(Kind.WANDER, null);
					return begin(now);
				}
				
				final Location place = strollPlace();
				return (place != null) && travelTo(place, now);
			}
			case BEG:
			{
				final WorldObject target = _manager.pickBegTarget(this);
				if (target == null)
				{
					return false;
				}
				
				final Location near = pointNear(target, 60, 110);
				if ((near == null) || !travelTo(near, now))
				{
					return false;
				}
				
				_other = target;
				final FakePlayerTownVisitor other = _manager.getVisitor(target);
				if (other != null)
				{
					other.hold(now, 60000); // It waits for this one to come over.
				}
				return true;
			}
			case STORE:
			{
				if (!town.isPrepared())
				{
					_plan.addFirst(_errand);
					_errand = new Errand(Kind.IDLE, null);
					_phase = Phase.WORK;
					_nextAction = now + Rnd.get(5000, 20000);
					return true;
				}
				
				final Location post = _manager.pickStorePost(town, this);
				if (post == null)
				{
					// Nowhere better: right where it stands.
					openStore(now);
					return true;
				}
				return travelTo(post, now);
			}
			case BROWSE_STORE:
			{
				final WorldObject store = _manager.pickStore(this);
				if (store == null)
				{
					return false;
				}
				
				final Location front = pointNear(store, 45, 80);
				if (front == null)
				{
					return false;
				}
				
				_other = store;
				return travelTo(front, now);
			}
			case BUFFER_POST:
			{
				final Location post = _manager.pickBufferPost(town, this);
				if (post == null)
				{
					// Nowhere better: buffs right where it stands.
					startDuty(now);
					return true;
				}
				return travelTo(post, now);
			}
			case LEAVE_BOARD:
			case LOGOUT:
			{
				_phase = Phase.WORK;
				_nextAction = now + Rnd.get(300, 3500) + _errand.extraDelay;
				return true;
			}
		}
		return false;
	}
	
	/**
	 * No buffer around: the guide if it can, its own buffs, or none.
	 */
	private boolean fallbackBuff(long now)
	{
		if ((level >= 6) && (level <= 75) && town.has(Role.GUIDE))
		{
			_errand = new Errand(Kind.GUIDE_BUFF, Role.GUIDE);
			return begin(now);
		}
		if (!_selfBuffs.isEmpty())
		{
			_errand = new Errand(Kind.SELF_BUFF, null);
			return begin(now);
		}
		return false;
	}
	
	/**
	 * @return a place close by to walk to (inside the town, not through a wall), {@code null} if none was found
	 */
	private Location wanderPlace()
	{
		final GeoEngine geo = GeoEngine.getInstance();
		final int range = Math.max(150, FakePlayersConfig.FAKE_TOWN_PLAYERS_WANDER_RANGE);
		
		// Sometimes towards a street point of the town, mostly just a few steps.
		if (Rnd.get(100) < 30)
		{
			final Location hub = town.getRandomHub();
			if (FakePlayerTown.distance2D(hub, npc) < (range * 3))
			{
				return jitter(hub, 80);
			}
		}
		
		for (int attempt = 0; attempt < 4; attempt++)
		{
			final double angle = Rnd.nextDouble() * 2 * Math.PI;
			final int distance = Rnd.get(100, range);
			final Location loc = geo.getValidLocation(npc.getX(), npc.getY(), npc.getZ(), npc.getX() + (int) (Math.cos(angle) * distance), npc.getY() + (int) (Math.sin(angle) * distance), npc.getZ(), 0);
			if ((FakePlayerTown.distance2D(loc, npc) > 80) && town.isInside(loc.getX(), loc.getY(), loc.getZ()))
			{
				return loc;
			}
		}
		return null;
	}
	
	/**
	 * @return a street point further away to walk to (another part of the town), {@code null} if none was found
	 */
	private Location strollPlace()
	{
		final List<Location> hubs = town.getRouteHubs();
		for (int attempt = 0; attempt < 8; attempt++)
		{
			final Location hub = hubs.get(Rnd.get(hubs.size()));
			final double distance = FakePlayerTown.distance2D(hub, npc);
			if ((distance > 500) && (distance < 3500))
			{
				return jitter(hub, 90);
			}
		}
		return null;
	}
	
	/**
	 * @param target a creature or point
	 * @param min the shortest distance
	 * @param max the longest distance
	 * @return a point at that distance from {@code target}, on the side this fake player comes from, on the ground, inside the town and in a straight line from {@code target}, {@code null} if none was found
	 */
	private Location pointNear(WorldObject target, int min, int max)
	{
		return pointNear(new Location(target.getX(), target.getY(), target.getZ()), min, max);
	}
	
	private Location pointNear(Location target, int min, int max)
	{
		final GeoEngine geo = GeoEngine.getInstance();
		final double towards = Math.atan2(npc.getY() - target.getY(), npc.getX() - target.getX());
		for (int attempt = 0; attempt < 6; attempt++)
		{
			final double angle = towards + (((Rnd.nextDouble() * 2) - 1) * Math.toRadians(attempt < 3 ? 50 : 150));
			final int distance = Rnd.get(min, max);
			final int x = target.getX() + (int) (Math.cos(angle) * distance);
			final int y = target.getY() + (int) (Math.sin(angle) * distance);
			final int z = geo.getHeight(x, y, target.getZ());
			if ((Math.abs(z - target.getZ()) < 60) && town.isInside(x, y, z) && geo.canMoveToTarget(target.getX(), target.getY(), target.getZ(), x, y, z, 0))
			{
				return new Location(x, y, z);
			}
		}
		return null;
	}
	
	/**
	 * @param location a point
	 * @param range how far it may move
	 * @return a point close to {@code location}, in a straight line from it
	 */
	private static Location jitter(Location location, int range)
	{
		final int x = location.getX() + Rnd.get(-range, range);
		final int y = location.getY() + Rnd.get(-range, range);
		return GeoEngine.getInstance().getValidLocation(location.getX(), location.getY(), location.getZ(), x, y, location.getZ(), 0);
	}
	
	// Walking.
	
	/**
	 * Walks to {@code destination}, through street points when it is far.
	 * @param destination where to go
	 * @param now the current time
	 * @return {@code false} if there is no way there
	 */
	private boolean travelTo(Location destination, long now)
	{
		_destination = destination;
		_travelFails = 0;
		return walkNextLeg(now);
	}
	
	private boolean walkNextLeg(long now)
	{
		final boolean watched = FakePlayerTown.isWatched(npc);
		if (walker)
		{
			npc.setWalking();
		}
		else
		{
			npc.setRunning();
		}
		
		// Nobody around: the move doesn't use the geodata anyway, it goes straight there.
		if (!watched)
		{
			return startLeg(_destination, false, now);
		}
		
		Location avoid = null;
		for (int attempt = 0; attempt < 3; attempt++)
		{
			final Location leg = town.nextLeg(npc, _destination, avoid);
			if (leg == null)
			{
				break;
			}
			
			startLeg(leg, true, now);
			
			// The movement found no way around the walls: it would walk through them. It stops right away and tries another way.
			if (npc.isMovingWithoutGeodata())
			{
				npc.stopMove(null);
				npc.getAI().setIntention(Intention.IDLE);
				if (leg == _destination)
				{
					if ((_npcTarget != null) && (_spot != null))
					{
						FakePlayerTown.dropSpot(_npcTarget, _spot);
					}
					break;
				}
				avoid = leg;
				continue;
			}
			return true;
		}
		return false;
	}
	
	private boolean startLeg(Location leg, boolean watched, long now)
	{
		_leg = leg;
		_legWatched = watched;
		npc.getAI().setIntention(Intention.MOVE_TO, leg);
		final double speed = Math.max(40, npc.getMoveSpeed());
		_legDeadline = now + (long) ((FakePlayerTown.distance2D(npc, leg) / speed) * 1700) + 4000;
		_phase = Phase.TRAVEL;
		return true;
	}
	
	private void updateTravel(long now)
	{
		if (npc.isMoving())
		{
			// A player came by while it walked without geodata: from here on it goes around the walls.
			if (!_legWatched && FakePlayerTown.isWatched(npc))
			{
				if (!walkNextLeg(now))
				{
					abort(now);
				}
			}
			else if (now > _legDeadline)
			{
				npc.stopMove(null);
				retry(now);
			}
			// Almost at a street point on the way: it clicks further before getting there, like a player, instead of stopping at each corner.
			else if (_legWatched && (_leg != _destination) && (FakePlayerTown.distance2D(npc, _leg) < 110) && (town.nextLeg(npc, _destination, _leg) != null))
			{
				final Location current = _leg;
				if (!walkNextLeg(now))
				{
					startLeg(current, true, now); // No better way after all: on to that street point first.
				}
			}
			return;
		}
		
		if (FakePlayerTown.distance2D(npc, _leg) > 80)
		{
			retry(now);
			return;
		}
		
		// A street point on the way: on to the next one, like a player clicking again.
		if ((_leg != _destination) && (FakePlayerTown.distance2D(npc, _destination) > 70))
		{
			if (!walkNextLeg(now))
			{
				abort(now);
			}
			return;
		}
		
		arrived(now);
	}
	
	private void retry(long now)
	{
		if ((++_travelFails > 2) || !walkNextLeg(now))
		{
			abort(now);
		}
	}
	
	/**
	 * Gives up the current errand (no way there, the one it walked to left...).
	 */
	private void abort(long now)
	{
		_aborts++;
		
		// A seller that can't get to its spot opens its store where it stands.
		if ((_errand != null) && (_errand.kind == Kind.STORE))
		{
			npc.stopMove(null);
			npc.getAI().setIntention(Intention.IDLE);
			openStore(now);
			return;
		}
		
		if ((_errand != null) && ((_errand.kind == Kind.SOCIALIZE) || (_errand.kind == Kind.BEG)) && (_other != null))
		{
			final FakePlayerTownVisitor partner = _manager.getVisitor(_other);
			if ((partner != null) && (partner.circle == null))
			{
				partner.release(now, Rnd.get(500, 3000));
			}
		}
		
		// It can't get out through a gatekeeper: it leaves another way.
		final boolean leaving = (_errand != null) && _errand.kind.isLeave();
		clearTarget();
		_errand = null;
		_phase = Phase.START;
		_nextAction = now + Rnd.get(500, 2500);
		if (leaving)
		{
			_plan.clear();
			_plan.add(leaveErrand(CommunityBoardConfig.CUSTOM_CB_ENABLED && CommunityBoardConfig.COMMUNITYBOARD_ENABLE_TELEPORTS ? Kind.LEAVE_BOARD : Kind.LOGOUT));
		}
	}
	
	private void arrived(long now)
	{
		_phase = Phase.WORK;
		switch (_errand.kind)
		{
			case NPC:
			case LEAVE_GATEKEEPER:
			{
				talkTo(_npcTarget);
				if (_errand.kind == Kind.NPC)
				{
					_nextAction = now + talkTime(_errand.role);
				}
				else
				{
					// Leaving with others: the first one ports quickly, the others right after.
					_nextAction = now + (_errand.presetNpc != null ? Rnd.get(1500, 3000) : duration(5, 2, 14)) + _errand.extraDelay;
				}
				break;
			}
			case GUIDE_BUFF:
			{
				final Npc guide = talkTo(_npcTarget);
				_phase = Phase.WORK;
				if (guide == null)
				{
					_nextAction = now + 1000;
					break;
				}
				
				// Reads the dialog, picks the buffs, and the guide casts them one after another.
				final long start = Rnd.get(1200, 3500);
				final List<Skill> buffs = guideBuffs();
				_manager.schedule(start, () ->
				{
					if (!gone)
					{
						_manager.castBuffs(guide, npc, buffs, 1, null);
					}
				});
				_nextAction = now + start + _manager.castTime(buffs, 1) + Rnd.get(800, 3500);
				break;
			}
			case ASK_BUFFER:
			{
				final FakePlayerTownVisitor buffer = _manager.getVisitor(_other);
				if ((buffer == null) || !buffer.isOnDuty())
				{
					clearTarget();
					if (!fallbackBuff(now))
					{
						_errand = null;
						_phase = Phase.START;
						_nextAction = now + Rnd.get(500, 2000);
					}
					break;
				}
				
				face(buffer.npc);
				say(FakePlayerTownChat.askBuff(style, buffer.bufferLine.songsOrDances()));
				_nextAction = now + 120000;
				hold(now, 120000);
				buffer.requestBuffs(this, now);
				break;
			}
			case SOCIALIZE:
			{
				if (_joining != null)
				{
					final FakePlayerTownCircle joining = _joining;
					_joining = null;
					if (joining.join(this, now))
					{
						break;
					}
				}
				else
				{
					final FakePlayerTownVisitor partner = _manager.getVisitor(_other);
					if ((partner != null) && partner._held && (partner.circle == null))
					{
						_other = null;
						_manager.startCircle(town, List.of(this, partner), FakePlayerTownChat.Topic.CHAT, now);
						break;
					}
				}
				
				// Nobody to talk to after all.
				clearTarget();
				_errand = new Errand(Kind.IDLE, null);
				begin(now);
				break;
			}
			case BROWSE_STORE:
			{
				if (_other != null)
				{
					face(_other);
				}
				_phase = Phase.WORK;
				final long browse = duration(10, 4, 40);
				_nextAction = now + browse;
				
				// A fake player's store: now and then it buys something.
				final FakePlayerTownVisitor seller = _manager.getVisitor(_other);
				if ((seller != null) && seller.isStoreOpen() && (Rnd.get(100) < 30))
				{
					_manager.schedule(Math.max(1000, browse - 1500), () -> seller.soldToFake(this));
				}
				break;
			}
			case STROLL:
			{
				// Sometimes on to another street right away.
				if ((++_strolls < 3) && (Rnd.get(100) < 35))
				{
					_plan.addFirst(new Errand(Kind.STROLL, null));
				}
				else
				{
					_strolls = 0;
				}
				finishErrand(now);
				break;
			}
			case BEG:
			{
				beg(now);
				break;
			}
			case STORE:
			{
				openStore(now);
				break;
			}
			case BUFFER_POST:
			{
				startDuty(now);
				break;
			}
			case WANDER:
			{
				if (Rnd.get(100) < 25)
				{
					_plan.addFirst(new Errand(Kind.WANDER, null));
				}
				finishErrand(now);
				break;
			}
			default:
			{
				finishErrand(now);
				break;
			}
		}
	}
	
	private void finishErrand(long now)
	{
		if (_errand == null)
		{
			_phase = Phase.START;
			return;
		}
		
		switch (_errand.kind)
		{
			case LEAVE_GATEKEEPER:
			case LEAVE_BOARD:
			case LOGOUT:
			{
				_manager.leave(this);
				return;
			}
			case BUFFER_POST:
			{
				// Done for the day, but not in the middle of buffing someone.
				if ((now < leaveBy) || _serving || !_requests.isEmpty())
				{
					dutyStep(now);
					return;
				}
				break;
			}
			case STORE:
			{
				// Still something to sell and not tired of it: it sits on, and now and then advertises in general chat.
				if (isStoreOpen() && (now < leaveBy) && !store.isEmpty())
				{
					storeStep(now);
					return;
				}
				
				closeStore();
				final Errand next = _plan.peekFirst();
				if ((next == null) || (next.kind != Kind.LOGOUT))
				{
					standUp();
				}
				break;
			}
			case SIT:
			{
				// About to log off: it does so sitting.
				final Errand next = _plan.peekFirst();
				if ((next == null) || (next.kind != Kind.LOGOUT))
				{
					standUp();
				}
				break;
			}
			case IDLE:
			{
				if (Rnd.nextDouble() < (gestures * 0.3))
				{
					social(Rnd.get(100) < 60 ? SOCIAL_WAITING : Rnd.nextBoolean() ? SOCIAL_UNAWARE : SOCIAL_DANCE);
				}
				break;
			}
			default:
			{
				break;
			}
		}
		
		clearTarget();
		_errand = null;
		_phase = Phase.START;
		_nextAction = now + pause();
	}
	
	private void clearTarget()
	{
		FakePlayerTown.releaseSpot(_npcTarget, _spot);
		_npcTarget = null;
		_spot = null;
		_other = null;
		_joining = null;
	}
	
	/**
	 * @return the time it takes to go on after an errand, mostly a moment, sometimes a while
	 */
	private long pause()
	{
		final int roll = Rnd.get(100);
		if (roll < 68)
		{
			return (long) (Rnd.get(300, 3000) * patience);
		}
		if (roll < 90)
		{
			return (long) (Rnd.get(3000, 12000) * patience);
		}
		return (long) (Rnd.get(12000, 45000) * patience);
	}
	
	/**
	 * @param median the usual time in seconds
	 * @param min the shortest time in seconds
	 * @param max the longest time in seconds
	 * @return a time in milliseconds around {@code median}, now and then much shorter or longer, scaled by its patience
	 */
	long duration(double median, double min, double max)
	{
		final double seconds = median * Math.exp(Rnd.nextGaussian() * 0.55) * patience;
		return (long) (Math.max(min, Math.min(max, seconds)) * 1000);
	}
	
	private long talkTime(Role role)
	{
		switch (role)
		{
			case WAREHOUSE:
			{
				return duration(15, 4, 60);
			}
			case GROCER:
			{
				return duration(14, 4, 70);
			}
			case MERCHANT:
			{
				return duration(18, 5, 100);
			}
			case BLACKSMITH:
			{
				return duration(25, 6, 140);
			}
			case MASTER:
			{
				return duration(10, 3, 50);
			}
			default:
			{
				return duration(12, 3, 70);
			}
		}
	}
	
	private List<Skill> guideBuffs()
	{
		final List<Skill> buffs = new ArrayList<>();
		for (int id : mage ? GUIDE_MAGE_BUFFS : GUIDE_FIGHTER_BUFFS)
		{
			addSkill(buffs, id);
		}
		if (!mage)
		{
			addSkill(buffs, level >= 40 ? GUIDE_HASTE_HIGH : GUIDE_HASTE_LOW);
		}
		return buffs;
	}
	
	private static void addSkill(List<Skill> skills, int id)
	{
		final Skill skill = SkillData.getInstance().getSkill(id, 1);
		if (skill != null)
		{
			skills.add(skill);
		}
	}
	
	/**
	 * @param skill a buff
	 * @return {@code true} if it makes the one buffed run faster
	 */
	static boolean isSpeedBuff(Skill skill)
	{
		for (int id : SPEED_BUFFS)
		{
			if (skill.getId() == id)
			{
				return true;
			}
		}
		return false;
	}
	
	// Resident buffer.
	
	/**
	 * @return {@code true} if it is a buffer waiting at its post, ready to buff
	 */
	boolean isOnDuty()
	{
		return isBuffer() && !gone && (_errand != null) && (_errand.kind == Kind.BUFFER_POST) && (_phase == Phase.WORK) && (System.currentTimeMillis() < (leaveBy - 30000));
	}
	
	/**
	 * @return how many are waiting for its buffs
	 */
	int getQueueSize()
	{
		return _requests.size() + (_serving ? 1 : 0);
	}
	
	private void startDuty(long now)
	{
		if ((_errand == null) || (_errand.kind != Kind.BUFFER_POST))
		{
			_errand = new Errand(Kind.BUFFER_POST, null);
		}
		_phase = Phase.WORK;
		_nextAction = now + Rnd.get(20000, 120000);
		if (Rnd.get(100) < 55)
		{
			sitDown();
		}
	}
	
	/**
	 * Between buffs: sits down to get its MP back, stands up again, sometimes talks.
	 */
	private void dutyStep(long now)
	{
		if (_serving)
		{
			_nextAction = now + 5000;
			return;
		}
		
		if (isSitting())
		{
			if (Rnd.get(100) < 45)
			{
				standUp();
			}
		}
		else if (Rnd.get(100) < 65)
		{
			sitDown();
		}
		_nextAction = now + Rnd.get(40000, 300000);
	}
	
	/**
	 * A fake player asks this buffer for buffs: it buffs the ones asking one after another.
	 * @param requester the one asking, held until it is buffed
	 * @param now the current time
	 */
	void requestBuffs(FakePlayerTownVisitor requester, long now)
	{
		_requests.addLast(requester);
		if (!_serving)
		{
			serveNext(now);
		}
	}
	
	private void serveNext(long now)
	{
		FakePlayerTownVisitor requester = _requests.pollFirst();
		while ((requester != null) && (requester.gone || !requester._held))
		{
			requester = _requests.pollFirst();
		}
		
		if (requester == null)
		{
			_serving = false;
			if (!_held || (circle == null))
			{
				_nextAction = now + Rnd.get(5000, 40000); // It sits down again in a while.
			}
			return;
		}
		
		_serving = true;
		final FakePlayerTownVisitor asking = requester;
		
		// Out of MP, away from keyboard...
		if (Rnd.get(100) < 7)
		{
			_manager.schedule(reactionMs + Rnd.get(500, 3000), () ->
			{
				say(FakePlayerTownChat.bufferDecline(style));
				_manager.schedule(asking.reactionMs + Rnd.get(800, 2500), () ->
				{
					asking.say(FakePlayerTownChat.ok(asking.style));
					asking.afterBuffs(System.currentTimeMillis(), false);
				});
				_manager.schedule(1000, () -> serveNext(System.currentTimeMillis()));
			});
			return;
		}
		
		final boolean stand = isSitting();
		if (stand)
		{
			standUp();
		}
		
		final long react = reactionMs + Rnd.get(0, 2500) + (stand ? 1500 : 0);
		if (Rnd.get(100) < 40)
		{
			_manager.schedule(Math.max(600, react - 800), () -> say(FakePlayerTownChat.bufferAck(style)));
		}
		
		_manager.schedule(react, () ->
		{
			if (gone || asking.gone)
			{
				serveNext(System.currentTimeMillis());
				return;
			}
			
			face(asking.npc);
			final List<Skill> buffs = bufferLine.pickBuffs(asking.mage, level);
			final Npc target = asking.npc;
			final double factor = bufferLine.songsOrDances() ? 0.85 + (Rnd.nextDouble() * 0.15) : 0.32 + (Rnd.nextDouble() * 0.22);
			_manager.castBuffs(npc, target, buffs, factor, () ->
			{
				final long done = System.currentTimeMillis();
				asking.afterBuffs(done, true);
				if (Rnd.get(100) < 30)
				{
					_manager.schedule(Rnd.get(1500, 4500), () -> say(FakePlayerTownChat.bufferDone(style)));
				}
				_manager.schedule(Rnd.get(800, 2500), () -> serveNext(System.currentTimeMillis()));
			});
		});
	}
	
	/**
	 * The buffer is done with it.
	 * @param now the current time
	 * @param buffed {@code true} if it got its buffs
	 */
	private void afterBuffs(long now, boolean buffed)
	{
		if (gone)
		{
			return;
		}
		
		if (buffed && (Rnd.get(100) < 75))
		{
			_manager.schedule(reactionMs / 2, () ->
			{
				say(FakePlayerTownChat.thanks(style));
				if (Rnd.nextDouble() < (gestures * 0.4))
				{
					social(SOCIAL_BOW);
				}
			});
		}
		
		clearTarget();
		_errand = null;
		_phase = Phase.START;
		release(now, Rnd.get(1500, 5000));
	}
	
	// Asking for adena.
	
	/**
	 * Next to the one it walked up to: asks for a little adena. Another fake player gives some, says no or ignores it; a player is asked again once and then left alone.
	 */
	private void beg(long now)
	{
		_phase = Phase.WORK;
		final WorldObject target = _other;
		if ((target == null) || (FakePlayerTown.distance2D(npc, target) > 300))
		{
			final FakePlayerTownVisitor other = _manager.getVisitor(target);
			if ((other != null) && other._held && (other.circle == null))
			{
				other.release(now, Rnd.get(500, 3000));
			}
			finishErrand(now);
			return;
		}
		
		face(target);
		say(FakePlayerTownChat.begAsk(speaker(), town.shortName));
		final Errand errand = _errand;
		final FakePlayerTownVisitor giver = _manager.getVisitor(target);
		if (giver != null)
		{
			giver.face(npc);
			final long answer = giver.reactionMs + Rnd.get(1500, 5000);
			final long after = answer + reactionMs + Rnd.get(500, 2500);
			final int roll = Rnd.get(100);
			if (roll < 40)
			{
				_manager.schedule(answer, () -> giver.say(FakePlayerTownChat.begGive(giver.style)));
				_manager.schedule(after, () ->
				{
					say(FakePlayerTownChat.begThanks(style));
					if (Rnd.nextDouble() < (0.2 + gestures))
					{
						social(Rnd.nextBoolean() ? SOCIAL_BOW : SOCIAL_VICTORY);
					}
				});
			}
			else if (roll < 82)
			{
				_manager.schedule(answer, () ->
				{
					giver.say(FakePlayerTownChat.begRefuse(giver.style));
					if (Rnd.nextDouble() < (giver.gestures * 0.5))
					{
						giver.social(SOCIAL_NO);
					}
				});
				if (Rnd.get(100) < 55)
				{
					_manager.schedule(after, () -> say(FakePlayerTownChat.begGiveUp(style)));
				}
			}
			else
			{
				// Ignored.
				if (Rnd.get(100) < 50)
				{
					_manager.schedule(answer + 4000, () -> say(FakePlayerTownChat.begAgain(speaker(), town.shortName)));
				}
				_manager.schedule(after + 8000, () -> say(FakePlayerTownChat.begGiveUp(style)));
			}
			
			final long done = after + 9000;
			_manager.schedule(done, () -> giver.release(System.currentTimeMillis(), Rnd.get(1000, 4000)));
			_nextAction = now + done + Rnd.get(500, 2500);
			return;
		}
		
		// A player: it waits for an answer, asks once more, then gives up.
		final long wait = Rnd.get(8000, 16000);
		if (Rnd.get(100) < 50)
		{
			_manager.schedule(wait, () ->
			{
				if (_errand == errand)
				{
					say(FakePlayerTownChat.begAgain(speaker(), town.shortName));
				}
			});
		}
		_manager.schedule(wait * 2, () ->
		{
			if ((_errand == errand) && (Rnd.get(100) < 60))
			{
				say(FakePlayerTownChat.begGiveUp(style));
			}
		});
		_nextAction = now + (wait * 2) + Rnd.get(1000, 3000);
	}
	
	/**
	 * @return how others see it, for the placeholders of what it says
	 */
	FakePlayerTownChat.Speaker speaker()
	{
		final FakePlayerHolder holder = npc.getTemplate().getFakePlayerInfo();
		return new FakePlayerTownChat.Speaker(npc.getName(), level, FakePlayerTownManager.className(playerClass), holder.getEquipRHand(), holder.getWeaponEnchantLevel(), style);
	}
	
	/**
	 * A player walks by: it turns to look, and may say hello or wave (once per player).
	 * @param player the player
	 */
	void notice(Player player)
	{
		if (gone || _held || (circle != null) || !_greeted.add(player.getObjectId()))
		{
			return;
		}
		
		face(player);
		if (isChatEnabled() && (Rnd.nextDouble() < (chatty * 0.45 * chatRate())))
		{
			_manager.schedule(reactionMs / 2, () -> say(FakePlayerTownChat.greetPlayer(style, player.getName())));
		}
		else if (Rnd.nextDouble() < (gestures * 0.7))
		{
			_manager.schedule(Rnd.get(300, 1500), () -> social(SOCIAL_GREETING));
		}
	}
	
	// Private store.
	
	/**
	 * Starts sitting in its store (server start: it was already selling).
	 */
	void startInStore()
	{
		_plan.removeIf(errand -> (errand.kind == Kind.STORE) || (errand.kind == Kind.NPC));
		_errand = new Errand(Kind.STORE, null);
		openStore(System.currentTimeMillis());
	}
	
	/**
	 * At its spot: sits down and opens its private store.
	 */
	private void openStore(long now)
	{
		if ((_errand == null) || (_errand.kind != Kind.STORE))
		{
			_errand = new Errand(Kind.STORE, null);
		}
		_phase = Phase.WORK;
		if ((store == null) || store.isEmpty())
		{
			_nextAction = now + 1000;
			return;
		}
		
		sitDown();
		npc.getTemplate().getFakePlayerInfo().setPrivateStore(1, store.getMessage());
		_storeOpen = true;
		npc.broadcastInfo();
		_nextAction = now + Rnd.get(30000, 120000);
	}
	
	/**
	 * Closes its private store (still sitting).
	 */
	void closeStore()
	{
		if (!_storeOpen)
		{
			return;
		}
		
		_storeOpen = false;
		npc.getTemplate().getFakePlayerInfo().setPrivateStore(0, "");
		if (npc.isSpawned())
		{
			npc.broadcastInfo();
		}
	}
	
	/**
	 * Sitting in its store: now and then it advertises it in general chat.
	 */
	private void storeStep(long now)
	{
		_nextAction = now + Rnd.get(40000, 160000);
		if (isChatEnabled() && FakePlayerTown.isWatched(npc) && (Rnd.nextDouble() < (0.15 * chatRate())))
		{
			say(FakePlayerTownChat.storeShout(style, store.getHeadline(), town.shortName));
		}
	}
	
	/**
	 * Another fake player bought something in its store.
	 * @param buyer the buyer
	 */
	private void soldToFake(FakePlayerTownVisitor buyer)
	{
		if (!isStoreOpen() || buyer.gone || !store.sellToFake())
		{
			return;
		}
		
		if (Rnd.get(100) < 30)
		{
			_manager.schedule(reactionMs, () -> say(FakePlayerTownChat.storeThanks(style)));
		}
		if (store.isEmpty())
		{
			soldOut();
		}
	}
	
	/**
	 * A player bought something in its store (on the manager's thread).
	 */
	void boughtByPlayer()
	{
		if (gone)
		{
			return;
		}
		
		if (Rnd.get(100) < 45)
		{
			_manager.schedule(reactionMs, () -> say(FakePlayerTownChat.storeThanks(style)));
		}
		if (store.isEmpty())
		{
			soldOut();
		}
	}
	
	private void soldOut()
	{
		if (!isStoreOpen())
		{
			return;
		}
		
		closeStore();
		if (Rnd.get(100) < 50)
		{
			_manager.schedule(reactionMs + Rnd.get(1000, 4000), () -> say(FakePlayerTownChat.storeSoldOut(style)));
		}
		if ((_errand != null) && (_errand.kind == Kind.STORE))
		{
			_nextAction = System.currentTimeMillis() + Rnd.get(4000, 15000);
		}
	}
	
	// Being driven by others.
	
	/**
	 * Stops whatever it does and waits (someone comes to talk, a conversation, being buffed).
	 * @param now the current time
	 * @param max how long it waits at most
	 */
	void hold(long now, long max)
	{
		if (!_held && (_phase == Phase.TRAVEL))
		{
			// It was walking somewhere: it goes there afterwards.
			npc.stopMove(null);
			npc.getAI().setIntention(Intention.IDLE);
			if ((_errand != null) && (_errand.kind != Kind.SOCIALIZE))
			{
				final Errand again = new Errand(_errand.kind, _errand.role);
				again.presetNpc = _errand.presetNpc;
				again.extraDelay = _errand.extraDelay;
				again.longVersion = _errand.longVersion;
				_plan.addFirst(again);
			}
			clearTarget();
			_errand = null;
			_phase = Phase.START;
		}
		_held = true;
		_heldUntil = now + max;
	}
	
	/**
	 * Goes on with its own business.
	 * @param now the current time
	 * @param pause how long until it does something
	 */
	void release(long now, long pause)
	{
		_held = false;
		_nextAction = Math.max(_nextAction, now + pause);
	}
	
	boolean isHeld()
	{
		return _held;
	}
	
	/**
	 * @return {@code true} if someone may walk up to it for a talk
	 */
	boolean isAvailableForChat(long now)
	{
		if (gone || _held || (circle != null) || (now > (leaveBy - 60000)) || (_phase == Phase.TRAVEL) || _serving)
		{
			return false;
		}
		if (_errand == null)
		{
			return true;
		}
		return (_errand.kind == Kind.IDLE) || (_errand.kind == Kind.SIT) || (_errand.kind == Kind.BUFFER_POST);
	}
	
	/**
	 * After teaming up in a conversation: leaves through a gatekeeper with the others.
	 * @param gatekeeper the gatekeeper
	 * @param delay how long after the first one it goes
	 */
	void leaveWith(TownNpc gatekeeper, long delay)
	{
		final Errand leave = new Errand(Kind.LEAVE_GATEKEEPER, Role.GATEKEEPER);
		leave.presetNpc = gatekeeper;
		leave.extraDelay = delay;
		_plan.clear();
		_plan.add(leave);
		
		// Whatever it was doing, it goes now.
		clearTarget();
		_errand = null;
		_phase = Phase.START;
		_nextAction = System.currentTimeMillis() + Math.min(delay, 2500);
	}
	
	// What players see.
	
	/**
	 * Turns to an npc and talks to it, like a player clicking it: the npc may play an animation.
	 * @param townNpc the npc
	 * @return the npc, {@code null} if it is gone
	 */
	private Npc talkTo(TownNpc townNpc)
	{
		final Npc target = townNpc != null ? townNpc.getNpc() : null;
		if (target == null)
		{
			return null;
		}
		
		face(target);
		if (target.hasRandomAnimation())
		{
			target.onRandomAnimation(Rnd.get(8));
		}
		return target;
	}
	
	void face(WorldObject target)
	{
		// A sitting player doesn't turn around.
		if (isSitting())
		{
			return;
		}
		
		final int heading = LocationUtil.calculateHeadingFrom(npc, target);
		npc.setHeading(heading);
		npc.broadcastPacket(new StopRotation(npc.getObjectId(), heading, 0));
	}
	
	/**
	 * Says something in general chat: the players around see it like the chat of a player.
	 * @param text the text
	 */
	void say(String text)
	{
		if (gone || !isChatEnabled() || (text == null) || text.isEmpty())
		{
			return;
		}
		
		final CreatureSay packet = new CreatureSay(npc, ChatType.GENERAL, npc.getName(), text);
		World.getInstance().forEachVisibleObjectInRange(npc, Player.class, 1250, player -> player.sendPacket(packet));
		town.remember(text);
	}
	
	void social(int actionId)
	{
		if (!gone && !npc.isMoving() && !isSitting())
		{
			npc.broadcastPacket(new SocialAction(npc.getObjectId(), actionId));
		}
	}
	
	boolean isSitting()
	{
		final FakePlayerHolder holder = npc.getTemplate().getFakePlayerInfo();
		return (holder != null) && holder.isSitting();
	}
	
	void sitDown()
	{
		final FakePlayerHolder holder = npc.getTemplate().getFakePlayerInfo();
		if ((holder != null) && !holder.isSitting() && !npc.isMoving())
		{
			holder.setSitting(true);
			npc.broadcastPacket(new ChangeWaitType(npc, ChangeWaitType.WT_SITTING));
		}
	}
	
	void standUp()
	{
		final FakePlayerHolder holder = npc.getTemplate().getFakePlayerInfo();
		if ((holder != null) && holder.isSitting())
		{
			holder.setSitting(false);
			npc.broadcastPacket(new ChangeWaitType(npc, ChangeWaitType.WT_STANDING));
		}
	}
	
	/**
	 * Casts a buff animation, from the caster on the target (a party song or dance shows on the caster).
	 */
	static void showCast(Npc caster, Npc target, Skill skill, int hitTime)
	{
		final boolean party = skill.getTargetType() != null && skill.getTargetType().name().startsWith("PARTY");
		caster.broadcastPacket(new MagicSkillUse(caster, party ? caster : target, skill.getId(), skill.getLevel(), hitTime, 0));
	}
	
	static void showLaunch(Npc caster, Npc target, Skill skill)
	{
		final boolean party = skill.getTargetType() != null && skill.getTargetType().name().startsWith("PARTY");
		caster.broadcastPacket(new MagicSkillLaunched(caster, skill.getId(), skill.getLevel(), party ? caster : target));
	}
	
	/**
	 * Called once it is out of the world: frees what it held.
	 */
	void onGone()
	{
		gone = true;
		
		closeStore();
		if (store != null)
		{
			store.release();
		}
		
		// Someone was waiting for it to come over for a talk (or to be asked for adena).
		if ((_errand != null) && ((_errand.kind == Kind.SOCIALIZE) || (_errand.kind == Kind.BEG)) && (_other != null))
		{
			final FakePlayerTownVisitor partner = _manager.getVisitor(_other);
			if ((partner != null) && partner._held && (partner.circle == null))
			{
				partner.release(System.currentTimeMillis(), Rnd.get(500, 3000));
			}
		}
		clearTarget();
		for (FakePlayerTownVisitor requester : _requests)
		{
			if (!requester.gone)
			{
				requester.afterBuffs(System.currentTimeMillis(), false);
			}
		}
		_requests.clear();
	}
	
	/**
	 * @return what it does and plans, for //faketown
	 */
	String describe()
	{
		final long now = System.currentTimeMillis();
		final StringBuilder sb = new StringBuilder();
		sb.append(npc.getName()).append(" lv ").append(level).append(' ').append(playerClass.name().toLowerCase());
		sb.append(isBuffer() ? " (buffer)" : " (" + (_visit != null ? _visit.name().toLowerCase() : "?") + ")");
		sb.append(": ").append(_held ? (circle != null ? "talking" : "waiting") : _errand != null ? _errand + " " + _phase.name().toLowerCase() : "pause");
		if (_npcTarget != null)
		{
			sb.append(" at ").append(_npcTarget.name);
		}
		if (store != null)
		{
			sb.append(isStoreOpen() ? ", store open: " : ", store closed: ").append(store.describe());
		}
		sb.append(", then ").append(_plan);
		if (_aborts > 0)
		{
			sb.append(", gave up ").append(_aborts).append(" walks");
		}
		sb.append(", in town ").append((now - arrivedAt) / 60000).append(" min, leaves in ").append(Math.max(0, (leaveBy - now) / 60000)).append(" min at most");
		return sb.toString();
	}
}
