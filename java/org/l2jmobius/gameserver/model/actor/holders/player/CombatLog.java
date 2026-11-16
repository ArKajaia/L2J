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
package org.l2jmobius.gameserver.model.actor.holders.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.l2jmobius.gameserver.model.skill.Skill;

/**
 * The PvP combat log of one player (see {@link org.l2jmobius.gameserver.managers.CombatLogManager}).<br>
 * It is only created once the player is in a PvP fight, and keeps the last events of the fight in a ring of reused entries plus a few running totals, so recording a hit allocates nothing but the first time an attacker or a skill shows up. On death the fight is frozen
 * into a {@link Report} and the log starts over.
 */
public class CombatLog
{
	// Event types.
	public static final byte HIT = 0;
	public static final byte DOT = 1;
	public static final byte EVADED = 2;
	public static final byte RESISTED = 3;
	public static final byte DEBUFF = 4;
	public static final byte CANCEL = 5;
	public static final byte HEAL = 6;
	public static final byte DEALT = 7;
	public static final byte YOU_MISSED = 8;

	// What a skill row is, for the client's death recap (client/DeathRecap/PROTOCOL.txt).
	public static final byte CATEGORY_ATTACK = 0;
	public static final byte CATEGORY_PHYSICAL_SKILL = 1;
	public static final byte CATEGORY_MAGIC_SKILL = 2;
	public static final byte CATEGORY_OVER_TIME = 3;
	public static final byte CATEGORY_REFLECT = 4;

	/**
	 * One event of the fight. Ring entries are mutable and reused; the ones handed out in a {@link Report} are copies and never change.
	 */
	public static class Entry
	{
		public byte type;
		public long time;
		/** Who did it (or, for {@link #DEALT}, who you hit). */
		public String name;
		public boolean pvp;
		/** The skill used, {@code null} for an auto attack. */
		public Skill skill;
		/** HP damage, or HP healed. */
		public int hp;
		/** CP damage, or CP healed. */
		public int cp;
		public boolean crit;
		/** Your HP and CP right after the event, -1 when unknown. */
		public int hpAfter;
		public int cpAfter;
		/** Buffs cancelled. */
		public int count;
		/** The {@link SkillUse#key} of a {@link #HIT} or {@link #DOT}. */
		public long useKey;

		Entry copy()
		{
			final Entry e = new Entry();
			e.useKey = useKey;
			e.type = type;
			e.time = time;
			e.name = name;
			e.pvp = pvp;
			e.skill = skill;
			e.hp = hp;
			e.cp = cp;
			e.crit = crit;
			e.hpAfter = hpAfter;
			e.cpAfter = cpAfter;
			e.count = count;
			return e;
		}

		public int getTotal()
		{
			return hp + cp;
		}
	}

	/**
	 * Totals for one attacker, or for one target you hit.
	 */
	public static class Side
	{
		/** The object id of the attacker, or of the target you hit. */
		public final int id;
		public final String name;
		public final boolean pvp;
		public long damage;
		public long cpDamage;
		public int hits;
		public int crits;
		public int dots;
		public int evaded;
		public int resisted;
		public int debuffs;
		public int cancels;
		public int biggest;
		public Skill biggestSkill;
		public long first;
		public long last;
		/** Where the kill recap of this target last stopped: its damage then, and when (see {@link CombatLog#summarizeKill}). */
		long reportedDamage;
		long reportedAt;

		Side(int id, String name, boolean pvp, long now)
		{
			this.id = id;
			this.name = name;
			this.pvp = pvp;
			first = now;
			last = now;
		}
	}

	/**
	 * Totals for one skill (or the auto attack) of one attacker.
	 */
	public static class SkillUse
	{
		/** Attacker, skill and whether it is damage over time: what {@link Entry#useKey} names. */
		public final long key;
		public final int attackerId;
		public final String source;
		public final Skill skill;
		/** One of the CATEGORY_ constants. */
		public final byte category;
		/** The attacker's WeaponType ordinal + 1, 0 when unknown. */
		public final int weaponType;
		public long damage;
		public int hits;
		public int crits;
		public int biggest;
		public boolean biggestCrit;
		/** How long the longest debuff this skill left on you lasts, in milliseconds; 0 for none. */
		public int effectMillis;

		SkillUse(long key, int attackerId, String source, Skill skill, byte category, int weaponType)
		{
			this.key = key;
			this.attackerId = attackerId;
			this.source = source;
			this.skill = skill;
			this.category = category;
			this.weaponType = weaponType;
		}
	}

	/**
	 * One thing the killer wore, for the client's death details.
	 */
	public static class Equipped
	{
		/** The paperdoll slot (Inventory.PAPERDOLL_*). */
		public int slot;
		public int itemId;
		public int enchant;
		/** The augmentation id, 0 for none. */
		public int augmentation;
		/** What the augmentation gives, a line each ("P. Atk. +24.5", "Chance on attack (5%): Stun Lv 1"). */
		public List<String> augmentEffects = Collections.emptyList();
		/** 0 none, else the attack element + 1 (1 fire, 2 water, 3 wind, 4 earth, 5 holy, 6 dark). */
		public int attackElement;
		public int attackElementValue;
		/** Defence against fire, water, wind, earth, holy and dark. */
		public final int[] defence = new int[6];
	}

	/**
	 * What you did to one target up to its death, for the client's kill recap.
	 */
	public static class KillSummary
	{
		public long fightMillis;
		public long dealt;
		/** What the target, and anything of the same name's side given with it, dealt to you. */
		public long taken;
		/** Most damage first. */
		public List<SkillUse> rows;
		public int bestHit;
		public Skill bestHitSkill;
	}

	/**
	 * The frozen fight, as it was when the player died.
	 */
	public static class Report
	{
		public long deathTime;
		public long fightStart;
		/** Oldest first. */
		public List<Entry> entries;
		/** Events that did not fit in the ring. */
		public int droppedEntries;
		/** Most damage first. */
		public List<Side> attackers;
		public List<SkillUse> skills;
		public List<Side> targets;
		public long damageTaken;
		public long cpTaken;
		public long healedHp;
		public long healedCp;
		public int heals;
		public long damageDealt;
		public int hitsDealt;
		public int critsDealt;
		public int missesDealt;
		public int debuffsTaken;
		public int resists;
		public int cancelled;
		// The killer.
		public String killerName;
		public String killerOwner;
		public String killerClass;
		public int killerLevel;
		public String killerClan;
		public String killerWeapon;
		/** For the client's death recap: the killer itself (a summon stays the summon), and the weapon of whoever is behind it. */
		public int killerObjectId;
		public boolean killerSummon;
		public int killerWeaponItemId;
		public int killerWeaponEnchant;
		/** WeaponType ordinal + 1, 0 when unknown. */
		public int killerWeaponType;
		/** 0 none, else the attack element + 1 (1 fire, 2 water, 3 wind, 4 earth, 5 holy, 6 dark). */
		public int killerElement;
		public int killerElementValue;
		/** What you dealt to the killer and to whoever is behind it. */
		public long dealtToKiller;
		/** What whoever is behind the killer wore, for the client's death details. */
		public List<Equipped> killerEquipment = Collections.emptyList();
		/** Their passive tree's allocated nodes, {@code null} when they have none (a fake player). */
		public int[] killerTreeNodes;
		public int killerTreeEarned;
		public int killerTreeSpent;
		public int killerHp;
		public int killerMaxHp;
		public int killerCp;
		public int killerMaxCp;
		public int killerDistance;
		public boolean killerPvp;
		public String killingBlowSkill;
		public int killingBlow;
		public boolean killingBlowCrit;
		// You.
		public int maxHp;
		public int maxCp;
		/** Debuffs on you when you died: "name|icon|seconds left|caster". */
		public List<String[]> activeDebuffs;
	}

	private final Entry[] _ring;
	private int _next;
	private int _size;
	private int _dropped;

	private final Map<Integer, Side> _attackers = new HashMap<>();
	private final Map<Long, SkillUse> _skills = new HashMap<>();
	private final Map<Integer, Side> _targets = new HashMap<>();
	/** What you dealt since the last kill recap, by target and skill (the keys of {@link #addHit}, with the target for the attacker). */
	private final Map<Long, SkillUse> _dealt = new HashMap<>();
	/** The longest debuff a skill left, in milliseconds, by attacker and skill id. */
	private final Map<Long, Integer> _effects = new HashMap<>();

	private long _fightStart;
	private long _lastPvp;
	private long _damageTaken;
	private long _cpTaken;
	private long _healedHp;
	private long _healedCp;
	private int _heals;
	private long _damageDealt;
	private int _hitsDealt;
	private int _critsDealt;
	private int _missesDealt;
	private int _debuffsTaken;
	private int _resists;
	private int _cancelled;

	// The crit flag of the next hit, set by the damage message that is sent just before the damage is applied.
	private int _pendingCritFrom;
	private boolean _pendingCrit;

	private volatile Report _lastReport;

	public CombatLog(int capacity)
	{
		_ring = new Entry[capacity];
		for (int i = 0; i < capacity; i++)
		{
			_ring[i] = new Entry();
		}
	}

	/**
	 * @param now the current time
	 * @param window how long a fight lasts without PvP, in milliseconds
	 * @return {@code true} if a PvP fight is going on
	 */
	public synchronized boolean isActive(long now, long window)
	{
		return (_lastPvp > 0) && ((now - _lastPvp) <= window);
	}

	/**
	 * Notes PvP activity, and starts a new fight if the last one is over.
	 * @param now the current time
	 * @param window how long a fight lasts without PvP, in milliseconds
	 */
	public synchronized void touchPvp(long now, long window)
	{
		if ((_lastPvp == 0) || ((now - _lastPvp) > window))
		{
			clear();
			_fightStart = now;
		}
		_lastPvp = now;
	}

	private void clear()
	{
		_next = 0;
		_size = 0;
		_dropped = 0;
		for (Entry e : _ring)
		{
			e.name = null;
			e.skill = null;
		}
		_attackers.clear();
		_skills.clear();
		_targets.clear();
		_effects.clear();
		_dealt.clear();
		_damageTaken = 0;
		_cpTaken = 0;
		_healedHp = 0;
		_healedCp = 0;
		_heals = 0;
		_damageDealt = 0;
		_hitsDealt = 0;
		_critsDealt = 0;
		_missesDealt = 0;
		_debuffsTaken = 0;
		_resists = 0;
		_cancelled = 0;
		_pendingCritFrom = 0;
		_pendingCrit = false;
		_lastPvp = 0;
	}

	private Entry nextEntry(byte type, long now, String name, boolean pvp, Skill skill)
	{
		if (_size == _ring.length)
		{
			_dropped++;
		}
		else
		{
			_size++;
		}

		final Entry e = _ring[_next];
		_next = (_next + 1) % _ring.length;
		e.type = type;
		e.time = now;
		e.name = name;
		e.pvp = pvp;
		e.skill = skill;
		e.hp = 0;
		e.cp = 0;
		e.crit = false;
		e.hpAfter = -1;
		e.cpAfter = -1;
		e.count = 0;
		e.useKey = 0;
		return e;
	}

	private Side attacker(int id, String name, boolean pvp, long now)
	{
		Side side = _attackers.get(id);
		if (side == null)
		{
			side = new Side(id, name, pvp, now);
			_attackers.put(id, side);
		}
		side.last = now;
		return side;
	}

	public synchronized void setPendingCrit(int attackerId, boolean crit)
	{
		_pendingCritFrom = attackerId;
		_pendingCrit = crit;
	}

	public synchronized void addHit(long now, int attackerId, String name, boolean pvp, Skill skill, boolean dot, byte category, int weaponType, int hp, int cp, int hpAfter, int cpAfter)
	{
		final boolean crit = !dot && _pendingCrit && (_pendingCritFrom == attackerId);
		_pendingCrit = false;
		_pendingCritFrom = 0;

		final Entry e = nextEntry(dot ? DOT : HIT, now, name, pvp, skill);
		e.hp = hp;
		e.cp = cp;
		e.crit = crit;
		e.hpAfter = hpAfter;
		e.cpAfter = cpAfter;

		final int total = hp + cp;
		_damageTaken += total;
		_cpTaken += cp;

		final Side side = attacker(attackerId, name, pvp, now);
		side.damage += total;
		side.cpDamage += cp;
		if (dot)
		{
			side.dots++;
		}
		else
		{
			side.hits++;
		}
		if (crit)
		{
			side.crits++;
		}
		if (total > side.biggest)
		{
			side.biggest = total;
			side.biggestSkill = skill;
		}

		// A skill's own hit and its damage over time are two rows; so are a normal attack and the damage it reflected.
		final long key = ((long) attackerId << 32) | (dot ? 0x80000000L : 0) | ((category == CATEGORY_REFLECT) && (skill == null) ? 0x40000000L : 0) | (skill == null ? 0 : skill.getId());
		e.useKey = key;
		SkillUse use = _skills.get(key);
		if (use == null)
		{
			use = new SkillUse(key, attackerId, name, skill, category, weaponType);
			_skills.put(key, use);
		}
		use.damage += total;
		use.hits++;
		if (crit)
		{
			use.crits++;
		}
		if (total > use.biggest)
		{
			use.biggest = total;
			use.biggestCrit = crit;
		}
	}

	public synchronized void addEvaded(long now, int attackerId, String name, boolean pvp, Skill skill)
	{
		nextEntry(EVADED, now, name, pvp, skill);
		attacker(attackerId, name, pvp, now).evaded++;
	}

	public synchronized void addResisted(long now, int attackerId, String name, boolean pvp, Skill skill)
	{
		nextEntry(RESISTED, now, name, pvp, skill);
		attacker(attackerId, name, pvp, now).resisted++;
		_resists++;
	}

	public synchronized void addDebuff(long now, int attackerId, String name, boolean pvp, Skill skill, int millis)
	{
		if ((skill != null) && (millis > 0))
		{
			_effects.merge(((long) attackerId << 32) | skill.getId(), millis, Math::max);
		}
		nextEntry(DEBUFF, now, name, pvp, skill);
		attacker(attackerId, name, pvp, now).debuffs++;
		_debuffsTaken++;
	}

	public synchronized void addCancel(long now, int attackerId, String name, boolean pvp, Skill skill, int count)
	{
		nextEntry(CANCEL, now, name, pvp, skill).count = count;
		attacker(attackerId, name, pvp, now).cancels += count;
		_cancelled += count;
	}

	public synchronized void addHeal(long now, String name, Skill skill, int hp, int cp, int hpAfter, int cpAfter)
	{
		final Entry e = nextEntry(HEAL, now, name, false, skill);
		e.hp = hp;
		e.cp = cp;
		e.hpAfter = hpAfter;
		e.cpAfter = cpAfter;
		_healedHp += hp;
		_healedCp += cp;
		_heals++;
	}

	public synchronized void addDealt(long now, int targetId, String name, boolean pvp, Skill skill, int damage, boolean crit, boolean dot, byte category, int weaponType, boolean timeline)
	{
		final long key = ((long) targetId << 32) | (dot ? 0x80000000L : 0) | ((category == CATEGORY_REFLECT) && (skill == null) ? 0x40000000L : 0) | (skill == null ? 0 : skill.getId());
		SkillUse use = _dealt.get(key);
		if (use == null)
		{
			use = new SkillUse(key, targetId, name, skill, category, weaponType);
			_dealt.put(key, use);
		}
		use.damage += damage;
		use.hits++;
		if (crit)
		{
			use.crits++;
		}
		if (damage > use.biggest)
		{
			use.biggest = damage;
			use.biggestCrit = crit;
		}

		if (timeline)
		{
			final Entry e = nextEntry(DEALT, now, name, pvp, skill);
			e.hp = damage;
			e.crit = crit;
		}

		_damageDealt += damage;
		_hitsDealt++;
		if (crit)
		{
			_critsDealt++;
		}

		Side side = _targets.get(targetId);
		if (side == null)
		{
			side = new Side(targetId, name, pvp, now);
			_targets.put(targetId, side);
		}
		side.last = now;
		side.damage += damage;
		if (dot)
		{
			side.dots++;
		}
		else
		{
			side.hits++;
		}
		if (crit)
		{
			side.crits++;
		}
		if (damage > side.biggest)
		{
			side.biggest = damage;
			side.biggestSkill = skill;
		}
	}

	public synchronized void addMissed(long now, String targetName, boolean pvp, boolean timeline)
	{
		if (timeline)
		{
			nextEntry(YOU_MISSED, now, targetName, pvp, null);
		}
		_missesDealt++;
	}

	/**
	 * What you dealt to a target since the fight began, or since its last kill recap, and what it dealt to you in that time. The next recap of the same target starts from here.
	 * @param targetId the object id of who died
	 * @param now the time of the kill
	 * @return the summary, {@code null} if you dealt it nothing
	 */
	public synchronized KillSummary summarizeKill(int targetId, long now)
	{
		final List<SkillUse> rows = new ArrayList<>();
		for (java.util.Iterator<SkillUse> it = _dealt.values().iterator(); it.hasNext();)
		{
			final SkillUse use = it.next();
			if (use.attackerId == targetId)
			{
				rows.add(use);
				it.remove();
			}
		}
		if (rows.isEmpty())
		{
			return null;
		}
		rows.sort((a, b) -> Long.compare(b.damage, a.damage));

		final KillSummary summary = new KillSummary();
		summary.rows = rows;
		for (SkillUse use : rows)
		{
			summary.dealt += use.damage;
			if (use.biggest > summary.bestHit)
			{
				summary.bestHit = use.biggest;
				summary.bestHitSkill = use.skill;
			}
		}

		long since = _fightStart;
		final Side target = _targets.get(targetId);
		if (target != null)
		{
			since = target.reportedAt > 0 ? target.reportedAt : Math.max(_fightStart, target.first);
			target.reportedAt = now;
		}
		final Side attacker = _attackers.get(targetId);
		if (attacker != null)
		{
			summary.taken = attacker.damage - attacker.reportedDamage;
			attacker.reportedDamage = attacker.damage;
			if (attacker.first < since)
			{
				since = Math.max(_fightStart, attacker.reportedAt > 0 ? attacker.reportedAt : attacker.first);
			}
			attacker.reportedAt = now;
		}
		summary.fightMillis = Math.max(0, now - since);
		return summary;
	}

	/**
	 * Freezes the fight into a report, keeps it as the last report and starts over.
	 * @param now the time of death
	 * @return the report; the killer and "you" fields are left for the caller to fill in
	 */
	public synchronized Report freeze(long now)
	{
		final Report report = new Report();
		report.deathTime = now;
		report.fightStart = _fightStart;

		final List<Entry> entries = new ArrayList<>(_size);
		final int start = (_next - _size + _ring.length) % _ring.length;
		for (int i = 0; i < _size; i++)
		{
			entries.add(_ring[(start + i) % _ring.length].copy());
		}
		report.entries = entries;
		report.droppedEntries = _dropped;

		final List<Side> attackers = new ArrayList<>(_attackers.values());
		attackers.sort((a, b) -> Long.compare(b.damage, a.damage));
		report.attackers = attackers;

		final List<SkillUse> skills = new ArrayList<>(_skills.values());
		for (SkillUse use : skills)
		{
			// The debuff is the skill's, not its damage over time's.
			final Integer millis = (use.skill == null) || ((use.key & 0x80000000L) != 0) ? null : _effects.get(((long) use.attackerId << 32) | use.skill.getId());
			if (millis != null)
			{
				use.effectMillis = millis;
			}
		}
		skills.sort((a, b) -> Long.compare(b.damage, a.damage));
		report.skills = skills;

		final List<Side> targets = new ArrayList<>(_targets.values());
		targets.sort((a, b) -> Long.compare(b.damage, a.damage));
		report.targets = targets;

		report.damageTaken = _damageTaken;
		report.cpTaken = _cpTaken;
		report.healedHp = _healedHp;
		report.healedCp = _healedCp;
		report.heals = _heals;
		report.damageDealt = _damageDealt;
		report.hitsDealt = _hitsDealt;
		report.critsDealt = _critsDealt;
		report.missesDealt = _missesDealt;
		report.debuffsTaken = _debuffsTaken;
		report.resists = _resists;
		report.cancelled = _cancelled;
		report.activeDebuffs = Collections.emptyList();

		clear();
		_lastReport = report;
		return report;
	}

	/**
	 * @return the fight of the last PvP death, {@code null} if none
	 */
	public Report getLastReport()
	{
		return _lastReport;
	}
}
