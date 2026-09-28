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

import org.l2jmobius.gameserver.config.custom.ClassBalanceConfig;
import org.l2jmobius.gameserver.config.custom.CommunityBoardConfig;
import org.l2jmobius.gameserver.config.custom.CustomBuffConfig;
import org.l2jmobius.gameserver.data.xml.SkillTreeData;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.Summon;
import org.l2jmobius.gameserver.model.effects.AbstractEffect;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.skill.EffectScope;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.targets.TargetType;
import org.l2jmobius.gameserver.model.stats.Stat;
import org.l2jmobius.gameserver.model.zone.ZoneId;

/**
 * Community Board buff templates: each character keeps a few named lists of buffs taken from its own class skill tree and can re-apply a whole list from the Community Board instead of casting every buff by hand.<br>
 * The only thing a template saves is casting time. Every use behaves like casting each buff:
 * <ul>
 * <li>the skill must still be known, at the level the character knows it now (stored templates hold skill ids only, never levels);</li>
 * <li>the skill must be in the character's class skill tree and pass the same structural buff filter used when it was added;</li>
 * <li>MP and consumed items are charged, the skill's reuse delay must have expired and is started again, and its cast conditions are tested;</li>
 * <li>the buff only lands on a target a real cast on that target would have reached.</li>
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
	 * Structural filter deciding whether a skill the player knows may be kept in a template. Deliberately strict: anything that is not a plain, positive, timed buff from the class skill tree is rejected.
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
		
		// Costs a template cannot reproduce faithfully.
		if ((skill.getHpConsume() > 0) || (skill.getChargeConsumeCount() > 0) || (skill.getMaxSoulConsumeCount() > 0) || (skill.getReferenceItemId() > 0))
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
	
	public boolean addBuff(Player player, int slot, int skillId)
	{
		if (!isValidSlot(slot))
		{
			return false;
		}
		
		synchronized (getLock(player))
		{
			final BuffTemplate template = getTemplate(player, slot);
			if (template.skillIds().contains(skillId))
			{
				return false;
			}
			
			if (template.skillIds().size() >= getMaxBuffs())
			{
				player.sendMessage("A template can hold at most " + getMaxBuffs() + " buffs.");
				return false;
			}
			
			final Skill skill = player.getKnownSkill(skillId);
			if (!isEligible(player, skill))
			{
				player.sendMessage("That skill cannot be added to a buff template.");
				return false;
			}
			
			final List<Integer> skillIds = new ArrayList<>(template.skillIds());
			skillIds.add(skillId);
			storeTemplate(player, slot, template.name(), skillIds);
			return true;
		}
	}
	
	public boolean removeBuff(Player player, int slot, int skillId)
	{
		if (!isValidSlot(slot))
		{
			return false;
		}
		
		synchronized (getLock(player))
		{
			final BuffTemplate template = getTemplate(player, slot);
			if (!template.skillIds().contains(skillId))
			{
				return false;
			}
			
			final List<Integer> skillIds = new ArrayList<>(template.skillIds());
			skillIds.remove(Integer.valueOf(skillId));
			storeTemplate(player, slot, template.name(), skillIds);
			return true;
		}
	}
	
	public boolean rename(Player player, int slot, String name)
	{
		final String trimmed = (name == null) ? "" : name.trim();
		if (!isValidSlot(slot) || !isValidName(trimmed))
		{
			player.sendMessage("Template names must be 1-" + MAX_NAME_LENGTH + " characters: letters, digits, spaces, '-' or '_'.");
			return false;
		}
		
		synchronized (getLock(player))
		{
			storeTemplate(player, slot, trimmed, getTemplate(player, slot).skillIds());
			return true;
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
			player.getVariables().remove(VARIABLE_PREFIX + slot);
		}
	}
	
	/**
	 * Checks the character state a template may be used in. Sends the reason to the player on failure.
	 * @param player the player
	 * @return {@code true} if the player may use a template right now
	 */
	public boolean canUseTemplates(Player player)
	{
		final String reason;
		if (player.isAlikeDead())
		{
			reason = "You cannot use buff templates while dead.";
		}
		else if (player.isInOlympiadMode() || player.inObserverMode() || player.isOnEvent())
		{
			reason = "You cannot use buff templates right now.";
		}
		else if (player.isInCombat() || player.isInDuel() || (player.getPvpFlag() > 0))
		{
			reason = "You cannot use buff templates during combat.";
		}
		else if (player.isCursedWeaponEquipped() || (CommunityBoardConfig.COMMUNITYBOARD_KARMA_DISABLED && (player.getKarma() > 0)))
		{
			reason = "You cannot use buff templates in a chaotic state.";
		}
		else if (player.isInsideZone(ZoneId.SIEGE) || player.isInsideZone(ZoneId.PVP) || player.isJailed() || (CommunityBoardConfig.COMMUNITYBOARD_BUFF_TEMPLATE_PEACE_ONLY && !player.isInsideZone(ZoneId.PEACE)))
		{
			reason = CommunityBoardConfig.COMMUNITYBOARD_BUFF_TEMPLATE_PEACE_ONLY ? "Buff templates can only be used inside a peace zone." : "You cannot use buff templates here.";
		}
		else if (player.isInStoreMode() || player.isFishing() || player.isMounted() || player.isFlying() || player.isFlyingMounted() || player.isTransformed() || player.isSitting() || player.isTeleporting())
		{
			reason = "You cannot use buff templates in your current state.";
		}
		else if (player.isCastingNow() || player.isCastingSimultaneouslyNow() || player.isAllSkillsDisabled())
		{
			reason = "You cannot use buff templates while casting or unable to use skills.";
		}
		else
		{
			return true;
		}
		
		player.sendMessage(reason);
		return false;
	}
	
	/**
	 * Applies a template the same way casting its buffs one by one would, minus the cast time.
	 * @param player the owner
	 * @param slot the template slot, 1-based
	 * @param onSummon {@code true} to buff the player's summon instead of the player
	 */
	public void useTemplate(Player player, int slot, boolean onSummon)
	{
		if (!isEnabled() || !isValidSlot(slot))
		{
			return;
		}
		
		synchronized (getLock(player))
		{
			if (!canUseTemplates(player))
			{
				return;
			}
			
			final long now = System.currentTimeMillis();
			final long cooldown = CommunityBoardConfig.COMMUNITYBOARD_BUFF_TEMPLATE_COOLDOWN * 1000L;
			final Long lastUse = _lastUse.get(player.getObjectId());
			if ((lastUse != null) && ((now - lastUse) < cooldown))
			{
				player.sendMessage("You must wait " + (((cooldown - (now - lastUse)) / 1000) + 1) + " more second(s) before using a buff template again.");
				return;
			}
			
			final BuffTemplate template = getTemplate(player, slot);
			if (template.skillIds().isEmpty())
			{
				player.sendMessage("This template is empty.");
				return;
			}
			
			final Creature target;
			if (onSummon)
			{
				final Summon summon = player.getSummon();
				if ((summon == null) || summon.isAlikeDead() || !summon.isSpawned() || (summon.getInstanceId() != player.getInstanceId()) || !player.isInsideRadius3D(summon, SUMMON_MAX_DISTANCE))
				{
					player.sendMessage("Your summon must be alive and near you.");
					return;
				}
				
				target = summon;
			}
			else
			{
				target = player;
			}
			
			// Dry run first, so the player is not charged for a template that cannot apply anything.
			final List<Skill> castable = new ArrayList<>();
			for (int skillId : template.skillIds())
			{
				final Skill skill = player.getKnownSkill(skillId);
				if (canCastOn(player, skill, target, false))
				{
					castable.add(skill);
				}
			}
			
			if (castable.isEmpty())
			{
				player.sendMessage("None of the buffs in this template can be used on " + (onSummon ? "your summon" : "yourself") + " right now.");
				return;
			}
			
			final int price = CommunityBoardConfig.COMMUNITYBOARD_BUFF_TEMPLATE_PRICE;
			if ((price > 0) && !player.destroyItemByItemId(ItemProcessType.FEE, CommunityBoardConfig.COMMUNITYBOARD_CURRENCY, price, player, true))
			{
				player.sendMessage("Not enough currency!");
				return;
			}
			
			_lastUse.put(player.getObjectId(), now);
			
			int applied = 0;
			for (Skill skill : castable)
			{
				// Re-checked with messages: MP drops and reuse starts as buffs are applied.
				if (!canCastOn(player, skill, target, true))
				{
					continue;
				}
				
				final int mpCost = player.getStat().getMpConsume(skill) + player.getStat().getMpInitialConsume(skill);
				if ((skill.getItemConsumeId() > 0) && !player.destroyItemByItemId(ItemProcessType.NONE, skill.getItemConsumeId(), skill.getItemConsumeCount(), player, true))
				{
					continue;
				}
				
				if (mpCost > 0)
				{
					player.getStatus().reduceMp(mpCost);
				}
				
				startReuse(player, skill);
				skill.activateSkill(player, Collections.singletonList(target));
				applied++;
			}
			
			player.broadcastStatusUpdate();
			
			final int skipped = template.skillIds().size() - applied;
			player.sendMessage("Template \"" + template.name() + "\": " + applied + " buff(s) applied" + (skipped > 0 ? ", " + skipped + " skipped (unknown, not usable, on reuse, or not enough MP/items)." : "."));
		}
	}
	
	/**
	 * Everything a real cast of this buff on this target would check, except range/geodata for the player itself.
	 * @param player the caster
	 * @param skill the skill as currently known by the player, may be {@code null}
	 * @param target the player or its summon
	 * @param verbose whether failing conditions may send their system messages
	 * @return {@code true} if the buff can be applied now
	 */
	private boolean canCastOn(Player player, Skill skill, Creature target, boolean verbose)
	{
		if (!isEligible(player, skill) || player.isSkillDisabled(skill))
		{
			return false;
		}
		
		if (!skill.isStatic() && (skill.isMagic() ? player.isMuted() : player.isPhysicalMuted()))
		{
			return false;
		}
		
		final int mpCost = player.getStat().getMpConsume(skill) + player.getStat().getMpInitialConsume(skill);
		if (player.getCurrentMp() < mpCost)
		{
			return false;
		}
		
		if ((skill.getItemConsumeId() > 0) && (player.getInventory().getInventoryItemCount(skill.getItemConsumeId(), -1) < skill.getItemConsumeCount()))
		{
			return false;
		}
		
		if (verbose ? !skill.checkCondition(player, target, false) : !skill.checkPreConditions(player, target))
		{
			return false;
		}
		
		// The buff must be able to reach this target the way a real cast would (e.g. a self-only buff never reaches the summon).
		final List<WorldObject> targets = skill.getTargetList(player, false, target);
		return (targets != null) && targets.contains(target);
	}
	
	/**
	 * Starts the skill reuse exactly like {@link Creature#doCast(Skill)} does, without the skill mastery chance.
	 * @param player the caster
	 * @param skill the skill
	 */
	private void startReuse(Player player, Skill skill)
	{
		int reuseDelay;
		if (skill.isStaticReuse() || skill.isStatic())
		{
			reuseDelay = skill.getReuseDelay();
		}
		else if (skill.isMagic())
		{
			reuseDelay = (int) (skill.getReuseDelay() * player.calcStat(Stat.MAGIC_REUSE_RATE, 1, null, null));
		}
		else if (skill.isPhysical())
		{
			reuseDelay = (int) (skill.getReuseDelay() * player.calcStat(Stat.P_REUSE, 1, null, null));
		}
		else
		{
			reuseDelay = (int) (skill.getReuseDelay() * player.calcStat(Stat.DANCE_REUSE, 1, null, null));
		}
		
		reuseDelay = (int) (reuseDelay * ClassBalanceConfig.SKILL_REUSE_MULTIPLIERS[player.getPlayerClass().getId()]);
		if (reuseDelay > 1000)
		{
			player.addTimeStamp(skill, reuseDelay);
		}
		else if (reuseDelay > 10)
		{
			player.disableSkill(skill, reuseDelay);
		}
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
	
	public static BuffTemplateManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final BuffTemplateManager INSTANCE = new BuffTemplateManager();
	}
}
