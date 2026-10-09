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
package org.l2jmobius.gameserver.model.actor.holders.npc;

import java.util.Collections;
import java.util.Map;

import org.l2jmobius.gameserver.data.xml.FakePlayerData;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.itemcontainer.Inventory;

/**
 * @author Mobius
 */
public class FakePlayerHolder
{
	private final PlayerClass _playerClass;
	private final int _hair;
	private final int _hairColor;
	private final int _face;
	private final int _nameColor;
	private final int _titleColor;
	private final int _equipHead;
	private int _equipRHand;
	private int _equipLHand;
	private final int _equipGloves;
	private final int _equipChest;
	private final int _equipLegs;
	private final int _equipFeet;
	private final int _equipCloak;
	private final int _equipShirt;
	private final int _equipBelt;
	private final int _equipHair;
	private final int _equipHair2;
	private final int _agathionId;
	private int _weaponEnchantLevel;
	private final int _armorEnchantLevel;
	// The enchant of each armor piece, shield, shirt and belt by paperdoll slot, for a fake player made by FakePlayerPvpFactory (it enchants every piece on its own). Without one, a piece has the armor enchant level.
	private final Map<Integer, Integer> _slotEnchantLevels;
	private final boolean _fishing;
	private final int _baitLocationX;
	private final int _baitLocationY;
	private final int _baitLocationZ;
	private final int _recommends;
	private final int _nobleLevel;
	private final boolean _hero;
	// Its clan and title: a fake player may join a clan (see FakeClanManager).
	private volatile int _clanId;
	private volatile String _title;
	private final int _pledgeStatus;
	private volatile boolean _isSitting;
	// Shown instead of its own title, title color and hero aura while it leads a PvP spot (see PvpSpotManager), null when it doesn't.
	private volatile String _leaderTitle;
	private volatile int _leaderTitleColor;
	private volatile boolean _leaderAura;
	// The transformation it shows (a roaming Kamael fake player in Final Form), 0 for none.
	private volatile int _transformDisplayId;
	private volatile int _privateStoreType;
	private volatile String _privateStoreMessage;
	private final boolean _talkable;
	
	public FakePlayerHolder(StatSet set)
	{
		_playerClass = PlayerClass.getPlayerClass(set.getInt("classId", 1));
		_hair = set.getInt("hair", 1);
		_hairColor = set.getInt("hairColor", 1);
		_face = set.getInt("face", 1);
		_nameColor = set.getInt("nameColor", 0xFFFFFF);
		_titleColor = set.getInt("titleColor", 0xECF9A2);
		_equipHead = set.getInt("equipHead", 0);
		_equipRHand = set.getInt("equipRHand", 0); // Or dual hand.
		_equipLHand = set.getInt("equipLHand", 0);
		_equipGloves = set.getInt("equipGloves", 0);
		_equipChest = set.getInt("equipChest", 0);
		_equipLegs = set.getInt("equipLegs", 0);
		_equipFeet = set.getInt("equipFeet", 0);
		_equipCloak = set.getInt("equipCloak", 0);
		_equipShirt = set.getInt("equipShirt", 0);
		_equipBelt = set.getInt("equipBelt", 0);
		_equipHair = set.getInt("equipHair", 0);
		_equipHair2 = set.getInt("equipHair2", 0);
		_agathionId = set.getInt("agathionId", 0);
		_weaponEnchantLevel = set.getInt("weaponEnchantLevel", 0);
		_armorEnchantLevel = set.getInt("armorEnchantLevel", 0);
		final Map<Integer, Integer> slotEnchantLevels = set.getMap("slotEnchantLevels", Integer.class, Integer.class);
		_slotEnchantLevels = slotEnchantLevels != null ? slotEnchantLevels : Collections.emptyMap();
		_fishing = set.getBoolean("fishing", false);
		_baitLocationX = set.getInt("baitLocationX", 0);
		_baitLocationY = set.getInt("baitLocationY", 0);
		_baitLocationZ = set.getInt("baitLocationZ", 0);
		_recommends = set.getInt("recommends", 0);
		_nobleLevel = set.getInt("nobleLevel", 0);
		_hero = set.getBoolean("hero", false);
		_clanId = set.getInt("clanId", 0);
		_title = set.getString("title", "");
		_pledgeStatus = set.getInt("pledgeStatus", 0);
		_isSitting = set.getBoolean("sitting", false);
		_privateStoreType = set.getInt("privateStoreType", 0);
		_privateStoreMessage = set.getString("privateStoreMessage", "");
		_talkable = set.getBoolean("fakePlayerTalkable", true);
		
		// Populate FakePlayerData mappings.
		final String name = set.getString("name", "");
		FakePlayerData.getInstance().addFakePlayerId(name, set.getInt("id", 0)); // Map name to npcId.
		final String lowercaseName = name.toLowerCase();
		FakePlayerData.getInstance().addFakePlayerName(lowercaseName, name); // Map lowercase name to original name.
		if (_talkable)
		{
			FakePlayerData.getInstance().addTalkableFakePlayerName(lowercaseName);
		}
	}
	
	public PlayerClass getPlayerClass()
	{
		return _playerClass;
	}
	
	public int getHair()
	{
		return _hair;
	}
	
	public int getHairColor()
	{
		return _hairColor;
	}
	
	public int getFace()
	{
		return _face;
	}
	
	public int getNameColor()
	{
		return _nameColor;
	}
	
	public int getTitleColor()
	{
		return _leaderTitle != null ? _leaderTitleColor : _titleColor;
	}
	
	public int getEquipHead()
	{
		return _equipHead;
	}
	
	public int getEquipRHand()
	{
		return _equipRHand;
	}
	
	public int getEquipLHand()
	{
		return _equipLHand;
	}
	
	public int getEquipGloves()
	{
		return _equipGloves;
	}
	
	public int getEquipChest()
	{
		return _equipChest;
	}
	
	public int getEquipLegs()
	{
		return _equipLegs;
	}
	
	public int getEquipFeet()
	{
		return _equipFeet;
	}
	
	public int getEquipCloak()
	{
		return _equipCloak;
	}
	
	public int getEquipShirt()
	{
		return _equipShirt;
	}
	
	public int getEquipBelt()
	{
		return _equipBelt;
	}
	
	public int getEquipHair()
	{
		return _equipHair;
	}
	
	public int getEquipHair2()
	{
		return _equipHair2;
	}
	
	public int getAgathionId()
	{
		return _agathionId;
	}
	
	public int getWeaponEnchantLevel()
	{
		return _weaponEnchantLevel;
	}
	
	/**
	 * Changes the weapon it shows, for a roaming fake player switching weapons (its holder is its own).
	 * @param rightHand the weapon id
	 * @param leftHand the shield id
	 * @param enchantLevel the weapon enchant level
	 */
	public void setWeapon(int rightHand, int leftHand, int enchantLevel)
	{
		_equipRHand = rightHand;
		_equipLHand = leftHand;
		_weaponEnchantLevel = enchantLevel;
	}
	
	public int getArmorEnchantLevel()
	{
		return _armorEnchantLevel;
	}
	
	/**
	 * @param paperdollSlot a paperdoll slot ({@link Inventory#PAPERDOLL_HEAD}, {@link Inventory#PAPERDOLL_LHAND}...)
	 * @return the enchant level of what it wears there: the weapon enchant level for the weapon, the armor enchant level for an armor piece or shield that has none of its own, 0 for a cloak or hair accessory
	 */
	public int getEnchantLevel(int paperdollSlot)
	{
		switch (paperdollSlot)
		{
			case Inventory.PAPERDOLL_RHAND:
			{
				return _weaponEnchantLevel;
			}
			case Inventory.PAPERDOLL_LHAND:
			case Inventory.PAPERDOLL_HEAD:
			case Inventory.PAPERDOLL_CHEST:
			case Inventory.PAPERDOLL_LEGS:
			case Inventory.PAPERDOLL_GLOVES:
			case Inventory.PAPERDOLL_FEET:
			{
				return _slotEnchantLevels.getOrDefault(paperdollSlot, _armorEnchantLevel);
			}
			default:
			{
				return _slotEnchantLevels.getOrDefault(paperdollSlot, 0);
			}
		}
	}
	
	public boolean isFishing()
	{
		return _fishing;
	}
	
	public int getBaitLocationX()
	{
		return _baitLocationX;
	}
	
	public int getBaitLocationY()
	{
		return _baitLocationY;
	}
	
	public int getBaitLocationZ()
	{
		return _baitLocationZ;
	}
	
	public int getRecommends()
	{
		return _recommends;
	}
	
	public int getNobleLevel()
	{
		return _nobleLevel;
	}
	
	public boolean isHero()
	{
		return _hero || ((_leaderTitle != null) && _leaderAura);
	}
	
	public int getClanId()
	{
		return _clanId;
	}
	
	/**
	 * Joins or leaves a clan, for a fake player that is its own (see {@link org.l2jmobius.gameserver.managers.FakeClanManager}).
	 * @param clanId the clan id, 0 for none
	 * @param title the title it shows
	 */
	public void setClan(int clanId, String title)
	{
		_title = title != null ? title : "";
		_clanId = clanId;
	}
	
	public String getTitle()
	{
		final String leaderTitle = _leaderTitle;
		return leaderTitle != null ? leaderTitle : _title;
	}
	
	/**
	 * Shows it as the leader of a PvP spot (see {@link org.l2jmobius.gameserver.managers.PvpSpotManager}), or stops showing it.
	 * @param title the title it shows meanwhile, {@code null} to show its own title again
	 * @param titleColor the color of that title
	 * @param heroAura {@code true} to show the hero aura meanwhile
	 */
	public void setLeader(String title, int titleColor, boolean heroAura)
	{
		_leaderTitleColor = titleColor;
		_leaderAura = heroAura;
		_leaderTitle = title;
	}
	
	/**
	 * @return {@code true} while it is shown as the leader of a PvP spot
	 */
	public boolean isShownAsLeader()
	{
		return _leaderTitle != null;
	}
	
	public int getPledgeStatus()
	{
		return _pledgeStatus;
	}
	
	public boolean isSitting()
	{
		return _isSitting;
	}
	
	/**
	 * Sits down or stands up, for a roaming fake player resting after a fight (its holder is its own).
	 * @param sitting {@code true} to sit down
	 */
	public void setSitting(boolean sitting)
	{
		_isSitting = sitting;
	}
	
	public int getTransformDisplayId()
	{
		return _transformDisplayId;
	}
	
	public void setTransformDisplayId(int transformDisplayId)
	{
		_transformDisplayId = transformDisplayId;
	}
	
	public int getPrivateStoreType()
	{
		return _privateStoreType;
	}
	
	public String getPrivateStoreMessage()
	{
		return _privateStoreMessage;
	}
	
	/**
	 * Opens or closes the private store of a fake player (town fake players open and close theirs like players do).
	 * @param type the store type (0 = none, 1 = sell, 3 = buy, 5 = manufacture, 8 = package sell)
	 * @param message the store message
	 */
	public void setPrivateStore(int type, String message)
	{
		_privateStoreMessage = message != null ? message : "";
		_privateStoreType = type;
	}
	
	public boolean isTalkable()
	{
		return _talkable;
	}
}
