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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.dynamicquest.ObjectiveTemplate;
import org.l2jmobius.gameserver.model.dynamicquest.QuestInstance;
import org.l2jmobius.gameserver.util.QuestSpawnUtil;

/**
 * CAPTURE: take a monster alive. It is spawned {@code spawnMin}-{@code spawnMax} from {@code at}, at the quest's champion tier plus {@code tierBonus}, titled {@code title} (else the context's
 * {@code target}), with {@code guards} guards ({@code guardTitle}). It can't be killed: beaten below {@code threshold} percent of its HP it is subdued (it stops fighting and can't be hurt). A
 * participant standing next to it then binds it in {@code bindTime} seconds, but not while one of its guards still stands within reach. Left alone for {@code breakFree} seconds, a subdued
 * monster breaks free with {@code recover} percent of its HP and fights again. On escalation it flees {@code fleeMin}-{@code fleeMax} away.
 * @author Mobius
 */
public class CaptureObjective extends AbstractObjective
{
	private static final int BIND_RANGE = 150;
	private static final int GUARD_RANGE = 600;

	private final List<Npc> _guards = new ArrayList<>();
	private Npc _target;
	private Location _spot;
	private String _title;
	private boolean _subdued;
	private int _bindProgress;
	private int _alone;

	public CaptureObjective(QuestInstance quest, ObjectiveTemplate template, int index)
	{
		super(quest, template, index);
	}

	@Override
	public String start(boolean restored)
	{
		_spot = point(resolve(_params.getString("at", "LAST")), _params.getInt("spawnMin", 0), _params.getInt("spawnMax", 500));
		_quest.putLocation("last", _spot);
		_title = title("title");
		if (_title == null)
		{
			_title = _quest.getContext("target", null);
		}
		if (!spawnTarget(_spot))
		{
			return "there is nothing to capture";
		}
		spawnGuards(_spot, count("guards", 0));
		_quest.setMarker(_spot);
		return null;
	}

	private boolean spawnTarget(Location where)
	{
		_target = _quest.spawnMonster(this, pickMonster(random("target")), where, _params.getInt("tierBonus", 2), _title, null);
		if (_target == null)
		{
			return false;
		}
		// Taken alive, never killed: below its threshold it is subdued instead.
		_target.setMortal(false);
		_quest.putContext("captureKind", _target.getName());
		return true;
	}

	private void spawnGuards(Location around, int amount)
	{
		final Random random = random("guards");
		for (int i = 0; i < amount; i++)
		{
			final Npc guard = _quest.spawnMonster(this, pickMonster(random), point(around, 80, 250), _params.getInt("guardTierBonus", 0), title("guardTitle"), null);
			if (guard != null)
			{
				_guards.add(guard);
			}
		}
	}

	private int getHpPercent()
	{
		return (int) Math.ceil((_target.getCurrentHp() * 100) / Math.max(1, _target.getMaxHp()));
	}

	@Override
	public void onTick(int second, List<Player> near)
	{
		if (!QuestSpawnUtil.isAlive(_target))
		{
			return;
		}
		if (!_subdued)
		{
			if (getHpPercent() <= _params.getInt("threshold", 25))
			{
				subdue();
			}
			else if ((second % 5) == 0)
			{
				_quest.setMarker(new Location(_target.getX(), _target.getY(), _target.getZ()));
			}
			return;
		}

		// Subdued: it stays down while someone binds it.
		_target.getAI().setIntention(Intention.IDLE);
		Player binder = null;
		for (Player player : near)
		{
			if (!player.isDead() && (player.calculateDistance2D(_target) <= BIND_RANGE))
			{
				binder = player;
				break;
			}
		}
		if (binder == null)
		{
			if (++_alone >= _params.getInt("breakFree", 25))
			{
				breakFree();
			}
			else if ((_alone % 5) == 0)
			{
				_quest.screen(text("aloneText", "It is stirring - bind it before it gets up!"));
			}
			return;
		}
		_alone = 0;
		if (isGuarded())
		{
			if ((second % 3) == 0)
			{
				QuestSpawnUtil.screen(binder, text("guardedText", "Its guards won't let you bind it - deal with them first!"));
			}
			return;
		}
		final int bindTime = _params.getInt("bindTime", 8);
		_bindProgress++;
		if (_bindProgress < bindTime)
		{
			QuestSpawnUtil.screen(binder, text("bindingText", "Binding") + "... " + ((_bindProgress * 100) / bindTime) + "%");
			return;
		}

		_quest.addContribution(binder.getObjectId(), 3);
		_quest.putLocation("last", new Location(_target.getX(), _target.getY(), _target.getZ()));
		_quest.despawn(_target);
		_target = null;
		_quest.setMarker(null);
		_quest.announce(text("captureText", "Bound and taken alive!"));
		complete();
	}

	private boolean isGuarded()
	{
		for (Npc guard : _guards)
		{
			if (QuestSpawnUtil.isAlive(guard) && (guard.calculateDistance2D(_target) <= GUARD_RANGE))
			{
				return true;
			}
		}
		return false;
	}

	private void subdue()
	{
		_subdued = true;
		_bindProgress = 0;
		_alone = 0;
		_target.setCurrentHp(Math.max(1, _target.getCurrentHp()));
		_target.setInvul(true);
		_target.abortAttack();
		_target.abortCast();
		_target.stopMove(null);
		_target.setTarget(null);
		if (_target.isAttackable())
		{
			_target.asAttackable().clearAggroList();
		}
		_target.disableCoreAI(true);
		_target.setImmobilized(true);
		_target.getAI().setIntention(Intention.IDLE);
		_target.setTitle(text("subduedTitle", "Subdued"));
		_target.broadcastInfo();
		_quest.setMarker(new Location(_target.getX(), _target.getY(), _target.getZ()));
		_quest.announce(text("subdueText", "It's down! Stand next to it to bind it - quickly!"));
	}

	private void breakFree()
	{
		_subdued = false;
		_bindProgress = 0;
		_alone = 0;
		_target.setInvul(false);
		_target.setImmobilized(false);
		_target.disableCoreAI(false);
		_target.setCurrentHp((_target.getMaxHp() * _params.getInt("recover", 50)) / 100.0);
		_target.setTitle(_title != null ? _title : "");
		_target.broadcastInfo();
		final Player owner = _quest.getOwner();
		if (owner != null)
		{
			QuestSpawnUtil.attack(_target, owner);
		}
		_quest.announce(text("breakText", "It broke free! Wear it down again."));
	}

	@Override
	public void onNpcDeath(Npc npc, Creature killer)
	{
		// Only its guards can die; the target itself is immortal while the quest holds it.
		_guards.remove(npc);
	}

	@Override
	public void onEscalate(int level)
	{
		if (!QuestSpawnUtil.isAlive(_target) || _subdued)
		{
			return;
		}
		_quest.despawn(_target);
		_spot = point(_spot, _params.getInt("fleeMin", 2000), _params.getInt("fleeMax", 3000));
		_quest.putLocation("last", _spot);
		if (spawnTarget(_spot))
		{
			_quest.setMarker(_spot);
			_quest.announce(text("fleeText", "It got away! Follow the radar."));
		}
	}

	@Override
	public String getProgress()
	{
		if (isDone())
		{
			return "Captured";
		}
		if (!QuestSpawnUtil.isAlive(_target))
		{
			return "Find it";
		}
		if (_subdued)
		{
			return "Subdued - bind it: " + ((_bindProgress * 100) / Math.max(1, _params.getInt("bindTime", 8))) + "%";
		}
		return "Its health: " + getHpPercent() + "% - wear it below " + _params.getInt("threshold", 25) + "%";
	}

	@Override
	protected String defaultDescription()
	{
		return "Take " + (_title != null ? _title : "the {captureKind}") + " alive: wear it down, then bind it.";
	}
}
