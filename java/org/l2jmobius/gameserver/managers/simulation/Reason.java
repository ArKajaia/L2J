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
package org.l2jmobius.gameserver.managers.simulation;

/**
 * Why a fake player did what it did this think (see {@link FakeAiTrace}): one code for each way out of the AI's think methods.
 */
public enum Reason
{
	// Fighting
	/** Cast a skill on its target (the kind of skill is in the "kind" field). */
	CAST,
	/** Walks into casting range of a skill it wants to use. */
	APPROACH,
	/** In range of a skill but can't see its target: walks toward it. */
	APPROACH_NO_LOS,
	/** In range but something is in the way: goes to a spot that sees its target (the skill or action is in "kind"). */
	FIRING_POSITION,
	/** An archer or mage that can't see its target turns to another enemy it sees and reaches. */
	RETARGET_VISIBLE,
	/** Auto attack. */
	AUTO_ATTACK,
	/** Walks into auto attack range. */
	APPROACH_ATTACK,
	/** In auto attack range but can't see its target: walks toward it. */
	APPROACH_ATTACK_NO_LOS,
	/** A mage between spells walks back into casting range. */
	MAGE_POSITION,
	/** A mage between spells, in range, with nothing to cast. */
	MAGE_HOLD,
	/** Steps back from its target. */
	KITE_STEP,
	/** Wanted to step back, but every way back was blocked. */
	KITE_CORNERED,
	/** Lets a step back finish. */
	KITE_MOVING,
	/** Keeps away from a player whose defensive buff it noticed (Ultimate Defense, Zealot...). */
	HOLD_OFF,
	/** Waits out an invincible player. */
	WAIT_INVUL,
	/** A gap closer (Rush, Shadow Step). */
	RUSH,
	/** A speed buff to catch up. */
	SPRINT,
	/** A step of a combo. */
	COMBO_STEP,
	/** A buff, heal or emergency skill on itself. */
	SELF_SKILL,
	/** Removed a root, bleed... from itself. */
	CLEANSE,
	/** A Totem Warchief walks up to plant its Totem of Flames. */
	TOTEM_APPROACH,
	/** Took out its bow or polearm, or put it back. */
	WEAPON_SWAP,
	/** Stands up first. */
	STAND_UP,
	/** Busy casting (a long spell, a scroll). */
	CASTING,

	// Giving up
	/** Dropped a target it couldn't hit for too long. */
	DROP_TARGET_STUCK,
	/** Dropped a target too far from its leader or hunting ground. */
	DROP_TARGET_LEASH,
	/** Its target is dead, gone or no longer valid. */
	DROP_TARGET_INVALID,

	// Running
	/** Runs away from a fight it is losing. */
	FLEE,
	/** Reads a Scroll of Escape. */
	ESCAPE,
	/** A necromancer runs off to summon a new servitor. */
	REGROUP,

	// Party
	/** Follows its party leader. */
	FOLLOW_LEADER,
	/** Joins the fight of its party. */
	PARTY_ASSIST,
	/** Heals a party member (or several). */
	SUPPORT_HEAL,
	/** Buffs a party member (or the party). */
	SUPPORT_BUFF,
	/** Resurrects a party member. */
	SUPPORT_RESURRECT,
	/** Restores the MP of a party member. */
	SUPPORT_RECHARGE,
	/** Walks into range of a party member to support it. */
	SUPPORT_APPROACH,
	/** Rests (sits) with its party. */
	REST,
	/** A tank goes for an enemy that dives one of its squishies (mage, archer, healer). */
	GUARD_PEEL,
	/** A tank takes on an enemy close to its squishies instead of one in the enemy backline. */
	GUARD_RETARGET,
	/** A tank or support holds its ground by its squishies instead of chasing an enemy far from them. */
	GUARD_HOLD,
	/** A healer someone is on stops them (root, sleep...), or looks after its party without attacking. */
	HEALER_DEFEND,
	/** A healer someone is on runs to its tank. */
	HEALER_RETREAT,
	/** An archer or mage stops an enemy that is on its party's healer. */
	PEEL_FOR_ALLY,
	/** A melee damage dealer holds by its party instead of charging an enemy far from it alone. */
	HOLD_FOR_TANK,

	// Simulation
	/** Gets ready before the teams march (buffs, servitor). */
	SIM_PREP,
	/** Saw an enemy and goes for it. */
	SIM_ENGAGE,
	/** Marches toward the nearest enemy team. */
	SIM_MARCH,
	/** Nothing to do: no enemy left, or the run is over. */
	SIM_WAIT,
	/** On its own in a Team vs Team match: goes for an enemy that is alone, or roams the field looking for one. */
	LONE_HUNT,
	/** On its own in a Team vs Team match: goes to a wall's corner on the enemies' way, or waits there for someone alone. */
	LONE_AMBUSH,
	/** Alone in a Team vs Team match: doesn't take on (or chase into) a group of enemies, waits in its corner or slips back to its team mates. */
	LONE_AVOID,

	/** Nothing else to do this think. */
	IDLE
}
