/*
 * This file is part of the L2J Mobius project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.l2jmobius.gameserver.model.actor.holders.npc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.l2jmobius.gameserver.model.skill.Skill;

/**
 * The fight against one raid boss (see {@link org.l2jmobius.gameserver.managers.RaidLogManager}): what each player (or fake player) did, the damage of their summons included.<br>
 * Each participant has its own lock, so players hitting the boss at the same time don't wait on each other.
 */
public class RaidLog
{
	/**
	 * What one skill (or the auto attack) of a participant did to the boss and its minions.
	 */
	public static class SkillUse
	{
		public final Skill skill;
		/** An auto attack of a summon. */
		public final boolean summon;
		public long damage;
		public int hits;
		public int crits;
		public int biggest;
		
		SkillUse(Skill skill, boolean summon)
		{
			this.skill = skill;
			this.summon = summon;
		}
	}

	/**
	 * One player or fake player of the fight.
	 */
	public static class Participant
	{
		public final int objectId;
		public final String name;
		public final String className;
		public final int level;
		public final boolean player;

		/** Damage to the boss, by them and their summons. */
		public long damage;
		/** The part of {@link #damage} done by summons, traps and servitors. */
		public long summonDamage;
		public long minionDamage;
		public int hits;
		public int crits;
		public int dots;
		public int misses;
		public int biggest;
		public Skill biggestSkill;
		public long healed;
		public int heals;
		public int debuffs;
		public int resisted;
		public long damageTaken;
		public int deaths;
		public long first;
		public long last;
		/** Filled in when the boss dies. */
		public String partyLeader;

		private final Map<Integer, SkillUse> _skills = new HashMap<>();
		private int _pendingCritFrom;
		private boolean _pendingCrit;

		Participant(int objectId, String name, String className, int level, boolean player, long now)
		{
			this.objectId = objectId;
			this.name = name;
			this.className = className;
			this.level = level;
			this.player = player;
			first = now;
			last = now;
		}

		public synchronized void setPendingCrit(int attackerId, boolean crit)
		{
			_pendingCritFrom = attackerId;
			_pendingCrit = crit;
		}

		public synchronized void addDamage(long now, int attackerId, boolean bySummon, boolean toMinion, Skill skill, int amount, boolean dot)
		{
			final boolean crit = !dot && _pendingCrit && (_pendingCritFrom == attackerId);
			_pendingCrit = false;
			_pendingCritFrom = 0;
			last = now;

			if (toMinion)
			{
				minionDamage += amount;
			}
			else
			{
				damage += amount;
				if (bySummon)
				{
					summonDamage += amount;
				}
			}
			if (dot)
			{
				dots++;
			}
			else
			{
				hits++;
			}
			if (crit)
			{
				crits++;
			}
			if (amount > biggest)
			{
				biggest = amount;
				biggestSkill = skill;
			}

			final int key = skill == null ? (bySummon ? -1 : 0) : skill.getId();
			SkillUse use = _skills.get(key);
			if (use == null)
			{
				use = new SkillUse(skill, key == -1);
				_skills.put(key, use);
			}
			use.damage += amount;
			use.hits++;
			if (crit)
			{
				use.crits++;
			}
			if (amount > use.biggest)
			{
				use.biggest = amount;
			}
		}

		public synchronized void addMiss(long now)
		{
			misses++;
			last = now;
		}

		public synchronized void addHeal(long now, int amount)
		{
			healed += amount;
			heals++;
			last = now;
		}

		public synchronized void addDebuff(long now, boolean landed)
		{
			if (landed)
			{
				debuffs++;
			}
			else
			{
				resisted++;
			}
			last = now;
		}

		public synchronized void addDamageTaken(long now, int amount)
		{
			damageTaken += amount;
			last = now;
		}

		public synchronized void addDeath()
		{
			deaths++;
		}

		/**
		 * @return the skills, most damage first
		 */
		public synchronized List<SkillUse> getSkills()
		{
			final List<SkillUse> list = new ArrayList<>(_skills.values());
			list.sort((a, b) -> Long.compare(b.damage, a.damage));
			return list;
		}
	}

	/**
	 * The fight as it was when the boss died.
	 */
	public static class Report
	{
		public String bossName;
		public int bossLevel;
		public long bossMaxHp;
		public long start;
		public long end;
		/** Damage to the boss by the participants. */
		public long totalDamage;
		/** Damage to the boss by anything else (guards, monsters...). */
		public long otherDamage;
		public long minionDamage;
		public long healed;
		public int deaths;
		public String killerName;
		public String killingSkill;
		public int killingBlow;
		/** Most damage first. */
		public List<Participant> participants;
		/** Every participant's skills, most damage first. */
		public Map<Integer, List<SkillUse>> skills;
		/** Party leader name, then its members' total damage, most damage first. */
		public List<Object[]> parties;

		public Participant get(int objectId)
		{
			for (Participant p : participants)
			{
				if (p.objectId == objectId)
				{
					return p;
				}
			}
			return null;
		}

		public int rankOf(int objectId)
		{
			for (int i = 0; i < participants.size(); i++)
			{
				if (participants.get(i).objectId == objectId)
				{
					return i + 1;
				}
			}
			return 0;
		}
	}

	private final String _bossName;
	private final int _bossLevel;
	private final long _bossMaxHp;
	private final long _start;
	private volatile long _last;
	private volatile boolean _finished;
	private final Map<Integer, Participant> _participants = new ConcurrentHashMap<>();
	private final AtomicLong _otherDamage = new AtomicLong();

	// The last hit on the boss, the killing blow once it died.
	private volatile String _lastHitName;
	private volatile Skill _lastHitSkill;
	private volatile int _lastHitDamage;

	public RaidLog(String bossName, int bossLevel, long bossMaxHp, long now)
	{
		_bossName = bossName;
		_bossLevel = bossLevel;
		_bossMaxHp = bossMaxHp;
		_start = now;
		_last = now;
	}

	/**
	 * @param now the current time
	 * @param idle how long a fight lasts with nothing happening, in milliseconds
	 * @return {@code true} if the fight is still going on
	 */
	public boolean isActive(long now, long idle)
	{
		return !_finished && ((now - _last) <= idle);
	}

	public void touch(long now)
	{
		_last = now;
	}

	/**
	 * @param objectId the player or fake player
	 * @return its participant, {@code null} if it took no part
	 */
	public Participant getParticipant(int objectId)
	{
		return _participants.get(objectId);
	}

	/**
	 * @return its participant, created if it took no part yet
	 */
	public Participant getParticipant(int objectId, String name, String className, int level, boolean player, long now)
	{
		Participant participant = _participants.get(objectId);
		if (participant == null)
		{
			participant = _participants.computeIfAbsent(objectId, _ -> new Participant(objectId, name, className, level, player, now));
		}
		return participant;
	}

	public void addOtherDamage(int amount)
	{
		_otherDamage.addAndGet(amount);
	}

	public void setLastHit(String name, Skill skill, int damage)
	{
		_lastHitName = name;
		_lastHitSkill = skill;
		_lastHitDamage = damage;
	}

	public String getLastHitName()
	{
		return _lastHitName;
	}

	public Skill getLastHitSkill()
	{
		return _lastHitSkill;
	}

	public int getLastHitDamage()
	{
		return _lastHitDamage;
	}

	/**
	 * Ends the fight: nothing is recorded any more.
	 * @param now the time the boss died
	 * @return the report, without its parties and killer (filled in by the caller)
	 */
	public Report finish(long now)
	{
		_finished = true;

		final Report report = new Report();
		report.bossName = _bossName;
		report.bossLevel = _bossLevel;
		report.bossMaxHp = _bossMaxHp;
		report.start = _start;
		report.end = now;
		report.otherDamage = _otherDamage.get();

		final List<Participant> participants = new ArrayList<>(_participants.values());
		participants.sort((a, b) -> b.damage != a.damage ? Long.compare(b.damage, a.damage) : Long.compare(b.healed, a.healed));
		report.participants = participants;

		final Map<Integer, List<SkillUse>> skills = new HashMap<>();
		for (Participant p : participants)
		{
			synchronized (p)
			{
				report.totalDamage += p.damage;
				report.minionDamage += p.minionDamage;
				report.healed += p.healed;
				report.deaths += p.deaths;
			}
			skills.put(p.objectId, p.getSkills());
		}
		report.skills = skills;
		return report;
	}
}
