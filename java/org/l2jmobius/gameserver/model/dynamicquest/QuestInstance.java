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
package org.l2jmobius.gameserver.model.dynamicquest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.custom.DynamicQuestConfig;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.managers.DynamicQuestManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.dynamicquest.objective.AbstractObjective;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.actor.creature.OnCreatureDeath;
import org.l2jmobius.gameserver.model.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.model.groups.Party;
import org.l2jmobius.gameserver.model.hiddenquest.task.AbstractHiddenTask;
import org.l2jmobius.gameserver.model.interfaces.ILocational;
import org.l2jmobius.gameserver.util.LocationUtil;
import org.l2jmobius.gameserver.util.QuestSpawnUtil;

/**
 * A dynamic quest rolled from a template: its seed, difficulty, owner and party, the stage it is at, its objectives and what it spawned.
 * <p>
 * Everything a quest picks with {@link #random(String)} comes from its seed, so a quest rebuilt after a restart picks the same monsters and names. Places found on the ground are kept in its context instead.
 * </p>
 * @author Mobius
 */
public class QuestInstance
{
	/** NPC variable holding the id of the quest that spawned an npc. */
	public static final String NPC_VAR = "DQ_INSTANCE";

	private final long _id;
	private final QuestTemplate _template;
	private final long _seed;
	private final DifficultyContext _difficulty;
	private final int _ownerId;
	private final String _ownerName;
	private final String _account;
	private final String _offerKey;
	private final long _created;
	private final Map<String, String> _context = new ConcurrentHashMap<>();
	private final Set<Integer> _participants = ConcurrentHashMap.newKeySet();
	private final Map<Integer, Integer> _presence = new ConcurrentHashMap<>();
	private final Map<Integer, Integer> _contribution = new ConcurrentHashMap<>();
	private final Map<Npc, AbstractObjective> _npcs = new ConcurrentHashMap<>();
	private final List<AbstractObjective> _objectives = new ArrayList<>();

	private volatile DynamicQuestState _state = DynamicQuestState.ACTIVE;
	private int _stageIndex;
	private long _stageStart;
	private long _stageDeadline;
	private int _escalations;
	private int _chain;
	private int _optionalDone;
	private int _activeTicks;
	private boolean _deathOccurred;
	private boolean _dirty;
	private boolean _stageEnding;
	private boolean _needsCheck;
	private int _second;
	private Location _marker;

	public QuestInstance(long id, QuestTemplate template, long seed, DifficultyContext difficulty, int ownerId, String ownerName, String account, String offerKey, long created)
	{
		_id = id;
		_template = template;
		_seed = seed;
		_difficulty = difficulty;
		_ownerId = ownerId;
		_ownerName = ownerName;
		_account = account;
		_offerKey = offerKey;
		_created = created;
		if (ownerId > 0)
		{
			_participants.add(ownerId);
		}
	}

	public long getId()
	{
		return _id;
	}

	public QuestTemplate getTemplate()
	{
		return _template;
	}

	public long getSeed()
	{
		return _seed;
	}

	public DifficultyContext getDifficulty()
	{
		return _difficulty;
	}

	public boolean isWorld()
	{
		return _template.getScope() == QuestScope.WORLD;
	}

	public int getOwnerId()
	{
		return _ownerId;
	}

	public String getOwnerName()
	{
		return _ownerName;
	}

	public String getAccount()
	{
		return _account;
	}

	/**
	 * @return the board offer it was taken from, {@code null} if none
	 */
	public String getOfferKey()
	{
		return _offerKey;
	}

	public long getCreated()
	{
		return _created;
	}

	/**
	 * @return the owner if online, {@code null} otherwise (always for world quests)
	 */
	public Player getOwner()
	{
		if (_ownerId <= 0)
		{
			return null;
		}
		final Player player = World.getInstance().getPlayer(_ownerId);
		return (player != null) && player.isOnline() ? player : null;
	}

	// ---------------------------------------------------------------------------------------------
	// Seeded choices and context
	// ---------------------------------------------------------------------------------------------

	/**
	 * @param key what is being picked
	 * @return a random source that always gives the same picks for this quest, stage and key
	 */
	public Random random(String key)
	{
		return new Random(_seed ^ (key.hashCode() * 0x9E3779B97F4A7C15L) ^ ((_stageIndex + 1) * 1000003L));
	}

	public String getContext(String key)
	{
		return _context.get(key);
	}

	public String getContext(String key, String defaultValue)
	{
		final String value = _context.get(key);
		return value != null ? value : defaultValue;
	}

	public int getContextInt(String key, int defaultValue)
	{
		try
		{
			final String value = _context.get(key);
			return value != null ? Integer.parseInt(value) : defaultValue;
		}
		catch (NumberFormatException e)
		{
			return defaultValue;
		}
	}

	public void putContext(String key, String value)
	{
		if (value == null)
		{
			_context.remove(key);
		}
		else
		{
			_context.put(key, value.replace(';', ',').replace('=', '-'));
		}
		_dirty = true;
	}

	public void putLocation(String key, ILocational location)
	{
		putContext(key, location.getX() + "," + location.getY() + "," + location.getZ());
	}

	/**
	 * @param key a context key
	 * @return the location stored under it, {@code null} if none
	 */
	public Location getLocation(String key)
	{
		final String value = _context.get(key);
		if (value == null)
		{
			return null;
		}
		final String[] split = value.split(",");
		if (split.length != 3)
		{
			return null;
		}
		try
		{
			return new Location(Integer.parseInt(split[0]), Integer.parseInt(split[1]), Integer.parseInt(split[2]));
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/**
	 * @return the centre of the quest's hunting ground
	 */
	public Location getArea()
	{
		return getLocation("area");
	}

	/**
	 * @return the monsters of the quest's hunting ground
	 */
	public List<Integer> getAreaNpcIds()
	{
		final List<Integer> npcIds = new ArrayList<>();
		final String value = _context.get("areaNpcs");
		if (value != null)
		{
			for (String id : value.split(","))
			{
				try
				{
					npcIds.add(Integer.parseInt(id.trim()));
				}
				catch (NumberFormatException e)
				{
					// Skipped.
				}
			}
		}
		return npcIds;
	}

	public void setArea(HuntingGround ground)
	{
		putLocation("area", ground.getCenter());
		final StringBuilder sb = new StringBuilder();
		for (int npcId : ground.getNpcIds())
		{
			sb.append(sb.length() > 0 ? "," : "").append(npcId);
		}
		putContext("areaNpcs", sb.toString());
		putContext("near", ground.getTown());
		if (!ground.getNpcIds().isEmpty())
		{
			final NpcTemplate template = NpcData.getInstance().getTemplate(ground.getNpcIds().get(0));
			if (template != null)
			{
				putContext("monster", template.getName());
			}
		}
	}

	public String serializeContext()
	{
		final StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, String> entry : _context.entrySet())
		{
			sb.append(entry.getKey()).append('=').append(entry.getValue()).append(';');
		}
		return sb.toString();
	}

	public void loadContext(String value)
	{
		if ((value == null) || value.isEmpty())
		{
			return;
		}
		for (String pair : value.split(";"))
		{
			final int index = pair.indexOf('=');
			if (index > 0)
			{
				_context.put(pair.substring(0, index), pair.substring(index + 1));
			}
		}
	}

	/**
	 * @param text a text with {placeholders}
	 * @return the text with the quest's context, level, tier and owner filled in
	 */
	public String format(String text)
	{
		if (text == null)
		{
			return "";
		}
		String result = text;
		if (result.indexOf('{') < 0)
		{
			return result;
		}
		for (Map.Entry<String, String> entry : _context.entrySet())
		{
			result = result.replace("{" + entry.getKey() + "}", entry.getValue());
		}
		result = result.replace("{level}", String.valueOf(_difficulty.getQuestLevel()));
		result = result.replace("{tier}", _difficulty.getTier().getDisplayName());
		result = result.replace("{owner}", _ownerName != null ? _ownerName : "adventurer");
		return result;
	}

	public String getName()
	{
		return format(_template.getName());
	}

	// ---------------------------------------------------------------------------------------------
	// Participants
	// ---------------------------------------------------------------------------------------------

	public Set<Integer> getParticipants()
	{
		return Collections.unmodifiableSet(_participants);
	}

	public boolean isParticipant(int objectId)
	{
		return _participants.contains(objectId);
	}

	public boolean addParticipant(int objectId)
	{
		return _participants.add(objectId);
	}

	/**
	 * @return the participants online
	 */
	public List<Player> getOnlineParticipants()
	{
		final List<Player> players = new ArrayList<>();
		for (int objectId : _participants)
		{
			final Player player = World.getInstance().getPlayer(objectId);
			if ((player != null) && player.isOnline())
			{
				players.add(player);
			}
		}
		return players;
	}

	/**
	 * Adds the owner's party members near it, counts who is present and returns the players the quest's messages go to.
	 * @return the players near the quest
	 */
	public List<Player> updatePresence()
	{
		_activeTicks++;
		final List<Player> near = new ArrayList<>();
		if (isWorld())
		{
			final Location area = getArea();
			if (area != null)
			{
				for (Player player : playersNear(area, DynamicQuestConfig.WORLD_EVENT_RANGE))
				{
					near.add(player);
					if (_participants.contains(player.getObjectId()))
					{
						_presence.merge(player.getObjectId(), 1, Integer::sum);
					}
				}
			}
			return near;
		}

		final Player owner = getOwner();
		if (owner == null)
		{
			return near;
		}
		final Party party = owner.getParty();
		if (party != null)
		{
			for (Player member : party.getMembers())
			{
				if ((member != owner) && !_participants.contains(member.getObjectId()) && (member.calculateDistance2D(owner) <= DynamicQuestConfig.PARTY_RANGE))
				{
					if (_participants.add(member.getObjectId()))
					{
						DynamicQuestManager.getInstance().onParticipantAdded(this, member);
					}
				}
			}
		}
		for (Player player : getOnlineParticipants())
		{
			if ((player == owner) || (player.calculateDistance2D(owner) <= DynamicQuestConfig.PARTY_RANGE))
			{
				near.add(player);
				_presence.merge(player.getObjectId(), 1, Integer::sum);
			}
		}
		return near;
	}

	/**
	 * @param objectId a participant
	 * @return the share of the quest's time it was present, 0-1
	 */
	public double getPresence(int objectId)
	{
		if (_activeTicks <= 0)
		{
			return objectId == _ownerId ? 1 : 0;
		}
		return Math.min(1, _presence.getOrDefault(objectId, 0) / (double) _activeTicks);
	}

	public void addContribution(int objectId, int amount)
	{
		_contribution.merge(objectId, amount, Integer::sum);
	}

	public int getContribution(int objectId)
	{
		return _contribution.getOrDefault(objectId, 0);
	}

	public Map<Integer, Integer> getContributions()
	{
		return Collections.unmodifiableMap(_contribution);
	}

	// ---------------------------------------------------------------------------------------------
	// Stage state
	// ---------------------------------------------------------------------------------------------

	public DynamicQuestState getState()
	{
		return _state;
	}

	public void setState(DynamicQuestState state)
	{
		_state = state;
	}

	public boolean isActive()
	{
		return _state == DynamicQuestState.ACTIVE;
	}

	public int getStageIndex()
	{
		return _stageIndex;
	}

	public void setStageIndex(int stageIndex)
	{
		_stageIndex = stageIndex;
	}

	public StageTemplate getStage()
	{
		return (_stageIndex >= 0) && (_stageIndex < _template.getStages().size()) ? _template.getStages().get(_stageIndex) : null;
	}

	public List<AbstractObjective> getObjectives()
	{
		return _objectives;
	}

	public long getStageStart()
	{
		return _stageStart;
	}

	public void setStageStart(long stageStart)
	{
		_stageStart = stageStart;
	}

	public long getStageDeadline()
	{
		return _stageDeadline;
	}

	public void setStageDeadline(long stageDeadline)
	{
		_stageDeadline = stageDeadline;
		_dirty = true;
	}

	/**
	 * @return the seconds left in the stage, -1 if it has no time limit
	 */
	public int getSecondsLeft()
	{
		return _stageDeadline <= 0 ? -1 : (int) Math.max(0, (_stageDeadline - System.currentTimeMillis()) / 1000);
	}

	public int getEscalations()
	{
		return _escalations;
	}

	public void setEscalations(int escalations)
	{
		_escalations = escalations;
	}

	public int getChain()
	{
		return _chain;
	}

	public void setChain(int chain)
	{
		_chain = chain;
	}

	public int getOptionalDone()
	{
		return _optionalDone;
	}

	public void setOptionalDone(int optionalDone)
	{
		_optionalDone = optionalDone;
	}

	public boolean isDeathOccurred()
	{
		return _deathOccurred;
	}

	public void setDeathOccurred()
	{
		_deathOccurred = true;
	}

	public boolean isDirty()
	{
		return _dirty;
	}

	public void setDirty(boolean dirty)
	{
		_dirty = dirty;
	}

	/**
	 * @return {@code true} while a transition is being applied (objective callbacks are ignored)
	 */
	public boolean isStageEnding()
	{
		return _stageEnding;
	}

	public void setStageEnding(boolean stageEnding)
	{
		_stageEnding = stageEnding;
	}

	/**
	 * @return {@code true} if an objective changed and the stage must be checked
	 */
	public boolean isNeedsCheck()
	{
		return _needsCheck;
	}

	public void setNeedsCheck(boolean needsCheck)
	{
		_needsCheck = needsCheck;
	}

	/**
	 * @return the next second of the quest's clock
	 */
	public int nextSecond()
	{
		return ++_second;
	}

	/**
	 * @return the objectives' states, for the database
	 */
	public String serializeObjectives()
	{
		final StringBuilder sb = new StringBuilder();
		for (AbstractObjective objective : _objectives)
		{
			if (sb.length() > 0)
			{
				sb.append('|');
			}
			sb.append(objective.isDone() ? "1" : "0").append(':').append(objective.saveState().replace('|', '/'));
		}
		return sb.toString();
	}

	// ---------------------------------------------------------------------------------------------
	// Spawns and messages
	// ---------------------------------------------------------------------------------------------

	/**
	 * Spawns an npc for an objective. It is tracked by the quest, its death goes to the objective and its kills count for no other system.
	 * @param objective the objective
	 * @param npcId the npc id
	 * @param location where
	 * @param heading the heading
	 * @return the npc, {@code null} if it could not be spawned
	 */
	public Npc spawn(AbstractObjective objective, int npcId, ILocational location, int heading)
	{
		final Npc npc = QuestSpawnUtil.spawnTaskNpc(npcId, location, heading, 0, AbstractHiddenTask.TASK_NPC_VAR);
		if (npc == null)
		{
			return null;
		}
		track(objective, npc);
		return npc;
	}

	/**
	 * Makes an npc spawned elsewhere (a fake player) one of an objective's: tracked, despawned with it, its death sent to it.
	 * @param objective the objective
	 * @param npc the npc
	 */
	public void track(AbstractObjective objective, Npc npc)
	{
		npc.getVariables().set(AbstractHiddenTask.TASK_NPC_VAR, true);
		npc.getVariables().set(NPC_VAR, String.valueOf(_id));
		_npcs.put(npc, objective);
		npc.addListener(new ConsumerEventListener(npc, EventType.ON_CREATURE_DEATH, (OnCreatureDeath event) -> ThreadPool.execute(() -> DynamicQuestManager.getInstance().onQuestNpcDeath(this, npc, event.getAttacker())), this));
	}

	/**
	 * Spawns a monster for an objective at the quest's champion tier.
	 * @param objective the objective
	 * @param npcId the monster
	 * @param location where
	 * @param tierBonus champion tiers added by the objective
	 * @param title the title, {@code null} for none
	 * @param target who it attacks at once, may be {@code null}
	 * @return the monster, {@code null} if it could not be spawned
	 */
	public Npc spawnMonster(AbstractObjective objective, int npcId, ILocational location, int tierBonus, String title, Creature target)
	{
		final Npc npc = spawn(objective, npcId, location, target != null ? LocationUtil.calculateHeadingFrom(location, target) : 0);
		if (npc == null)
		{
			return null;
		}
		QuestSpawnUtil.applyTier(npc, _difficulty.championTier(tierBonus), title);
		QuestSpawnUtil.attack(npc, target);
		return npc;
	}

	/**
	 * @param npc an npc
	 * @return the objective that spawned it, {@code null} if none of this quest's
	 */
	public AbstractObjective getSpawner(Npc npc)
	{
		return _npcs.get(npc);
	}

	public void despawn(Npc npc)
	{
		if (npc == null)
		{
			return;
		}
		_npcs.remove(npc);
		if (npc.isSpawned())
		{
			npc.deleteMe();
		}
	}

	/**
	 * Removes everything an objective spawned.
	 * @param objective the objective, {@code null} for all
	 */
	public void despawnAll(AbstractObjective objective)
	{
		for (Map.Entry<Npc, AbstractObjective> entry : new ArrayList<>(_npcs.entrySet()))
		{
			if ((objective == null) || (entry.getValue() == objective))
			{
				despawn(entry.getKey());
			}
		}
	}

	/**
	 * Points the participants' radars at a place.
	 * @param location the place, {@code null} to clear
	 */
	public void setMarker(Location location)
	{
		for (Player player : getOnlineParticipants())
		{
			QuestSpawnUtil.moveMarker(player, _marker, location);
		}
		_marker = location;
	}

	public Location getMarker()
	{
		return _marker;
	}

	/**
	 * Shows the marker again to one player (after a login or a track request).
	 * @param player the player
	 */
	public void showMarker(Player player)
	{
		if (_marker != null)
		{
			QuestSpawnUtil.moveMarker(player, _marker, _marker);
		}
	}

	/**
	 * @return the players the quest talks to: the participants online, or everyone near a world quest
	 */
	public List<Player> getAudience()
	{
		if (!isWorld())
		{
			return getOnlineParticipants();
		}
		final List<Player> players = new ArrayList<>();
		final Location area = getArea();
		if (area != null)
		{
			players.addAll(playersNear(area, DynamicQuestConfig.WORLD_EVENT_RANGE));
		}
		for (Player player : getOnlineParticipants())
		{
			if (!players.contains(player))
			{
				players.add(player);
			}
		}
		return players;
	}

	/**
	 * @param location a place
	 * @param range a range
	 * @return the players online within range of the place (in the main world)
	 */
	public static List<Player> playersNear(ILocational location, int range)
	{
		final List<Player> players = new ArrayList<>();
		final long rangeSq = (long) range * range;
		for (Player player : World.getInstance().getPlayers())
		{
			if (!player.isOnline() || (player.getInstanceId() != 0))
			{
				continue;
			}
			final long dx = player.getX() - location.getX();
			final long dy = player.getY() - location.getY();
			if (((dx * dx) + (dy * dy)) <= rangeSq)
			{
				players.add(player);
			}
		}
		return players;
	}

	public void screen(String message)
	{
		final String text = format(message);
		for (Player player : getAudience())
		{
			QuestSpawnUtil.screen(player, text);
		}
	}

	public void announce(String message)
	{
		final String text = format(message);
		for (Player player : getAudience())
		{
			QuestSpawnUtil.announce(player, text);
		}
	}

	public void message(String message)
	{
		final String text = format(message);
		for (Player player : getAudience())
		{
			player.sendMessage(getName() + ": " + text);
		}
	}

	/**
	 * An npc line the quest's players near it hear.
	 * @param npc the speaker
	 * @param message the line
	 */
	public void say(Npc npc, String message)
	{
		if ((npc == null) || (message == null) || message.isEmpty())
		{
			return;
		}
		final String text = format(message);
		for (Player player : getAudience())
		{
			if (player.calculateDistance2D(npc) <= 2500)
			{
				QuestSpawnUtil.say(player, npc, text);
			}
		}
	}
}
