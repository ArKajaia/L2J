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
package org.l2jmobius.gameserver.model.dynamicquest.objective;

import java.util.List;
import java.util.Random;

import org.l2jmobius.gameserver.config.custom.DynamicQuestConfig;
import org.l2jmobius.gameserver.data.xml.DynamicQuestData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.managers.DynamicQuestManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.dynamicquest.NoticeBoardSite;
import org.l2jmobius.gameserver.model.dynamicquest.ObjectiveTemplate;
import org.l2jmobius.gameserver.model.dynamicquest.QuestInstance;
import org.l2jmobius.gameserver.model.dynamicquest.WorldMonsterIndex;
import org.l2jmobius.gameserver.model.hiddenquest.MonsterBand;
import org.l2jmobius.gameserver.model.hiddenquest.MonsterSet;
import org.l2jmobius.gameserver.model.interfaces.ILocational;
import org.l2jmobius.gameserver.util.QuestSpawnUtil;

/**
 * A reusable step of a dynamic quest. Its parameters come from the data; what it picks comes from the quest's seed and difficulty.
 * <p>
 * The manager calls every method while holding the quest's lock. Any objective can be {@code optional="true"}, and any can carry a {@code bonus} condition that counts as an optional objective when it holds:
 * {@code HP_ABOVE:n} (the owner never fell below n% HP during it), {@code METER_ABOVE:n} (its escortee or banner ended with at least n%), {@code FAST:n} (done within n% of the stage's time) or {@code NO_DEATHS}.
 * </p>
 * @author Mobius
 */
public abstract class AbstractObjective
{
	protected final QuestInstance _quest;
	protected final ObjectiveTemplate _template;
	protected final StatSet _params;
	protected final int _index;

	private boolean _done;
	private boolean _failed;
	private String _failReason;
	private boolean _bonusBroken;
	private long _doneTime;

	protected AbstractObjective(QuestInstance quest, ObjectiveTemplate template, int index)
	{
		_quest = quest;
		_template = template;
		_params = template.getParams();
		_index = index;
	}

	/**
	 * Sets the objective up: spawns, radar, first instructions.
	 * @param restored {@code true} if the quest was rebuilt (after a login or restart) and its state already loaded
	 * @return {@code null} if it started, else why not
	 */
	public abstract String start(boolean restored);

	/**
	 * @return a short line of its progress
	 */
	public abstract String getProgress();

	/**
	 * @return what it asks, when the data gives no text
	 */
	protected abstract String defaultDescription();

	/**
	 * A participant (or its summon) killed a monster that isn't one of the quest's own.
	 * @param killer the player
	 * @param monster the monster
	 */
	public void onKill(Player killer, Attackable monster)
	{
	}

	/**
	 * A participant caught a fish.
	 * @param fisher the player
	 * @param fishId the fish item
	 * @param lureId the lure used
	 */
	public void onFish(Player fisher, int fishId, int lureId)
	{
	}

	/**
	 * An npc this objective spawned died.
	 * @param npc the npc
	 * @param killer the killer, may be {@code null}
	 */
	public void onNpcDeath(Npc npc, Creature killer)
	{
	}

	/**
	 * A player talks to an npc this objective spawned.
	 * @param player the player
	 * @param npc the npc
	 * @return the dialog's body, {@code null} for the default
	 */
	public String onTalk(Player player, Npc npc)
	{
		return null;
	}

	/**
	 * A player pressed a button of this objective's dialog.
	 * @param player the player
	 * @param npc the npc talked to, may be {@code null}
	 * @param action the action
	 * @return a dialog body to show, {@code null} for none
	 */
	public String onAction(Player player, Npc npc, String action)
	{
		return null;
	}

	/**
	 * Called every second.
	 * @param second seconds since the quest was started or restored
	 * @param near the players taking part near the quest
	 */
	public void onTick(int second, List<Player> near)
	{
	}

	/**
	 * A participant died.
	 * @param player the player
	 */
	public void onPlayerDeath(Player player)
	{
	}

	/**
	 * The stage escalates instead of ending.
	 * @param level how many times it has escalated (1 = the first)
	 */
	public void onEscalate(int level)
	{
	}

	/**
	 * @return its state for the database
	 */
	public String saveState()
	{
		return "";
	}

	/**
	 * @param state a state from {@link #saveState()}
	 */
	public void loadState(String state)
	{
	}

	/**
	 * @return its escortee's or banner's meter in percent, -1 if it has none
	 */
	public int getMeterPercent()
	{
		return -1;
	}

	// ---------------------------------------------------------------------------------------------
	// Outcome
	// ---------------------------------------------------------------------------------------------

	public final void tick(int second, List<Player> near)
	{
		if (_done || _failed)
		{
			return;
		}

		final String bonus = _params.getString("bonus", null);
		if ((bonus != null) && bonus.startsWith("HP_ABOVE:"))
		{
			final Player owner = _quest.getOwner();
			if ((owner != null) && ((owner.getCurrentHp() * 100) < (owner.getMaxHp() * parseInt(bonus.substring(9), 50))))
			{
				_bonusBroken = true;
			}
		}
		onTick(second, near);
	}

	protected void complete()
	{
		if (_done || _failed)
		{
			return;
		}
		_done = true;
		_doneTime = System.currentTimeMillis();
		DynamicQuestManager.getInstance().onObjectiveChanged(_quest);
	}

	protected void fail(String reason)
	{
		if (_done || _failed)
		{
			return;
		}
		_failed = true;
		_failReason = reason;
		DynamicQuestManager.getInstance().onObjectiveChanged(_quest);
	}

	/**
	 * Marks it done without its callbacks (restored from the database).
	 */
	public void setDone()
	{
		_done = true;
	}

	public boolean isDone()
	{
		return _done;
	}

	public boolean isFailed()
	{
		return _failed;
	}

	public String getFailReason()
	{
		return _failReason;
	}

	public boolean isOptional()
	{
		return _template.isOptional();
	}

	public ObjectiveTemplate getTemplate()
	{
		return _template;
	}

	public int getIndex()
	{
		return _index;
	}

	/**
	 * @return {@code true} if its bonus condition held (always {@code false} without one)
	 */
	public boolean isBonusEarned()
	{
		final String bonus = _params.getString("bonus", null);
		if ((bonus == null) || !_done || _bonusBroken)
		{
			return false;
		}
		if (bonus.startsWith("HP_ABOVE:"))
		{
			return true;
		}
		if (bonus.startsWith("METER_ABOVE:"))
		{
			return getMeterPercent() >= parseInt(bonus.substring(12), 75);
		}
		if (bonus.startsWith("FAST:"))
		{
			final long limit = _quest.getStageDeadline() - _quest.getStageStart();
			return (limit > 0) && ((_doneTime - _quest.getStageStart()) <= ((limit * parseInt(bonus.substring(5), 50)) / 100));
		}
		if (bonus.equals("NO_DEATHS"))
		{
			return !_quest.isDeathOccurred();
		}
		return false;
	}

	/**
	 * @return the bonus condition in words, {@code null} without one
	 */
	public String describeBonus()
	{
		final String bonus = _params.getString("bonus", null);
		if (bonus == null)
		{
			return null;
		}
		if (bonus.startsWith("HP_ABOVE:"))
		{
			return "never fall below " + parseInt(bonus.substring(9), 50) + "% HP";
		}
		if (bonus.startsWith("METER_ABOVE:"))
		{
			return "keep it above " + parseInt(bonus.substring(12), 75) + "%";
		}
		if (bonus.startsWith("FAST:"))
		{
			return "finish within " + parseInt(bonus.substring(5), 50) + "% of the time";
		}
		if (bonus.equals("NO_DEATHS"))
		{
			return "nobody dies";
		}
		return null;
	}

	/**
	 * Removes what it spawned.
	 */
	public void cleanup()
	{
		_quest.despawnAll(this);
	}

	/**
	 * @return the line describing it, with the quest's context filled in
	 */
	public String describe()
	{
		return _quest.format(_template.getText() != null ? _template.getText() : defaultDescription());
	}

	// ---------------------------------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------------------------------

	protected static int parseInt(String value, int defaultValue)
	{
		try
		{
			return Integer.parseInt(value.trim());
		}
		catch (Exception e)
		{
			return defaultValue;
		}
	}

	/**
	 * @param key a count parameter
	 * @param defaultValue its default
	 * @return the count for this quest's difficulty (scaled with the party unless {@code scale="false"})
	 */
	protected int count(String key, int defaultValue)
	{
		return _quest.getDifficulty().count(_params.getInt(key, defaultValue), _params.getBoolean("scale", true));
	}

	/**
	 * @param key a text parameter
	 * @param defaultValue its default
	 * @return the text with the quest's context filled in
	 */
	protected String text(String key, String defaultValue)
	{
		return _quest.format(_params.getString(key, defaultValue));
	}

	/**
	 * @param key a title parameter
	 * @return the title with the quest's context filled in, {@code null} if the parameter isn't set
	 */
	protected String title(String key)
	{
		final String value = _params.getString(key, null);
		return value != null ? _quest.format(value) : null;
	}

	protected Random random(String key)
	{
		return _quest.random(key + "#" + _index);
	}

	/**
	 * @param at AREA (the hunting ground), LAST (where the previous step ended), NEAR (the owner), HOME (the Notice Board of the quest's town)
	 * @return the place
	 */
	protected Location resolve(String at)
	{
		Location location = null;
		switch (at.toUpperCase())
		{
			case "HOME":
			{
				location = getHome();
				break;
			}
			case "LAST":
			{
				location = _quest.getLocation("last");
				break;
			}
			case "NEAR":
			{
				final Player owner = _quest.getOwner();
				if (owner != null)
				{
					location = owner.getLocation();
				}
				break;
			}
		}
		if (location == null)
		{
			location = _quest.getArea();
		}
		if (location == null)
		{
			final Player owner = _quest.getOwner();
			location = owner != null ? owner.getLocation() : new Location(0, 0, 0);
		}
		return location;
	}

	/**
	 * @return the Notice Board of the town the quest was taken at (else the nearest to the owner), {@code null} if none
	 */
	protected Location getHome()
	{
		NoticeBoardSite site = DynamicQuestData.getInstance().getBoard(_quest.getContext("town", ""));
		if ((site == null) || (site.getLocation() == null))
		{
			final Player owner = _quest.getOwner();
			site = owner != null ? DynamicQuestData.getInstance().getNearestBoard(owner, 0) : null;
			if (site != null)
			{
				_quest.putContext("town", site.getTown());
			}
		}
		return (site != null) ? site.getLocation() : null;
	}

	/**
	 * @param from the origin
	 * @param minDistance the minimum distance
	 * @param maxDistance the maximum distance
	 * @return a reachable point outside towns
	 */
	protected static Location point(ILocational from, int minDistance, int maxDistance)
	{
		return QuestSpawnUtil.findPoint(from, minDistance, maxDistance, false, 0);
	}

	/**
	 * @param random the random source
	 * @return a monster for the quest: {@code npcId} if set, else one of the hunting ground's, else of {@code pool} at the quest's level
	 */
	protected int pickMonster(Random random)
	{
		final int npcId = _params.getInt("npcId", 0);
		if (npcId > 0)
		{
			return npcId;
		}
		final List<Integer> area = _quest.getAreaNpcIds();
		if (!area.isEmpty())
		{
			return area.get(random.nextInt(area.size()));
		}
		return pickFromPool(_params.getString("pool", "default"));
	}

	/**
	 * @param poolName a monster pool of the data
	 * @return its monster for the quest's level, 0 if none
	 */
	protected int pickFromPool(String poolName)
	{
		final MonsterSet pool = DynamicQuestData.getInstance().getMonsterPool(poolName);
		final MonsterBand band = pool != null ? pool.getBand(_quest.getDifficulty().getQuestLevel()) : null;
		return band != null ? band.getNpcId() : 0;
	}

	/**
	 * @param around a place
	 * @param random the random source
	 * @return a monster living near the place at the quest's level, else one of the quest's
	 */
	protected int pickMonsterNear(ILocational around, Random random)
	{
		final List<Integer> near = WorldMonsterIndex.getInstance().getNpcIdsNear(around.getX(), around.getY(), 4000, _quest.getDifficulty().getQuestLevel(), 6);
		if (!near.isEmpty())
		{
			return near.get(random.nextInt(Math.min(3, near.size())));
		}
		return pickMonster(random);
	}

	/**
	 * @param monster a world monster
	 * @return {@code true} if its level is high enough for its kill to count
	 */
	protected boolean isWorthy(Attackable monster)
	{
		return monster.getLevel() >= (_quest.getDifficulty().getQuestLevel() - DynamicQuestConfig.KILL_LEVEL_GAP);
	}

	/**
	 * @param poolName a text pool
	 * @param random the random source
	 * @param defaultValue the text when the pool is empty
	 * @return a line of the pool
	 */
	protected static String pickText(String poolName, Random random, String defaultValue)
	{
		final List<String> pool = poolName != null ? DynamicQuestData.getInstance().getTextPool(poolName) : null;
		return (pool == null) || pool.isEmpty() ? defaultValue : pool.get(random.nextInt(pool.size()));
	}

	protected static String npcName(int npcId)
	{
		final NpcTemplate template = NpcData.getInstance().getTemplate(npcId);
		return template != null ? template.getName() : "monster";
	}

	/**
	 * @param npc an npc
	 * @param player a player
	 * @return {@code true} if the player stands close enough to work on the npc
	 */
	protected static boolean isClose(Npc npc, Player player)
	{
		return (npc != null) && (player != null) && !player.isDead() && (player.calculateDistance2D(npc) <= 200);
	}

	/**
	 * @param bypass the action
	 * @param label the button text
	 * @return a button that runs an action of this objective
	 */
	protected String button(String bypass, String label)
	{
		return "<button value=\"" + label + "\" action=\"%prefix%act " + _quest.getId() + " " + _index + " " + bypass + "\" width=160 height=21 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">";
	}
}
