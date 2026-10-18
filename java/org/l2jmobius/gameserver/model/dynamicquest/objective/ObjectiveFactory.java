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

import org.l2jmobius.gameserver.model.dynamicquest.ObjectiveTemplate;
import org.l2jmobius.gameserver.model.dynamicquest.QuestInstance;

/**
 * Builds the objective of each {@link org.l2jmobius.gameserver.model.dynamicquest.ObjectiveType}.
 * @author Mobius
 */
public final class ObjectiveFactory
{
	private ObjectiveFactory()
	{
	}

	/**
	 * @param quest the quest
	 * @param template the objective's data
	 * @param index its index in the stage
	 * @return the objective
	 */
	public static AbstractObjective create(QuestInstance quest, ObjectiveTemplate template, int index)
	{
		switch (template.getType())
		{
			case KILL:
			{
				return new KillObjective(quest, template, index);
			}
			case CULL:
			{
				return new CullObjective(quest, template, index);
			}
			case COLLECT:
			{
				return new CollectObjective(quest, template, index);
			}
			case DELIVER:
			{
				return new DeliverObjective(quest, template, index);
			}
			case ESCORT:
			{
				return new EscortObjective(quest, template, index);
			}
			case INVESTIGATE:
			{
				return new InvestigateObjective(quest, template, index);
			}
			case DEFEND:
			{
				return new DefendObjective(quest, template, index);
			}
			case INTERACT:
			{
				return new InteractObjective(quest, template, index);
			}
			case REACH:
			{
				return new ReachObjective(quest, template, index);
			}
			case DESTROY:
			{
				return new DestroyObjective(quest, template, index);
			}
		}
		throw new IllegalArgumentException("Unknown objective type " + template.getType());
	}
}
