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
 * Community Board page for the passive tree, reached via Alt+B or .passives. A summary page: a button that opens the web planner for this character, the total stat bonuses, every skill granted by the allocated nodes, and a full-tree reset. Allocating and per-node respecs happen in the web planner.
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
	}

	private static final String[] COMMAND =
	{
		"_bbspassives",
		"_bbspassives_weblink",
		"_bbspassives_reset"
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

		showSummary(player);
		return true;
	}

	private void showSummary(Player player)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append("<html><body><center>");
		sb.append("<br><font color=\"LEVEL\" name=\"hs16\">Passive Skill Tree</font><br><br>");
		sb.append("<button value=\"Open Passive Tree for ").append(player.getName()).append("\" action=\"bypass _bbspassives_weblink\" width=260 height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></button><br>");
		sb.append("<img src=\"L2UI.SquareGray\" width=700 height=1><br><br>");

		appendTotalsSection(sb, player);

		sb.append("<img src=\"L2UI.SquareGray\" width=700 height=1><br><br>");

		appendSkillsSection(sb, player);

		sb.append("<img src=\"L2UI.SquareGray\" width=700 height=1><br><br>");
		sb.append("<button value=\"Reset Tree (").append(PassiveTreeManager.getInstance().getResetCostText()).append(")\" action=\"bypass _bbspassives_reset\" width=260 height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></button>");
		sb.append("</center></body></html>");

		CommunityBoardHandler.separateAndSend(sb.toString(), player);
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
			final boolean pct = key.endsWith("_PCT");
			final String sign = rounded > 0 ? "+" : "";
			final String color = rounded >= 0 ? "55FF55" : "FF6060";

			sb.append("<font color=\"").append(color).append("\">").append(sign).append(formatNumber(rounded)).append(pct ? "%" : "").append(" ").append(STAT_LABEL.getOrDefault(key, key)).append("</font><br1>");
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

				final Skill skill = SkillData.getInstance().getSkill(node.getSkillId(), node.getSkillLevel());
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
			sb.append("<font color=\"60C0FF\">").append(skill.getName()).append("</font> <font color=\"777777\">Lv. ").append(skill.getLevel()).append(skill.isPassive() ? " (passive)" : "").append("</font><br1>");
		}
		sb.append("<br>");
	}

	@Override
	public String[] getCommandList()
	{
		return COMMAND;
	}
}
