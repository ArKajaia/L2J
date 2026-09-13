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
package org.l2jmobius.gameserver.model.actor.instance;

import java.util.List;
import java.util.Map;

import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.data.xml.SkillTreeData;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.creature.InstanceType;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.status.FolkStatus;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.enums.AcquireSkillType;
import org.l2jmobius.gameserver.model.skill.holders.SkillLearn;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.serverpackets.AcquireSkillList;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;
import org.l2jmobius.gameserver.network.serverpackets.SystemMessage;

public class Folk extends Npc
{
	/**
	 * Creates a NPC.
	 * @param template the NPC template
	 */
	public Folk(NpcTemplate template)
	{
		super(template);
		setInstanceType(InstanceType.Folk);
		setInvul(false);
	}
	
	@Override
	public FolkStatus getStatus()
	{
		return (FolkStatus) super.getStatus();
	}
	
	@Override
	public void initCharStatus()
	{
		setStatus(new FolkStatus(this));
	}
	
	public List<PlayerClass> getClassesToTeach()
	{
		return getTemplate().getTeachInfo();
	}
	
	/**
	 * Displays Skill Tree for a given player, npc and class Id.
	 * @param player the active character.
	 * @param npc the last folk.
	 * @param playerClass the player's active class identifier as a {@link PlayerClass} enum value
	 */
	public static void showSkillList(Player player, Npc npc, PlayerClass playerClass)
	{
		final int npcId = npc.getTemplate().getId();
		if (npcId == 32611) // Tolonis (Officer)
		{
			final List<SkillLearn> skills = SkillTreeData.getInstance().getAvailableCollectSkills(player);
			final AcquireSkillList asl = new AcquireSkillList(AcquireSkillType.COLLECT);
			int counts = 0;
			for (SkillLearn s : skills)
			{
				final Skill sk = SkillData.getInstance().getSkill(s.getSkillId(), s.getSkillLevel());
				if (sk != null)
				{
					counts++;
					asl.addSkill(s.getSkillId(), s.getSkillLevel(), s.getSkillLevel(), 0, 1);
				}
			}
			
			if (counts == 0) // No more skills to learn, come back when you level.
			{
				final int minLevel = SkillTreeData.getInstance().getMinLevelForNewSkill(player, SkillTreeData.getInstance().getCollectSkillTree());
				if (minLevel > 0)
				{
					final SystemMessage sm = new SystemMessage(SystemMessageId.YOU_DO_NOT_HAVE_ANY_FURTHER_SKILLS_TO_LEARN_COME_BACK_WHEN_YOU_HAVE_REACHED_LEVEL_S1);
					sm.addInt(minLevel);
					player.sendPacket(sm);
				}
				else
				{
					player.sendPacket(SystemMessageId.THERE_ARE_NO_OTHER_SKILLS_TO_LEARN);
				}
			}
			else
			{
				player.sendPacket(asl);
			}
			return;
		}
		
		if (!npc.getTemplate().canTeach(playerClass))
		{
			npc.showNoTeachHtml(player);
			return;
		}
		
		if (((Folk) npc).getClassesToTeach().isEmpty())
		{
			final NpcHtmlMessage html = new NpcHtmlMessage(npc.getObjectId());
			final String sb = "<html><body>I cannot teach you. My class list is empty.<br>Ask admin to fix it. Need add my npcid and classes to skill_learn.sql.<br>NpcId:" + npcId + ", Your playerClass:" + player.getPlayerClass().getId() + "</body></html>";
			html.setHtml(sb);
			player.sendPacket(html);
			return;
		}
		
		// Normal skills, No LearnedByFS, no AutoGet skills.
		final List<SkillLearn> skills = SkillTreeData.getInstance().getAvailableSkills(player, playerClass, false, false);
		final AcquireSkillList asl = new AcquireSkillList(AcquireSkillType.CLASS);
		int count = 0;
		player.setLearningClass(playerClass);
		for (SkillLearn s : skills)
		{
			if (SkillData.getInstance().getSkill(s.getSkillId(), s.getSkillLevel()) != null)
			{
				asl.addSkill(s.getSkillId(), s.getSkillLevel(), s.getSkillLevel(), s.getCalculatedLevelUpSp(player.getPlayerClass(), playerClass), 0);
				count++;
			}
		}
		
		if (count == 0)
		{
			final Map<Integer, SkillLearn> skillTree = SkillTreeData.getInstance().getCompleteClassSkillTree(playerClass);
			final int minLevel = SkillTreeData.getInstance().getMinLevelForNewSkill(player, skillTree);
			if (minLevel > 0)
			{
				final SystemMessage sm = new SystemMessage(SystemMessageId.YOU_DO_NOT_HAVE_ANY_FURTHER_SKILLS_TO_LEARN_COME_BACK_WHEN_YOU_HAVE_REACHED_LEVEL_S1);
				sm.addInt(minLevel);
				player.sendPacket(sm);
			}
			else
			{
				if (player.getPlayerClass().level() == 1)
				{
					final SystemMessage sm = new SystemMessage(SystemMessageId.THERE_ARE_NO_OTHER_SKILLS_TO_LEARN_PLEASE_COME_BACK_AFTER_S1ND_CLASS_CHANGE);
					sm.addInt(2);
					player.sendPacket(sm);
				}
				else
				{
					player.sendPacket(SystemMessageId.THERE_ARE_NO_OTHER_SKILLS_TO_LEARN);
				}
			}
		}
		else
		{
			player.sendPacket(asl);
		}
	}
}
