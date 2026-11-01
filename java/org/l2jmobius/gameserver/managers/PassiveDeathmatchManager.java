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
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.TeamVsTeamConfig;
import org.l2jmobius.gameserver.data.custom.PassiveTreeData;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpBuild.Role;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpPassives;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;
import org.l2jmobius.gameserver.model.events.Containers;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerPressTutorialMark;
import org.l2jmobius.gameserver.model.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.model.item.Weapon;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.item.type.WeaponType;
import org.l2jmobius.gameserver.model.itemcontainer.Inventory;
import org.l2jmobius.gameserver.model.passivetree.PassiveMechanics;
import org.l2jmobius.gameserver.model.passivetree.PassiveNode;
import org.l2jmobius.gameserver.model.passivetree.PassiveStatBonusCache;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.stats.Stat;
import org.l2jmobius.gameserver.model.stats.functions.FuncAdd;
import org.l2jmobius.gameserver.model.stats.functions.FuncMul;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;
import org.l2jmobius.gameserver.network.serverpackets.TutorialShowQuestionMark;

/**
 * The passives of Deathmatch: Mayhem ({@link TeamVsTeamManager.Mode#PASSIVE_DEATHMATCH}, the "Passive Deathmatch" in the code): everyone fights with a blank passive tree, and each kill offers the killer {@link TeamVsTeamConfig#PDM_CHOICES} strong passives (by default the notables and keystones of the
 * tree, {@link TeamVsTeamConfig#PDM_NODE_TYPES}) picked by chance, to take 1 of. The more one kills, the stronger one gets.
 * <ul>
 * <li>A player's event tree stands in for its own one through {@link PassiveTreeManager#beginEventTree}: in memory only, never saved, and gone the moment the player is off the event (the match ends, it leaves, logs off, or the server restarts). Its own tree is never
 * touched and can't be changed meanwhile.</li>
 * <li>Each kill rolls an offer that waits in the player's queue until it picks: closing the window, dying, or killing again before choosing loses nothing. No window opens in the middle of a fight: a kill blinks the tutorial question mark
 * ({@link #QUESTION_MARK_ID}), which opens it when clicked. The window opens by itself when the player dies with picks waiting, and again after each pick while one is left. The question mark comes back on respawn, and {@code .dmpick} opens the window
 * any time.</li>
 * <li>A fake player takes one of its offer's passives at once, chosen by the weights its class grows its own tree with ({@link FakePlayerPvpPassiveTree#weigh}), each fake player with a style and jitter of its own, and a little more jitter on each pick; by
 * chance if none of the offer is of use to it.</li>
 * </ul>
 */
public class PassiveDeathmatchManager
{
	/** The voiced command that shows the waiting pick (.dmpick) and takes one (.dmpick &lt;offer&gt; &lt;node&gt;, the window's buttons). */
	public static final String COMMAND = "dmpick";

	private static final Logger LOGGER = Logger.getLogger(PassiveDeathmatchManager.class.getName());

	private static final String TAG = "Mayhem";

	/** The tutorial question mark a kill blinks (the other systems use 9300 to 94xx, see HiddenQuestManager, CraftingGoalManager, DynamicQuestManager, AdventurersGuideManager). */
	public static final int QUESTION_MARK_ID = 9330;

	/** Owner of the stat Funcs of Mayhem's balance (M.Atk, P.Atk, MP cost, max HP, see applyModifiers). */
	private static final Object MATK_OWNER = new Object();
	/** Order of that Func: after every other one (the passive tree's highest is 0x52), so it scales the whole M.Atk. */
	private static final int MATK_ORDER = 0x60;

	/** How far a fake player's want of each offered passive strays at random on each pick. */
	private static final double FAKE_PICK_JITTER = 0.15;
	/** How often a fake player takes a passive by weighted chance instead of the one worth most to it. */
	private static final double FAKE_PICK_EXPLORE = 0.15;

	/** A pick waiting for its player: its number, and the nodes it offers. */
	private static class Offer
	{
		private final int _id;
		private final List<Integer> _options;

		Offer(int id, List<Integer> options)
		{
			_id = id;
			_options = new ArrayList<>(options);
		}
	}

	/**
	 * Who a fighter is, for its offers: its role (the fake player roles: FIGHTER, TANK, DAGGER, ARCHER, MAGE), race and class, which event-only nodes it may get, and what of the tree is of use to it.
	 */
	private static class Profile
	{
		private final String _name;
		private final Role _role;
		private final String _race;
		private final String _className;
		/** What of the tree is of use to it ({@code null} if it couldn't be worked out: everything is). */
		private FakePlayerPvpPassiveTree.NodeTaste _taste;
		/** Its picks are logged (a simulated match). */
		private final boolean _log;
		/** A hybrid class (MayhemHybridClasses): {@link #_taste} is a mage's, this a fighter's, {@link #_mainShare} of its useful passives are a mage's. {@code null} for the others. */
		private FakePlayerPvpPassiveTree.NodeTaste _altTaste;
		private double _mainShare = 1;
		/** How much its notables count. */
		private double _notableScale = 1;
		/** One of MayhemHybridClasses. */
		private boolean _hybrid;

		Profile(String name, Role role, String race, String className, FakePlayerPvpPassiveTree.NodeTaste taste, boolean log)
		{
			_name = name;
			_role = role;
			_race = race;
			_className = className;
			_taste = taste;
			_log = log;
		}

		/**
		 * @param node a node
		 * @return {@code true} if it may be offered to this fighter: any tree node, an event-only node only to the roles, races and classes it is for
		 */
		boolean mayTake(PassiveNode node)
		{
			if (!node.isEventOnly())
			{
				return true;
			}
			return (node.getEventRoles().isEmpty() || ((_role != null) && node.getEventRoles().contains(_role.name()))) && (node.getEventRaces().isEmpty() || node.getEventRaces().contains(_race)) && (node.getEventClasses().isEmpty() || node.getEventClasses().contains(_className));
		}

		/**
		 * @param node a node it may take
		 * @return how much it wants it, 0 for of no use: an event-only node by its fakeWeight, a tree node by the weights its role grows its tree with
		 */
		double weigh(PassiveNode node)
		{
			if (_altTaste == null)
			{
				return weighWith(node, false);
			}
			return (_mainShare * weighWith(node, false)) + ((1 - _mainShare) * weighWith(node, true));
		}

		/**
		 * @param node a node it may take
		 * @param alt {@code true} for a hybrid's fighter side, {@code false} for its main one
		 * @return how much that side wants it
		 */
		double weighWith(PassiveNode node, boolean alt)
		{
			return weighWith(node, alt, null, 1);
		}

		/**
		 * @param node a node it may take
		 * @param alt {@code true} for a hybrid's fighter side
		 * @param have the summed effects of what it has, {@code null} for nothing
		 * @param survival how much more HP and defence count
		 * @return how much it wants it, given what it has
		 */
		double weighWith(PassiveNode node, boolean alt, PassiveStatBonusCache have, double survival)
		{
			if (node.isEventOnly())
			{
				return mayTake(node) ? node.getEventWeight() : 0;
			}
			final FakePlayerPvpPassiveTree.NodeTaste taste = alt && (_altTaste != null) ? _altTaste : _taste;
			return taste == null ? 1 : FakePlayerPvpPassiveTree.getInstance().weigh(taste, node, have, survival);
		}

		/**
		 * @return how much it wants a node, given what it has
		 */
		double weigh(PassiveNode node, PassiveStatBonusCache have, double survival)
		{
			if (_altTaste == null)
			{
				return weighWith(node, false, have, survival);
			}
			return (_mainShare * weighWith(node, false, have, survival)) + ((1 - _mainShare) * weighWith(node, true, have, survival));
		}

		/**
		 * Makes it a hybrid if its class is one of MayhemHybridClasses: offered a mage's and a fighter's passives, its notables worth more.
		 * @param armorMask the item mask of its body armour
		 * @param wornMask the item mask of everything it wears and holds
		 * @param random {@code true} for a fake player's rolled tastes
		 */
		void makeHybrid(int armorMask, int wornMask, boolean random)
		{
			if (!TeamVsTeamConfig.PDM_HYBRID_CLASSES.contains(_className))
			{
				return;
			}
			_hybrid = true;
			try
			{
				final FakePlayerPvpPassiveTree tree = FakePlayerPvpPassiveTree.getInstance();
				final FakePlayerPvpPassiveTree.NodeTaste magic = tree.rollTaste(Role.MAGE, false, null, null, armorMask, wornMask, random);
				final FakePlayerPvpPassiveTree.NodeTaste fighter = tree.rollTaste(Role.FIGHTER, false, null, null, armorMask, wornMask, random);
				_taste = magic;
				_altTaste = fighter;
				_mainShare = TeamVsTeamConfig.PDM_HYBRID_MAGIC / 100.0;
			}
			catch (Exception e)
			{
				// Not a hybrid then: its own taste.
			}
			_notableScale = 1 + (TeamVsTeamConfig.PDM_HYBRID_NOTABLE_BONUS / 100.0);
		}

		/**
		 * How much its notables count: a hybrid's more (see {@link #makeHybrid}), a mage's {@link TeamVsTeamConfig#PDM_MAGE_NOTABLES} %, a tank's {@link TeamVsTeamConfig#PDM_TANK_NOTABLES} %. Call after {@link #makeHybrid}, before its tree is applied.
		 */
		void initNotableScale()
		{
			if (_hybrid)
			{
				return;
			}
			if (_role == Role.MAGE)
			{
				_notableScale = TeamVsTeamConfig.PDM_MAGE_NOTABLES / 100.0;
			}
			else if (_role == Role.TANK)
			{
				_notableScale = TeamVsTeamConfig.PDM_TANK_NOTABLES / 100.0;
			}
		}
	}

	/** A hit a fighter took: who dealt it, how much, when (assists, see {@link #onFighterDeath}). */
	private static class Hit
	{
		private final int _attackerId;
		private final double _damage;
		private final long _time;

		Hit(int attackerId, double damage, long time)
		{
			_attackerId = attackerId;
			_damage = damage;
			_time = time;
		}
	}

	/** A player's picks: the ones waiting, first first. */
	private static class Picks
	{
		private final Profile _profile;
		private final Deque<Offer> _pending = new ArrayDeque<>();
		private int _nextOfferId = 1;
		/** {@code true} while its question mark blinks (sent and not clicked yet): one is enough for any number of picks. */
		private boolean _markShown;

		Picks(Profile profile)
		{
			_profile = profile;
		}
	}

	/** A fake player's passives: what it took, and the share of its max HP that max HP % bonuses grow (see {@link FakePlayerPvpPassives#getHpShare}). */
	private static class FakePicks
	{
		private final List<Integer> _nodes = new ArrayList<>();
		private final double _hpShare;
		private final Profile _profile;
		/** Its kills and deaths in the match, for what it picks (see {@link #pickForFake}). */
		private int _kills;
		private int _deaths;

		FakePicks(double hpShare, Profile profile)
		{
			_hpShare = hpShare;
			_profile = profile;
		}
	}

	/** The players in a Mayhem match, by object id. */
	private final Map<Integer, Picks> _players = new ConcurrentHashMap<>();
	/** The fake players in a Mayhem match, by object id. */
	private final Map<Integer, FakePicks> _fakes = new ConcurrentHashMap<>();
	/** The hits each fighter took lately, by its object id (see {@link #onFighterDeath}). */
	private final Map<Integer, Deque<Hit>> _hits = new ConcurrentHashMap<>();

	protected PassiveDeathmatchManager()
	{
		Containers.Players().addListener(new ConsumerEventListener(Containers.Players(), EventType.ON_PLAYER_PRESS_TUTORIAL_MARK, (OnPlayerPressTutorialMark event) -> onQuestionMark(event.getPlayer(), event.getMarkId()), this));
	}

	private void onQuestionMark(Player player, int markId)
	{
		if (markId != QUESTION_MARK_ID)
		{
			return;
		}
		final Picks picks = _players.get(player.getObjectId());
		if (picks != null)
		{
			synchronized (picks)
			{
				picks._markShown = false;
			}
		}
		showWindow(player);
	}

	/**
	 * Blinks the question mark of a player's waiting picks, unless it already does.
	 * @param player the player
	 */
	private void showQuestionMark(Player player)
	{
		final Picks picks = _players.get(player.getObjectId());
		if (picks == null)
		{
			return;
		}
		synchronized (picks)
		{
			if (picks._markShown || picks._pending.isEmpty())
			{
				return;
			}
			picks._markShown = true;
		}
		player.sendPacket(new TutorialShowQuestionMark(QUESTION_MARK_ID));
	}

	// ---------------------------------------------------------------------------------------------
	// Players
	// ---------------------------------------------------------------------------------------------

	/**
	 * A player enters a Mayhem match: its tree is blank until it leaves. Call once it is on the event ({@link Player#isOnEvent()}).
	 * @param player the player
	 */
	public void join(Player player, boolean simulated)
	{
		final Role role = TeamVsTeamConfig.PDM_TANK_CLASSES.contains(player.getPlayerClass().name()) ? Role.TANK : roleOf(player);
		FakePlayerPvpPassiveTree.NodeTaste taste = null;
		try
		{
			final Item chest = player.getInventory().getPaperdollItem(Inventory.PAPERDOLL_CHEST);
			taste = FakePlayerPvpPassiveTree.getInstance().rollTaste(role, false, null, null, chest != null ? chest.getTemplate().getItemMask() : 0, player.getInventory().getWearedMask(), false);
		}
		catch (Exception e)
		{
			// No taste: every passive counts as of use.
		}
		final Profile profile = new Profile(player.getName(), role, player.getRace().name(), player.getPlayerClass().name(), taste, simulated);
		final Item worn = player.getInventory().getPaperdollItem(Inventory.PAPERDOLL_CHEST);
		profile.makeHybrid(worn != null ? worn.getTemplate().getItemMask() : 0, player.getInventory().getWearedMask(), false);
		profile.initNotableScale();
		_players.put(player.getObjectId(), new Picks(profile));
		PassiveTreeManager.getInstance().beginEventTree(player, profile._notableScale);
		applyModifiers(player, profile);
	}

	/**
	 * Mayhem's balance, for the match (it goes with {@link #leave}, and with a fake player's body): every fighter has {@link TeamVsTeamConfig#PDM_MATK} % of its M.Atk and pays {@link TeamVsTeamConfig#PDM_MP_COST} % of its skills' MP; fighters, tanks and archers
	 * have {@link TeamVsTeamConfig#PDM_PATK} % of their P.Atk, daggers {@link TeamVsTeamConfig#PDM_DAGGER_PATK} %; tanks and dwarves {@link TeamVsTeamConfig#PDM_TANK_HP} % of their max HP.
	 * @param creature a player or fake player entering a Mayhem match
	 * @param profile who it is
	 */
	private static void applyModifiers(Creature creature, Profile profile)
	{
		creature.removeStatsOwner(MATK_OWNER);
		mul(creature, Stat.MAGIC_ATTACK, TeamVsTeamConfig.PDM_MATK);
		mul(creature, Stat.MP_CONSUME, TeamVsTeamConfig.PDM_MP_COST);
		mul(creature, Stat.BOW_MP_CONSUME_RATE, TeamVsTeamConfig.PDM_MP_COST);
		if (((profile._role == Role.FIGHTER) || (profile._role == Role.TANK) || (profile._role == Role.ARCHER)) && !TeamVsTeamConfig.PDM_PATK_EXCLUDED.contains(profile._className))
		{
			mul(creature, Stat.POWER_ATTACK, TeamVsTeamConfig.PDM_PATK);
		}
		if ((profile._role == Role.ARCHER) && (TeamVsTeamConfig.PDM_ARCHER_SPEED != 0))
		{
			creature.addStatFunc(new FuncAdd(Stat.MOVE_SPEED, MATK_ORDER, MATK_OWNER, TeamVsTeamConfig.PDM_ARCHER_SPEED, null));
		}
		else if (profile._role == Role.DAGGER)
		{
			mul(creature, Stat.POWER_ATTACK, TeamVsTeamConfig.PDM_DAGGER_PATK);
		}
		if ((profile._role == Role.TANK) || "DWARF".equals(profile._race))
		{
			mul(creature, Stat.MAX_HP, TeamVsTeamConfig.PDM_TANK_HP);
		}
	}

	private static void mul(Creature creature, Stat stat, int percent)
	{
		if (percent != 100)
		{
			creature.addStatFunc(new FuncMul(stat, MATK_ORDER, MATK_OWNER, percent / 100.0, null));
		}
	}

	/**
	 * @param player a player
	 * @return its role among the fake player roles, for its offers: an archer with a bow, a mage for a mystic (healers too), a tank for a knight, a dagger with a dagger, a fighter otherwise
	 */
	private static Role roleOf(Player player)
	{
		switch (TeamVsTeamManager.getRole(player))
		{
			case ARCHER:
			{
				return Role.ARCHER;
			}
			case NUKER:
			case HEALER:
			{
				return Role.MAGE;
			}
			case TANK:
			{
				return Role.TANK;
			}
			default:
			{
				final Weapon weapon = player.getActiveWeaponItem();
				return (weapon != null) && ((weapon.getItemType() == WeaponType.DAGGER) || (weapon.getItemType() == WeaponType.DUALDAGGER)) ? Role.DAGGER : Role.FIGHTER;
			}
		}
	}

	/**
	 * A player leaves a match (any match, any way): its event passives and waiting picks are gone, its own tree applies again. Harmless for a player that wasn't in a Mayhem match.
	 * @param player the player
	 */
	public void leave(Player player)
	{
		final boolean had = _players.remove(player.getObjectId()) != null;
		player.removeStatsOwner(MATK_OWNER);
		_hits.remove(player.getObjectId());
		final boolean onEventTree = PassiveTreeManager.getInstance().isOnEventTree(player);
		PassiveTreeManager.getInstance().endEventTree(player);
		if ((had || onEventTree) && player.isOnline())
		{
			player.sendMessage(TAG + ": Your own passive tree is back.");
		}
	}

	/**
	 * @param player a player
	 * @return {@code true} if it is in a Mayhem match
	 */
	public boolean isIn(Player player)
	{
		return _players.containsKey(player.getObjectId());
	}

	/**
	 * @param player a player
	 * @return how many picks wait for it
	 */
	public int getPendingCount(Player player)
	{
		final Picks picks = _players.get(player.getObjectId());
		if (picks == null)
		{
			return 0;
		}
		synchronized (picks)
		{
			return picks._pending.size();
		}
	}

	/**
	 * Someone of a Mayhem match killed an enemy: a player gets a pick waiting (and the window), a fake player takes a passive at once.
	 * @param killer the killer
	 */
	public void onKill(Creature killer)
	{
		grantPick(killer, false);
	}

	/**
	 * A pick for a fighter: a player gets one waiting, a fake player takes a passive at once.
	 * @param fighter the fighter
	 * @param assist {@code true} for an assist, {@code false} for a kill
	 */
	private void grantPick(Creature fighter, boolean assist)
	{
		if (fighter == null)
		{
			return;
		}
		if (fighter.isPlayer())
		{
			offer(fighter.asPlayer(), assist);
		}
		else if (fighter.isNpc())
		{
			onFakeKill(fighter.asNpc(), assist);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Damage: the area damage cut and the assists
	// ---------------------------------------------------------------------------------------------

	/**
	 * @param creature a creature
	 * @return {@code true} if it fights in a Mayhem match
	 */
	private boolean isFighter(Creature creature)
	{
		return (creature != null) && (_players.containsKey(creature.getObjectId()) || _fakes.containsKey(creature.getObjectId()));
	}

	/**
	 * Damage a fighter of a Mayhem match is about to take from another one (called by Player and Attackable while a match goes on): area skills deal {@link TeamVsTeamConfig#PDM_AREA_DAMAGE} % of their damage, and the hit is remembered for the assists.
	 * @param target the creature about to take damage
	 * @param attacker the attacker (a servitor counts as its owner)
	 * @param skill the skill that deals it, {@code null} for a normal attack
	 * @param damage the damage
	 * @return the damage to take
	 */
	public double onIncomingDamage(Creature target, Creature attacker, Skill skill, double damage)
	{
		final Creature source = (attacker != null) && attacker.isSummon() ? attacker.asSummon().getOwner() : attacker;
		if ((source == null) || (source == target) || (damage <= 0) || !isFighter(target) || !isFighter(source))
		{
			return damage;
		}
		final double taken = (skill != null) && skill.isAOE() ? (damage * TeamVsTeamConfig.PDM_AREA_DAMAGE) / 100 : damage;
		final long now = System.currentTimeMillis();
		final Deque<Hit> hits = _hits.computeIfAbsent(target.getObjectId(), k -> new ArrayDeque<>());
		synchronized (hits)
		{
			hits.addLast(new Hit(source.getObjectId(), taken, now));
			while (!hits.isEmpty() && ((now - hits.peekFirst()._time) > (TeamVsTeamConfig.PDM_ASSIST_WINDOW * 1000L)))
			{
				hits.removeFirst();
			}
		}
		return taken;
	}

	/**
	 * A fighter of a Mayhem match died: everyone but the killer who dealt it at least {@link TeamVsTeamConfig#PDM_ASSIST_SHARE} % of the damage it took in the last {@link TeamVsTeamConfig#PDM_ASSIST_WINDOW} seconds gets a pick for the assist.
	 * @param victim who died
	 * @param killer who killed it (a servitor counts as its owner), may be {@code null}
	 */
	public void onFighterDeath(Creature victim, Creature killer)
	{
		final FakePicks dead = _fakes.get(victim.getObjectId());
		if (dead != null)
		{
			synchronized (dead)
			{
				dead._deaths++;
			}
		}
		final Deque<Hit> hits = _hits.remove(victim.getObjectId());
		if ((hits == null) || (TeamVsTeamConfig.PDM_ASSIST_SHARE <= 0))
		{
			return;
		}
		final Creature source = (killer != null) && killer.isSummon() ? killer.asSummon().getOwner() : killer;
		final long now = System.currentTimeMillis();
		final Map<Integer, Double> dealt = new HashMap<>();
		double total = 0;
		synchronized (hits)
		{
			for (Hit hit : hits)
			{
				if ((now - hit._time) <= (TeamVsTeamConfig.PDM_ASSIST_WINDOW * 1000L))
				{
					dealt.merge(hit._attackerId, hit._damage, Double::sum);
					total += hit._damage;
				}
			}
		}
		if (total <= 0)
		{
			return;
		}
		for (Map.Entry<Integer, Double> entry : dealt.entrySet())
		{
			if (((source != null) && (entry.getKey() == source.getObjectId())) || (((entry.getValue() * 100) / total) < TeamVsTeamConfig.PDM_ASSIST_SHARE))
			{
				continue;
			}
			final WorldObject assister = World.getInstance().findObject(entry.getKey());
			if ((assister != null) && assister.isCreature() && isFighter(assister.asCreature()))
			{
				grantPick(assister.asCreature(), true);
			}
		}
	}

	/**
	 * Rolls an offer for a player and puts it at the end of its waiting picks. No window in the middle of the fight: the question mark blinks (the window opens by itself if the killer is dead).
	 * @param player the player
	 */
	private void offer(Player player, boolean assist)
	{
		final Picks picks = _players.get(player.getObjectId());
		if (picks == null)
		{
			return;
		}

		synchronized (picks)
		{
			// The waiting offers' passives too: each offer has its own, so one pick never empties another.
			final Set<Integer> taken = new HashSet<>(getEventNodes(player));
			for (Offer waiting : picks._pending)
			{
				taken.addAll(waiting._options);
			}
			final List<Integer> options = roll(getEventNodes(player), taken, TeamVsTeamConfig.PDM_CHOICES, picks._profile, isKeystoneTurn(getEventNodes(player).size() + picks._pending.size()));
			if (options.isEmpty())
			{
				player.sendMessage(TAG + ": You have every passive this event offers.");
				return;
			}
			picks._pending.addLast(new Offer(picks._nextOfferId++, options));
		}
		if (player.isDead())
		{
			showWindow(player);
			return;
		}
		showQuestionMark(player);
		final int pending = getPendingCount(player);
		player.sendPacket(new ExShowScreenMessage((assist ? "Assist! " : "") + (pending > 1 ? pending + " passives to pick: click the ?" : "A passive to pick: click the ?"), ExShowScreenMessage.TOP_CENTER, 3000, 0, true, false));
	}

	/**
	 * A player of a Mayhem match died: with picks waiting, their window opens by itself (it has the time now).
	 * @param player the player
	 */
	public void onDeath(Player player)
	{
		if (getPendingCount(player) > 0)
		{
			showWindow(player);
		}
	}

	/**
	 * A player came back to the fight with picks still waiting: the question mark blinks again (no window in the fight).
	 * @param player the player
	 */
	public void remindPending(Player player)
	{
		showQuestionMark(player);
	}

	/**
	 * Shows a player the window of its first waiting pick (.dmpick).
	 * @param player the player
	 */
	public void showWindow(Player player)
	{
		final Picks picks = _players.get(player.getObjectId());
		if (picks == null)
		{
			player.sendMessage(TAG + ": You are not in a Deathmatch: Mayhem.");
			return;
		}

		final Offer offer;
		final int pending;
		synchronized (picks)
		{
			offer = getValidFirst(player, picks);
			pending = picks._pending.size();
		}
		if (offer == null)
		{
			player.sendMessage(TAG + ": No passive is waiting for you. Kill to earn one!");
			return;
		}

		final StringBuilder sb = new StringBuilder(2048);
		sb.append("<html><title>Deathmatch: Mayhem</title><body><center>");
		sb.append("<font color=\"LEVEL\">Pick a passive</font>");
		if (pending > 1)
		{
			sb.append("<br1><font color=\"B09878\">").append(pending).append(" picks waiting: this is the first.</font>");
		}
		sb.append("<br>");
		for (int nodeId : offer._options)
		{
			final PassiveNode node = PassiveTreeData.getInstance().getNode(nodeId);
			if (node == null)
			{
				continue;
			}
			final boolean keystone = node.getType() == PassiveNode.NodeType.KEYSTONE;
			sb.append("<table width=280 border=0 bgcolor=\"000000\"><tr><td align=center>");
			sb.append("<font color=\"").append(keystone ? "FF9900" : "CDB67F").append("\">").append(escape(node.getName())).append("</font>");
			sb.append(" <font color=\"808080\">").append(keystone ? "Keystone" : capitalize(node.getType().name())).append(" - ").append(escape(node.getSector())).append("</font>");
			sb.append("</td></tr><tr><td><font color=\"B09878\">").append(escape(describe(node))).append("</font></td></tr></table>");
			sb.append("<button value=\"Take ").append(escape(shorten(node.getName(), 22))).append("\" action=\"bypass -h voiced_").append(COMMAND).append(' ').append(offer._id).append(' ').append(nodeId).append("\" width=200 height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"><br>");
		}

		final Set<Integer> owned = getEventNodes(player);
		if (!owned.isEmpty())
		{
			sb.append("<font color=\"808080\">Your event passives (").append(owned.size()).append("): ");
			boolean first = true;
			for (int id : owned)
			{
				final PassiveNode node = PassiveTreeData.getInstance().getNode(id);
				if (node != null)
				{
					sb.append(first ? "" : ", ").append(escape(node.getName()));
					first = false;
				}
			}
			sb.append("</font><br>");
		}
		sb.append("<font color=\"808080\">Closing this window keeps your pick: it opens by itself when you die, the ? comes back when you respawn, and .").append(COMMAND).append(" opens it any time. Event passives last for this match only.</font>");
		sb.append("</center></body></html>");
		player.sendPacket(new NpcHtmlMessage(sb.toString()));
	}

	/**
	 * A player takes a passive of its first waiting pick (the window's buttons).
	 * @param player the player
	 * @param offerId the offer the button is of
	 * @param nodeId the passive it takes
	 * @return {@code true} if it took it
	 */
	public boolean choose(Player player, int offerId, int nodeId)
	{
		final Picks picks = _players.get(player.getObjectId());
		if (picks == null)
		{
			player.sendMessage(TAG + ": You are not in a Deathmatch: Mayhem.");
			return false;
		}

		final PassiveNode node;
		final int left;
		synchronized (picks)
		{
			final Offer offer = getValidFirst(player, picks);
			// Only the first waiting pick can be taken, with one of its passives: an old window's buttons (or a made up bypass) do nothing but show the right one.
			if ((offer == null) || (offer._id != offerId) || !offer._options.contains(nodeId))
			{
				node = null;
				left = -1;
			}
			else
			{
				node = PassiveTreeData.getInstance().getNode(nodeId);
				if ((node == null) || !PassiveTreeManager.getInstance().addEventNode(player, nodeId))
				{
					return false;
				}
				picks._pending.removeFirst();
				left = picks._pending.size();
			}
		}

		if (node == null)
		{
			showWindow(player);
			return false;
		}

		player.sendMessage(TAG + ": You took " + node.getName() + " (" + describe(node) + ")." + (left > 0 ? " " + left + " more to pick." : ""));
		if (picks._profile._log)
		{
			LOGGER.info(getClass().getSimpleName() + ": Mayhem pick: " + player.getName() + " (" + player.getPlayerClass().name() + ") took " + node.getName() + (node.isEventOnly() ? " [event]" : "") + " (" + node.getType() + "), " + getEventNodes(player).size() + " passives.");
		}
		if (left > 0)
		{
			showWindow(player);
		}
		return true;
	}

	/**
	 * Drops from a player's first waiting pick the passives it can no longer take (it took the same one, or one that excludes it, since), puts new ones in their place, and drops the pick if none is left.
	 * @param player the player
	 * @param picks its picks, locked by the caller
	 * @return its first waiting pick, {@code null} if none is left
	 */
	private Offer getValidFirst(Player player, Picks picks)
	{
		while (!picks._pending.isEmpty())
		{
			final Offer offer = picks._pending.peekFirst();
			final Set<Integer> owned = getEventNodes(player);
			final List<PassiveNode> ownedNodes = toNodes(owned);
			final Set<String> ownedNames = names(ownedNodes);
			final List<Integer> valid = new ArrayList<>();
			for (int id : offer._options)
			{
				final PassiveNode node = PassiveTreeData.getInstance().getNode(id);
				if ((node != null) && isAllowed(node, owned, ownedNames, ownedNodes))
				{
					valid.add(id);
				}
			}
			if (valid.size() < offer._options.size())
			{
				final Set<Integer> taken = new HashSet<>(owned);
				for (Offer other : picks._pending)
				{
					taken.addAll(other._options);
				}
				final Set<String> offerNames = names(toNodes(valid));
				for (int id : roll(owned, taken, TeamVsTeamConfig.PDM_CHOICES - valid.size(), picks._profile, false))
				{
					final PassiveNode node = PassiveTreeData.getInstance().getNode(id);
					if ((node != null) && offerNames.add(node.getName()))
					{
						valid.add(id);
					}
				}
				offer._options.clear();
				offer._options.addAll(valid);
			}
			if (!offer._options.isEmpty())
			{
				return offer;
			}
			picks._pending.removeFirst();
		}
		return null;
	}

	/**
	 * @param player a player
	 * @return the passives of its event tree, empty if it has none
	 */
	private static Set<Integer> getEventNodes(Player player)
	{
		final PassiveTreeManager manager = PassiveTreeManager.getInstance();
		return manager.isOnEventTree(player) ? manager.getAllocatedNodes(player) : Collections.emptySet();
	}

	// ---------------------------------------------------------------------------------------------
	// Fake players
	// ---------------------------------------------------------------------------------------------

	/**
	 * A fake player enters a Mayhem match: its passive tree is blank, at full HP.
	 * @param fake the fake player, spawned
	 */
	public void prepareFake(Npc fake, boolean simulated)
	{
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		if (profile == null)
		{
			return;
		}
		final FakePlayerPvpPassives own = profile.getPassives();
		final String className = profile.getPlayerClass() != null ? profile.getPlayerClass().name() : "";
		final String race = profile.getPlayerClass() != null ? profile.getPlayerClass().getRace().name() : "";
		final String buildName = profile.getBuild() != null ? profile.getBuild().getName() : className;
		// The classes that play as tanks in this mode (MayhemTankClasses) want what a tank wants.
		final Role role = TeamVsTeamConfig.PDM_TANK_CLASSES.contains(className) ? Role.TANK : profile.getRole();
		FakePlayerPvpPassiveTree.NodeTaste taste = null;
		try
		{
			final FakePlayerPvpBuild build = profile.getBuild();
			taste = FakePlayerPvpPassiveTree.getInstance().rollTaste(role, build.isHybrid(), role == build.getRole() ? build.getKeystones() : null, build.getWeights(), profile.getArmorMask(), profile.getWornMask(), true);
		}
		catch (Exception e)
		{
			// No taste: it picks by chance.
		}
		final Profile who = new Profile(fake.getName() + " (" + buildName + ")", role, race, className, taste, simulated);
		who.makeHybrid(profile.getArmorMask(), profile.getWornMask(), true);
		who.initNotableScale();
		final FakePicks picks = new FakePicks(own != null ? own.getHpShare() : 1, who);
		_fakes.put(fake.getObjectId(), picks);
		applyFake(fake, profile, picks);
		applyModifiers(fake, who);
		fake.setCurrentHpMp(fake.getMaxHp(), fake.getMaxMp());
	}

	/**
	 * A fake player of a Mayhem match killed someone: it takes one of an offer's passives at once.
	 * @param fake the fake player
	 */
	private void onFakeKill(Npc fake, boolean assist)
	{
		final FakePicks picks = _fakes.get(fake.getObjectId());
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		if ((picks == null) || (profile == null))
		{
			return;
		}
		synchronized (picks)
		{
			if (!assist)
			{
				picks._kills++;
			}
			final Set<Integer> owned = new HashSet<>(picks._nodes);
			final List<Integer> options = roll(owned, owned, TeamVsTeamConfig.PDM_CHOICES, picks._profile, isKeystoneTurn(picks._nodes.size()));
			if (options.isEmpty())
			{
				return;
			}
			final int picked = pickForFake(picks, fake.getTemplate().getFakePlayerPvpProfile().getPassives(), options);
			picks._nodes.add(picked);
			applyFake(fake, profile, picks);
			if (picks._profile._log)
			{
				final PassiveNode node = PassiveTreeData.getInstance().getNode(picked);
				LOGGER.info(getClass().getSimpleName() + ": Mayhem pick: " + picks._profile._name + " took " + node.getName() + (node.isEventOnly() ? " [event]" : "") + " (" + node.getType() + (assist ? ", assist" : ", kill") + "), " + picks._nodes.size() + " passives.");
			}
		}
		fake.broadcastStatusUpdate();
	}

	/**
	 * What a fake player takes of an offer:
	 * <ul>
	 * <li>one of its own event keystones, while it has none;</li>
	 * <li>else the one worth most to it ({@link Profile#weigh(PassiveNode, PassiveStatBonusCache, double)}: its class's weights, what is past a cap worth nothing, a stat it has a lot of worth less, HP and defence worth more while it dies more than it kills), with a
	 * little jitter, and now and then ({@link #FAKE_PICK_EXPLORE}) one by chance weighted by worth, so fake players of one class don't all end up alike;</li>
	 * <li>by plain chance if none is worth anything to it.</li>
	 * </ul>
	 * @param picks the fake player's picks
	 * @param current its passives now, {@code null} for none
	 * @param options the passives offered, at least one
	 * @return the one it takes
	 */
	private static int pickForFake(FakePicks picks, FakePlayerPvpPassives current, List<Integer> options)
	{
		final Profile profile = picks._profile;
		final boolean hasEventKeystone = picks._nodes.stream().map(PassiveTreeData.getInstance()::getNode).anyMatch((n) -> (n != null) && n.isEventOnly());
		if (!hasEventKeystone)
		{
			for (int id : options)
			{
				final PassiveNode node = PassiveTreeData.getInstance().getNode(id);
				if ((node != null) && node.isEventOnly() && profile.mayTake(node))
				{
					return id;
				}
			}
		}

		final PassiveStatBonusCache have = current != null ? current.getBonus() : null;
		final double survival = picks._deaths > picks._kills ? 1 + Math.min(1, (picks._deaths - picks._kills) / 5.0) : 1;
		final double[] weights = new double[options.size()];
		double total = 0;
		int best = -1;
		for (int i = 0; i < weights.length; i++)
		{
			final PassiveNode node = PassiveTreeData.getInstance().getNode(options.get(i));
			weights[i] = node == null ? 0 : profile.weigh(node, have, survival) * (1 + (((Rnd.nextDouble() * 2) - 1) * FAKE_PICK_JITTER));
			total += weights[i];
			if ((weights[i] > 0) && ((best < 0) || (weights[i] > weights[best])))
			{
				best = i;
			}
		}
		if (total <= 0)
		{
			return options.get(Rnd.get(options.size()));
		}
		if (Rnd.nextDouble() >= FAKE_PICK_EXPLORE)
		{
			return options.get(best);
		}
		double roll = Rnd.nextDouble() * total;
		for (int i = 0; i < weights.length; i++)
		{
			roll -= weights[i];
			if ((roll < 0) && (weights[i] > 0))
			{
				return options.get(i);
			}
		}
		return options.get(best);
	}

	/**
	 * @param picksSoFar how many passives the fighter has (and has waiting)
	 * @return {@code true} if its next offer holds one of its own event keystones: its first, then every {@link TeamVsTeamConfig#PDM_KEYSTONE_EVERY}
	 */
	private static boolean isKeystoneTurn(int picksSoFar)
	{
		final int every = TeamVsTeamConfig.PDM_KEYSTONE_EVERY;
		return (every > 0) && ((picksSoFar == 0) || (((picksSoFar + 1) % every) == 0));
	}

	/**
	 * Gives a fake player the passives it took (none at first), the way a roaming fake player gets its tree.
	 */
	private static void applyFake(Npc fake, FakePlayerPvpProfile profile, FakePicks picks)
	{
		final PassiveStatBonusCache bonus = new PassiveStatBonusCache();
		bonus.recompute(picks._nodes, picks._profile._notableScale);
		final FakePlayerPvpPassives passives = new FakePlayerPvpPassives(0, picks._nodes.size(), picks._nodes.size(), "Deathmatch: Mayhem", "event", picks._nodes, bonus);
		passives.setHpShare(picks._hpShare);
		profile.setPassives(passives);
		PassiveTreeManager.getInstance().applyToFakePlayer(fake, passives);
	}

	/**
	 * @param creature a player or fake player, may be {@code null}
	 * @return the names of the passives it took in its Mayhem match, in the order it took them for a fake player, empty for none
	 */
	public List<String> getPassiveNames(Creature creature)
	{
		if (creature == null)
		{
			return List.of();
		}
		final Collection<Integer> ids;
		if (creature.isPlayer())
		{
			ids = getEventNodes(creature.asPlayer());
		}
		else
		{
			final FakePicks picks = _fakes.get(creature.getObjectId());
			if (picks == null)
			{
				return List.of();
			}
			synchronized (picks)
			{
				ids = new ArrayList<>(picks._nodes);
			}
		}
		final List<String> names = new ArrayList<>();
		for (PassiveNode node : toNodes(ids))
		{
			names.add(node.getName() + (node.getType() == PassiveNode.NodeType.KEYSTONE ? " (K)" : ""));
		}
		return names;
	}
	
	/**
	 * A fake player leaves its match (removed from the world).
	 * @param fake the fake player
	 */
	public void forgetFake(Npc fake)
	{
		_fakes.remove(fake.getObjectId());
		_hits.remove(fake.getObjectId());
	}

	// ---------------------------------------------------------------------------------------------
	// The passives offered
	// ---------------------------------------------------------------------------------------------

	/**
	 * Picks passives to offer by chance, each of a different name, among the ones the owner may take.
	 * @param owned the passives the owner has
	 * @param exclude passives not to offer (the owned ones, the ones other waiting offers hold)
	 * @param count how many
	 * @return the passives, fewer if the pool runs out
	 */
	private static List<Integer> roll(Collection<Integer> owned, Collection<Integer> exclude, int count, Profile profile, boolean keystoneTurn)
	{
		final List<PassiveNode> ownedNodes = toNodes(owned);
		final Set<String> ownedNames = names(ownedNodes);
		final List<PassiveNode> pool = new ArrayList<>();
		final List<PassiveNode> candidates = new ArrayList<>(PassiveTreeData.getInstance().getAllNodes().values());
		candidates.addAll(PassiveTreeData.getInstance().getEventNodes().values());
		for (PassiveNode node : candidates)
		{
			if (isOffered(node) && profile.mayTake(node) && !exclude.contains(node.getId()) && isAllowed(node, owned, ownedNames, ownedNodes))
			{
				pool.add(node);
			}
		}

		Collections.shuffle(pool);
		final List<Integer> result = new ArrayList<>(count);
		final Set<String> names = new HashSet<>();
		// A keystone turn: one of the fighter's own event keystones, the ones made for its class or race first.
		if (keystoneTurn && (count > 0))
		{
			PassiveNode own = null;
			for (PassiveNode node : pool)
			{
				if (node.isEventOnly() && ((own == null) || (isClassKeystone(node) && !isClassKeystone(own))))
				{
					own = node;
				}
			}
			if (own != null)
			{
				names.add(own.getName());
				result.add(own.getId());
			}
		}
		// Class-aware: the first ones of use to the fighter's role (up to PDM_USEFUL_CHOICES), then any. A hybrid's useful ones are a mage's or, now and then, a fighter's.
		final int useful = Math.min(count, TeamVsTeamConfig.PDM_USEFUL_CHOICES + result.size());
		for (int slot = result.size(); slot < useful; slot++)
		{
			final boolean alt = (profile._altTaste != null) && (Rnd.nextDouble() >= profile._mainShare);
			for (PassiveNode node : pool)
			{
				if (!names.contains(node.getName()) && (profile.weighWith(node, alt) > 0))
				{
					names.add(node.getName());
					result.add(node.getId());
					break;
				}
			}
		}
		for (PassiveNode node : pool)
		{
			if (result.size() >= count)
			{
				break;
			}
			// The tree repeats its notables in several places: one copy in an offer.
			if (names.add(node.getName()))
			{
				result.add(node.getId());
			}
		}
		Collections.shuffle(result);
		return result;
	}

	/**
	 * @param node an event-only node
	 * @return {@code true} if it is made for some classes or races, not a whole role
	 */
	private static boolean isClassKeystone(PassiveNode node)
	{
		return !node.getEventClasses().isEmpty() || !node.getEventRaces().isEmpty();
	}

	/**
	 * @param node a node of the tree
	 * @return {@code true} if a Mayhem match offers it at all: one of {@link TeamVsTeamConfig#PDM_NODE_TYPES}, with effects, none of them one of {@link TeamVsTeamConfig#PDM_EXCLUDED_EFFECTS}, granting no skill
	 */
	private static boolean isOffered(PassiveNode node)
	{
		if (!TeamVsTeamConfig.PDM_NODE_TYPES.contains(node.getType().name()) || node.grantsSkill() || node.getEffects().isEmpty())
		{
			return false;
		}
		for (String key : node.getEffects().keySet())
		{
			final int at = key.indexOf('@');
			if (TeamVsTeamConfig.PDM_EXCLUDED_EFFECTS.contains(at > 0 ? key.substring(0, at) : key))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * @return {@code true} if the owner of these passives may take this one: not the same one or one of the same name, and no keystone it can't be combined with
	 */
	private static boolean isAllowed(PassiveNode node, Collection<Integer> owned, Set<String> ownedNames, List<PassiveNode> ownedNodes)
	{
		if (owned.contains(node.getId()) || ownedNames.contains(node.getName()))
		{
			return false;
		}
		for (PassiveNode other : ownedNodes)
		{
			if (PassiveMechanics.conflicts(node, other))
			{
				return false;
			}
		}
		return true;
	}

	private static List<PassiveNode> toNodes(Collection<Integer> ids)
	{
		final List<PassiveNode> nodes = new ArrayList<>(ids.size());
		for (int id : ids)
		{
			final PassiveNode node = PassiveTreeData.getInstance().getNode(id);
			if (node != null)
			{
				nodes.add(node);
			}
		}
		return nodes;
	}

	private static Set<String> names(List<PassiveNode> nodes)
	{
		final Set<String> names = new HashSet<>();
		for (PassiveNode node : nodes)
		{
			names.add(node.getName());
		}
		return names;
	}

	// ---------------------------------------------------------------------------------------------
	// Text
	// ---------------------------------------------------------------------------------------------

	/**
	 * @param node a node
	 * @return what it does: a keystone's description, the effects of another node ("P.Atk +6%, STR +1")
	 */
	public static String describe(PassiveNode node)
	{
		final String description = node.getDescription();
		if ((node.getType() == PassiveNode.NodeType.KEYSTONE) && (description != null) && (description.length() > 20))
		{
			return description.startsWith("KEYSTONE: ") ? description.substring(10) : description;
		}

		final StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Double> effect : node.getEffects().entrySet())
		{
			if (sb.length() > 0)
			{
				sb.append(", ");
			}
			sb.append(describeEffect(effect.getKey(), effect.getValue()));
		}
		return sb.toString();
	}

	private static String describeEffect(String key, double value)
	{
		final int at = key.indexOf('@');
		final String base = at > 0 ? key.substring(0, at) : key;
		final String condition = at > 0 ? " (" + key.substring(at + 1).toLowerCase().replace('_', ' ') + ")" : "";
		final boolean percent = base.endsWith("_PCT");
		final String stem = percent ? base.substring(0, base.length() - 4) : base.endsWith("_ADD") ? base.substring(0, base.length() - 4) : base;
		final String amount = (value >= 0 ? "+" : "") + (value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value)) + (percent ? "%" : "");
		return label(stem) + " " + amount + condition;
	}

	private static String label(String stem)
	{
		return switch (stem)
		{
			case "STR", "DEX", "CON", "INT", "WIT", "MEN" -> stem;
			case "PATK" -> "P.Atk";
			case "MATK" -> "M.Atk";
			case "PDEF" -> "P.Def";
			case "MDEF" -> "M.Def";
			case "MAXHP" -> "Max HP";
			case "MAXMP" -> "Max MP";
			case "MAXCP" -> "Max CP";
			case "ATK_SPD" -> "Atk. Spd.";
			case "CAST_SPD" -> "Casting Spd.";
			case "MOVE_SPEED" -> "Speed";
			case "CRIT_RATE" -> "Critical Rate";
			case "CRIT_DMG" -> "Critical Damage";
			case "MCRIT_RATE" -> "Magic Critical Rate";
			case "MCRIT_DMG" -> "Magic Critical Damage";
			case "HP_REGEN" -> "HP Regen";
			case "MP_REGEN" -> "MP Regen";
			case "ACCURACY" -> "Accuracy";
			case "EVASION" -> "Evasion";
			case "SKILL_DODGE" -> "Skill Dodge";
			case "LIFESTEAL" -> "Lifesteal";
			case "HEALING_RECEIVED" -> "Healing Received";
			case "DEBUFF_RES" -> "Debuff Resistance";
			case "SHIELD_RATE" -> "Shield Rate";
			case "SHIELD_DEF" -> "Shield Def";
			case "SKILL_CDR" -> "Skill Reuse";
			case "SPELL_CDR" -> "Spell Reuse";
			case "SPELL_MP_COST_RED" -> "Spell MP Cost Reduction";
			case "CRIT_DMG_TAKEN_RED" -> "Critical Damage Taken Reduction";
			case "BLOW_RATE" -> "Blow Rate";
			case "PHYS_SKILL_POWER" -> "Physical Skill Power";
			case "INTERRUPT_RES" -> "Interrupt Resistance";
			case "ALL_ELEM_RES" -> "All Attribute Resistance";
			case "SUMMON_PATK" -> "Servitor P.Atk";
			case "SUMMON_MATK" -> "Servitor M.Atk";
			case "SUMMON_HP" -> "Servitor HP";
			case "SUMMON_PDEF" -> "Servitor P.Def";
			case "SUMMON_MDEF" -> "Servitor M.Def";
			case "SUMMON_ATK_SPD" -> "Servitor Atk. Spd.";
			default -> capitalize(stem.replace('_', ' '));
		};
	}

	private static String capitalize(String text)
	{
		final String lower = text.toLowerCase();
		final StringBuilder sb = new StringBuilder(lower.length());
		boolean upper = true;
		for (char c : lower.toCharArray())
		{
			sb.append(upper ? Character.toUpperCase(c) : c);
			upper = c == ' ';
		}
		return sb.toString();
	}

	private static String shorten(String text, int length)
	{
		return text.length() <= length ? text : text.substring(0, length - 1) + ".";
	}

	private static String escape(String text)
	{
		return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	public static PassiveDeathmatchManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PassiveDeathmatchManager INSTANCE = new PassiveDeathmatchManager();
	}
}
