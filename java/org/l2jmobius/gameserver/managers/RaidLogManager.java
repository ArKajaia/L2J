/*
 * This file is part of the L2J Mobius project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.l2jmobius.gameserver.managers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.custom.CombatLogConfig;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.holders.npc.RaidLog;
import org.l2jmobius.gameserver.model.actor.holders.npc.RaidLog.Participant;
import org.l2jmobius.gameserver.model.actor.holders.npc.RaidLog.Report;
import org.l2jmobius.gameserver.model.actor.holders.npc.RaidLog.SkillUse;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.groups.Party;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

/**
 * The raid log: while players fight a raid boss (or a grand boss), what each of them does is recorded in the boss's {@link RaidLog}: damage to the boss and its minions (their summons' included), hits, crits, skills, heals, debuffs, damage taken and deaths. When
 * the boss dies, everyone who took part gets the ranking in a window and in chat, and ".raidlog" opens it again.<br>
 * The hooks only look at raid bosses, their minions and the players fighting them; the windows are built after the kill, away from the combat threads.
 */
public class RaidLogManager
{
	private static final int HTML_LIMIT = 16000;
	private static final int PAGE_SIZE = 15;

	private RaidLogManager()
	{
	}

	// ---------------------------------------------------------------------------
	// Hooks
	// ---------------------------------------------------------------------------

	/**
	 * @param npc a raid boss or one of its minions
	 * @return the boss whose fight it is part of, {@code null} if it is not a raid
	 */
	private static Attackable getBoss(Creature npc)
	{
		if ((npc == null) || !npc.isAttackable() || !npc.isRaid())
		{
			return null;
		}

		if (npc.isRaidMinion() && npc.isMonster())
		{
			final Monster leader = npc.asMonster().getLeader();
			if (leader != null)
			{
				return leader;
			}
		}
		return npc.asAttackable();
	}

	/**
	 * @param boss the boss
	 * @param now the current time
	 * @param create start a fight if none is going on
	 * @return the fight against the boss, {@code null} if none is going on and {@code create} is {@code false}
	 */
	private static RaidLog getLog(Attackable boss, long now, boolean create)
	{
		RaidLog log = boss.getRaidLog();
		if ((log != null) && log.isActive(now, CombatLogConfig.RAID_IDLE_MILLIS))
		{
			return log;
		}
		if (!create || boss.isDead())
		{
			return null;
		}

		synchronized (boss)
		{
			log = boss.getRaidLog();
			if ((log == null) || !log.isActive(now, CombatLogConfig.RAID_IDLE_MILLIS))
			{
				log = new RaidLog(boss.getName(), boss.getLevel(), boss.getMaxHp(), now);
				boss.setRaidLog(log);
			}
			return log;
		}
	}

	/**
	 * @param log the fight
	 * @param owner a player or fake player
	 * @param now the current time
	 * @return its participant in the fight; a player is also told which fight it is in
	 */
	private static Participant participant(RaidLog log, Creature owner, long now)
	{
		Participant participant = log.getParticipant(owner.getObjectId());
		if (participant == null)
		{
			participant = log.getParticipant(owner.getObjectId(), owner.getName(), CombatLogManager.getClassName(owner), owner.getLevel(), owner.isPlayer(), now);
		}
		if (owner.isPlayer())
		{
			owner.asPlayer().setRaidFight(log);
		}
		return participant;
	}

	/**
	 * Called just before a raid boss or one of its minions loses HP.
	 * @param target the boss or minion
	 * @param attacker who hits it
	 * @param damage the damage
	 * @param dot damage over time
	 * @param skill the skill, {@code null} for an auto attack
	 */
	public static void onRaidDamaged(Attackable target, Creature attacker, double damage, boolean dot, Skill skill)
	{
		if (!CombatLogConfig.RAID_ENABLED || (attacker == null) || target.isDead() || (target.isInvul() && !dot))
		{
			return;
		}

		final int amount = (int) Math.min(damage, target.getCurrentHp());
		if (amount <= 0)
		{
			return;
		}

		final Player attackerPlayer = attacker.asPlayer();
		if ((attackerPlayer != null) && attackerPlayer.isGM() && !attackerPlayer.getAccessLevel().canGiveDamage())
		{
			return;
		}

		final Attackable boss = getBoss(target);
		if (boss == null)
		{
			return;
		}

		final long now = System.currentTimeMillis();
		final RaidLog log = getLog(boss, now, true);
		if (log == null)
		{
			return;
		}
		log.touch(now);

		final Creature owner = CombatLogManager.getPvpOwner(attacker);
		if (owner == null)
		{
			if (target == boss)
			{
				log.addOtherDamage(amount);
				log.setLastHit(attacker.getName(), skill, amount);
			}
			return;
		}

		participant(log, owner, now).addDamage(now, attacker.getObjectId(), attacker != owner, target != boss, skill, amount, dot);
		if (target == boss)
		{
			log.setLastHit(attacker == owner ? owner.getName() : owner.getName() + "'s " + attacker.getName(), skill, amount);
		}
	}

	/**
	 * Called with the damage message, before the damage is applied: notes a crit, or a missed attack.
	 * @param attacker who attacks
	 * @param target the raid boss or minion
	 * @param crit a critical hit
	 * @param miss the attack missed
	 */
	public static void onDamageMessage(Creature attacker, Creature target, boolean crit, boolean miss)
	{
		if (!CombatLogConfig.RAID_ENABLED || !target.isRaid())
		{
			return;
		}

		final Creature owner = CombatLogManager.getPvpOwner(attacker);
		final Attackable boss = getBoss(target);
		if ((owner == null) || (boss == null))
		{
			return;
		}

		final long now = System.currentTimeMillis();
		final RaidLog log = getLog(boss, now, true);
		if (log == null)
		{
			return;
		}

		final Participant participant = participant(log, owner, now);
		if (miss)
		{
			participant.addMiss(now);
		}
		else
		{
			participant.setPendingCrit(attacker.getObjectId(), crit);
		}
	}

	/**
	 * Called when a player lost CP or HP.
	 * @param player the player
	 * @param attacker who hit them
	 * @param amount the CP and HP lost
	 */
	public static void onPlayerDamaged(Player player, Creature attacker, int amount)
	{
		if (!CombatLogConfig.RAID_ENABLED || (amount <= 0) || !attacker.isRaid())
		{
			return;
		}

		final Attackable boss = getBoss(attacker);
		if (boss == null)
		{
			return;
		}

		final long now = System.currentTimeMillis();
		final RaidLog log = getLog(boss, now, true);
		if (log != null)
		{
			log.touch(now);
			participant(log, player, now).addDamageTaken(now, amount);
		}
	}

	/**
	 * Called when a player died: a death in the fight they are in.
	 * @param player the player
	 * @param killer the killer
	 */
	public static void onPlayerDied(Player player, Creature killer)
	{
		if (!CombatLogConfig.RAID_ENABLED)
		{
			return;
		}

		final long now = System.currentTimeMillis();
		RaidLog log = null;
		final Attackable boss = getBoss(killer);
		if (boss != null)
		{
			log = getLog(boss, now, false);
		}
		if (log == null)
		{
			log = player.getRaidFight();
			if ((log == null) || !log.isActive(now, CombatLogConfig.RAID_IDLE_MILLIS))
			{
				return;
			}
		}

		participant(log, player, now).addDeath();
	}

	/**
	 * Called when a player was healed: a heal for the fight they are in.
	 * @param healer who healed
	 * @param target who was healed
	 * @param skill the skill
	 * @param amount the HP and CP restored
	 */
	public static void onHealed(Creature healer, Creature target, Skill skill, int amount)
	{
		if (!CombatLogConfig.RAID_ENABLED || (healer == null) || (amount <= 0) || !target.isPlayer() || ((skill != null) && skill.isHealingPotionSkill()))
		{
			return;
		}

		final RaidLog log = target.asPlayer().getRaidFight();
		final long now = System.currentTimeMillis();
		if ((log == null) || !log.isActive(now, CombatLogConfig.RAID_IDLE_MILLIS))
		{
			return;
		}

		final Creature owner = CombatLogManager.getPvpOwner(healer);
		if (owner != null)
		{
			participant(log, owner, now).addHeal(now, amount);
		}
	}

	/**
	 * Called when a debuff landed on, or was resisted by, a raid boss or minion.
	 * @param attacker who cast it
	 * @param target the boss or minion
	 * @param landed {@code false} if it was resisted
	 */
	public static void onDebuff(Creature attacker, Creature target, boolean landed)
	{
		if (!CombatLogConfig.RAID_ENABLED || (attacker == null) || !target.isRaid())
		{
			return;
		}

		final Creature owner = CombatLogManager.getPvpOwner(attacker);
		final Attackable boss = getBoss(target);
		if ((owner == null) || (boss == null))
		{
			return;
		}

		final long now = System.currentTimeMillis();
		final RaidLog log = getLog(boss, now, true);
		if (log != null)
		{
			participant(log, owner, now).addDebuff(now, landed);
		}
	}

	/**
	 * Called when a raid boss died: the fight is over, and everyone who took part gets the log.
	 * @param boss the boss
	 * @param killer the killer
	 */
	public static void onRaidDied(Attackable boss, Creature killer)
	{
		final RaidLog log = boss.getRaidLog();
		boss.setRaidLog(null);
		if (!CombatLogConfig.RAID_ENABLED || (log == null))
		{
			return;
		}

		final long now = System.currentTimeMillis();
		final Report report = log.finish(now);
		report.killerName = log.getLastHitName() != null ? log.getLastHitName() : killer == null ? null : killer.getName();
		report.killingSkill = log.getLastHitSkill() == null ? "Attack" : CombatLogManager.getSkillName(log.getLastHitSkill());
		report.killingBlow = log.getLastHitDamage();
		if (report.participants.isEmpty())
		{
			return;
		}

		// The rest (parties, windows) is away from the combat thread.
		ThreadPool.execute(() -> deliver(report));
	}

	private static void deliver(Report report)
	{
		// The parties, as they are now.
		final Map<String, Object[]> parties = new LinkedHashMap<>();
		final List<Player> online = new ArrayList<>();
		for (Participant p : report.participants)
		{
			String group = "Others";
			if (p.player)
			{
				final Player player = World.getInstance().getPlayer(p.objectId);
				if (player != null)
				{
					online.add(player);
					final Party party = player.getParty();
					group = party == null ? p.name + " (solo)" : party.getLeader().getName();
				}
			}
			p.partyLeader = group;

			Object[] row = parties.get(group);
			if (row == null)
			{
				row = new Object[]
				{
					group,
					0L,
					0,
					0L
				};
				parties.put(group, row);
			}
			row[1] = (Long) row[1] + p.damage;
			row[2] = (Integer) row[2] + 1;
			row[3] = (Long) row[3] + p.healed;
		}
		final List<Object[]> partyList = new ArrayList<>(parties.values());
		partyList.sort((a, b) -> Long.compare((Long) b[1], (Long) a[1]));
		report.parties = partyList;

		// The top 3 for the chat line.
		final StringBuilder top = new StringBuilder();
		for (int i = 0; (i < 3) && (i < report.participants.size()); i++)
		{
			final Participant p = report.participants.get(i);
			if (p.damage <= 0)
			{
				break;
			}
			top.append(i == 0 ? " Top: " : ", ").append(p.name).append(' ').append(shortNumber(p.damage)).append(" (").append(percent(p.damage, report.totalDamage)).append("%)");
		}

		for (Player player : online)
		{
			player.setLastRaidReport(report);
			if (CombatLogConfig.RAID_CHAT_SUMMARY)
			{
				final Participant me = report.get(player.getObjectId());
				final StringBuilder sb = new StringBuilder(200);
				sb.append("Raid log: ").append(report.bossName).append(" killed in ").append(duration(report.end - report.start)).append(" by ").append(report.participants.size()).append(report.participants.size() == 1 ? " fighter" : " fighters").append('.');
				if (me != null)
				{
					sb.append(" You: ").append(shortNumber(me.damage)).append(" damage (").append(percent(me.damage, report.totalDamage)).append("%, rank ").append(report.rankOf(player.getObjectId())).append('/').append(report.participants.size()).append(')');
					if (me.healed > 0)
					{
						sb.append(", ").append(shortNumber(me.healed)).append(" healed");
					}
					sb.append('.');
				}
				sb.append(top).append(". Type .raidlog to see it.");
				player.sendMessage(sb.toString());
			}
			if (CombatLogConfig.RAID_SHOW_ON_KILL)
			{
				show(player, "summary", 1);
			}
		}
	}

	// ---------------------------------------------------------------------------
	// Windows
	// ---------------------------------------------------------------------------

	/**
	 * Opens a page of the last raid log of a player.
	 * @param player the player
	 * @param page "summary", "damage", "support", "parties" or "mine"
	 * @param pageNumber the page of a long list, from 1
	 */
	public static void show(Player player, String page, int pageNumber)
	{
		final Report report = player.getLastRaidReport();
		if (report == null)
		{
			player.sendMessage("You have not taken part in a raid boss kill yet.");
			return;
		}

		final StringBuilder sb = new StringBuilder(8192);
		sb.append("<html><title>Raid Log</title><body><center>");
		sb.append("<table width=280 border=0 cellspacing=0 cellpadding=0><tr>");
		tab(sb, "Summary", "summary", page);
		tab(sb, "Damage", "damage", page);
		tab(sb, "Support", "support", page);
		tab(sb, "Parties", "parties", page);
		tab(sb, "Mine", "mine", page);
		sb.append("</tr></table><br>");

		switch (page)
		{
			case "damage":
			{
				damagePage(sb, report, player.getObjectId(), pageNumber);
				break;
			}
			case "support":
			{
				supportPage(sb, report, player.getObjectId(), pageNumber);
				break;
			}
			case "parties":
			{
				partiesPage(sb, report);
				break;
			}
			case "mine":
			{
				minePage(sb, report, player.getObjectId());
				break;
			}
			default:
			{
				summaryPage(sb, report, player.getObjectId());
				break;
			}
		}

		sb.append("</center></body></html>");
		final NpcHtmlMessage html = new NpcHtmlMessage(0);
		html.setHtml(sb.toString());
		player.sendPacket(html);
	}

	private static void tab(StringBuilder sb, String label, String page, String current)
	{
		sb.append("<td align=center><button value=\"").append(label).append("\" action=\"bypass voiced_raidlog ").append(page).append("\" width=55 height=22 back=\"L2UI_CT1.Button_DF_Down\" fore=\"").append(page.equals(current) ? "L2UI_CT1.Button_DF_Down" : "L2UI_CT1.Button_DF").append("\"></td>");
	}

	private static void header(StringBuilder sb, String title)
	{
		sb.append("<table width=280 bgcolor=000000><tr><td align=center><font color=\"LEVEL\">").append(title).append("</font></td></tr></table>");
	}

	private static void row(StringBuilder sb, String label, String value)
	{
		sb.append("<tr><td width=95><font color=\"A0A0A0\">").append(label).append("</font></td><td width=185>").append(value).append("</td></tr>");
	}

	private static void summaryPage(StringBuilder sb, Report r, int viewerId)
	{
		final long fight = Math.max(1, r.end - r.start);
		header(sb, escape(r.bossName) + " Lv. " + r.bossLevel);
		sb.append("<table width=280 border=0 cellspacing=1 cellpadding=1 bgcolor=111111>");
		row(sb, "Killed in", duration(fight));
		row(sb, "Fighters", String.valueOf(r.participants.size()));
		row(sb, "Damage", r.totalDamage + " <font color=\"A0A0A0\">(" + shortNumber((r.totalDamage * 1000) / fight) + "/s)</font>");
		if (r.otherDamage > 0)
		{
			row(sb, "Other damage", r.otherDamage + " <font color=\"A0A0A0\">(guards, monsters...)</font>");
		}
		if (r.minionDamage > 0)
		{
			row(sb, "To minions", String.valueOf(r.minionDamage));
		}
		row(sb, "Healed", String.valueOf(r.healed));
		row(sb, "Deaths", String.valueOf(r.deaths));
		if (r.killerName != null)
		{
			row(sb, "Killing blow", "<font color=\"FFCC33\">" + escape(r.killerName) + "</font> " + escape(r.killingSkill) + " " + r.killingBlow);
		}
		sb.append("</table><br>");

		// You.
		final Participant me = r.get(viewerId);
		if (me != null)
		{
			header(sb, "YOU");
			sb.append("<table width=280 border=0 cellspacing=1 cellpadding=1 bgcolor=111111>");
			row(sb, "Rank", r.rankOf(viewerId) + " / " + r.participants.size());
			row(sb, "Damage", me.damage + " <font color=\"A0A0A0\">(" + percent(me.damage, r.totalDamage) + "%, " + ((me.damage * 1000) / fight) + "/s)</font>");
			if (me.healed > 0)
			{
				row(sb, "Healed", me.healed + " <font color=\"A0A0A0\">(" + me.heals + " heals)</font>");
			}
			if (me.deaths > 0)
			{
				row(sb, "Deaths", String.valueOf(me.deaths));
			}
			sb.append("</table><br>");
		}

		// The top 10.
		header(sb, "TOP DAMAGE");
		sb.append("<table width=280 border=0 cellspacing=1 cellpadding=1 bgcolor=111111>");
		for (int i = 0; (i < 10) && (i < r.participants.size()); i++)
		{
			final Participant p = r.participants.get(i);
			if (p.damage <= 0)
			{
				break;
			}
			sb.append("<tr><td width=25><font color=\"A0A0A0\">").append(i + 1).append("</font></td><td width=135><font color=\"").append(p.objectId == viewerId ? "00FF66" : "FFFFFF").append("\">").append(escape(p.name)).append("</font></td>");
			sb.append("<td width=75 align=right>").append(shortNumber(p.damage)).append("</td><td width=45 align=right><font color=\"A0A0A0\">").append(percent(p.damage, r.totalDamage)).append("%</font></td></tr>");
		}
		sb.append("</table><br>");

		// Highlights.
		Participant biggest = null;
		Participant healer = null;
		Participant debuffer = null;
		Participant tank = null;
		for (Participant p : r.participants)
		{
			if ((p.biggest > 0) && ((biggest == null) || (p.biggest > biggest.biggest)))
			{
				biggest = p;
			}
			if ((p.healed > 0) && ((healer == null) || (p.healed > healer.healed)))
			{
				healer = p;
			}
			if ((p.debuffs > 0) && ((debuffer == null) || (p.debuffs > debuffer.debuffs)))
			{
				debuffer = p;
			}
			if ((p.damageTaken > 0) && ((tank == null) || (p.damageTaken > tank.damageTaken)))
			{
				tank = p;
			}
		}
		header(sb, "HIGHLIGHTS");
		sb.append("<table width=280 border=0 cellspacing=1 cellpadding=1 bgcolor=111111>");
		if (biggest != null)
		{
			row(sb, "Biggest hit", escape(biggest.name) + " <font color=\"FFCC33\">" + biggest.biggest + "</font> <font color=\"A0A0A0\">(" + escape(biggest.biggestSkill == null ? "Attack" : biggest.biggestSkill.getName()) + ")</font>");
		}
		if (healer != null)
		{
			row(sb, "Top healer", escape(healer.name) + " <font color=\"66FF66\">" + healer.healed + "</font>");
		}
		if (debuffer != null)
		{
			row(sb, "Most debuffs", escape(debuffer.name) + " <font color=\"CC66FF\">" + debuffer.debuffs + "</font>");
		}
		if (tank != null)
		{
			row(sb, "Took the most", escape(tank.name) + " <font color=\"FF6666\">" + tank.damageTaken + "</font>");
		}
		sb.append("</table>");
	}

	private static void damagePage(StringBuilder sb, Report r, int viewerId, int pageNumber)
	{
		final long fight = Math.max(1, r.end - r.start);
		final List<Participant> list = new ArrayList<>();
		for (Participant p : r.participants)
		{
			if ((p.damage > 0) || (p.minionDamage > 0))
			{
				list.add(p);
			}
		}

		final int pages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		final int page = Math.max(1, Math.min(pages, pageNumber));
		header(sb, "DAMAGE TO " + escape(r.bossName).toUpperCase());
		sb.append("<table width=280 border=0 cellspacing=0 cellpadding=1>");
		boolean dark = false;
		for (int i = (page - 1) * PAGE_SIZE; (i < list.size()) && (i < (page * PAGE_SIZE)); i++)
		{
			if (sb.length() > (HTML_LIMIT - 800))
			{
				break;
			}
			final Participant p = list.get(i);
			final String bg = dark ? " bgcolor=111111" : "";
			dark = !dark;
			sb.append("<tr><td width=25 valign=top").append(bg).append("><font color=\"A0A0A0\">").append(i + 1).append("</font></td><td width=255").append(bg).append('>');
			sb.append("<font color=\"").append(p.objectId == viewerId ? "00FF66" : "FFFFFF").append("\">").append(escape(p.name)).append("</font> <font color=\"808080\">").append(p.className != null ? escape(p.className) + " " : "").append(p.level).append("</font><br1>");
			sb.append("<font color=\"FF9966\">").append(p.damage).append("</font> <font color=\"A0A0A0\">").append(percent(p.damage, r.totalDamage)).append("% ").append((p.damage * 1000) / fight).append("/s ").append(p.hits + p.dots).append(" hits ").append(p.crits).append(" crits max ").append(p.biggest).append("</font>");
			if ((p.summonDamage > 0) || (p.minionDamage > 0) || (p.misses > 0))
			{
				sb.append("<br1><font color=\"808080\">");
				if (p.summonDamage > 0)
				{
					sb.append("summon ").append(p.summonDamage).append(' ');
				}
				if (p.minionDamage > 0)
				{
					sb.append("minions ").append(p.minionDamage).append(' ');
				}
				if (p.misses > 0)
				{
					sb.append("missed ").append(p.misses);
				}
				sb.append("</font>");
			}
			sb.append("</td></tr>");
		}
		if (list.isEmpty())
		{
			sb.append("<tr><td><font color=\"808080\">Nobody hit it.</font></td></tr>");
		}
		sb.append("</table>");
		pager(sb, "damage", page, pages);
	}

	private static void supportPage(StringBuilder sb, Report r, int viewerId, int pageNumber)
	{
		final List<Participant> list = new ArrayList<>();
		for (Participant p : r.participants)
		{
			if ((p.healed > 0) || (p.debuffs > 0) || (p.resisted > 0) || (p.damageTaken > 0) || (p.deaths > 0))
			{
				list.add(p);
			}
		}
		list.sort((a, b) -> a.healed != b.healed ? Long.compare(b.healed, a.healed) : a.debuffs != b.debuffs ? Integer.compare(b.debuffs, a.debuffs) : Long.compare(b.damageTaken, a.damageTaken));

		final int pages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		final int page = Math.max(1, Math.min(pages, pageNumber));
		header(sb, "HEALS, DEBUFFS AND TANKING");
		sb.append("<table width=280 border=0 cellspacing=0 cellpadding=1>");
		boolean dark = false;
		for (int i = (page - 1) * PAGE_SIZE; (i < list.size()) && (i < (page * PAGE_SIZE)); i++)
		{
			if (sb.length() > (HTML_LIMIT - 800))
			{
				break;
			}
			final Participant p = list.get(i);
			final String bg = dark ? " bgcolor=111111" : "";
			dark = !dark;
			sb.append("<tr><td width=280").append(bg).append("><font color=\"").append(p.objectId == viewerId ? "00FF66" : "FFFFFF").append("\">").append(escape(p.name)).append("</font> <font color=\"808080\">").append(p.className != null ? escape(p.className) : "").append("</font><br1><font color=\"A0A0A0\">");
			sb.append("healed <font color=\"66FF66\">").append(p.healed).append("</font> (").append(p.heals).append(")");
			sb.append(" debuffs <font color=\"CC66FF\">").append(p.debuffs).append("</font>/").append(p.debuffs + p.resisted);
			sb.append("<br1>taken <font color=\"FF6666\">").append(p.damageTaken).append("</font> deaths ").append(p.deaths).append("</font></td></tr>");
		}
		if (list.isEmpty())
		{
			sb.append("<tr><td><font color=\"808080\">Nobody healed, debuffed or tanked.</font></td></tr>");
		}
		sb.append("</table>");
		pager(sb, "support", page, pages);
	}

	private static void partiesPage(StringBuilder sb, Report r)
	{
		header(sb, "PARTIES");
		sb.append("<table width=280 border=0 cellspacing=1 cellpadding=1 bgcolor=111111>");
		sb.append("<tr><td width=120><font color=\"A0A0A0\">Leader</font></td><td width=30 align=right><font color=\"A0A0A0\">Size</font></td><td width=80 align=right><font color=\"A0A0A0\">Damage</font></td><td width=50 align=right><font color=\"A0A0A0\">%</font></td></tr>");
		if (r.parties != null)
		{
			for (Object[] party : r.parties)
			{
				if (sb.length() > (HTML_LIMIT - 300))
				{
					break;
				}
				final long damage = (Long) party[1];
				sb.append("<tr><td>").append(escape((String) party[0])).append("</td><td align=right>").append(party[2]).append("</td><td align=right>").append(shortNumber(damage)).append("</td><td align=right>").append(percent(damage, r.totalDamage)).append("</td></tr>");
				final long healed = (Long) party[3];
				if (healed > 0)
				{
					sb.append("<tr><td colspan=4><font color=\"808080\">healed ").append(healed).append("</font></td></tr>");
				}
			}
		}
		sb.append("</table>");
	}

	private static void minePage(StringBuilder sb, Report r, int viewerId)
	{
		final Participant me = r.get(viewerId);
		if (me == null)
		{
			header(sb, "YOU");
			sb.append("<font color=\"808080\">You took no part in this fight.</font>");
			return;
		}

		final long fight = Math.max(1, r.end - r.start);
		final long active = Math.max(1, me.last - me.first);
		header(sb, "YOU - RANK " + r.rankOf(viewerId) + " / " + r.participants.size());
		sb.append("<table width=280 border=0 cellspacing=1 cellpadding=1 bgcolor=111111>");
		row(sb, "Damage", me.damage + " <font color=\"A0A0A0\">(" + percent(me.damage, r.totalDamage) + "%)</font>");
		row(sb, "Per second", ((me.damage * 1000) / fight) + " <font color=\"A0A0A0\">(" + ((me.damage * 1000) / active) + " while fighting)</font>");
		row(sb, "Fought", duration(active) + " of " + duration(fight));
		row(sb, "Hits", (me.hits + me.dots) + " <font color=\"A0A0A0\">(" + me.crits + " crits, " + me.dots + " over time, " + me.misses + " missed)</font>");
		row(sb, "Biggest hit", me.biggest + (me.biggestSkill != null ? " <font color=\"A0A0A0\">(" + escape(me.biggestSkill.getName()) + ")</font>" : ""));
		if (me.summonDamage > 0)
		{
			row(sb, "Your summon", String.valueOf(me.summonDamage));
		}
		if (me.minionDamage > 0)
		{
			row(sb, "To minions", String.valueOf(me.minionDamage));
		}
		row(sb, "Healed", me.healed + " <font color=\"A0A0A0\">(" + me.heals + " heals)</font>");
		row(sb, "Debuffs", me.debuffs + " landed, " + me.resisted + " resisted");
		row(sb, "Taken", String.valueOf(me.damageTaken));
		row(sb, "Deaths", String.valueOf(me.deaths));
		sb.append("</table><br>");

		header(sb, "YOUR SKILLS");
		sb.append("<table width=280 border=0 cellspacing=1 cellpadding=1 bgcolor=111111>");
		final List<SkillUse> skills = r.skills.get(viewerId);
		int shown = 0;
		if (skills != null)
		{
			for (SkillUse use : skills)
			{
				if ((shown++ >= 15) || (sb.length() > (HTML_LIMIT - 400)))
				{
					break;
				}
				final String name = use.skill == null ? (use.summon ? "Summon attack" : "Attack") : CombatLogManager.getSkillName(use.skill);
				sb.append("<tr><td width=34>").append(use.skill == null ? "" : "<img src=\"" + use.skill.getIcon() + "\" width=32 height=32>").append("</td><td width=246>").append(escape(name)).append("<br1><font color=\"FF9966\">").append(use.damage).append("</font> <font color=\"A0A0A0\">(").append(percent(use.damage, me.damage + me.minionDamage)).append("%) ").append(use.hits).append(" hits, ").append(use.crits).append(" crits, max ").append(use.biggest).append(", avg ").append(use.damage / Math.max(1, use.hits)).append("</font></td></tr>");
			}
		}
		if (shown == 0)
		{
			sb.append("<tr><td><font color=\"808080\">You dealt no damage.</font></td></tr>");
		}
		sb.append("</table>");
	}

	private static void pager(StringBuilder sb, String page, int current, int pages)
	{
		if (pages <= 1)
		{
			return;
		}
		sb.append("<br><table width=280><tr><td width=90 align=left>");
		if (current > 1)
		{
			sb.append("<button value=\"Previous\" action=\"bypass voiced_raidlog ").append(page).append(' ').append(current - 1).append("\" width=80 height=22 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
		}
		sb.append("</td><td width=100 align=center><font color=\"A0A0A0\">Page ").append(current).append(" / ").append(pages).append("</font></td><td width=90 align=right>");
		if (current < pages)
		{
			sb.append("<button value=\"Next\" action=\"bypass voiced_raidlog ").append(page).append(' ').append(current + 1).append("\" width=80 height=22 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">");
		}
		sb.append("</td></tr></table>");
	}

	// ---------------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------------

	private static long percent(long value, long max)
	{
		return max <= 0 ? 0 : Math.max(0, Math.min(100, Math.round((value * 100.0) / max)));
	}

	private static String duration(long millis)
	{
		final long seconds = millis / 1000;
		return seconds >= 60 ? (seconds / 60) + "m " + (seconds % 60) + "s" : seconds + "s";
	}

	private static String shortNumber(long value)
	{
		if (value >= 1_000_000)
		{
			return String.format(java.util.Locale.US, "%.2fM", value / 1_000_000.0);
		}
		if (value >= 10_000)
		{
			return String.format(java.util.Locale.US, "%.1fK", value / 1000.0);
		}
		return String.valueOf(value);
	}

	private static String escape(String text)
	{
		if (text == null)
		{
			return "";
		}
		return (text.indexOf('<') < 0) && (text.indexOf('>') < 0) ? text : text.replace("<", "&lt;").replace(">", "&gt;");
	}
}
