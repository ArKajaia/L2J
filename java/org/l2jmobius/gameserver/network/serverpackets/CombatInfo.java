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
package org.l2jmobius.gameserver.network.serverpackets;

import org.l2jmobius.commons.network.WritableBuffer;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.item.enums.ShotType;
import org.l2jmobius.gameserver.model.item.type.WeaponType;
import org.l2jmobius.gameserver.model.skill.Skill;

/**
 * The combat block a damage {@link SystemMessage} carries after its params, for the custom client's combat log window (client/CombatLog/PROTOCOL.txt).<br>
 * Who hit whom, with what skill, how (normal attack, skill, reflect, counterattack), the attacker's weapon type and the crit, shot, shield, miss, blocked and over-hit flags.<br>
 * What the damage message itself cannot tell (the kind of a hit, the soulshot and shield of a normal attack) is passed down by a {@link #scope} around the code that sends it.
 */
public class CombatInfo
{
	public static final int VERSION = 1;

	public static final int KIND_UNKNOWN = 0;
	public static final int KIND_NORMAL_ATTACK = 1;
	public static final int KIND_SKILL = 2;
	public static final int KIND_REFLECT = 3;
	public static final int KIND_COUNTERATTACK = 4;

	public static final int FLAG_CRIT = 1;
	public static final int FLAG_MAGIC_CRIT = 2;
	public static final int FLAG_SHOTS = 4;
	public static final int FLAG_SHIELD = 8;
	public static final int FLAG_MISS = 16;
	public static final int FLAG_BLOCKED = 32;
	public static final int FLAG_OVERHIT = 64;

	/** The innermost open scope of this thread. */
	private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

	private final int _attackerObjectId;
	private final int _targetObjectId;
	private final int _skillId;
	private final int _skillLevel;
	private final int _kind;
	private final int _weaponType;
	private final int _flags;

	private CombatInfo(Creature attacker, Creature target, Skill skill, int kind, int flags)
	{
		_attackerObjectId = attacker != null ? attacker.getObjectId() : 0;
		_targetObjectId = target != null ? target.getObjectId() : 0;
		_skillId = skill != null ? skill.getDisplayId() : 0;
		_skillLevel = skill != null ? skill.getDisplayLevel() : 0;
		_kind = kind;
		final WeaponType weaponType = attacker != null ? attacker.getAttackType() : null;
		_weaponType = weaponType != null ? weaponType.ordinal() + 1 : 0;
		_flags = flags;
	}

	/**
	 * @param attacker who deals the damage
	 * @param target who takes it
	 * @param skill the skill, {@code null} for none
	 * @param flags the flags the caller knows (crit, miss, blocked...)
	 * @return the block, with the kind and the other flags taken from the open scope when it is about this skill, else worked out from the skill
	 */
	public static CombatInfo of(Creature attacker, Creature target, Skill skill, int flags)
	{
		final Scope scope = CURRENT.get();
		if ((scope != null) && (scope._skill == skill))
		{
			return new CombatInfo(attacker, target, skill, scope._kind, flags | scope._flags);
		}

		if (skill == null)
		{
			return new CombatInfo(attacker, target, null, KIND_UNKNOWN, flags);
		}

		return new CombatInfo(attacker, target, skill, KIND_SKILL, flags | (usedShots(attacker, skill) ? FLAG_SHOTS : 0));
	}

	/**
	 * The block of a "done damage" message: {@link #of} with the crit and miss flags, and over-hit when an over-hit skill kills a monster.
	 * @param attacker who deals the damage
	 * @param target who takes it
	 * @param skill the skill, {@code null} for none
	 * @param damage the damage
	 * @param mcrit magic critical
	 * @param pcrit physical critical
	 * @param miss missed
	 * @param blocked the target was invulnerable
	 * @return the block
	 */
	public static CombatInfo ofHit(Creature attacker, Creature target, Skill skill, int damage, boolean mcrit, boolean pcrit, boolean miss, boolean blocked)
	{
		int flags = (pcrit ? FLAG_CRIT : 0) | (mcrit ? FLAG_MAGIC_CRIT : 0) | (miss ? FLAG_MISS : 0) | (blocked ? FLAG_BLOCKED : 0);
		if ((skill != null) && !miss && !blocked && skill.isOverhit() && target.isAttackable() && (target.isDead() || (damage >= target.getCurrentHp())))
		{
			flags |= FLAG_OVERHIT;
		}

		return of(attacker, target, skill, flags);
	}

	private static boolean usedShots(Creature attacker, Skill skill)
	{
		if (attacker == null)
		{
			return false;
		}

		if (skill.useSpiritShot())
		{
			return attacker.isChargedShot(ShotType.SPIRITSHOTS) || attacker.isChargedShot(ShotType.BLESSED_SPIRITSHOTS);
		}

		return skill.useSoulShot() && attacker.isChargedShot(ShotType.SOULSHOTS);
	}

	/**
	 * Opens a scope: until it is closed, the blocks made on this thread for this skill ({@code null} for none) get this kind and these flags.<br>
	 * Use with try-with-resources. Scopes nest; closing one brings back the one it was opened in.
	 * @param kind one of the KIND_ constants
	 * @param skill the skill the scope is about, {@code null} for a normal attack or a reflect
	 * @param flags flags added to every block of the scope, such as soulshot and shield of a normal attack
	 * @return the scope
	 */
	public static Scope scope(int kind, Skill skill, int flags)
	{
		final Scope scope = new Scope(kind, skill, flags, CURRENT.get());
		CURRENT.set(scope);
		return scope;
	}

	public static class Scope implements AutoCloseable
	{
		private final int _kind;
		private final Skill _skill;
		private final int _flags;
		private final Scope _outer;

		private Scope(int kind, Skill skill, int flags, Scope outer)
		{
			_kind = kind;
			_skill = skill;
			_flags = flags;
			_outer = outer;
		}

		@Override
		public void close()
		{
			if (_outer != null)
			{
				CURRENT.set(_outer);
			}
			else
			{
				CURRENT.remove();
			}
		}
	}

	/**
	 * Writes the block, 18 bytes.
	 * @param buffer the packet
	 */
	public void write(WritableBuffer buffer)
	{
		buffer.writeByte(VERSION);
		buffer.writeInt(_attackerObjectId);
		buffer.writeInt(_targetObjectId);
		buffer.writeInt(_skillId);
		buffer.writeShort(_skillLevel);
		buffer.writeByte(_kind);
		buffer.writeByte(_weaponType);
		buffer.writeByte(_flags);
	}
}
