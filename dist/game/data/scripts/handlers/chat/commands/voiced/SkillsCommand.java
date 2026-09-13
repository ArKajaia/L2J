package handlers.chat.commands.voiced;

import java.util.LinkedHashMap;
import java.util.Map;

import org.l2jmobius.gameserver.ai.AttackableAI;
import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.npc.MonsterArchetype;
import org.l2jmobius.gameserver.model.effects.EffectType;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

/**
 * Voiced command (".skills") showing every skill added to a targeted NPC by this mod's systems: random passive mods (Monster#addRandomPassiveSkill) and archetype-granted skills (AttackableAI#grantArchetypeSkill), merged, separated into Active/Passive sections, each with a short auto-generated
 * description.
 * @author YourName
 */
public class SkillsCommand implements IVoicedCommandHandler
{
	private static final String[] VOICED_COMMANDS =
	{
		"skills"
	};
	
	// Same NPC variable keys used by the systems that grant these skills - kept in sync with
	// Monster#addRandomPassiveSkill()/clearRandomPassiveSkills() and AttackableAI.VAR_GRANTED_SKILL_IDS.
	private static final String VAR_PASSIVE_SKILL_IDS = "PASSIVE_SKILL_IDS";
	private static final String VAR_ARCHETYPE_SKILL_IDS = "AI_ARCHETYPE_GRANTED_SKILL_IDS";
	
	@Override
	public boolean onCommand(String command, Player player, String params)
	{
		return handleCommand(command, player, params);
	}
	
	@Override
	public boolean useVoicedCommand(String command, Player activeChar, String params)
	{
		return handleCommand(command, activeChar, params);
	}
	
	private boolean handleCommand(String command, Player player, String params)
	{
		if (!command.equals("skills"))
		{
			return true;
		}
		
		if ((player.getTarget() == null) || !player.getTarget().isNpc())
		{
			player.sendMessage("You must target a monster to view its skills.");
			return true;
		}
		
		final Npc npc = (Npc) player.getTarget();
		final Map<Integer, SkillSource> gathered = new LinkedHashMap<>();
		
		if (RatesConfig.RANDOM_PASSIVE_SKILLS_ENABLED)
		{
			collectSkills(npc, VAR_PASSIVE_SKILL_IDS, "Random Passive", gathered);
		}
		
		collectSkills(npc, VAR_ARCHETYPE_SKILL_IDS, "Archetype Grant", gathered);
		
		if (gathered.isEmpty())
		{
			player.sendMessage(npc.getName() + " has no mod-granted skills.");
			return true;
		}
		
		showSkillsWindow(player, npc, gathered);
		return true;
	}
	
	/**
	 * Reads a comma-separated skill ID list from an NPC variable and adds each resolvable known skill into {@code gathered}, tagged with {@code sourceLabel}. If a skill ID was already gathered from an earlier call (e.g. granted by both systems, however unlikely), the first source tag wins rather
	 * than being overwritten.
	 * @param npc the NPC to read skills from
	 * @param variableKey the NPC variable holding the comma-separated skill ID list
	 * @param sourceLabel the label to show next to skills found via this variable
	 * @param gathered the running map of skillId -> (skill, sourceLabel) being built up
	 */
	private void collectSkills(Npc npc, String variableKey, String sourceLabel, Map<Integer, SkillSource> gathered)
	{
		final String idsAsString = npc.getVariables().getString(variableKey, "");
		if (idsAsString.isEmpty())
		{
			return;
		}
		
		for (String idStr : idsAsString.split(","))
		{
			try
			{
				final int skillId = Integer.parseInt(idStr.trim());
				final Skill skill = npc.getKnownSkill(skillId);
				if (skill == null)
				{
					continue;
				}
				
				gathered.putIfAbsent(skillId, new SkillSource(skill, sourceLabel));
			}
			catch (NumberFormatException e)
			{
				// ignore malformed entry
			}
		}
	}
	
	private void showSkillsWindow(Player player, Npc npc, Map<Integer, SkillSource> gathered)
	{
		final StringBuilder activeRows = new StringBuilder();
		final StringBuilder passiveRows = new StringBuilder();
		int activeCount = 0;
		int passiveCount = 0;
		
		for (SkillSource entry : gathered.values())
		{
			if (entry.skill.isPassive())
			{
				appendSkillRow(passiveRows, entry);
				passiveCount++;
			}
			else
			{
				appendSkillRow(activeRows, entry);
				activeCount++;
			}
		}
		
		final StringBuilder sb = new StringBuilder();
		sb.append("<html><body>");
		sb.append("<center><font color=\"LEVEL\">").append(npc.getName()).append("</font><br1>");
		sb.append("<font color=\"AAAAAA\">Mod-Granted Skills</font>");
		
		final String archetypeLabel = getArchetypeLabel(npc);
		if (archetypeLabel != null)
		{
			sb.append("<br1><font color=\"999999\">Archetype: ").append(archetypeLabel).append("</font>");
		}
		
		sb.append("</center><br>");
		sb.append("<img src=\"L2UI.SquareGray\" width=280 height=1><br>");
		
		sb.append("<font color=\"FFAA00\">Active Skills (").append(activeCount).append(")</font><br1>");
		sb.append("<table width=280 cellpadding=2 cellspacing=0>");
		sb.append(activeCount > 0 ? activeRows : "<tr><td><font color=\"777777\">None.</font></td></tr>");
		sb.append("</table><br>");
		
		sb.append("<img src=\"L2UI.SquareGray\" width=280 height=1><br>");
		
		sb.append("<font color=\"66CCFF\">Passive Skills (").append(passiveCount).append(")</font><br1>");
		sb.append("<table width=280 cellpadding=2 cellspacing=0>");
		sb.append(passiveCount > 0 ? passiveRows : "<tr><td><font color=\"777777\">None.</font></td></tr>");
		sb.append("</table>");
		
		sb.append("</body></html>");
		
		final NpcHtmlMessage html = new NpcHtmlMessage(0);
		html.setHtml(sb.toString());
		player.sendPacket(html);
	}
	
	private void appendSkillRow(StringBuilder sb, SkillSource entry)
	{
		final Skill skill = entry.skill;
		final String nameColor = skill.isPassive() ? "66CCFF" : "FFAA00";
		
		sb.append("<tr>");
		sb.append("<td width=32>");
		sb.append("<img src=\"").append(skill.getIcon()).append("\" width=32 height=32>");
		sb.append("</td>");
		sb.append("<td valign=top>");
		sb.append("<font color=\"").append(nameColor).append("\">").append(skill.getName()).append("</font>");
		// sb.append(" <font color=\"666666\">[").append(entry.sourceLabel).append("]</font><br1>");
		sb.append("<font color=\"999999\">Level ").append(skill.getLevel()).append("</font><br1>");
		sb.append("<font color=\"BBBBBB\">").append(buildShortDescription(skill)).append("</font>");
		sb.append("</td>");
		sb.append("</tr>");
	}
	
	/**
	 * Builds a short, one-line description from the skill's effect types. This codebase's skill data doesn't carry client-facing description text server-side (that's normally client string data in retail L2), so this is a best-effort summary rather than real tooltip text - swap in an actual
	 * getDescription()-style call here if your skill data has one.
	 * @param skill the skill to describe
	 * @return a short, human-readable summary of what the skill does
	 */
	private String buildShortDescription(Skill skill)
	{
		if (skill.hasEffectType(EffectType.HEAL))
		{
			return "Heals target's HP.";
		}
		if (skill.hasEffectType(EffectType.PHYSICAL_ATTACK, EffectType.PHYSICAL_ATTACK_HP_LINK))
		{
			return "Deals physical damage.";
		}
		if (skill.hasEffectType(EffectType.MAGICAL_ATTACK))
		{
			return "Deals magical damage.";
		}
		if (skill.hasEffectType(EffectType.DMG_OVER_TIME, EffectType.DMG_OVER_TIME_PERCENT))
		{
			return "Damages the target over time.";
		}
		if (skill.hasEffectType(EffectType.HP_DRAIN))
		{
			return "Drains HP from the target.";
		}
		if (skill.hasEffectType(EffectType.STUN))
		{
			return "Stuns the target.";
		}
		if (skill.hasEffectType(EffectType.ROOT))
		{
			return "Roots the target in place.";
		}
		if (skill.hasEffectType(EffectType.PARALYZE))
		{
			return "Paralyzes the target.";
		}
		if (skill.hasEffectType(EffectType.SLEEP))
		{
			return "Puts the target to sleep.";
		}
		if (skill.hasEffectType(EffectType.FEAR))
		{
			return "Terrifies the target, forcing it to flee.";
		}
		if (skill.hasEffectType(EffectType.MUTE))
		{
			return "Silences the target's spellcasting.";
		}
		if (skill.hasEffectType(EffectType.RESURRECTION))
		{
			return "Resurrects a fallen ally.";
		}
		if (skill.hasEffectType(EffectType.DISPEL, EffectType.DISPEL_BY_SLOT))
		{
			return "Removes buffs from the target.";
		}
		if (skill.isPassive())
		{
			return "Passive";
		}
		return "Buffs/Effects";
	}
	
	/**
	 * @param npc the NPC to check
	 * @return the resolved MonsterArchetype's display name if this NPC's AI is an AttackableAI, or {@code null} if it has none (not an Attackable, no AI attached, etc.)
	 */
	private String getArchetypeLabel(Npc npc)
	{
		if (!npc.hasAI() || !(npc.getAI() instanceof AttackableAI))
		{
			return null;
		}
		
		final MonsterArchetype archetype = ((AttackableAI) npc.getAI()).getArchetype();
		if (archetype == null)
		{
			return null;
		}
		
		return archetype.name().charAt(0) + archetype.name().substring(1).toLowerCase();
	}
	
	/** Pairs a resolved Skill with which system granted it, for display purposes only. */
	private static class SkillSource
	{
		private final Skill skill;
		// private final String sourceLabel;
		
		private SkillSource(Skill skill, String sourceLabel)
		{
			this.skill = skill;
			// this.sourceLabel = sourceLabel;
		}
	}
	
	@Override
	public String[] getCommandList()
	{
		return VOICED_COMMANDS;
	}
	
	@Override
	public String[] getVoicedCommandList()
	{
		return VOICED_COMMANDS;
	}
}