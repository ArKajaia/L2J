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
package org.l2jmobius.gameserver.managers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.managers.FakePlayerTown.Role;
import org.l2jmobius.gameserver.managers.FakePlayerTown.TownNpc;
import org.l2jmobius.gameserver.managers.FakePlayerTownChat.Line;
import org.l2jmobius.gameserver.managers.FakePlayerTownChat.Speaker;
import org.l2jmobius.gameserver.managers.FakePlayerTownChat.Topic;
import org.l2jmobius.gameserver.model.Location;

/**
 * A few town fake players standing together and talking in general chat (see {@link FakePlayerTownChat}): they face each other, take turns, take the time to type each line, wave, laugh or bow now and then, and go their own ways once the talk is over. Others may
 * join while it lasts.
 */
final class FakePlayerTownCircle
{
	private final FakePlayerTownManager _manager;
	private final FakePlayerTown _town;
	private final Topic _topic;
	/** Who says what: speaker index of a line, members in the order they came. */
	private final List<FakePlayerTownVisitor> _speakers = new ArrayList<>(4);
	private final List<FakePlayerTownVisitor> _members = new ArrayList<>(4);
	private final Deque<Line> _lines = new ArrayDeque<>();
	private long _nextLine;
	private boolean _ended;
	
	FakePlayerTownCircle(FakePlayerTownManager manager, FakePlayerTown town, List<FakePlayerTownVisitor> members, Topic topic, long now)
	{
		_manager = manager;
		_town = town;
		_topic = topic;
		for (FakePlayerTownVisitor member : members)
		{
			member.hold(now, 15 * 60000);
			member.circle = this;
			_members.add(member);
			_speakers.add(member);
		}
		
		final List<Speaker> speakers = new ArrayList<>();
		for (FakePlayerTownVisitor member : _speakers)
		{
			speakers.add(speaker(member));
		}
		_lines.addAll(FakePlayerTownChat.conversation(topic, town.shortName, speakers));
		
		faceEachOther();
		_nextLine = now + Rnd.get(700, 3000);
		
		// Some wave hello.
		if (topic != Topic.PARTY_END)
		{
			for (FakePlayerTownVisitor member : _members)
			{
				if (Rnd.nextDouble() < (member.gestures * 0.6))
				{
					_manager.schedule(Rnd.get(200, 1500), () -> member.social(FakePlayerTownVisitor.SOCIAL_GREETING));
				}
			}
		}
	}
	
	private static Speaker speaker(FakePlayerTownVisitor visitor)
	{
		final int weaponId = visitor.npc.getTemplate().getFakePlayerInfo().getEquipRHand();
		final int enchant = visitor.npc.getTemplate().getFakePlayerInfo().getWeaponEnchantLevel();
		return new Speaker(visitor.npc.getName(), visitor.level, FakePlayerTownManager.className(visitor.playerClass), weaponId, enchant, visitor.style);
	}
	
	/**
	 * @param joiner someone walking up to the talk
	 * @param now the current time
	 * @return {@code true} if it joined
	 */
	boolean join(FakePlayerTownVisitor joiner, long now)
	{
		if (_ended || (_members.size() >= 4) || (_lines.size() < 2) || (_speakers.size() < 2))
		{
			return false;
		}
		
		joiner.hold(now, 15 * 60000);
		joiner.circle = this;
		_members.add(joiner);
		_speakers.add(joiner);
		
		// Says hi first: the first two answer (lines for C are said by the one joining).
		final List<Speaker> speakers = List.of(speaker(_speakers.get(0)), speaker(_speakers.get(1)), speaker(joiner));
		final List<Line> hello = FakePlayerTownChat.join(_town.shortName, speakers);
		final int joinerIndex = _speakers.size() - 1;
		for (int i = hello.size() - 1; i >= 0; i--)
		{
			final Line line = hello.get(i);
			_lines.addFirst(new Line(line.speaker() == 2 ? joinerIndex : line.speaker(), line.text()));
		}
		
		faceEachOther();
		_nextLine = Math.max(_nextLine, now + Rnd.get(800, 2500));
		return true;
	}
	
	/**
	 * @return the middle of the group
	 */
	Location getCenter()
	{
		long x = 0;
		long y = 0;
		long z = 0;
		for (FakePlayerTownVisitor member : _members)
		{
			x += member.npc.getX();
			y += member.npc.getY();
			z += member.npc.getZ();
		}
		final int count = Math.max(1, _members.size());
		return new Location((int) (x / count), (int) (y / count), (int) (z / count));
	}
	
	/**
	 * @return {@code true} if someone may still join: there is a while left to talk
	 */
	boolean isOpen()
	{
		return !_ended && (_members.size() < 4) && (_lines.size() >= 3) && (_topic == Topic.CHAT);
	}
	
	/**
	 * Called by the manager every tick: says the next line once its speaker had the time to type it.
	 * @param now the current time
	 */
	void update(long now)
	{
		if (_ended)
		{
			return;
		}
		
		_members.removeIf(member -> member.gone);
		if (_members.size() < 2)
		{
			end(now);
			return;
		}
		
		if (now < _nextLine)
		{
			return;
		}
		
		final Line line = _lines.pollFirst();
		if (line == null)
		{
			end(now);
			return;
		}
		
		final FakePlayerTownVisitor speaker = line.speaker() < _speakers.size() ? _speakers.get(line.speaker()) : null;
		if ((speaker == null) || speaker.gone || !_members.contains(speaker))
		{
			_nextLine = now + 200;
			return;
		}
		
		speaker.say(line.text());
		gesture(speaker, line.text());
		
		// The next one needs a moment to read, think and type.
		final Line next = _lines.peekFirst();
		if (next != null)
		{
			final FakePlayerTownVisitor nextSpeaker = next.speaker() < _speakers.size() ? _speakers.get(next.speaker()) : speaker;
			long delay = (long) (nextSpeaker.reactionMs * (0.5 + Rnd.nextDouble())) + (long) (next.text().length() * nextSpeaker.typingMs * (0.7 + (Rnd.nextDouble() * 0.6)));
			if (Rnd.get(100) < 8)
			{
				delay += Rnd.get(4000, 15000); // Busy with something else for a moment.
			}
			_nextLine = now + Math.max(900, delay);
		}
		else
		{
			_nextLine = now + Rnd.get(1500, 5000);
		}
	}
	
	/**
	 * A social action that goes with what was said, now and then.
	 */
	private void gesture(FakePlayerTownVisitor speaker, String text)
	{
		final String lower = text.toLowerCase();
		int action = 0;
		int chance = 0;
		if (lower.contains("lol") || lower.contains("haha") || lower.contains("xd") || lower.contains("rofl"))
		{
			action = FakePlayerTownVisitor.SOCIAL_LAUGH;
			chance = 30;
		}
		else if (lower.startsWith("gz") || lower.startsWith("grat") || lower.startsWith("gj"))
		{
			action = FakePlayerTownVisitor.SOCIAL_APPLAUSE;
			chance = 25;
		}
		else if (lower.startsWith("cya") || lower.startsWith("bb") || lower.startsWith("later") || lower.startsWith("gn") || lower.startsWith("o/"))
		{
			action = Rnd.get(100) < 70 ? FakePlayerTownVisitor.SOCIAL_GREETING : FakePlayerTownVisitor.SOCIAL_BOW;
			chance = 25;
		}
		else if (lower.startsWith("rip") || lower.contains("broke") || lower.startsWith("fml") || lower.startsWith("ugh"))
		{
			action = FakePlayerTownVisitor.SOCIAL_SORROW;
			chance = 25;
		}
		else if (lower.startsWith("sure") || lower.startsWith("yea") || lower.startsWith("ok lets"))
		{
			action = FakePlayerTownVisitor.SOCIAL_YES;
			chance = 12;
		}
		else if (lower.startsWith("nah") || lower.startsWith("no "))
		{
			action = FakePlayerTownVisitor.SOCIAL_NO;
			chance = 12;
		}
		else if (lower.startsWith("finally") || lower.startsWith("dinged") || lower.startsWith("got "))
		{
			action = FakePlayerTownVisitor.SOCIAL_VICTORY;
			chance = 20;
		}
		
		if ((action > 0) && (Rnd.get(100) < (chance * (0.4 + (speaker.gestures * 1.6)))))
		{
			final int social = action;
			_manager.schedule(Rnd.get(150, 900), () -> speaker.social(social));
		}
	}
	
	/**
	 * Turns the members to each other: two face each other, more face the one across.
	 */
	private void faceEachOther()
	{
		if (_members.size() == 2)
		{
			_members.get(0).face(_members.get(1).npc);
			_members.get(1).face(_members.get(0).npc);
			return;
		}
		
		for (FakePlayerTownVisitor member : _members)
		{
			FakePlayerTownVisitor across = null;
			double farthest = -1;
			for (FakePlayerTownVisitor other : _members)
			{
				final double distance = (other != member) ? FakePlayerTown.distanceSq2D(member.npc, other.npc) : -1;
				if (distance > farthest)
				{
					farthest = distance;
					across = other;
				}
			}
			if (across != null)
			{
				member.face(across.npc);
			}
		}
	}
	
	/**
	 * A member leaves before the end (it had to go, it got stuck...).
	 * @param member the member
	 * @param now the current time
	 */
	void leave(FakePlayerTownVisitor member, long now)
	{
		if (_members.remove(member))
		{
			member.circle = null;
			member.release(now, Rnd.get(500, 2500));
		}
		if (!_ended && (_members.size() < 2))
		{
			end(now);
		}
	}
	
	/**
	 * The talk is over: everyone goes on with their plan, one after another.
	 * @param now the current time
	 */
	void end(long now)
	{
		if (_ended)
		{
			return;
		}
		_ended = true;
		_town.circles.remove(this);
		
		// They hunt together: off to the same gatekeeper, one right after the other.
		final TownNpc gatekeeper = (_topic == Topic.TEAM_UP) && (_members.size() >= 2) ? _town.pickNpc(Role.GATEKEEPER, _members.get(0).npc) : null;
		long delay = 0;
		for (FakePlayerTownVisitor member : _members)
		{
			member.circle = null;
			if (member.gone)
			{
				continue;
			}
			
			if (gatekeeper != null)
			{
				member.leaveWith(gatekeeper, delay);
				member.release(now, Rnd.get(300, 1200));
				delay += Rnd.get(800, 4000);
			}
			else
			{
				member.release(now, Rnd.get(800, 8000));
			}
		}
		_members.clear();
	}
	
	@Override
	public String toString()
	{
		return _topic + " " + _members.size() + " members, " + _lines.size() + " lines left";
	}
}
