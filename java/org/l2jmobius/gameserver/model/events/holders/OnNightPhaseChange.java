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
package org.l2jmobius.gameserver.model.events.holders;

import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.nightcycle.NightPhase;

/**
 * Fired on the global container whenever {@link org.l2jmobius.gameserver.managers.NightCycleManager} moves to another phase of the day (dusk, night, the Witching Hour, dawn, day).
 */
public class OnNightPhaseChange implements IBaseEvent
{
	private final NightPhase _previousPhase;
	private final NightPhase _phase;

	public OnNightPhaseChange(NightPhase previousPhase, NightPhase phase)
	{
		_previousPhase = previousPhase;
		_phase = phase;
	}

	/**
	 * @return the phase that just ended
	 */
	public NightPhase getPreviousPhase()
	{
		return _previousPhase;
	}

	/**
	 * @return the phase that just began
	 */
	public NightPhase getPhase()
	{
		return _phase;
	}

	@Override
	public EventType getType()
	{
		return EventType.ON_NIGHT_PHASE_CHANGE;
	}
}
