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
package handlers.bypass.communityboard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.l2jmobius.gameserver.data.custom.PassiveTreeData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.handler.CommunityBoardHandler;
import org.l2jmobius.gameserver.handler.IParseBoardHandler;
import org.l2jmobius.gameserver.managers.PassiveTreeManager;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.passivetree.PassiveNode;
import org.l2jmobius.gameserver.model.skill.Skill;

import handlers.chat.commands.voiced.PassiveTreeLinkVoiced;

/**
 * Community Board page for the passive tree, reached via Alt+B or .passives. A summary page: a button that opens the web planner for this character, the 5 template slots, the total stat bonuses, every skill granted by the allocated nodes, and a full-tree reset. Allocating and per-node respecs happen in the web planner.
 */
public class PassiveTreeBoard implements IParseBoardHandler
{
	// Mirrors STAT_LABEL in passive-tree.html exactly, so the "Total Bonuses" summary below
	// reads the same on both the web planner and this Community Board page.
	private static final Map<String, String> STAT_LABEL = new LinkedHashMap<>();
	static
	{
		STAT_LABEL.put("STR", "Strength");
		STAT_LABEL.put("DEX", "Dexterity");
		STAT_LABEL.put("CON", "Constitution");
		STAT_LABEL.put("INT", "Intelligence");
		STAT_LABEL.put("WIT", "Wit");
		STAT_LABEL.put("MEN", "Mentality");
		STAT_LABEL.put("MAXHP", "Maximum HP");
		STAT_LABEL.put("MAXMP", "Maximum MP");
		STAT_LABEL.put("MAXCP", "Maximum CP");
		STAT_LABEL.put("PATK_PCT", "Physical Attack");
		STAT_LABEL.put("PDEF_PCT", "Physical Defence");
		STAT_LABEL.put("MATK_PCT", "Magic Attack");
		STAT_LABEL.put("MDEF_PCT", "Magic Defence");
		STAT_LABEL.put("ATK_SPD_PCT", "Attack Speed");
		STAT_LABEL.put("CAST_SPD_PCT", "Casting Speed");
		STAT_LABEL.put("SHIELD_RATE_PCT", "Shield Block Rate");
		STAT_LABEL.put("SHIELD_DEF_PCT", "Shield Defence");
		STAT_LABEL.put("CRIT_RATE_ADD", "Critical Rate");
		STAT_LABEL.put("CRIT_DMG_PCT", "Critical Damage");
		STAT_LABEL.put("MCRIT_RATE_ADD", "Magic Critical Rate");
		STAT_LABEL.put("ACCURACY_ADD", "Accuracy");
		STAT_LABEL.put("EVASION_ADD", "Evasion");
		STAT_LABEL.put("MOVE_SPEED_ADD", "Movement Speed");
		STAT_LABEL.put("REFLECT_PCT", "Damage Reflected");
		STAT_LABEL.put("HP_REGEN_PCT", "HP Regeneration");
		STAT_LABEL.put("MP_REGEN_PCT", "MP Regeneration");
		STAT_LABEL.put("DROP_RATE_PCT", "Drop Rate");
		STAT_LABEL.put("SPOIL_RATE_PCT", "Spoil Rate");
		STAT_LABEL.put("EXP_RATE_PCT", "EXP Rate");
		STAT_LABEL.put("SP_RATE_PCT", "SP Rate");
		STAT_LABEL.put("INVENTORY_SLOTS_ADD", "Inventory Slots");
		STAT_LABEL.put("WEIGHT_LIMIT_PCT", "Weight Limit");
		STAT_LABEL.put("ADENA_RATE_PCT", "Adena Drop Amount");
		STAT_LABEL.put("LIFESTEAL_PCT", "Life Steal (melee)");
		STAT_LABEL.put("MANA_LEECH_PCT", "Mana Leech (melee)");
		STAT_LABEL.put("SKILL_DODGE_PCT", "Physical Skill Dodge");
		STAT_LABEL.put("MAGIC_REFLECT_PCT", "Magic Skill Reflect");
		STAT_LABEL.put("SKILL_REFLECT_PCT", "Physical Skill Reflect");
		STAT_LABEL.put("PVE_PDMG_PCT", "Physical Damage vs Monsters");
		STAT_LABEL.put("PVE_MDMG_PCT", "Magic Damage vs Monsters");
		STAT_LABEL.put("PVE_BOW_DMG_PCT", "Bow Damage vs Monsters");
		STAT_LABEL.put("PHYS_SKILL_POWER_PCT", "Physical Skill Power");
		STAT_LABEL.put("MCRIT_DMG_PCT", "Magic Critical Damage");
		STAT_LABEL.put("BLOW_RATE_PCT", "Blow Success Rate");
		STAT_LABEL.put("HEALING_RECEIVED_PCT", "Healing Received");
		STAT_LABEL.put("SKILL_CDR_PCT", "Skill Cooldown Reduction");
		STAT_LABEL.put("SPELL_CDR_PCT", "Spell Cooldown Reduction");
		STAT_LABEL.put("SPELL_MP_COST_RED_PCT", "Spell MP Cost Reduction");
		STAT_LABEL.put("CRIT_DMG_TAKEN_RED_PCT", "Critical Damage Taken Reduction");
		STAT_LABEL.put("INTERRUPT_RES_PCT", "Cast Interruption Resistance");
		STAT_LABEL.put("DEBUFF_RES_PCT", "Debuff Resistance");
		STAT_LABEL.put("MAXHP_PCT", "Maximum HP");
		STAT_LABEL.put("SERVITOR_SHARE_PCT", "Damage Taken Redirected to Servitor");
		STAT_LABEL.put("SHIELD_RATE_MUL_PCT", "More Shield Blocks");
	}

	// Keystone mechanics read as rules, not numbers ("%s" is the value). Mirrors KEYSTONE_TEXT in passive-tree.html.
	private static final Map<String, String> KEYSTONE_TEXT = new LinkedHashMap<>();
	static
	{
		KEYSTONE_TEXT.put("KS_MIND_OVER_MATTER", "%s%% of damage taken is drained from MP first");
		KEYSTONE_TEXT.put("KS_ARCANE_OVERLOAD", "Up to +%s%% spell damage as your MP runs low");
		KEYSTONE_TEXT.put("KS_STUN_IMMUNE", "Cannot be stunned");
		KEYSTONE_TEXT.put("KS_BLOOD_TOGGLES", "Toggle skills cost and drain HP instead of MP");
		KEYSTONE_TEXT.put("KS_BLOOD_MAGIC", "Skills cost HP instead of MP; +%s%% of max MP as max HP");
		KEYSTONE_TEXT.put("KS_POINT_BLANK", "Bow and spell damage +%1$s%% up close, -%1$s%% at long range");
		KEYSTONE_TEXT.put("KS_FAR_SHOT", "Bow and spell damage +%1$s%% at long range, -%1$s%% up close");
		KEYSTONE_TEXT.put("KS_MEN_STUN", "Stuns and other CON-resisted effects are resisted with MEN");
		KEYSTONE_TEXT.put("KS_PDEF_AS_MDEF", "M.Def is your P.Def -%s%%");
		KEYSTONE_TEXT.put("KS_SOUL_HARVEST", "%s%% chance per landed hit for a Force charge and a soul");
		KEYSTONE_TEXT.put("KS_CRIT_CAP", "Critical rate cap raised to %s");
		KEYSTONE_TEXT.put("KS_RESOLUTE", "Attacks never miss, but never critically hit");
		KEYSTONE_TEXT.put("KS_RAMPAGE", "Up to +%s%% P.Atk as your HP runs low");
		KEYSTONE_TEXT.put("KS_SPELL_LEECH", "%s%% of spell damage dealt heals you");
		KEYSTONE_TEXT.put("KS_ELDRITCH_BATTERY", "Max CP is 1; %s%% of it becomes max MP");
		KEYSTONE_TEXT.put("KS_PURITY", "Immune to poison and bleeding");
		KEYSTONE_TEXT.put("KS_OVERHEAL_CP", "%s%% of overhealing you cast becomes CP");
	}

	// Conditional bonuses ("PDEF_PCT@HEAVY") only apply in that situation. Mirrors COND_TEXT in passive-tree.html.
	private static final Map<String, String> COND_TEXT = Map.ofEntries(Map.entry("HEAVY", "in heavy armour"), Map.entry("LIGHT", "in light armour"), Map.entry("ROBE", "in a robe"), Map.entry("NOARMOR", "wearing no armour"), Map.entry("SHIELD", "with a shield"), Map.entry("BOW", "with a bow"), Map.entry("DAGGER", "with a dagger"), Map.entry("DUAL", "with dual swords"), Map.entry("SWORD", "with a sword"), Map.entry("BLUNT", "with a blunt weapon"), Map.entry("POLE", "with a polearm"), Map.entry("FIST", "with fist weapons"), Map.entry("LOWHP", "below 50% HP"), Map.entry("FULLHP", "above 90% HP"), Map.entry("NIGHT", "at night"), Map.entry("DAY", "during the day"));

	private static final String[] COMMAND =
	{
		"_bbspassives",
		"_bbspassives_weblink",
		"_bbspassives_reset",
		"_bbspassives_template"
	};

	@Override
	public boolean onCommand(String command, Player player)
	{
		if (player == null)
		{
			return false;
		}

		if (command.startsWith("_bbspassives_weblink"))
		{
			PassiveTreeLinkVoiced.sendPassiveTreeLink(player);
		}
		else if (command.startsWith("_bbspassives_reset"))
		{
			PassiveTreeManager.getInstance().resetTree(player);
		}
		else if (command.startsWith("_bbspassives_template"))
		{
			switchTemplate(player, command);
		}

		showSummary(player);
		return true;
	}

	/** Handles "_bbspassives_template N". The manager enforces peace zone and wait, and this tells the player why it said no. */
	private void switchTemplate(Player player, String command)
	{
		final PassiveTreeManager manager = PassiveTreeManager.getInstance();
		try
		{
			final int templateId = Integer.parseInt(command.substring("_bbspassives_template".length()).trim());
			final PassiveTreeManager.SwitchResult result = manager.switchTemplate(player, templateId);
			if (result != PassiveTreeManager.SwitchResult.OK)
			{
				player.sendMessage(manager.getSwitchFailureMessage(player, result));
			}
		}
		catch (NumberFormatException e)
		{
			player.sendMessage("There is no such template.");
		}
	}

	private void showSummary(Player player)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append("<html><body><center>");
		sb.append("<br><font color=\"LEVEL\" name=\"hs16\">Passive Skill Tree</font><br><br>");
		sb.append("<button value=\"Open Passive Tree for ").append(player.getName()).append("\" action=\"bypass _bbspassives_weblink\" width=260 height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"><br>");
		sb.append("<img src=\"L2UI.SquareGray\" width=700 height=1><br><br>");

		appendTemplatesSection(sb, player);

		sb.append("<img src=\"L2UI.SquareGray\" width=700 height=1><br><br>");

		appendTotalsSection(sb, player);

		sb.append("<img src=\"L2UI.SquareGray\" width=700 height=1><br><br>");

		appendSkillsSection(sb, player);

		sb.append("<img src=\"L2UI.SquareGray\" width=700 height=1><br><br>");
		sb.append("<button value=\"Reset Tree (").append(PassiveTreeManager.getInstance().getResetCostText()).append(")\" action=\"bypass _bbspassives_reset\" width=260 height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
		sb.append("</center></body></html>");

		CommunityBoardHandler.separateAndSend(sb.toString(), player);
	}

	/** Appends the template picker: one button per template, the active one marked, plus why switching may currently be refused. */
	private void appendTemplatesSection(StringBuilder sb, Player player)
	{
		final PassiveTreeManager manager = PassiveTreeManager.getInstance();

		sb.append("<font color=\"LEVEL\">Templates</font><br1>");
		sb.append("<table><tr>");
		for (PassiveTreeManager.TemplateInfo info : manager.getTemplates(player))
		{
			sb.append("<td align=center>");
			if (info.active())
			{
				sb.append("<font color=\"55FF55\">Template ").append(info.id()).append(" (active)</font>");
			}
			else
			{
				sb.append("<button value=\"Template ").append(info.id()).append("\" action=\"bypass _bbspassives_template ").append(info.id()).append("\" width=110 height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
			}
			sb.append("<br1><font color=\"777777\">").append(info.points()).append(" pts, ").append(info.nodes()).append(" nodes</font></td>");
		}
		sb.append("</tr></table><br1>");

		if (!manager.canSwitchTemplateHere(player))
		{
			sb.append("<font color=\"FF6060\">Templates can only be switched in a peace zone.</font><br1>");
		}
		final long wait = manager.getTemplateCooldownRemaining(player);
		if (wait > 0)
		{
			sb.append("<font color=\"FFCC33\">You can switch again in ").append((wait + 999) / 1000).append(" seconds.</font><br1>");
		}
		sb.append("<br>");
	}

	/**
	 * Sums every allocated node's raw effect spec into one totals-by-stat map, exactly mirroring aggregateTotals() in passive-tree.html (same keys, same un-capped raw sum) so this page and the web planner always agree on what "Total Bonuses" means.
	 * @param player whose allocated nodes to sum
	 * @return stat key (e.g. "PATK_PCT") -> summed raw value, only for stats with at least one allocated node
	 */
	private Map<String, Double> computeTotals(Player player)
	{
		final Map<String, Double> totals = new LinkedHashMap<>();
		final Set<Integer> allocated = PassiveTreeManager.getInstance().getAllocatedNodes(player);
		if ((allocated == null) || (PassiveTreeData.getInstance().getAllNodes() == null))
		{
			return totals;
		}

		for (int nodeId : allocated)
		{
			final PassiveNode node = PassiveTreeData.getInstance().getNode(nodeId);
			if ((node == null) || node.getEffectSpec().isEmpty())
			{
				continue;
			}

			for (String part : node.getEffectSpec().split(";"))
			{
				final String[] kv = part.split(":");
				if (kv.length != 2)
				{
					continue;
				}

				try
				{
					totals.merge(kv[0].trim(), Double.parseDouble(kv[1].trim()), Double::sum);
				}
				catch (NumberFormatException ignored)
				{
					// Malformed effect spec in the XML - skip rather than crash the page.
				}
			}
		}

		return totals;
	}

	/** Appends the "Total Bonuses" box - same raw per-stat sums as the web planner's own totals panel. */
	private void appendTotalsSection(StringBuilder sb, Player player)
	{
		final Map<String, Double> totals = computeTotals(player);

		final List<String> keys = new ArrayList<>(totals.keySet());
		keys.sort((a, b) -> STAT_LABEL.getOrDefault(a, a).compareToIgnoreCase(STAT_LABEL.getOrDefault(b, b)));

		sb.append("<font color=\"LEVEL\">Total Bonuses</font><br1>");
		if (keys.isEmpty())
		{
			sb.append("<font color=\"777777\">No bonuses allocated yet.</font><br><br>");
			return;
		}

		for (String key : keys)
		{
			final double val = totals.get(key);
			final double rounded = Math.round(val * 100) / 100.0;
			final String keystone = KEYSTONE_TEXT.get(key);
			if (keystone != null)
			{
				sb.append("<font color=\"FFCC33\">").append(String.format(keystone, formatNumber(rounded))).append("</font><br1>");
				continue;
			}

			final int at = key.indexOf('@');
			final String base = at > 0 ? key.substring(0, at) : key;
			final String condition = at > 0 ? " (" + COND_TEXT.getOrDefault(key.substring(at + 1), key.substring(at + 1).toLowerCase()) + ")" : "";
			final boolean pct = base.endsWith("_PCT");
			final String sign = rounded > 0 ? "+" : "";
			final String color = rounded >= 0 ? "55FF55" : "FF6060";

			sb.append("<font color=\"").append(color).append("\">").append(sign).append(formatNumber(rounded)).append(pct ? "%" : "").append(" ").append(STAT_LABEL.getOrDefault(base, base)).append(condition).append("</font><br1>");
		}
		sb.append("<br>");
	}

	/** Trims a trailing ".0" so whole numbers ("+90 Maximum HP") don't render as "+90.0". */
	private String formatNumber(double value)
	{
		return (value == Math.floor(value)) ? String.valueOf((long) value) : String.valueOf(value);
	}

	/** Appends the "Skills Gained" box - every skill granted by an allocated node, whatever the node's type. A skill granted by several nodes is listed once, at its highest level. */
	private void appendSkillsSection(StringBuilder sb, Player player)
	{
		final Map<Integer, Skill> skills = new LinkedHashMap<>();
		final Set<Integer> allocated = PassiveTreeManager.getInstance().getAllocatedNodes(player);
		if ((allocated != null) && (PassiveTreeData.getInstance().getAllNodes() != null))
		{
			for (int nodeId : allocated)
			{
				final PassiveNode node = PassiveTreeData.getInstance().getNode(nodeId);
				if ((node == null) || !node.grantsSkill())
				{
					continue;
				}

				final Skill skill = SkillData.getInstance().getSkill(node.getSkillId(), PassiveTreeManager.getNodeSkillLevel(player, node));
				if (skill != null)
				{
					skills.merge(skill.getId(), skill, (a, b) -> a.getLevel() >= b.getLevel() ? a : b);
				}
			}
		}

		final List<Skill> sorted = new ArrayList<>(skills.values());
		sorted.sort((a, b) -> String.valueOf(a.getName()).compareToIgnoreCase(String.valueOf(b.getName())));

		sb.append("<font color=\"LEVEL\">Skills Gained</font><br1>");
		if (sorted.isEmpty())
		{
			sb.append("<font color=\"777777\">No skills gained yet.</font><br><br>");
			return;
		}

		for (Skill skill : sorted)
		{
			// Show what the character really knows, so this page also confirms the server granted it.
			final Skill known = player.getKnownSkill(skill.getId());
			if (known == null)
			{
				sb.append("<font color=\"FF6060\">").append(skill.getName()).append("</font> <font color=\"777777\">not active - relog, or report it</font><br1>");
				continue;
			}
			sb.append("<font color=\"60C0FF\">").append(known.getName()).append("</font> <font color=\"777777\">Lv. ").append(known.getLevel()).append(known.isPassive() ? " (passive)" : "").append("</font><br1>");
		}
		sb.append("<br>");
	}

	@Override
	public String[] getCommandList()
	{
		return COMMAND;
	}
}
