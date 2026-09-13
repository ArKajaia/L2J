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
package org.l2jmobius.gameserver.network.clientpackets;

import java.util.logging.Logger;

import org.l2jmobius.gameserver.config.GeneralConfig;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.data.xml.EnchantItemData;
import org.l2jmobius.gameserver.managers.PunishmentManager;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.enchant.EnchantResultType;
import org.l2jmobius.gameserver.model.item.enchant.EnchantScroll;
import org.l2jmobius.gameserver.model.item.enchant.EnchantSupportItem;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.skill.CommonSkill;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.serverpackets.EnchantResult;
import org.l2jmobius.gameserver.network.serverpackets.InventoryUpdate;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillUse;
import org.l2jmobius.gameserver.network.serverpackets.SystemMessage;

/**
 * @author Mobius
 */
public class RequestEnchantItem extends ClientPacket
{
	protected static final Logger LOGGER_ENCHANT = Logger.getLogger("enchant.items");
	
	private int _objectId;
	// private int _supportId;
	
	@Override
	protected void readImpl()
	{
		_objectId = readInt();
		// _supportId = readInt();
	}
	
	@Override
	protected void runImpl()
	{
		final Player player = getPlayer();
		if ((player == null) || (_objectId == 0))
		{
			return;
		}
		
		if (!player.isOnline() || getClient().isDetached())
		{
			player.setActiveEnchantItemId(Player.ID_NONE);
			return;
		}
		
		if (player.isProcessingTransaction() || player.isInStoreMode())
		{
			player.sendPacket(SystemMessageId.YOU_CANNOT_ENCHANT_WHILE_OPERATING_A_PRIVATE_STORE_OR_PRIVATE_WORKSHOP);
			player.setActiveEnchantItemId(Player.ID_NONE);
			return;
		}
		
		final Item item = player.getInventory().getItemByObjectId(_objectId);
		Item scroll = player.getInventory().getItemByObjectId(player.getActiveEnchantItemId());
		Item support = player.getInventory().getItemByObjectId(player.getActiveEnchantSupportItemId());
		if ((item == null) || (scroll == null))
		{
			player.setActiveEnchantItemId(Player.ID_NONE);
			player.sendPacket(SystemMessageId.YOU_HAVE_CANCELLED_THE_ENCHANTING_PROCESS);
			player.sendPacket(new EnchantResult(2, 0, 0));
			return;
		}
		
		// Template for scroll.
		final EnchantScroll scrollTemplate = EnchantItemData.getInstance().getEnchantScroll(scroll);
		if (scrollTemplate == null)
		{
			return;
		}
		
		// Template for support item, if exist.
		EnchantSupportItem supportTemplate = null;
		if (support != null)
		{
			supportTemplate = EnchantItemData.getInstance().getSupportItem(support);
			if (supportTemplate == null)
			{
				player.setActiveEnchantItemId(Player.ID_NONE);
				return;
			}
		}
		
		// First validation check, also over enchant check.
		if (!scrollTemplate.isValid(item, supportTemplate) || (PlayerConfig.DISABLE_OVER_ENCHANTING && (item.getEnchantLevel() == scrollTemplate.getMaxEnchantLevel())))
		{
			player.sendPacket(SystemMessageId.INAPPROPRIATE_ENCHANT_CONDITIONS);
			player.setActiveEnchantItemId(Player.ID_NONE);
			player.sendPacket(new EnchantResult(2, 0, 0));
			return;
		}
		
		// Fast auto-enchant cheat check.
		if ((player.getActiveEnchantTimestamp() == 0) || ((System.currentTimeMillis() - player.getActiveEnchantTimestamp()) < 2000))
		{
			PunishmentManager.handleIllegalPlayerAction(player, player + " use autoenchant program ", GeneralConfig.DEFAULT_PUNISH);
			player.setActiveEnchantItemId(Player.ID_NONE);
			player.sendPacket(new EnchantResult(2, 0, 0));
			return;
		}
		
		// Attempting to destroy scroll.
		final Item destroyedScrollItem = player.getInventory().destroyItem(ItemProcessType.FEE, scroll.getObjectId(), 1, player, item);
		if (destroyedScrollItem == null)
		{
			player.sendPacket(SystemMessageId.INCORRECT_ITEM_COUNT_2);
			PunishmentManager.handleIllegalPlayerAction(player, player + " tried to enchant with a scroll he doesn't have", GeneralConfig.DEFAULT_PUNISH);
			player.setActiveEnchantItemId(Player.ID_NONE);
			player.sendPacket(new EnchantResult(2, 0, 0));
			return;
		}
		
		final InventoryUpdate iu = new InventoryUpdate();
		if (destroyedScrollItem.getCount() > 0)
		{
			iu.addModifiedItem(destroyedScrollItem);
		}
		else
		{
			iu.addRemovedItem(destroyedScrollItem);
		}
		
		// Attempting to destroy support if exists.
		if (support != null)
		{
			final Item destroyedSupportItem = player.getInventory().destroyItem(ItemProcessType.FEE, support.getObjectId(), 1, player, item);
			if (destroyedSupportItem == null)
			{
				player.sendPacket(SystemMessageId.INCORRECT_ITEM_COUNT_2);
				PunishmentManager.handleIllegalPlayerAction(player, player + " tried to enchant with a support item he doesn't have", GeneralConfig.DEFAULT_PUNISH);
				player.setActiveEnchantItemId(Player.ID_NONE);
				player.sendPacket(new EnchantResult(2, 0, 0));
				return;
			}
			
			if (destroyedSupportItem.getCount() > 0)
			{
				iu.addModifiedItem(destroyedSupportItem);
			}
			else
			{
				iu.addRemovedItem(destroyedSupportItem);
			}
		}
		
		synchronized (item)
		{
			// Last validation check.
			if ((item.getOwnerId() != player.getObjectId()) || !item.isEnchantable())
			{
				player.sendPacket(SystemMessageId.INAPPROPRIATE_ENCHANT_CONDITIONS);
				player.setActiveEnchantItemId(Player.ID_NONE);
				player.sendPacket(new EnchantResult(2, 0, 0));
				return;
			}
			
			final EnchantResultType resultType = scrollTemplate.calculateSuccess(player, item, supportTemplate);
			switch (resultType)
			{
				case ERROR:
				{
					player.sendPacket(SystemMessageId.INAPPROPRIATE_ENCHANT_CONDITIONS);
					player.setActiveEnchantItemId(Player.ID_NONE);
					player.sendPacket(new EnchantResult(2, 0, 0));
					break;
				}
				case SUCCESS:
				{
					Skill enchant4Skill = null;
					final ItemTemplate it = item.getTemplate();
					
					// Increase enchant level only if scroll's base template has chance, some armors can success over +20 but they shouldn't have increased.
					if (scrollTemplate.getChance(player, item) > 0)
					{
						item.setEnchantLevel(item.getEnchantLevel() + 1);
						iu.addModifiedItem(item);
						item.updateDatabase();
					}
					
					player.sendPacket(new EnchantResult(0, 0, 0));
					if (GeneralConfig.LOG_ITEM_ENCHANTS)
					{
						final StringBuilder sb = new StringBuilder();
						if (item.isEnchanted())
						{
							if (support == null)
							{
								LOGGER_ENCHANT.info(sb.append("Success, Character:").append(player.getName()).append(" [").append(player.getObjectId()).append("] Account:").append(player.getAccountName()).append(" IP:").append(player.getIPAddress()).append(", +").append(item.getEnchantLevel()).append(" ").append(item.getName()).append("(").append(item.getCount()).append(") [").append(item.getObjectId()).append("], ").append(scroll.getName()).append("(").append(scroll.getCount()).append(") [").append(scroll.getObjectId()).append("]").toString());
							}
							else
							{
								LOGGER_ENCHANT.info(sb.append("Success, Character:").append(player.getName()).append(" [").append(player.getObjectId()).append("] Account:").append(player.getAccountName()).append(" IP:").append(player.getIPAddress()).append(", +").append(item.getEnchantLevel()).append(" ").append(item.getName()).append("(").append(item.getCount()).append(") [").append(item.getObjectId()).append("], ").append(scroll.getName()).append("(").append(scroll.getCount()).append(") [").append(scroll.getObjectId()).append("], ").append(support.getName()).append("(").append(support.getCount()).append(") [").append(support.getObjectId()).append("]").toString());
							}
						}
						else if (support == null)
						{
							LOGGER_ENCHANT.info(sb.append("Success, Character:").append(player.getName()).append(" [").append(player.getObjectId()).append("] Account:").append(player.getAccountName()).append(" IP:").append(player.getIPAddress()).append(", ").append(item.getName()).append("(").append(item.getCount()).append(") [").append(item.getObjectId()).append("], ").append(scroll.getName()).append("(").append(scroll.getCount()).append(") [").append(scroll.getObjectId()).append("]").toString());
						}
						else
						{
							LOGGER_ENCHANT.info(sb.append("Success, Character:").append(player.getName()).append(" [").append(player.getObjectId()).append("] Account:").append(player.getAccountName()).append(" IP:").append(player.getIPAddress()).append(", ").append(item.getName()).append("(").append(item.getCount()).append(") [").append(item.getObjectId()).append("], ").append(scroll.getName()).append("(").append(scroll.getCount()).append(") [").append(scroll.getObjectId()).append("], ").append(support.getName()).append("(").append(support.getCount()).append(") [").append(support.getObjectId()).append("]").toString());
						}
					}
					
					// Announce the success.
					final int minEnchantAnnounce = item.isArmor() ? 6 : 7;
					final int maxEnchantAnnounce = item.isArmor() ? 0 : 15;
					if ((item.getEnchantLevel() == minEnchantAnnounce) || (item.getEnchantLevel() == maxEnchantAnnounce))
					{
						final SystemMessage sm = new SystemMessage(SystemMessageId.C1_HAS_SUCCESSFULLY_ENCHANTED_A_S2_S3);
						sm.addString(player.getName());
						sm.addInt(item.getEnchantLevel());
						sm.addItemName(item);
						player.broadcastPacket(sm);
						
						final Skill skill = CommonSkill.FIREWORK.getSkill();
						if (skill != null)
						{
							player.broadcastSkillPacket(new MagicSkillUse(player, player, skill.getId(), skill.getLevel(), skill.getHitTime(), skill.getReuseDelay()), player);
						}
					}
					
					if ((item.isArmor()) && (item.getEnchantLevel() == 4) && item.isEquipped())
					{
						enchant4Skill = it.getEnchant4Skill();
						if (enchant4Skill != null)
						{
							// Add skills bestowed from +4 armor.
							player.addSkill(enchant4Skill, false);
							player.sendSkillList();
						}
					}
					
					player.broadcastUserInfo(); // Update user info.
					break;
				}
				case FAILURE:
				{
					if (scrollTemplate.isSafe())
					{
						// Safe enchant: Remain old value.
						player.sendPacket(SystemMessageId.ENCHANT_FAILED_THE_ENCHANT_LEVEL_FOR_THE_CORRESPONDING_ITEM_WILL_BE_EXACTLY_RETAINED);
						player.sendPacket(new EnchantResult(5, 0, 0));
						if (GeneralConfig.LOG_ITEM_ENCHANTS)
						{
							final StringBuilder sb = new StringBuilder();
							if (item.isEnchanted())
							{
								if (support == null)
								{
									LOGGER_ENCHANT.info(sb.append("Safe Fail, Character:").append(player.getName()).append(" [").append(player.getObjectId()).append("] Account:").append(player.getAccountName()).append(" IP:").append(player.getIPAddress()).append(", +").append(item.getEnchantLevel()).append(" ").append(item.getName()).append("(").append(item.getCount()).append(") [").append(item.getObjectId()).append("], ").append(scroll.getName()).append("(").append(scroll.getCount()).append(") [").append(scroll.getObjectId()).append("]").toString());
								}
								else
								{
									LOGGER_ENCHANT.info(sb.append("Safe Fail, Character:").append(player.getName()).append(" [").append(player.getObjectId()).append("] Account:").append(player.getAccountName()).append(" IP:").append(player.getIPAddress()).append(", +").append(item.getEnchantLevel()).append(" ").append(item.getName()).append("(").append(item.getCount()).append(") [").append(item.getObjectId()).append("], ").append(scroll.getName()).append("(").append(scroll.getCount()).append(") [").append(scroll.getObjectId()).append("], ").append(support.getName()).append("(").append(support.getCount()).append(") [").append(support.getObjectId()).append("]").toString());
								}
							}
							else if (support == null)
							{
								LOGGER_ENCHANT.info(sb.append("Safe Fail, Character:").append(player.getName()).append(" [").append(player.getObjectId()).append("] Account:").append(player.getAccountName()).append(" IP:").append(player.getIPAddress()).append(", ").append(item.getName()).append("(").append(item.getCount()).append(") [").append(item.getObjectId()).append("], ").append(scroll.getName()).append("(").append(scroll.getCount()).append(") [").append(scroll.getObjectId()).append("]").toString());
							}
							else
							{
								LOGGER_ENCHANT.info(sb.append("Safe Fail, Character:").append(player.getName()).append(" [").append(player.getObjectId()).append("] Account:").append(player.getAccountName()).append(" IP:").append(player.getIPAddress()).append(", ").append(item.getName()).append("(").append(item.getCount()).append(") [").append(item.getObjectId()).append("], ").append(scroll.getName()).append("(").append(scroll.getCount()).append(") [").append(scroll.getObjectId()).append("], ").append(support.getName()).append("(").append(support.getCount()).append(") [").append(support.getObjectId()).append("]").toString());
							}
						}
					}
					else
					{
						// Unequip item on enchant failure to avoid item skills stack.
						if (item.isEquipped())
						{
							if (item.isEnchanted())
							{
								final SystemMessage sm = new SystemMessage(SystemMessageId.THE_EQUIPMENT_S1_S2_HAS_BEEN_REMOVED);
								sm.addInt(item.getEnchantLevel());
								sm.addItemName(item);
								player.sendPacket(sm);
							}
							else
							{
								final SystemMessage sm = new SystemMessage(SystemMessageId.S1_HAS_BEEN_DISARMED);
								sm.addItemName(item);
								player.sendPacket(sm);
							}
							
							for (Item itm : player.getInventory().unEquipItemInSlotAndRecord(item.getLocationSlot()))
							{
								iu.addModifiedItem(itm);
							}
						}
						
						if (scrollTemplate.isBlessed())
						{
							// Blessed enchant: Clear enchant value.
							player.sendPacket(SystemMessageId.THE_BLESSED_ENCHANT_FAILED_THE_ENCHANT_VALUE_OF_THE_ITEM_BECAME_0);
							
							item.setEnchantLevel(0);
							
							iu.addModifiedItem(item);
							item.updateDatabase();
							player.sendPacket(new EnchantResult(3, 0, 0));
							
							if (GeneralConfig.LOG_ITEM_ENCHANTS)
							{
								final StringBuilder sb = new StringBuilder();
								if (item.isEnchanted())
								{
									if (support == null)
									{
										LOGGER_ENCHANT.info(sb.append("Blessed Fail, Character:").append(player.getName()).append(" [").append(player.getObjectId()).append("] Account:").append(player.getAccountName()).append(" IP:").append(player.getIPAddress()).append(", +").append(item.getEnchantLevel()).append(" ").append(item.getName()).append("(").append(item.getCount()).append(") [").append(item.getObjectId()).append("], ").append(scroll.getName()).append("(").append(scroll.getCount()).append(") [").append(scroll.getObjectId()).append("]").toString());
									}
									else
									{
										LOGGER_ENCHANT.info(sb.append("Blessed Fail, Character:").append(player.getName()).append(" [").append(player.getObjectId()).append("] Account:").append(player.getAccountName()).append(" IP:").append(player.getIPAddress()).append(", +").append(item.getEnchantLevel()).append(" ").append(item.getName()).append("(").append(item.getCount()).append(") [").append(item.getObjectId()).append("], ").append(scroll.getName()).append("(").append(scroll.getCount()).append(") [").append(scroll.getObjectId()).append("], ").append(support.getName()).append("(").append(support.getCount()).append(") [").append(support.getObjectId()).append("]").toString());
									}
								}
								else if (support == null)
								{
									LOGGER_ENCHANT.info(sb.append("Blessed Fail, Character:").append(player.getName()).append(" [").append(player.getObjectId()).append("] Account:").append(player.getAccountName()).append(" IP:").append(player.getIPAddress()).append(", ").append(item.getName()).append("(").append(item.getCount()).append(") [").append(item.getObjectId()).append("], ").append(scroll.getName()).append("(").append(scroll.getCount()).append(") [").append(scroll.getObjectId()).append("]").toString());
								}
								else
								{
									LOGGER_ENCHANT.info(sb.append("Blessed Fail, Character:").append(player.getName()).append(" [").append(player.getObjectId()).append("] Account:").append(player.getAccountName()).append(" IP:").append(player.getIPAddress()).append(", ").append(item.getName()).append("(").append(item.getCount()).append(") [").append(item.getObjectId()).append("], ").append(scroll.getName()).append("(").append(scroll.getCount()).append(") [").append(scroll.getObjectId()).append("], ").append(support.getName()).append("(").append(support.getCount()).append(") [").append(support.getObjectId()).append("]").toString());
								}
							}
						}
						else
						{
							// Enchant failed, destroy item.
							final Item destroyedItem = player.getInventory().destroyItem(ItemProcessType.DESTROY, item, player, null);
							if (destroyedItem == null)
							{
								// Unable to destroy item, cheater?
								PunishmentManager.handleIllegalPlayerAction(player, "Unable to delete item on enchant failure from " + player + ", possible cheater !", GeneralConfig.DEFAULT_PUNISH);
								player.setActiveEnchantItemId(Player.ID_NONE);
								player.sendPacket(new EnchantResult(2, 0, 0));
								if (GeneralConfig.LOG_ITEM_ENCHANTS)
								{
									final StringBuilder sb = new StringBuilder();
									if (item.isEnchanted())
									{
										if (support == null)
										{
											LOGGER_ENCHANT.info(sb.append("Unable to destroy, Character:").append(player.getName()).append(" [").append(player.getObjectId()).append("] Account:").append(player.getAccountName()).append(" IP:").append(player.getIPAddress()).append(", +").append(item.getEnchantLevel()).append(" ").append(item.getName()).append("(").append(item.getCount()).append(") [").append(item.getObjectId()).append("], ").append(scroll.getName()).append("(").append(scroll.getCount()).append(") [").append(scroll.getObjectId()).append("]").toString());
										}
										else
										{
											LOGGER_ENCHANT.info(sb.append("Unable to destroy, Character:").append(player.getName()).append(" [").append(player.getObjectId()).append("] Account:").append(player.getAccountName()).append(" IP:").append(player.getIPAddress()).append(", +").append(item.getEnchantLevel()).append(" ").append(item.getName()).append("(").append(item.getCount()).append(") [").append(item.getObjectId()).append("], ").append(scroll.getName()).append("(").append(scroll.getCount()).append(") [").append(scroll.getObjectId()).append("], ").append(support.getName()).append("(").append(support.getCount()).append(") [").append(support.getObjectId()).append("]").toString());
										}
									}
									else if (support == null)
									{
										LOGGER_ENCHANT.info(sb.append("Unable to destroy, Character:").append(player.getName()).append(" [").append(player.getObjectId()).append("] Account:").append(player.getAccountName()).append(" IP:").append(player.getIPAddress()).append(", ").append(item.getName()).append("(").append(item.getCount()).append(") [").append(item.getObjectId()).append("], ").append(scroll.getName()).append("(").append(scroll.getCount()).append(") [").append(scroll.getObjectId()).append("]").toString());
									}
									else
									{
										LOGGER_ENCHANT.info(sb.append("Unable to destroy, Character:").append(player.getName()).append(" [").append(player.getObjectId()).append("] Account:").append(player.getAccountName()).append(" IP:").append(player.getIPAddress()).append(", ").append(item.getName()).append("(").append(item.getCount()).append(") [").append(item.getObjectId()).append("], ").append(scroll.getName()).append("(").append(scroll.getCount()).append(") [").append(scroll.getObjectId()).append("], ").append(support.getName()).append("(").append(support.getCount()).append(") [").append(support.getObjectId()).append("]").toString());
									}
								}
								return;
							}
							
							iu.addRemovedItem(destroyedItem); // Item is gone, always tell the client to remove it.
							
							final int crystalId = item.getTemplate().getCrystalItemId();
							if ((crystalId != 0) && item.getTemplate().isCrystallizable())
							{
								int count = item.getCrystalCount() - ((item.getTemplate().getCrystalCount() + 1) / 2);
								count = count < 1 ? 1 : count;
								final Item crystals = player.getInventory().addItem(ItemProcessType.COMPENSATE, crystalId, count, player, item);
								
								final SystemMessage sm = new SystemMessage(SystemMessageId.YOU_HAVE_EARNED_S2_S1_S);
								sm.addItemName(crystalId);
								sm.addLong(count);
								player.sendPacket(sm);
								
								// Add the crystals gained, not the destroyed item.
								// Report as modified if stacked onto an existing crystal stack, otherwise as a new item.
								if (crystals.getLastChange() == Item.MODIFIED)
								{
									iu.addModifiedItem(crystals);
								}
								else
								{
									iu.addNewItem(crystals);
								}
								
								player.sendPacket(new EnchantResult(1, crystalId, count));
							}
							else
							{
								player.sendPacket(new EnchantResult(4, 0, 0));
							}
							
							if (GeneralConfig.LOG_ITEM_ENCHANTS)
							{
								final StringBuilder sb = new StringBuilder();
								if (item.isEnchanted())
								{
									if (support == null)
									{
										LOGGER_ENCHANT.info(sb.append("Fail, Character:").append(player.getName()).append(" [").append(player.getObjectId()).append("] Account:").append(player.getAccountName()).append(" IP:").append(player.getIPAddress()).append(", +").append(item.getEnchantLevel()).append(" ").append(item.getName()).append("(").append(item.getCount()).append(") [").append(item.getObjectId()).append("], ").append(scroll.getName()).append("(").append(scroll.getCount()).append(") [").append(scroll.getObjectId()).append("]").toString());
									}
									else
									{
										LOGGER_ENCHANT.info(sb.append("Fail, Character:").append(player.getName()).append(" [").append(player.getObjectId()).append("] Account:").append(player.getAccountName()).append(" IP:").append(player.getIPAddress()).append(", +").append(item.getEnchantLevel()).append(" ").append(item.getName()).append("(").append(item.getCount()).append(") [").append(item.getObjectId()).append("], ").append(scroll.getName()).append("(").append(scroll.getCount()).append(") [").append(scroll.getObjectId()).append("], ").append(support.getName()).append("(").append(support.getCount()).append(") [").append(support.getObjectId()).append("]").toString());
									}
								}
								else if (support == null)
								{
									LOGGER_ENCHANT.info(sb.append("Fail, Character:").append(player.getName()).append(" [").append(player.getObjectId()).append("] Account:").append(player.getAccountName()).append(" IP:").append(player.getIPAddress()).append(", ").append(item.getName()).append("(").append(item.getCount()).append(") [").append(item.getObjectId()).append("], ").append(scroll.getName()).append("(").append(scroll.getCount()).append(") [").append(scroll.getObjectId()).append("]").toString());
								}
								else
								{
									LOGGER_ENCHANT.info(sb.append("Fail, Character:").append(player.getName()).append(" [").append(player.getObjectId()).append("] Account:").append(player.getAccountName()).append(" IP:").append(player.getIPAddress()).append(", ").append(item.getName()).append("(").append(item.getCount()).append(") [").append(item.getObjectId()).append("], ").append(scroll.getName()).append("(").append(scroll.getCount()).append(") [").append(scroll.getObjectId()).append("], ").append(support.getName()).append("(").append(support.getCount()).append(") [").append(support.getObjectId()).append("]").toString());
								}
							}
						}
					}
					break;
				}
			}
			
			player.sendInventoryUpdate(iu);
			player.broadcastUserInfo();
			player.setActiveEnchantItemId(Player.ID_NONE);
		}
	}
}
