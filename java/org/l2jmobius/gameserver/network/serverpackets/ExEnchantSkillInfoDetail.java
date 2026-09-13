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
package org.l2jmobius.gameserver.network.serverpackets;

import org.l2jmobius.commons.network.WritableBuffer;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.data.xml.EnchantSkillGroupsData;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.itemcontainer.Inventory;
import org.l2jmobius.gameserver.model.skill.holders.EnchantSkillLearn;
import org.l2jmobius.gameserver.model.skill.holders.EnchantSkillGroup.EnchantSkillHolder;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;

/**
 * @author KenM
 */
public class ExEnchantSkillInfoDetail extends ServerPacket
{
	private static final int TYPE_NORMAL_ENCHANT = 0;
	private static final int TYPE_SAFE_ENCHANT = 1;
	private static final int TYPE_UNTRAIN_ENCHANT = 2;
	private static final int TYPE_CHANGE_ENCHANT = 3;
	
	private int bookId = 0;
	private boolean reqCount = false;
	private int multi = 1;
	private final int _type;
	private final int _skillId;
	private final int _skillLevel;
	private final int _chance;
	private int _sp;
	private final int _adenacount;
	
	public ExEnchantSkillInfoDetail(int type, int skillId, int skillLevel, Player ply)
	{
		final EnchantSkillLearn enchantLearn = EnchantSkillGroupsData.getInstance().getSkillEnchantmentBySkillId(skillId);
		EnchantSkillHolder esd = null;
		
		// do we have this skill?
		if (enchantLearn != null)
		{
			if (skillLevel > 100)
			{
				esd = enchantLearn.getEnchantSkillHolder(skillLevel);
			}
			else
			{
				esd = enchantLearn.getFirstRouteGroup().getEnchantGroupDetails().get(0);
			}
		}
		
		if (esd == null)
		{
			throw new IllegalArgumentException("Skill " + skillId + " does not have enchant data for level " + skillLevel);
		}
		
		if (type == 0)
		{
			multi = EnchantSkillGroupsData.NORMAL_ENCHANT_COST_MULTIPLIER;
		}
		else if (type == 1)
		{
			multi = EnchantSkillGroupsData.SAFE_ENCHANT_COST_MULTIPLIER;
		}
		
		_chance = esd.getRate(ply);
		_sp = esd.getSpCost();
		if (type == TYPE_UNTRAIN_ENCHANT)
		{
			_sp = (int) (0.8 * _sp);
		}
		
		_adenacount = esd.getAdenaCost() * multi;
		_type = type;
		_skillId = skillId;
		_skillLevel = skillLevel;
		switch (type)
		{
			case TYPE_NORMAL_ENCHANT:
			{
				bookId = EnchantSkillGroupsData.NORMAL_ENCHANT_BOOK;
				reqCount = (_skillLevel % 100) < 2;
				break;
			}
			case TYPE_SAFE_ENCHANT:
			{
				bookId = EnchantSkillGroupsData.SAFE_ENCHANT_BOOK;
				reqCount = true;
				break;
			}
			case TYPE_UNTRAIN_ENCHANT:
			{
				bookId = EnchantSkillGroupsData.UNTRAIN_ENCHANT_BOOK;
				reqCount = true;
				break;
			}
			case TYPE_CHANGE_ENCHANT:
			{
				bookId = EnchantSkillGroupsData.CHANGE_ENCHANT_BOOK;
				reqCount = true;
				break;
			}
			default:
			{
				return;
			}
		}
		
		if ((type != TYPE_SAFE_ENCHANT) && !PlayerConfig.ES_SP_BOOK_NEEDED)
		{
			reqCount = false;
		}
	}
	
	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		ServerPackets.EX_ENCHANT_SKILL_INFO_DETAIL.writeId(this, buffer);
		buffer.writeInt(_type);
		buffer.writeInt(_skillId);
		buffer.writeInt(_skillLevel);
		buffer.writeInt(_sp * multi); // sp
		buffer.writeInt(_chance); // exp
		buffer.writeInt(2); // items count?
		buffer.writeInt(Inventory.ADENA_ID); // Adena
		buffer.writeInt(_adenacount); // Adena count
		buffer.writeInt(bookId); // ItemId Required
		buffer.writeInt(reqCount);
	}
}
