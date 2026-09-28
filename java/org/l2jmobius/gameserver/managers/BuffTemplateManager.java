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

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.custom.CommunityBoardConfig;
import org.l2jmobius.gameserver.config.custom.CustomBuffConfig;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.data.xml.SkillTreeData;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.Summon;
import org.l2jmobius.gameserver.model.effects.AbstractEffect;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.skill.EffectScope;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.targets.TargetType;
import org.l2jmobius.gameserver.model.zone.ZoneId;

/**
 * Community Board buff templates: each character keeps a few named lists of buffs taken from its own class skill tree and can apply a whole list instantly from the Community Board instead of casting every buff by hand.<br>
 * Applying a template has no casting prerequisites (no MP, items, reuse or cast conditions). What keeps it cheat-proof is which buffs can be in a template at all, re-checked on every use:
 * <ul>
 * <li>the skill must still be known, at the level the character knows it now (stored templates hold skill ids only, never levels);</li>
 * <li>it must come from the character's own skill tree, be listed in Player.ini SkillDurationList and pass the structural buff filter;</li>
 * <li>it is only placed on the character or its own summon, never on anyone else.</li>
 * </ul>
 * Templates are stored in character variables, so they are saved and deleted together with the character.
 */
public class BuffTemplateManager
{
	public static final int MAX_NAME_LENGTH = 16;
	
	private static final String VARIABLE_PREFIX = "CB_BUFF_TEMPLATE_";
	private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9 _-]{1," + MAX_NAME_LENGTH + "}");
	
	/** Target types a template buff may have: the ones a player can land on itself or on its own summon. */
	private static final Set<TargetType> ALLOWED_TARGET_TYPES = EnumSet.of(TargetType.SELF, TargetType.ONE, TargetType.PARTY, TargetType.PARTY_MEMBER, TargetType.PARTY_CLAN, TargetType.CLAN, TargetType.CLAN_MEMBER, TargetType.SUMMON, TargetType.SERVITOR);
	
	/** A summon farther than this cannot be buffed through a template, whatever the skill's own range. */
	private static final int SUMMON_MAX_DISTANCE = 900;
	
	// Serializes edits and uses per character, so parallel bypasses cannot double-apply or race the cooldown.
	private static final int LOCK_STRIPES = 64;
	private final Object[] _locks = new Object[LOCK_STRIPES];
	private final Map<Integer, Long> _lastUse = new ConcurrentHashMap<>();
	
	protected BuffTemplateManager()
	{
		for (int i = 0; i < LOCK_STRIPES; i++)
		{
			_locks[i] = new Object();
		}
	}
	
	public boolean isEnabled()
	{
		return CommunityBoardConfig.COMMUNITYBOARD_ENABLE_BUFF_TEMPLATES;
	}
	
	public int getSlotCount()
	{
		return CommunityBoardConfig.COMMUNITYBOARD_BUFF_TEMPLATE_SLOTS;
	}
	
	public int getMaxBuffs()
	{
		return CommunityBoardConfig.COMMUNITYBOARD_BUFF_TEMPLATE_MAX_BUFFS;
	}
	
	public boolean isValidSlot(int slot)
	{
		return (slot >= 1) && (slot <= getSlotCount());
	}
	
	/**
	 * @param player the owner
	 * @param slot the template slot, 1-based
	 * @return the stored template, or an empty default one for an unused or invalid slot
	 */
	public BuffTemplate getTemplate(Player player, int slot)
	{
		final String defaultName = "Template " + slot;
		if (!isValidSlot(slot))
		{
			return new BuffTemplate(slot, defaultName, Collections.emptyList());
		}
		
		final String value = player.getVariables().getString(VARIABLE_PREFIX + slot, "");
		final int separator = value.indexOf(';');
		if (separator < 0)
		{
			return new BuffTemplate(slot, defaultName, Collections.emptyList());
		}
		
		final String name = value.substring(0, separator);
		final List<Integer> skillIds = new ArrayList<>();
		for (String id : value.substring(separator + 1).split(","))
		{
			try
			{
				final int skillId = Integer.parseInt(id.trim());
				if ((skillId > 0) && !skillIds.contains(skillId) && (skillIds.size() < getMaxBuffs()))
				{
					skillIds.add(skillId);
				}
			}
			catch (NumberFormatException e)
			{
				// Ignore garbage, the next save rewrites the value cleanly.
			}
		}
		
		return new BuffTemplate(slot, isValidName(name) ? name : defaultName, Collections.unmodifiableList(skillIds));
	}
	
	private void storeTemplate(Player player, int slot, String name, List<Integer> skillIds)
	{
		final StringBuilder sb = new StringBuilder(name).append(';');
		for (int i = 0; i < skillIds.size(); i++)
		{
			if (i > 0)
			{
				sb.append(',');
			}
			
			sb.append(skillIds.get(i));
		}
		
		player.getVariables().set(VARIABLE_PREFIX + slot, sb.toString());
	}
	
	public static boolean isValidName(String name)
	{
		return (name != null) && NAME_PATTERN.matcher(name).matches() && !name.isBlank();
	}
	
	/**
	 * Structural filter deciding whether a skill the player knows may be kept in a template. Deliberately strict: anything that is not a plain, positive, timed buff from the class skill tree, listed in Player.ini SkillDurationList, is rejected.
	 * @param player the owner
	 * @param skill the skill as the player knows it now
	 * @return {@code true} if the skill may be placed in (and applied from) a template
	 */
	public boolean isEligible(Player player, Skill skill)
	{
		if ((skill == null) || CommunityBoardConfig.COMMUNITYBOARD_BUFF_TEMPLATE_FORBIDDEN_SKILLS.contains(skill.getId()))
		{
			return false;
		}
		
		// Only buffs listed in Player.ini SkillDurationList (loaded only while EnableModifySkillDuration is on).
		if (!PlayerConfig.ENABLE_MODIFY_SKILL_DURATION || (PlayerConfig.SKILL_DURATION_LIST == null) || !PlayerConfig.SKILL_DURATION_LIST.containsKey(skill.getId()))
		{
			return false;
		}
		
		// Must be the exact skill (id and level) the player currently knows.
		final Skill known = player.getKnownSkill(skill.getId());
		if ((known == null) || (known.getLevel() != skill.getLevel()))
		{
			return false;
		}
		
		// Plain timed active buff: no toggles, passives, channeling, triggered, self-continuous or instant skills.
		if (!skill.isActive() || !skill.isContinuous() || skill.isSelfContinuous() || skill.isToggle() || skill.isPassive() || skill.isChanneling() || skill.isTriggeredSkill())
		{
			return false;
		}
		
		if (skill.isDebuff() || (skill.getEffectPoint() < 0) || skill.hasNegativeEffect() || skill.isTransformation() || skill.isSuicideAttack())
		{
			return false;
		}
		
		if (skill.isHeroSkill() || skill.isGMSkill() || skill.is7Signs() || skill.isClanSkill() || skill.isExcludedFromCheck())
		{
			return false;
		}
		
		if ((skill.getAbnormalTime() <= 0) || skill.isAbnormalInstant())
		{
			return false;
		}
		
		if (!ALLOWED_TARGET_TYPES.contains(skill.getTargetType()) || !hasOnlyAllowedEffects(skill))
		{
			return false;
		}
		
		// Only skills from the character's own skill tree, or learned from a Custom Buff book (no item, GM, passive tree or otherwise granted skills).
		return SkillTreeData.getInstance().isSkillAllowed(player, skill) || CustomBuffConfig.SKILLS.contains(skill.getId());
	}
	
	/**
	 * @param skill the skill
	 * @return {@code true} if every effect of the skill, in every scope, is listed in the configured allow list, and it has at least one general effect
	 */
	private boolean hasOnlyAllowedEffects(Skill skill)
	{
		boolean hasGeneralEffect = false;
		for (EffectScope scope : EffectScope.values())
		{
			final List<AbstractEffect> effects = skill.getEffects(scope);
			if (effects == null)
			{
				continue;
			}
			
			for (AbstractEffect effect : effects)
			{
				if (effect == null)
				{
					continue;
				}
				
				if (!CommunityBoardConfig.COMMUNITYBOARD_BUFF_TEMPLATE_ALLOWED_EFFECTS.contains(effect.getName()))
				{
					return false;
				}
				
				if (scope == EffectScope.GENERAL)
				{
					hasGeneralEffect = true;
				}
			}
		}
		
		return hasGeneralEffect;
	}
	
	/**
	 * @param player the owner
	 * @return every skill the player currently knows that may be placed in a template, sorted by name
	 */
	public List<Skill> getEligibleBuffs(Player player)
	{
		final List<Skill> result = new ArrayList<>();
		for (Skill skill : player.getAllSkills())
		{
			if (isEligible(player, skill))
			{
				result.add(skill);
			}
		}
		
		result.sort((a, b) -> String.valueOf(a.getName()).compareToIgnoreCase(String.valueOf(b.getName())));
		return result;
	}
	
	/**
	 * @param player the owner
	 * @param slot the template slot, 1-based
	 * @param skillId the buff to add
	 * @return {@code null} on success, otherwise the reason it was not added
	 */
	public String addBuff(Player player, int slot, int skillId)
	{
		if (!isValidSlot(slot))
		{
			return "Invalid template.";
		}
		
		synchronized (getLock(player))
		{
			final BuffTemplate template = getTemplate(player, slot);
			if (template.skillIds().contains(skillId))
			{
				return null;
			}
			
			if (template.skillIds().size() >= getMaxBuffs())
			{
				return "A template can hold at most " + getMaxBuffs() + " buffs.";
			}
			
			final Skill skill = player.getKnownSkill(skillId);
			if (!isEligible(player, skill))
			{
				return "That skill cannot be added to a buff template.";
			}
			
			final List<Integer> skillIds = new ArrayList<>(template.skillIds());
			skillIds.add(skillId);
			storeTemplate(player, slot, template.name(), skillIds);
			return null;
		}
	}
	
	/**
	 * Adds every eligible buff the template does not hold yet, in name order, until the template is full.
	 * @param player the owner
	 * @param slot the template slot, 1-based
	 * @return the number of buffs added
	 */
	public int addAllBuffs(Player player, int slot)
	{
		if (!isValidSlot(slot))
		{
			return 0;
		}
		
		synchronized (getLock(player))
		{
			final BuffTemplate template = getTemplate(player, slot);
			final List<Integer> skillIds = new ArrayList<>(template.skillIds());
			int added = 0;
			for (Skill skill : getEligibleBuffs(player))
			{
				if (skillIds.size() >= getMaxBuffs())
				{
					break;
				}
				
				if (!skillIds.contains(skill.getId()))
				{
					skillIds.add(skill.getId());
					added++;
				}
			}
			
			if (added > 0)
			{
				storeTemplate(player, slot, template.name(), skillIds);
			}
			
			return added;
		}
	}
	
	public void removeBuff(Player player, int slot, int skillId)
	{
		if (!isValidSlot(slot))
		{
			return;
		}
		
		synchronized (getLock(player))
		{
			final BuffTemplate template = getTemplate(player, slot);
			if (template.skillIds().contains(skillId))
			{
				final List<Integer> skillIds = new ArrayList<>(template.skillIds());
				skillIds.remove(Integer.valueOf(skillId));
				storeTemplate(player, slot, template.name(), skillIds);
			}
		}
	}
	
	/**
	 * @param player the owner
	 * @param slot the template slot, 1-based
	 * @param name the new name
	 * @return {@code null} on success, otherwise the reason it was not renamed
	 */
	public String rename(Player player, int slot, String name)
	{
		final String trimmed = (name == null) ? "" : name.trim();
		if (!isValidSlot(slot) || !isValidName(trimmed))
		{
			return "Names must be 1-" + MAX_NAME_LENGTH + " characters: letters, digits, spaces, '-' or '_'.";
		}
		
		synchronized (getLock(player))
		{
			storeTemplate(player, slot, trimmed, getTemplate(player, slot).skillIds());
			return null;
		}
	}
	
	public void clear(Player player, int slot)
	{
		if (!isValidSlot(slot))
		{
			return;
		}
		
		synchronized (getLock(player))
		{
			storeTemplate(player, slot, getTemplate(player, slot).name(), Collections.emptyList());
		}
	}
	
	/**
	 * Checks the character state a template may be used in.
	 * @param player the player
	 * @return {@code null} if the player may use a template right now, otherwise the reason
	 */
	public String checkUseConditions(Player player)
	{
		if (player.isAlikeDead())
		{
			return "You cannot use buff templates while dead.";
		}
		
		if (player.isInOlympiadMode() || player.inObserverMode() || player.isOnEvent())
		{
			return "You cannot use buff templates right now.";
		}
		
		if (player.isInCombat() || player.isInDuel() || (player.getPvpFlag() > 0))
		{
			return "You cannot use buff templates during combat.";
		}
		
		if (player.isCursedWeaponEquipped() || (CommunityBoardConfig.COMMUNITYBOARD_KARMA_DISABLED && (player.getKarma() > 0)))
		{
			return "You cannot use buff templates in a chaotic state.";
		}
		
		if (player.isInsideZone(ZoneId.SIEGE) || player.isInsideZone(ZoneId.PVP) || player.isJailed() || (CommunityBoardConfig.COMMUNITYBOARD_BUFF_TEMPLATE_PEACE_ONLY && !player.isInsideZone(ZoneId.PEACE)))
		{
			return CommunityBoardConfig.COMMUNITYBOARD_BUFF_TEMPLATE_PEACE_ONLY ? "Buff templates can only be used inside a peace zone." : "You cannot use buff templates here.";
		}
		
		if (player.isInStoreMode() || player.isFishing() || player.isMounted() || player.isFlying() || player.isFlyingMounted() || player.isTransformed() || player.isTeleporting())
		{
			return "You cannot use buff templates in your current state.";
		}
		
		return null;
	}
	
	/**
	 * Applies every buff of a template instantly. No MP, items, reuse or cast conditions are involved; a buff is only skipped when the character does not know it (anymore) or it cannot be placed on the chosen target.
	 * @param player the owner
	 * @param slot the template slot, 1-based
	 * @param onSummon {@code true} to buff the player's summon instead of the player
	 * @return whether anything was applied, with a summary of what was applied and, per buff, why anything was skipped
	 */
	public UseResult useTemplate(Player player, int slot, boolean onSummon)
	{
		if (!isEnabled() || !isValidSlot(slot))
		{
			return new UseResult(false, "Invalid template.");
		}
		
		synchronized (getLock(player))
		{
			final String reason = checkUseConditions(player);
			if (reason != null)
			{
				return new UseResult(false, reason);
			}
			
			final long now = System.currentTimeMillis();
			final long cooldown = CommunityBoardConfig.COMMUNITYBOARD_BUFF_TEMPLATE_COOLDOWN * 1000L;
			final Long lastUse = _lastUse.get(player.getObjectId());
			if ((lastUse != null) && ((now - lastUse) < cooldown))
			{
				return new UseResult(false, "Please wait " + (((cooldown - (now - lastUse)) / 1000) + 1) + " second(s) before using a buff template again.");
			}
			
			final BuffTemplate template = getTemplate(player, slot);
			if (template.skillIds().isEmpty())
			{
				return new UseResult(false, "\"" + template.name() + "\" is empty. Press Edit to add buffs.");
			}
			
			final Creature target;
			if (onSummon)
			{
				final Summon summon = player.getSummon();
				if ((summon == null) || summon.isAlikeDead() || !summon.isSpawned() || (summon.getInstanceId() != player.getInstanceId()) || !player.isInsideRadius3D(summon, SUMMON_MAX_DISTANCE))
				{
					return new UseResult(false, "Your summon must be alive and near you.");
				}
				
				target = summon;
			}
			else
			{
				target = player;
			}
			
			// Work out what lands before charging anything.
			final List<Skill> toApply = new ArrayList<>();
			final List<String> skipped = new ArrayList<>();
			for (int skillId : template.skillIds())
			{
				final Skill skill = player.getKnownSkill(skillId);
				if (skill == null)
				{
					skipped.add(skillName(skillId) + " (not learned)");
				}
				else if (!isEligible(player, skill))
				{
					skipped.add(skill.getName() + " (no longer allowed)");
				}
				else if (!canTarget(skill, target))
				{
					skipped.add(skill.getName() + (onSummon ? " (self only)" : " (summon only)"));
				}
				else
				{
					toApply.add(skill);
				}
			}
			
			if (toApply.isEmpty())
			{
				return new UseResult(false, "Nothing to apply. Skipped: " + String.join(", ", skipped) + ".");
			}
			
			final int price = CommunityBoardConfig.COMMUNITYBOARD_BUFF_TEMPLATE_PRICE;
			if ((price > 0) && !player.destroyItemByItemId(ItemProcessType.FEE, CommunityBoardConfig.COMMUNITYBOARD_CURRENCY, price, player, true))
			{
				return new UseResult(false, "Not enough currency!");
			}
			
			_lastUse.put(player.getObjectId(), now);
			for (Skill skill : toApply)
			{
				skill.applyEffects(player, target);
				if (skill.hasEffects(EffectScope.SELF))
				{
					skill.applyEffects(player, player, true, false, true, 0);
				}
			}
			
			final StringBuilder sb = new StringBuilder();
			sb.append('"').append(template.name()).append("\": applied ").append(toApply.size()).append(" buff(s) to ").append(onSummon ? "your summon" : "you").append('.');
			if (!skipped.isEmpty())
			{
				sb.append(" Skipped: ").append(String.join(", ", skipped)).append('.');
			}
			
			final String result = sb.toString();
			player.sendMessage(result);
			return new UseResult(true, result);
		}
	}
	
	/**
	 * @param skill the buff
	 * @param target the player or its summon
	 * @return {@code false} only for buffs that can never be placed on that kind of target (self-only buffs on a summon, summon-only buffs on a player)
	 */
	private boolean canTarget(Skill skill, Creature target)
	{
		switch (skill.getTargetType())
		{
			case SELF:
			{
				return target.isPlayer();
			}
			case SUMMON:
			{
				return target.isSummon();
			}
			case SERVITOR:
			{
				return target.isServitor();
			}
			default:
			{
				return true;
			}
		}
	}
	
	private static String skillName(int skillId)
	{
		final Skill skill = SkillData.getInstance().getSkill(skillId, 1);
		return skill != null ? skill.getName() : "Skill " + skillId;
	}
	
	private Object getLock(Player player)
	{
		return _locks[Math.floorMod(player.getObjectId(), LOCK_STRIPES)];
	}
	
	/**
	 * A stored template. Holds skill ids only; levels are always taken from what the player knows at use time.
	 * @param slot the 1-based slot
	 * @param name the display name
	 * @param skillIds the buff skill ids in application order
	 */
	public record BuffTemplate(int slot, String name, List<Integer> skillIds)
	{
	}
	
	/**
	 * Outcome of {@link BuffTemplateManager#useTemplate(Player, int, boolean)}.
	 * @param applied whether at least one buff was applied
	 * @param message what happened, for the player
	 */
	public record UseResult(boolean applied, String message)
	{
	}
	
	public static BuffTemplateManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final BuffTemplateManager INSTANCE = new BuffTemplateManager();
	}
}
