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
package org.l2jmobius.gameserver.model.actor.holders.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.custom.OraclesWarchiefsConfig;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.Totem;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.enums.SkillFinishType;
import org.l2jmobius.gameserver.model.stats.Formulas;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillUse;
import org.l2jmobius.gameserver.network.serverpackets.SkillCoolTime;

/**
 * The Totem Warchiefs: the Warcryer line (Warcryer, Doomcryer). They don't chant any more, they plant totems (data/stats/skills/custom/warchief_skills.xml) that pulse every few seconds (see config/Custom/OraclesWarchiefs.ini):
 * <ul>
 * <li>Blood: the party around gets an echo that turns part of its damage into HP.</li>
 * <li>Horde: monsters around that fight the party turn on the totem.</li>
 * <li>Frost-Teeth: enemies around get a hex that slows their runs and attacks.</li>
 * <li>Ancestors: the first party member that lies dead around it gets up, and the totem is spent.</li>
 * <li>Flames: every pulse (every {@link OraclesWarchiefsConfig#FLAME_TOTEM_PULSE} milliseconds) burns the enemies close to it with magic damage.</li>
 * </ul>
 * Up to {@link OraclesWarchiefsConfig#TOTEM_MAX_COUNT} totems: another one replaces the oldest. One of each kind, but up to {@link OraclesWarchiefsConfig#FLAME_TOTEM_MAX_COUNT} Totems of Flames (another one replaces the oldest of them). The Great Totem of the
 * Horde-Father (the Doomcryer finale) pulses Blood, Horde and Frost-Teeth farther, and doesn't count.<br>
 * A player's totem planted without Ctrl hits monsters and the players and fake players with PvP status; planted with Ctrl it also hits the white ones. A hit on a player or fake player flags its Warchief (see {@link AreaTargets#isTotemEnemy}).<br>
 * A totem falls when its time is up, when it is broken, or when its Warchief dies, leaves or goes too far.
 * @author Mobius
 */
public class Totems
{
	public enum Kind
	{
		BLOOD,
		HORDE,
		FROST,
		ANCESTORS,
		FLAME
	}

	/** Echo of the Totem of Blood (a buff on the party). */
	public static final int BLOOD_ECHO_ID = 27546;
	/** Echo of the Totem of Frost-Teeth (a hex on enemies). */
	public static final int FROST_ECHO_ID = 27549;
	/** The burn of the Totem of Flames (its power is the damage of a pulse). */
	public static final int FLAME_BURN_ID = 27560;
	/** Flame Strike: what a pulse of the Totem of Flames looks like on each enemy it burns. */
	private static final int FLAME_VISUAL_ID = 1181;
	/** Ancestral Bond: a Doomcryer near its own totem gets {@link #BOND_ECHO_ID}. */
	public static final int BOND_SKILL_ID = 27555;
	public static final int BOND_ECHO_ID = 27556;
	/** Totem Circle: shows how many totems the Warchief has up (Shatter needs 3). */
	public static final int CIRCLE_SKILL_ID = 27557;
	/** The Totem Warchief skills, for the fake players that play them (see FakePlayerPvpAI). */
	public static final int SHATTER_SKILL_ID = 27553;
	public static final int SPIRIT_WALK_SKILL_ID = 27554;
	public static final int GREAT_TOTEM_SKILL_ID = 27558;
	public static final int SPIRIT_TRANCE_SKILL_ID = 27561;
	/** The skills that plant a small totem, and what it pulses. */
	private static final Map<Integer, Kind> PLANT_SKILLS = Map.of(27545, Kind.BLOOD, 27547, Kind.HORDE, 27548, Kind.FROST, 27550, Kind.ANCESTORS, 27559, Kind.FLAME);
	/** Skills whose reuse War Drums shortens: the ones that plant a totem, not Totem of Ancestors (a resurrection), Shatter or Spirit Walk. */
	private static final int[] TOTEM_SKILL_IDS =
	{
		27545, // Totem of Blood
		27547, // Totem of the Horde
		27548, // Totem of Frost-Teeth
		27559, // Totem of Flames
	};

	/** The totems of each Warchief, oldest first, by object id. */
	private static final Map<Integer, List<Totem>> TOTEMS = new ConcurrentHashMap<>();

	/** Totem of Flames: by Warchief and enemy object id, the totem that burns that enemy in full and when it last did; the Warchief's other Totems of Flames burn it for less, see {@link OraclesWarchiefsConfig#FLAME_TOTEM_OVERLAP_DAMAGE}. */
	private static final Map<Integer, Map<Integer, long[]>> FLAME_BURNS = new ConcurrentHashMap<>();

	private Totems()
	{
	}

	/**
	 * @param owner the Warchief
	 * @return its totems, oldest first
	 */
	public static List<Totem> getTotems(Creature owner)
	{
		final List<Totem> totems = TOTEMS.get(owner.getObjectId());
		return totems == null ? Collections.emptyList() : totems;
	}

	/**
	 * Plants a totem at the Warchief's feet.
	 * @param owner the Warchief
	 * @param kinds what it pulses
	 * @param npcId its NPC template
	 * @param skillLevel the level of the skill that planted it
	 * @param great {@code true} for the Great Totem of the Horde-Father
	 * @param forced {@code true} if planted with Ctrl held: it also hits the white players and fake players around
	 * @return the totem, {@code null} if it couldn't be planted
	 */
	public static Totem plant(Creature owner, Set<Kind> kinds, int npcId, int skillLevel, boolean great, boolean forced)
	{
		final NpcTemplate template = NpcData.getInstance().getTemplate(npcId);
		if ((template == null) || owner.isDead())
		{
			return null;
		}

		final List<Totem> totems = TOTEMS.computeIfAbsent(owner.getObjectId(), id -> new CopyOnWriteArrayList<>());
		if (great)
		{
			for (Totem totem : totems)
			{
				if (totem.isGreat())
				{
					totem.unsummon();
				}
			}
		}
		else
		{
			if (isFlames(kinds))
			{
				// Several Totems of Flames: the oldest of them falls.
				List<Totem> flames = getFlameTotems(owner);
				while (flames.size() >= OraclesWarchiefsConfig.FLAME_TOTEM_MAX_COUNT)
				{
					flames.get(0).unsummon();
					flames = getFlameTotems(owner);
				}
			}
			else
			{
				// One of each other kind.
				for (Totem totem : totems)
				{
					if (!totem.isGreat() && totem.getKinds().equals(kinds))
					{
						totem.unsummon();
					}
				}
			}

			// Up to the most a Warchief can have, the Totems of Flames included: the oldest falls.
			List<Totem> small = getSmallTotems(owner);
			while (small.size() >= OraclesWarchiefsConfig.TOTEM_MAX_COUNT)
			{
				small.get(0).unsummon();
				small = getSmallTotems(owner);
			}
		}

		final int range = great ? OraclesWarchiefsConfig.GREAT_TOTEM_RANGE : OraclesWarchiefsConfig.TOTEM_RANGE;
		final int lifetime = great ? OraclesWarchiefsConfig.GREAT_TOTEM_LIFETIME : OraclesWarchiefsConfig.TOTEM_LIFETIME;
		final double hpShare = great ? OraclesWarchiefsConfig.GREAT_TOTEM_HP_SHARE : kinds.contains(Kind.HORDE) ? OraclesWarchiefsConfig.HORDE_TOTEM_HP_SHARE : OraclesWarchiefsConfig.TOTEM_HP_SHARE;
		final Totem totem = new Totem(template, owner, kinds, range, lifetime, great, skillLevel, forced, (int) Math.max(1, owner.getMaxHp() * hpShare));
		totem.setHeading(owner.getHeading());
		totem.fullRestore();
		totem.spawnMe(owner.getX(), owner.getY(), owner.getZ());
		TOTEMS.computeIfAbsent(owner.getObjectId(), id -> new CopyOnWriteArrayList<>()).add(totem);
		totem.startPulse(!great && isFlames(kinds) ? OraclesWarchiefsConfig.FLAME_TOTEM_PULSE : OraclesWarchiefsConfig.TOTEM_PULSE);
		updateCircle(owner);
		ThreadPool.execute(() -> pulse(totem));
		return totem;
	}

	/**
	 * Life taken from a totem (vampiric hits, drain skills, Spell Leech) is cut to {@link OraclesWarchiefsConfig#TOTEM_LEECH_RATE}: breaking a totem is no safe way to heal.
	 * @param target what was hit
	 * @return the part of the usual HP or MP its attacker absorbs, 1 for anything but a totem
	 */
	public static double getLeechRate(WorldObject target)
	{
		return target instanceof Totem ? OraclesWarchiefsConfig.TOTEM_LEECH_RATE : 1;
	}

	/**
	 * @param skillId a skill id
	 * @return what the small totem it plants pulses, {@code null} for a skill that plants none (the Great Totem included)
	 */
	public static Kind getPlantedKind(int skillId)
	{
		return PLANT_SKILLS.get(skillId);
	}

	/**
	 * @param owner the Warchief
	 * @param kind a kind of small totem
	 * @return its totem of that kind, {@code null} if it has none up
	 */
	public static Totem getTotem(Creature owner, Kind kind)
	{
		for (Totem totem : getTotems(owner))
		{
			if (!totem.isGreat() && totem.isSpawned() && totem.getKinds().contains(kind))
			{
				return totem;
			}
		}

		return null;
	}

	/**
	 * @param owner the Warchief
	 * @return its small totems (the Great Totem aside), oldest first
	 */
	public static List<Totem> getSmallTotems(Creature owner)
	{
		final List<Totem> result = new ArrayList<>();
		for (Totem totem : getTotems(owner))
		{
			if (!totem.isGreat())
			{
				result.add(totem);
			}
		}

		return result;
	}

	/**
	 * @param owner the Warchief
	 * @return its Totems of Flames, oldest first
	 */
	public static List<Totem> getFlameTotems(Creature owner)
	{
		final List<Totem> result = new ArrayList<>();
		for (Totem totem : getTotems(owner))
		{
			if (!totem.isGreat() && isFlames(totem.getKinds()))
			{
				result.add(totem);
			}
		}

		return result;
	}

	/**
	 * @param kinds what a totem pulses
	 * @return {@code true} for a Totem of Flames
	 */
	public static boolean isFlames(Set<Kind> kinds)
	{
		return (kinds.size() == 1) && kinds.contains(Kind.FLAME);
	}

	/**
	 * A totem fell: it is no longer the Warchief's.
	 * @param totem the totem
	 */
	public static void forget(Totem totem)
	{
		final Creature owner = totem.getOwner();
		final List<Totem> totems = TOTEMS.get(owner.getObjectId());
		if ((totems != null) && totems.remove(totem))
		{
			if (totems.isEmpty())
			{
				TOTEMS.remove(owner.getObjectId(), totems);
			}

			if (getFlameTotems(owner).isEmpty())
			{
				FLAME_BURNS.remove(owner.getObjectId());
			}

			updateCircle(owner);
		}
	}

	/**
	 * Totem Circle: a buff whose level is how many totems the Warchief has up (the Great Totem aside), so Shatter can ask for 3.
	 * @param owner the Warchief
	 */
	private static void updateCircle(Creature owner)
	{
		final int count = Math.min(5, getSmallTotems(owner).size());
		owner.getEffectList().stopSkillEffects(SkillFinishType.SILENT, CIRCLE_SKILL_ID);
		if ((count > 0) && !owner.isDead())
		{
			final Skill circle = SkillData.getInstance().getSkill(CIRCLE_SKILL_ID, count);
			if (circle != null)
			{
				circle.applyEffects(owner, owner);
			}
		}
	}

	/**
	 * A pulse of a totem: everything it does to the characters around it.
	 * @param totem the totem
	 */
	public static void pulse(Totem totem)
	{
		final Creature owner = totem.getOwner();
		if (!totem.isSpawned() || totem.isDead() || (owner == null) || owner.isDead() || !owner.isSpawned() || (owner.isPlayer() && !owner.asPlayer().isOnline()) || (owner.getInstanceId() != totem.getInstanceId()) || (System.currentTimeMillis() > totem.getExpiresAt()) || !totem.isInsideRadius3D(owner, OraclesWarchiefsConfig.TOTEM_LEASH))
		{
			totem.unsummon();
			return;
		}

		final int range = totem.getRange();
		final List<Creature> party = AreaTargets.getParty(owner);
		final Set<Kind> kinds = totem.getKinds();

		if (kinds.contains(Kind.BLOOD))
		{
			final Skill echo = SkillData.getInstance().getSkill(BLOOD_ECHO_ID, totem.getSkillLevel());
			if (echo != null)
			{
				for (Creature member : party)
				{
					if (!member.isDead() && member.isInsideRadius3D(totem, range))
					{
						AreaTargets.keepEcho(owner, member, echo);
					}
				}
			}
		}

		if (kinds.contains(Kind.FROST))
		{
			final Skill hex = SkillData.getInstance().getSkill(FROST_ECHO_ID, totem.getSkillLevel());
			if (hex != null)
			{
				for (Creature enemy : AreaTargets.getTotemEnemies(owner, totem, range, hex, totem.isForced()))
				{
					AreaTargets.flagFor(owner, enemy);
					AreaTargets.keepEcho(owner, enemy, hex);
				}
			}
		}

		if (kinds.contains(Kind.FLAME))
		{
			final Skill burn = SkillData.getInstance().getSkill(FLAME_BURN_ID, totem.getSkillLevel());
			if (burn != null)
			{
				burn(owner, totem, burn);
			}
		}

		if (kinds.contains(Kind.HORDE))
		{
			for (Attackable monster : World.getInstance().getVisibleObjectsInRange(totem, Attackable.class, range))
			{
				if (monster.isDead() || monster.isFakePlayer() || monster.isRaid() || monster.isRaidMinion() || !totem.isAutoAttackable(monster))
				{
					continue;
				}

				// Only monsters that are fighting the party turn on the totem.
				final Creature mostHated = monster.getMostHated();
				if ((mostHated == null) || (mostHated == totem) || !party.contains(mostHated))
				{
					continue;
				}

				final long hate = (monster.getHating(mostHated) - monster.getHating(totem)) + 100;
				if (hate > 0)
				{
					monster.addDamageHate(totem, 0, hate);
				}
			}
		}

		if (kinds.contains(Kind.ANCESTORS))
		{
			for (Creature member : party)
			{
				if (!member.isPlayer() || !member.isDead() || member.isResurrectionBlocked() || !member.isInsideRadius3D(totem, range))
				{
					continue;
				}

				final Player player = member.asPlayer();
				player.doRevive();
				player.setCurrentHp(player.getMaxHp() * OraclesWarchiefsConfig.ANCESTORS_REVIVE_HP);
				totem.broadcastPacket(new MagicSkillUse(totem, player, 1016, 1, 0, 0)); // Resurrection
				totem.unsummon(); // Spent.
				return;
			}
		}

		// Ancestral Bond: the Doomcryer near its own totem.
		if ((owner.getKnownSkill(BOND_SKILL_ID) != null) && owner.isInsideRadius3D(totem, range))
		{
			final Skill bond = SkillData.getInstance().getSkill(BOND_ECHO_ID, 1);
			if (bond != null)
			{
				AreaTargets.keepEcho(owner, owner, bond);
			}
		}
	}

	/**
	 * Totem of Flames: a pulse burns up to as many enemies as the burn's level allows (its affect limit), never more than {@link OraclesWarchiefsConfig#FLAME_TOTEM_MAX_TARGETS}, within {@link OraclesWarchiefsConfig#FLAME_TOTEM_RANGE} of the totem, the nearest first. An enemy that
	 * another of the Warchief's Totems of Flames burns in full takes {@link OraclesWarchiefsConfig#FLAME_TOTEM_OVERLAP_DAMAGE} of the burn.
	 * @param owner the Warchief (the damage is its magic)
	 * @param totem the totem
	 * @param burn the burn (its power is the damage)
	 */
	private static void burn(Creature owner, Totem totem, Skill burn)
	{
		final List<Creature> enemies = AreaTargets.getTotemEnemies(owner, totem, OraclesWarchiefsConfig.FLAME_TOTEM_RANGE, burn, totem.isForced());
		if (enemies.isEmpty())
		{
			return;
		}

		final int limit = burn.getAffectLimit();
		final int maxTargets = (limit > 0) ? Math.min(limit, OraclesWarchiefsConfig.FLAME_TOTEM_MAX_TARGETS) : OraclesWarchiefsConfig.FLAME_TOTEM_MAX_TARGETS;

		enemies.sort((a, b) -> Double.compare(totem.calculateDistance3D(a), totem.calculateDistance3D(b)));
		final Map<Integer, long[]> burns = FLAME_BURNS.computeIfAbsent(owner.getObjectId(), id -> new ConcurrentHashMap<>());
		final long now = System.currentTimeMillis();
		if (burns.size() > 100)
		{
			burns.values().removeIf(last -> (now - last[1]) > 10000);
		}

		int hits = 0;
		for (Creature enemy : enemies)
		{
			// The totem that burns it in full, as long as it keeps burning it; the Warchief's other Totems of Flames burn it for less.
			final long[] last = burns.get(enemy.getObjectId());
			final boolean overlap = (last != null) && (last[0] != totem.getObjectId()) && ((now - last[1]) <= (OraclesWarchiefsConfig.FLAME_TOTEM_PULSE + 250));
			if (!overlap)
			{
				burns.put(enemy.getObjectId(), new long[]
				{
					totem.getObjectId(),
					now
				});
			}

			// The flames strike each enemy, not the totem.
			enemy.broadcastPacket(new MagicSkillUse(totem, enemy, FLAME_VISUAL_ID, 1, 0, 0));
			AreaTargets.flagFor(owner, enemy);
			final boolean mcrit = Formulas.calcMCrit(owner.getMCriticalHit(enemy, burn));
			final byte shld = Formulas.calcShldUse(owner, enemy, burn);
			final int damage = (int) (Formulas.calcMagicDam(owner, enemy, burn, shld, false, false, mcrit) * (overlap ? OraclesWarchiefsConfig.FLAME_TOTEM_OVERLAP_DAMAGE : 1));
			if (damage > 0)
			{
				enemy.reduceCurrentHp(damage, owner, burn);
				enemy.notifyDamageReceived(damage, owner, burn, mcrit, false);
				owner.sendDamageMessage(enemy, damage, mcrit, false, false);
			}

			if (++hits >= maxTargets)
			{
				break;
			}
		}
	}

	/**
	 * Shatter: every totem of the Warchief pulses one last time and explodes. The explosions hit white players and fake players only if Shatter is cast with Ctrl held, or the totem was planted so.
	 * @param owner the Warchief
	 * @param skill Shatter (its power is the explosion's)
	 * @param forced {@code true} if Shatter is cast with Ctrl held
	 * @return how many exploded
	 */
	public static int shatter(Creature owner, Skill skill, boolean forced)
	{
		final List<Totem> small = getSmallTotems(owner);
		for (Totem totem : small)
		{
			pulse(totem);
			if (!totem.isSpawned())
			{
				continue; // The Totem of Ancestors was spent.
			}

			totem.broadcastPacket(new MagicSkillUse(totem, totem, skill.getDisplayId(), 1, 0, 0));
			int hits = 0;
			for (Creature enemy : AreaTargets.getTotemEnemies(owner, totem, OraclesWarchiefsConfig.SHATTER_RANGE, skill, forced || totem.isForced()))
			{
				AreaTargets.flagFor(owner, enemy);
				final boolean mcrit = Formulas.calcMCrit(owner.getMCriticalHit(enemy, skill));
				final byte shld = Formulas.calcShldUse(owner, enemy, skill);
				final int damage = (int) Formulas.calcMagicDam(owner, enemy, skill, shld, false, false, mcrit);
				if (damage > 0)
				{
					enemy.reduceCurrentHp(damage, owner, skill);
					enemy.notifyDamageReceived(damage, owner, skill, mcrit, false);
					owner.sendDamageMessage(enemy, damage, mcrit, false, false);
				}

				if (++hits >= 10)
				{
					break;
				}
			}

			totem.unsummon();
		}

		return small.size();
	}

	/**
	 * Spirit Walk: the Warchief and one of its totems (the one it targets, or the nearest) swap places: the Warchief stands where the totem stood, and the totem where the Warchief stood.
	 * @param owner the Warchief
	 * @param target what it targets
	 * @return {@code true} if they swapped
	 */
	public static boolean spiritWalk(Creature owner, WorldObject target)
	{
		Totem totem = null;
		if ((target instanceof Totem) && (((Totem) target).getOwner() == owner) && !((Totem) target).isGreat())
		{
			totem = (Totem) target;
		}
		else
		{
			double nearest = Double.MAX_VALUE;
			for (Totem candidate : getSmallTotems(owner))
			{
				final double distance = owner.calculateDistance3D(candidate);
				if (distance < nearest)
				{
					nearest = distance;
					totem = candidate;
				}
			}
		}

		if ((totem == null) || !totem.isSpawned() || (owner.calculateDistance3D(totem) > 1500) || owner.isMovementDisabled())
		{
			return false;
		}

		final Location from = owner.getLocation();
		final Location to = totem.getLocation();
		AreaTargets.flyTo(owner, to.getX(), to.getY(), to.getZ());
		totem.relocate(from.getX(), from.getY(), from.getZ(), from.getHeading());
		return true;
	}

	/**
	 * War Drums: each hit of the Warchief takes some time off the reuse of the skills that plant a totem.
	 * @param owner the Warchief
	 * @param millis how much
	 */
	public static void shortenReuse(Creature owner, long millis)
	{
		boolean changed = false;
		for (int skillId : TOTEM_SKILL_IDS)
		{
			final Skill skill = owner.getKnownSkill(skillId);
			if (skill == null)
			{
				continue;
			}

			final long remaining = owner.getSkillRemainingReuseTime(skill.getReuseHashCode());
			if (remaining <= 0)
			{
				continue;
			}

			final long left = remaining - millis;
			if (left <= 0)
			{
				owner.removeTimeStamp(skill);
				owner.enableSkill(skill);
			}
			else
			{
				owner.addTimeStamp(skill, left);
				owner.disableSkill(skill, left);
			}
			changed = true;
		}

		if (changed && owner.isPlayer())
		{
			owner.sendPacket(new SkillCoolTime(owner.asPlayer()));
		}
	}
}
