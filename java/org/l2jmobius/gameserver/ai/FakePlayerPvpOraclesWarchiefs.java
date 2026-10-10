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
package org.l2jmobius.gameserver.ai;

import java.util.List;

import org.l2jmobius.gameserver.config.custom.OraclesWarchiefsConfig;
import org.l2jmobius.gameserver.managers.FakePlayerPvpManager;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;
import org.l2jmobius.gameserver.model.actor.holders.player.AreaTargets;
import org.l2jmobius.gameserver.model.actor.holders.player.Prophecies;
import org.l2jmobius.gameserver.model.actor.holders.player.Totems;
import org.l2jmobius.gameserver.model.actor.holders.player.Totems.Kind;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.actor.instance.Totem;
import org.l2jmobius.gameserver.model.skill.Skill;

/**
 * How a roaming fake player plays the Oracle (Prophet line) and Totem Warchief (Warcryer line) skills (see README "Oracles and Totem Warchiefs"), like a player who knows what each one is for: a skill listed in its build is only cast when it pays off.
 * <ul>
 * <li>Prophecy of Doom: never cast again on a target that has its Doom on (it would start over and never come true), unless it knows Inevitable Doom and the Doom has room for another stack.</li>
 * <li>Prophecy of Salvation on itself while it takes damage, Prophecy of Reversal as a save point while it is still healthy in a PvP (never once it is low: it would only write down a low HP).</li>
 * <li>Fulfilment when it brings on a big Doom, finishes a target or before its Foresight runs out; Unwritten on two enemies or more.</li>
 * <li>Totems where they work: Flames and Frost-Teeth by its target, Blood while it is hurt (or for its party), the Horde against a pack of monsters, Ancestors on a dead party member. Shatter when the totems are about to fall or the explosions catch a crowd. Spirit Walk to get
 * out of melee.</li>
 * </ul>
 */
final class FakePlayerPvpOraclesWarchiefs
{
	/** Totem of Flames: what the Warchief fights next to. */
	static final int FLAME_TOTEM_SKILL_ID = 27559;
	/** Spell Turning (Hierophant): cancels a spell, only worth it while the target casts. */
	private static final int SPELL_TURNING_SKILL_ID = 1412;
	
	/** A totem with less than this left is planted again where it is needed, or Shattered. */
	private static final long TOTEM_EXPIRING = 6000;
	/** Fulfilment brings on a Doom of at least this many stacks. */
	private static final int FULFIL_DOOM_STACKS = 3;
	/** Fulfilment finishes a target this low that has its Doom on. */
	private static final double FULFIL_FINISH_HP = 0.35;
	/** Fulfilment spends a Foresight about to run out (seconds left) on whatever prophecy is on. */
	private static final int FULFIL_FORESIGHT_ENDING = 5;
	/** Prophecy of Reversal: written down between these shares of HP in a PvP. */
	private static final double REVERSAL_MIN_HP = 0.4;
	private static final double REVERSAL_MAX_HP = 0.85;
	/** Prophecy of Salvation on itself below this share of HP (against players, against monsters). */
	private static final double SALVATION_PVP_HP = 0.85;
	private static final double SALVATION_PVE_HP = 0.6;
	/** Shatter goes off on this many hits (an enemy caught by two totems counts twice) whatever the totems' time left. */
	private static final int SHATTER_CROWD = 4;
	/** Unwritten, the Great Totem and the Horde need this many enemies around (or it in trouble). */
	private static final int CROWD = 2;
	/** Spirit Walk: the totem it swaps with is at least this far from the enemy on it. */
	private static final int SPIRIT_WALK_MIN_GAP = 450;
	/** Spirit Walk can't reach a totem farther than this (see Totems#spiritWalk). */
	private static final int SPIRIT_WALK_RANGE = 1500;
	/** It kites around a totem that is this close to it. */
	private static final int KITE_ANCHOR_RANGE = 700;
	/** It walks up to plant the Totem of Flames on a target this close (collisions excluded), not further. */
	private static final int FLAME_APPROACH_RANGE = 900;
	
	private FakePlayerPvpOraclesWarchiefs()
	{
	}
	
	/**
	 * @param npc the fake player
	 * @param target the target of a skill cast on an enemy
	 * @param skill the skill
	 * @return {@code false} if casting it on {@code target} now would be wasted (a Doom that would start over, Spell Turning on a target that doesn't cast)
	 */
	static boolean isWorthCasting(Attackable npc, Creature target, Skill skill)
	{
		switch (skill.getId())
		{
			case Prophecies.DOOM_SKILL_ID:
			{
				return Prophecies.canStackDoom(npc, target);
			}
			case SPELL_TURNING_SKILL_ID:
			{
				return target.isCastingNow();
			}
			default:
			{
				return true;
			}
		}
	}
	
	/**
	 * @param skill a skill it casts on itself
	 * @return {@code true} for one used on monsters too although its reuse is long (Totem of Ancestors: a dead party member is no less dead in a hunt)
	 */
	static boolean ignoresPvpOnly(Skill skill)
	{
		return Totems.getPlantedKind(skill.getId()) == Kind.ANCESTORS;
	}
	
	/**
	 * @param npc the fake player
	 * @param target the creature it fights, {@code null} for none
	 * @param skill a skill it would cast on itself (a self buff, a heal, a totem...)
	 * @return {@code false} if casting it now would be wasted
	 */
	static boolean isWorthSelfCast(Attackable npc, Creature target, Skill skill)
	{
		final int skillId = skill.getId();
		final Kind kind = Totems.getPlantedKind(skillId);
		switch (skillId)
		{
			case Prophecies.SALVATION_SKILL_ID:
			case Prophecies.REVERSAL_SKILL_ID:
			case Prophecies.FULFILMENT_SKILL_ID:
			case Prophecies.UNWRITTEN_SKILL_ID:
			case Totems.SHATTER_SKILL_ID:
			case Totems.SPIRIT_WALK_SKILL_ID:
			case Totems.SPIRIT_TRANCE_SKILL_ID:
			case Totems.GREAT_TOTEM_SKILL_ID:
			{
				break;
			}
			default:
			{
				if (kind == null)
				{
					return true;
				}
				break;
			}
		}
		
		final boolean fighting = (target != null) && (target != npc) && !target.isDead();
		final boolean pvp = fighting && FakePlayerPvpManager.isPvpEnemy(target);
		final double hpRatio = npc.getCurrentHp() / npc.getMaxHp();
		switch (skillId)
		{
			case Prophecies.SALVATION_SKILL_ID:
			{
				// It heals back the damage taken while it is on: worth it while the hits come in.
				return fighting && !npc.isAffectedBySkill(skillId) && (hpRatio < (pvp ? SALVATION_PVP_HP : SALVATION_PVE_HP));
			}
			case Prophecies.REVERSAL_SKILL_ID:
			{
				// A save point: written down while it still has HP to come back to.
				return pvp && (hpRatio >= REVERSAL_MIN_HP) && (hpRatio < REVERSAL_MAX_HP) && !npc.isAffectedBySkill(skillId);
			}
			case Prophecies.FULFILMENT_SKILL_ID:
			{
				return isFulfilmentWorth(npc, target, fighting);
			}
			case Prophecies.UNWRITTEN_SKILL_ID:
			{
				return fighting && ((countEnemies(npc, npc, 600, skill) >= CROWD) || (pvp && (hpRatio < 0.5) && npc.isInsideRadius2D(target, 600)));
			}
			case Totems.SHATTER_SKILL_ID:
			{
				return fighting && isShatterWorth(npc, target, skill);
			}
			case Totems.SPIRIT_WALK_SKILL_ID:
			{
				return false; // Played on its own, with the totem it swaps with picked (see getSpiritWalkTotem).
			}
			case Totems.SPIRIT_TRANCE_SKILL_ID:
			{
				// Turned to ice while its totems fight: only to get back on its feet.
				return fighting && (hpRatio < 0.4);
			}
			case Totems.GREAT_TOTEM_SKILL_ID:
			{
				return fighting && ((countEnemies(npc, npc, OraclesWarchiefsConfig.GREAT_TOTEM_RANGE, skill) >= CROWD) || (pvp && (hpRatio < 0.6) && npc.isInsideRadius2D(target, 600)));
			}
			default:
			{
				return isTotemWorth(npc, target, kind, fighting, pvp, hpRatio);
			}
		}
	}
	
	/**
	 * Fulfilment makes every prophecy of the Oracle come true now, x1.5, and spends its 5 Foresight: it brings on a big Doom, finishes a target with a Doom on, or spends a Foresight about to run out.
	 */
	private static boolean isFulfilmentWorth(Attackable npc, Creature target, boolean fighting)
	{
		if (Prophecies.getForesight(npc) < Prophecies.FORESIGHT_MAX)
		{
			return false;
		}
		
		if (fighting)
		{
			final int stacks = Prophecies.getDoomStacks(npc, target);
			if ((stacks >= Math.min(FULFIL_DOOM_STACKS, OraclesWarchiefsConfig.DOOM_MAX_STACKS)) || ((stacks > 0) && ((target.getCurrentHp() / target.getMaxHp()) < FULFIL_FINISH_HP)))
			{
				return true;
			}
			
			// Its Ruin comes true now, while the target is in the middle of a fight: a stun or a silence when it counts.
			if ((stacks > 0) && target.isAffectedBySkill(Prophecies.RUIN_SKILL_ID) && FakePlayerPvpManager.isPvpEnemy(target))
			{
				return true;
			}
		}
		
		return (Prophecies.getForesightTime(npc) <= FULFIL_FORESIGHT_ENDING) && (Prophecies.countPending(npc) > 0);
	}
	
	/**
	 * Shatter makes every totem explode on the enemies within {@link OraclesWarchiefsConfig#SHATTER_RANGE} of it. A Totem of Flames burns for far more over its lifetime than its explosion, so the totems are cashed in when they are about to fall anyway, when one is being broken, to finish a
	 * low target, or when the explosions catch a crowd.
	 */
	private static boolean isShatterWorth(Attackable npc, Creature target, Skill skill)
	{
		final long now = System.currentTimeMillis();
		int hits = 0;
		boolean targetHit = false;
		boolean falling = false;
		for (Totem totem : Totems.getSmallTotems(npc))
		{
			falling |= ((totem.getExpiresAt() - now) < TOTEM_EXPIRING) || ((totem.getCurrentHp() / totem.getMaxHp()) < 0.3);
			for (Creature enemy : AreaTargets.getEnemies(npc, totem, OraclesWarchiefsConfig.SHATTER_RANGE, skill))
			{
				hits++;
				targetHit |= enemy == target;
			}
		}
		
		return (hits >= SHATTER_CROWD) || (targetHit && (falling || ((target.getCurrentHp() / target.getMaxHp()) < 0.3)));
	}
	
	/**
	 * A totem is planted where it works, and not again while the one up still does its job.
	 */
	private static boolean isTotemWorth(Attackable npc, Creature target, Kind kind, boolean fighting, boolean pvp, double hpRatio)
	{
		if (!fighting || !hasRoomFor(npc, kind))
		{
			return false;
		}
		
		final Totem current = Totems.getTotem(npc, kind);
		final boolean replace = (current == null) || ((current.getExpiresAt() - System.currentTimeMillis()) < TOTEM_EXPIRING);
		final double gap = gap(npc, target);
		switch (kind)
		{
			case FLAME:
			{
				// It burns what stands within FlameTotemRange of it, and it stands at its feet: planted by the target, unless one already burns there.
				return (gap <= getFlamePlantGap()) && !isBurning(npc, target);
			}
			case FROST:
			{
				// It slows the ones that come close: they chase it through the flames.
				return (gap <= (OraclesWarchiefsConfig.TOTEM_RANGE - 200)) && (replace || !current.isInsideRadius2D(target, OraclesWarchiefsConfig.TOTEM_RANGE - 150));
			}
			case BLOOD:
			{
				// Life steal for itself, or for its party around it.
				final boolean worth = (hpRatio < (pvp ? 0.95 : 0.8)) || (AreaTargets.getParty(npc).size() > 1);
				return worth && (replace || !current.isInsideRadius2D(npc, OraclesWarchiefsConfig.TOTEM_RANGE - 150));
			}
			case HORDE:
			{
				// Monsters that fight it turn on the totem: against a pack, never against players or a raid boss.
				return !pvp && target.isMonster() && !target.isRaid() && ((countMonstersOn(npc) >= CROWD) || (hpRatio < 0.5)) && (replace || !current.isInsideRadius2D(npc, 300));
			}
			case ANCESTORS:
			{
				return (current == null) && hasDeadPartyMember(npc);
			}
			default:
			{
				return false;
			}
		}
	}
	
	/**
	 * @return {@code false} if every totem slot is taken and a new one would knock down the oldest while it still stands a while, when Shatter is ready to cash them in first
	 */
	private static boolean hasRoomFor(Attackable npc, Kind kind)
	{
		// It takes the place of its own kind (several Totems of Flames, up to their most).
		if ((kind == Kind.FLAME) ? (Totems.getFlameTotems(npc).size() >= OraclesWarchiefsConfig.FLAME_TOTEM_MAX_COUNT) : (Totems.getTotem(npc, kind) != null))
		{
			return true;
		}
		
		final List<Totem> small = Totems.getSmallTotems(npc);
		if (small.size() < OraclesWarchiefsConfig.TOTEM_MAX_COUNT)
		{
			return true;
		}
		
		final Skill shatter = npc.getKnownSkill(Totems.SHATTER_SKILL_ID);
		return (shatter == null) || npc.isSkillDisabled(shatter) || ((small.get(0).getExpiresAt() - System.currentTimeMillis()) < TOTEM_EXPIRING);
	}
	
	/**
	 * A Totem Warchief fights where its Totem of Flames burns. The totem stands at its feet, so when the Totem of Flames is ready and its target is further than the flames would reach, it walks up to plant it.
	 * @param npc the fake player
	 * @param profile its profile
	 * @param target the creature it fights
	 * @param gap the distance to it, collisions excluded
	 * @param hpRatio its share of HP
	 * @return {@code true} if it should walk closer to its target
	 */
	static boolean wantsCloserForFlames(Attackable npc, FakePlayerPvpProfile profile, Creature target, double gap, double hpRatio)
	{
		final Skill flames = profile.getListedSkill(FLAME_TOTEM_SKILL_ID);
		if ((flames == null) || npc.isSkillDisabled(flames) || npc.isMuted() || target.isInvul() || (gap <= getFlamePlantGap()) || (gap > FLAME_APPROACH_RANGE))
		{
			return false;
		}
		
		// Against a player it doesn't walk into the fight hurt.
		if (FakePlayerPvpManager.isPvpEnemy(target) && (hpRatio < 0.5))
		{
			return false;
		}
		
		if (!hasRoomFor(npc, Kind.FLAME))
		{
			return false;
		}
		
		return !isBurning(npc, target);
	}
	
	/**
	 * @param npc the fake player
	 * @param target the creature it fights
	 * @return {@code true} if one of its Totems of Flames, not about to fall, already burns {@code target}
	 */
	private static boolean isBurning(Attackable npc, Creature target)
	{
		final long now = System.currentTimeMillis();
		for (Totem flames : Totems.getFlameTotems(npc))
		{
			if (flames.isSpawned() && ((flames.getExpiresAt() - now) >= TOTEM_EXPIRING) && flames.isInsideRadius2D(target, OraclesWarchiefsConfig.FLAME_TOTEM_RANGE - 50))
			{
				return true;
			}
		}
		
		return false;
	}
	
	/**
	 * Spirit Walk swaps the Warchief with one of its totems: out of melee, it takes the one farthest from the enemy on it (who is left by the totem it swapped with, in its pulses).
	 * @param npc the fake player
	 * @param enemy the enemy on it
	 * @return the totem to swap with, {@code null} for none far enough from {@code enemy}
	 */
	static Totem getSpiritWalkTotem(Attackable npc, Creature enemy)
	{
		Totem best = null;
		double bestGap = Math.max(SPIRIT_WALK_MIN_GAP, npc.calculateDistance2D(enemy) + 200);
		for (Totem totem : Totems.getSmallTotems(npc))
		{
			if (!totem.isSpawned() || (npc.calculateDistance3D(totem) > SPIRIT_WALK_RANGE))
			{
				continue;
			}
			
			final double totemGap = totem.calculateDistance2D(enemy);
			if (totemGap > bestGap)
			{
				best = totem;
				bestGap = totemGap;
			}
		}
		
		return best;
	}
	
	/**
	 * Stepping back from an enemy in melee, a Warchief runs around its Totem of Flames (or Frost-Teeth), so the enemy chases it through the pulses.
	 * @param npc the fake player
	 * @param enemy the enemy it steps back from
	 * @return the totem to run around, {@code null} for none
	 */
	static Totem getKiteAnchor(Attackable npc, Creature enemy)
	{
		for (Totem flames : Totems.getFlameTotems(npc))
		{
			if (flames.isSpawned() && npc.isInsideRadius2D(flames, KITE_ANCHOR_RANGE) && flames.isInsideRadius2D(enemy, OraclesWarchiefsConfig.FLAME_TOTEM_RANGE + 150))
			{
				return flames;
			}
		}
		
		final Totem frost = Totems.getTotem(npc, Kind.FROST);
		if ((frost != null) && npc.isInsideRadius2D(frost, KITE_ANCHOR_RANGE) && frost.isInsideRadius2D(enemy, OraclesWarchiefsConfig.TOTEM_RANGE))
		{
			return frost;
		}
		
		return null;
	}
	
	/**
	 * @return how close (collisions excluded) its target must be for a Totem of Flames planted at its feet to burn it
	 */
	static int getFlamePlantGap()
	{
		return Math.max(100, OraclesWarchiefsConfig.FLAME_TOTEM_RANGE - 100);
	}
	
	private static double gap(Creature npc, Creature target)
	{
		return npc.calculateDistance2D(target) - npc.getTemplate().getCollisionRadius() - target.getTemplate().getCollisionRadius();
	}
	
	private static int countEnemies(Attackable npc, Creature center, int range, Skill skill)
	{
		return AreaTargets.getEnemies(npc, center, range, skill).size();
	}
	
	/**
	 * @return how many monsters around it fight it or its party
	 */
	private static int countMonstersOn(Attackable npc)
	{
		final List<Creature> party = AreaTargets.getParty(npc);
		int count = 0;
		for (Monster monster : World.getInstance().getVisibleObjectsInRange(npc, Monster.class, 600))
		{
			if (!monster.isAlikeDead() && !monster.isFakePlayer() && party.contains(monster.getMostHated()))
			{
				count++;
			}
		}
		
		return count;
	}
	
	/**
	 * @return {@code true} if a player of its party lies dead where a Totem of Ancestors planted at its feet would bring them back
	 */
	private static boolean hasDeadPartyMember(Attackable npc)
	{
		for (Creature member : AreaTargets.getParty(npc))
		{
			if (member.isPlayer() && member.isDead() && !member.isResurrectionBlocked() && npc.isInsideRadius3D(member, OraclesWarchiefsConfig.TOTEM_RANGE - 100))
			{
				return true;
			}
		}
		
		return false;
	}
}
