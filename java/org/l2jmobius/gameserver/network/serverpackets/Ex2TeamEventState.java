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

import java.util.List;

import org.l2jmobius.commons.network.WritableBuffer;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;

/**
 * The state of a team event (TvT, Conquest) for the strip at the top of the client's combat log: phase, time left, scores and capture points.<br>
 * The layout is in {@code client/TeamEvents/PROTOCOL.txt}.
 */
public class Ex2TeamEventState extends ServerPacket
{
	public static final int MODE_TVT = 1;
	public static final int MODE_CONQUEST = 2;

	public static final int PHASE_PREP = 0;
	public static final int PHASE_FIGHT = 1;
	public static final int PHASE_ENDED = 2;

	/** Teams: of the recipient, of a point's owner. */
	public static final int TEAM_NONE = 0;
	public static final int TEAM_BLUE = 1;
	public static final int TEAM_RED = 2;

	public static final int RESULT_NONE = 0;
	public static final int RESULT_BLUE = 1;
	public static final int RESULT_RED = 2;
	public static final int RESULT_DRAW = 3;
	public static final int RESULT_NO_RESULT = 4;

	/** Point flag: both teams are on it and it does not move. */
	public static final int POINT_CONTESTED = 1;

	/** The client's limits: points and a point's name length. */
	public static final int MAX_POINTS = 16;
	public static final int MAX_POINT_NAME = 8;

	/**
	 * A capture point.
	 * @param name its name (cut to {@link #MAX_POINT_NAME} characters)
	 * @param owner {@link #TEAM_NONE}, {@link #TEAM_BLUE} or {@link #TEAM_RED}
	 * @param progress -1000 (Red) to 1000 (Blue)
	 * @param rate per-mille of the full range per second, the sign as for the progress
	 * @param flags {@link #POINT_CONTESTED}
	 */
	public record Point(String name, int owner, int progress, int rate, int flags)
	{
	}

	/**
	 * Everything but the recipient's team, the same for every player of a match.
	 * @param mode {@link #MODE_TVT} or {@link #MODE_CONQUEST}
	 * @param phase {@link #PHASE_PREP}, {@link #PHASE_FIGHT} or {@link #PHASE_ENDED}
	 * @param result {@link #RESULT_NONE} until the end, then who won
	 * @param secondsLeft time left in the phase, 0 once ended
	 * @param blueScore Blue's score
	 * @param redScore Red's score
	 * @param maxScore the score a team starts with (Conquest), 0 for none
	 * @param blueDrain score Blue loses per second
	 * @param redDrain score Red loses per second
	 * @param points the capture points, at most {@link #MAX_POINTS}
	 */
	public record State(int mode, int phase, int result, int secondsLeft, int blueScore, int redScore, int maxScore, int blueDrain, int redDrain, List<Point> points)
	{
	}

	private final State _state;
	private final int _myTeam;

	/**
	 * @param state the match's state
	 * @param myTeam the recipient's team: {@link #TEAM_NONE}, {@link #TEAM_BLUE} or {@link #TEAM_RED}
	 */
	public Ex2TeamEventState(State state, int myTeam)
	{
		_state = state;
		_myTeam = myTeam;
	}

	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		ServerPackets.EX2_TEAM_EVENT_STATE.writeId(this, buffer);
		buffer.writeByte(_state.mode());
		buffer.writeByte(_state.phase());
		buffer.writeByte(_myTeam);
		buffer.writeByte(_state.result());
		buffer.writeInt(_state.secondsLeft());
		buffer.writeInt(_state.blueScore());
		buffer.writeInt(_state.redScore());
		buffer.writeInt(_state.maxScore());
		buffer.writeInt(_state.blueDrain());
		buffer.writeInt(_state.redDrain());

		final List<Point> points = _state.points();
		final int count = Math.min(MAX_POINTS, points.size());
		buffer.writeByte(count);
		for (int i = 0; i < count; i++)
		{
			final Point point = points.get(i);
			final String name = point.name() == null ? "" : point.name();
			buffer.writeString(name.length() > MAX_POINT_NAME ? name.substring(0, MAX_POINT_NAME) : name);
			buffer.writeByte(point.owner());
			buffer.writeShort(Math.max(-1000, Math.min(1000, point.progress())));
			buffer.writeShort(Math.max(-1000, Math.min(1000, point.rate())));
			buffer.writeByte(point.flags());
		}
	}
}
