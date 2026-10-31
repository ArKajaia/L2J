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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.TeamVsTeamConfig;
import org.l2jmobius.gameserver.data.custom.PassiveTreeData;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpPassives;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;
import org.l2jmobius.gameserver.model.events.Containers;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerPressTutorialMark;
import org.l2jmobius.gameserver.model.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.model.passivetree.PassiveMechanics;
import org.l2jmobius.gameserver.model.passivetree.PassiveNode;
import org.l2jmobius.gameserver.model.passivetree.PassiveStatBonusCache;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;
import org.l2jmobius.gameserver.network.serverpackets.TutorialShowQuestionMark;

/**
 * The passives of a Passive Deathmatch ({@link TeamVsTeamManager.Mode#PASSIVE_DEATHMATCH}): everyone fights with a blank passive tree, and each kill offers the killer {@link TeamVsTeamConfig#PDM_CHOICES} strong passives (by default the notables and keystones of the
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

	private static final String TAG = "Passive DM";

	/** The tutorial question mark a kill blinks (the other systems use 9300 to 94xx, see HiddenQuestManager, CraftingGoalManager, DynamicQuestManager, AdventurersGuideManager). */
	public static final int QUESTION_MARK_ID = 9330;

	/** How far a fake player's want of each offered passive strays at random on each pick. */
	private static final double FAKE_PICK_JITTER = 0.25;

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

	/** A player's picks: the ones waiting, first first. */
	private static class Picks
	{
		private final Deque<Offer> _pending = new ArrayDeque<>();
		private int _nextOfferId = 1;
		/** {@code true} while its question mark blinks (sent and not clicked yet): one is enough for any number of picks. */
		private boolean _markShown;
	}

	/** A fake player's passives: what it took, and the share of its max HP that max HP % bonuses grow (see {@link FakePlayerPvpPassives#getHpShare}). */
	private static class FakePicks
	{
		private final List<Integer> _nodes = new ArrayList<>();
		private final double _hpShare;
		/** What it wants of the tree, by its class ({@code null} if it couldn't be worked out: it picks by chance). */
		private final FakePlayerPvpPassiveTree.NodeTaste _taste;

		FakePicks(double hpShare, FakePlayerPvpPassiveTree.NodeTaste taste)
		{
			_hpShare = hpShare;
			_taste = taste;
		}
	}

	/** The players in a Passive Deathmatch, by object id. */
	private final Map<Integer, Picks> _players = new ConcurrentHashMap<>();
	/** The fake players in a Passive Deathmatch, by object id. */
	private final Map<Integer, FakePicks> _fakes = new ConcurrentHashMap<>();

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
	 * A player enters a Passive Deathmatch: its tree is blank until it leaves. Call once it is on the event ({@link Player#isOnEvent()}).
	 * @param player the player
	 */
	public void join(Player player)
	{
		_players.put(player.getObjectId(), new Picks());
		PassiveTreeManager.getInstance().beginEventTree(player);
	}

	/**
	 * A player leaves a match (any match, any way): its event passives and waiting picks are gone, its own tree applies again. Harmless for a player that wasn't in a Passive Deathmatch.
	 * @param player the player
	 */
	public void leave(Player player)
	{
		final boolean had = _players.remove(player.getObjectId()) != null;
		final boolean onEventTree = PassiveTreeManager.getInstance().isOnEventTree(player);
		PassiveTreeManager.getInstance().endEventTree(player);
		if ((had || onEventTree) && player.isOnline())
		{
			player.sendMessage(TAG + ": Your own passive tree is back.");
		}
	}

	/**
	 * @param player a player
	 * @return {@code true} if it is in a Passive Deathmatch
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
	 * Someone of a Passive Deathmatch killed an enemy: a player gets a pick waiting (and the window), a fake player takes a passive at once.
	 * @param killer the killer
	 */
	public void onKill(Creature killer)
	{
		if (killer == null)
		{
			return;
		}
		if (killer.isPlayer())
		{
			offer(killer.asPlayer());
		}
		else if (killer.isNpc())
		{
			onFakeKill(killer.asNpc());
		}
	}

	/**
	 * Rolls an offer for a player and puts it at the end of its waiting picks. No window in the middle of the fight: the question mark blinks (the window opens by itself if the killer is dead).
	 * @param player the player
	 */
	private void offer(Player player)
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
			final List<Integer> options = roll(getEventNodes(player), taken, TeamVsTeamConfig.PDM_CHOICES);
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
		player.sendPacket(new ExShowScreenMessage(pending > 1 ? pending + " passives to pick: click the ?" : "A passive to pick: click the ?", ExShowScreenMessage.TOP_CENTER, 3000, 0, true, false));
	}

	/**
	 * A player of a Passive Deathmatch died: with picks waiting, their window opens by itself (it has the time now).
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
			player.sendMessage(TAG + ": You are not in a Passive Deathmatch.");
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
		sb.append("<html><title>Passive Deathmatch</title><body><center>");
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
			player.sendMessage(TAG + ": You are not in a Passive Deathmatch.");
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
				for (int id : roll(owned, taken, TeamVsTeamConfig.PDM_CHOICES - valid.size()))
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
	 * A fake player enters a Passive Deathmatch: its passive tree is blank, at full HP.
	 * @param fake the fake player, spawned
	 */
	public void prepareFake(Npc fake)
	{
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		if (profile == null)
		{
			return;
		}
		final FakePlayerPvpPassives own = profile.getPassives();
		FakePlayerPvpPassiveTree.NodeTaste taste = null;
		try
		{
			taste = FakePlayerPvpPassiveTree.getInstance().rollTaste(profile.getBuild(), profile.getArmorMask(), profile.getWornMask());
		}
		catch (Exception e)
		{
			// No taste: it picks by chance.
		}
		final FakePicks picks = new FakePicks(own != null ? own.getHpShare() : 1, taste);
		_fakes.put(fake.getObjectId(), picks);
		applyFake(fake, profile, picks);
		fake.setCurrentHpMp(fake.getMaxHp(), fake.getMaxMp());
	}

	/**
	 * A fake player of a Passive Deathmatch killed someone: it takes one of an offer's passives at once.
	 * @param fake the fake player
	 */
	private void onFakeKill(Npc fake)
	{
		final FakePicks picks = _fakes.get(fake.getObjectId());
		final FakePlayerPvpProfile profile = fake.getTemplate().getFakePlayerPvpProfile();
		if ((picks == null) || (profile == null))
		{
			return;
		}
		synchronized (picks)
		{
			final Set<Integer> owned = new HashSet<>(picks._nodes);
			final List<Integer> options = roll(owned, owned, TeamVsTeamConfig.PDM_CHOICES);
			if (options.isEmpty())
			{
				return;
			}
			picks._nodes.add(pickForFake(picks._taste, options));
			applyFake(fake, profile, picks);
		}
		fake.broadcastStatusUpdate();
	}

	/**
	 * @param taste what the fake player wants of the tree, {@code null} for nothing in particular
	 * @param options the passives offered, at least one
	 * @return the one it takes: by chance weighted by what each is worth to it (with some jitter), or plain chance if none is worth anything to it
	 */
	private static int pickForFake(FakePlayerPvpPassiveTree.NodeTaste taste, List<Integer> options)
	{
		if (taste != null)
		{
			final FakePlayerPvpPassiveTree tree = FakePlayerPvpPassiveTree.getInstance();
			final double[] weights = new double[options.size()];
			double total = 0;
			for (int i = 0; i < weights.length; i++)
			{
				final PassiveNode node = PassiveTreeData.getInstance().getNode(options.get(i));
				weights[i] = node == null ? 0 : tree.weigh(taste, node) * (1 + (((Rnd.nextDouble() * 2) - 1) * FAKE_PICK_JITTER));
				total += weights[i];
			}
			if (total > 0)
			{
				double roll = Rnd.nextDouble() * total;
				for (int i = 0; i < weights.length; i++)
				{
					roll -= weights[i];
					if ((roll < 0) && (weights[i] > 0))
					{
						return options.get(i);
					}
				}
				for (int i = weights.length - 1; i >= 0; i--)
				{
					if (weights[i] > 0)
					{
						return options.get(i);
					}
				}
			}
		}
		return options.get(Rnd.get(options.size()));
	}

	/**
	 * Gives a fake player the passives it took (none at first), the way a roaming fake player gets its tree.
	 */
	private static void applyFake(Npc fake, FakePlayerPvpProfile profile, FakePicks picks)
	{
		final PassiveStatBonusCache bonus = new PassiveStatBonusCache();
		bonus.recompute(picks._nodes);
		final FakePlayerPvpPassives passives = new FakePlayerPvpPassives(0, picks._nodes.size(), picks._nodes.size(), "Passive Deathmatch", "event", picks._nodes, bonus);
		passives.setHpShare(picks._hpShare);
		profile.setPassives(passives);
		PassiveTreeManager.getInstance().applyToFakePlayer(fake, passives);
	}

	/**
	 * A fake player leaves its match (removed from the world).
	 * @param fake the fake player
	 */
	public void forgetFake(Npc fake)
	{
		_fakes.remove(fake.getObjectId());
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
	private static List<Integer> roll(Collection<Integer> owned, Collection<Integer> exclude, int count)
	{
		final List<PassiveNode> ownedNodes = toNodes(owned);
		final Set<String> ownedNames = names(ownedNodes);
		final List<PassiveNode> pool = new ArrayList<>();
		for (PassiveNode node : PassiveTreeData.getInstance().getAllNodes().values())
		{
			if (isOffered(node) && !exclude.contains(node.getId()) && isAllowed(node, owned, ownedNames, ownedNodes))
			{
				pool.add(node);
			}
		}

		Collections.shuffle(pool);
		final List<Integer> result = new ArrayList<>(count);
		final Set<String> names = new HashSet<>();
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
		return result;
	}

	/**
	 * @param node a node of the tree
	 * @return {@code true} if a Passive Deathmatch offers it at all: one of {@link TeamVsTeamConfig#PDM_NODE_TYPES}, with effects, none of them one of {@link TeamVsTeamConfig#PDM_EXCLUDED_EFFECTS}, granting no skill
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
