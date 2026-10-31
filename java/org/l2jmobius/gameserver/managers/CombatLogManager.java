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
import java.util.List;
import java.util.Locale;

import org.l2jmobius.gameserver.config.custom.CombatLogConfig;
import org.l2jmobius.gameserver.data.xml.ClassListData;
import org.l2jmobius.gameserver.managers.simulation.FakeSimulationManager;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.Summon;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerHolder;
import org.l2jmobius.gameserver.model.actor.holders.player.ClassInfoHolder;
import org.l2jmobius.gameserver.model.actor.holders.player.CombatLog;
import org.l2jmobius.gameserver.model.actor.holders.player.CombatLog.Entry;
import org.l2jmobius.gameserver.model.actor.holders.player.CombatLog.Report;
import org.l2jmobius.gameserver.model.actor.holders.player.CombatLog.Side;
import org.l2jmobius.gameserver.model.actor.holders.player.CombatLog.SkillUse;
import org.l2jmobius.gameserver.model.actor.instance.FakePlayerPvpServitor;
import org.l2jmobius.gameserver.model.actor.instance.Trap;
import org.l2jmobius.gameserver.model.clan.Clan;
import org.l2jmobius.gameserver.model.item.Weapon;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.skill.BuffInfo;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

/**
 * The PvP combat log: while a player fights other players (their summons, traps and cubics included) or fake players, what happens to them is recorded in their {@link CombatLog}. When they die in that fight, they get a window with who killed them and how, the
 * attackers, the skills that hurt them most, the debuffs they had, what they dealt themselves, and a timeline of the last events. ".combatlog" opens the last one again.<br>
 * The hooks are called from the combat code on every hit, so they return at once for anything that is not a player in a PvP fight; the window is only built on death or on request.
 */
public class CombatLogManager
{
	private static final int HTML_LIMIT = 16000;
	/** The width of the content: the NPC window is 294 wide, and its scroll bar takes some of it. */
	private static final int WIDTH = 270;

	private CombatLogManager()
	{
	}

	// ---------------------------------------------------------------------------
	// Hooks
	// ---------------------------------------------------------------------------

	/**
	 * @param creature a creature
	 * @return the player or fake player that is behind it (itself, the owner of a summon, trap or fake player servitor), {@code null} if it is not a PvP fighter
	 */
	public static Creature getPvpOwner(Creature creature)
	{
		if (creature == null)
		{
			return null;
		}
		if (creature.isPlayer())
		{
			return creature;
		}
		if (creature.isSummon())
		{
			return ((Summon) creature).getOwner();
		}
		if (creature.isFakePlayer())
		{
			return creature;
		}
		if (creature instanceof Trap)
		{
			return ((Trap) creature).getOwner();
		}
		if (creature instanceof FakePlayerPvpServitor)
		{
			return ((FakePlayerPvpServitor) creature).getOwner();
		}
		return null;
	}

	/**
	 * @param player the player
	 * @param source who did something to the player
	 * @return the log to record it in, {@code null} if nothing is recorded (not PvP, and no PvP fight going on)
	 */
	private static CombatLog getLog(Player player, Creature source, long now)
	{
		final Creature owner = getPvpOwner(source);
		if ((owner != null) && (owner != player))
		{
			final CombatLog log = player.getCombatLog(true);
			log.touchPvp(now, CombatLogConfig.FIGHT_MILLIS);
			return log;
		}

		final CombatLog log = player.getCombatLog(false);
		return (log != null) && log.isActive(now, CombatLogConfig.FIGHT_MILLIS) ? log : null;
	}

	/**
	 * Called with the damage message, just before the damage is applied: notes a crit for the hit that follows, or an evaded attack.
	 * @param attacker who attacks
	 * @param target who is attacked
	 * @param crit a physical or magic critical hit
	 * @param miss the attack was evaded
	 */
	public static void onDamageMessage(Creature attacker, Creature target, boolean crit, boolean miss)
	{
		if ((target == null) || (attacker == null) || (attacker == target))
		{
			return;
		}

		if (target.isRaid())
		{
			RaidLogManager.onDamageMessage(attacker, target, crit, miss);
			return;
		}

		if (!CombatLogConfig.ENABLED)
		{
			return;
		}

		final long now = System.currentTimeMillis();
		if (target.isPlayer())
		{
			final Player player = target.asPlayer();
			final CombatLog log = getLog(player, attacker, now);
			if (log != null)
			{
				if (miss)
				{
					log.addEvaded(now, attacker.getObjectId(), describe(attacker), isPvp(attacker, player), null);
				}
				else
				{
					log.setPendingCrit(attacker.getObjectId(), crit);
				}
			}
		}

		// Your own missed attacks against PvP targets.
		if (miss && attacker.isPlayer() && (getPvpOwner(target) != null) && (getPvpOwner(target) != attacker))
		{
			final Player player = attacker.asPlayer();
			final CombatLog log = player.getCombatLog(true);
			log.touchPvp(now, CombatLogConfig.FIGHT_MILLIS);
			log.addMissed(now, describe(target), true, CombatLogConfig.TIMELINE_OWN_HITS);
		}
	}

	/**
	 * Called once the damage was taken off the player's CP and HP, before they die of it.
	 * @param player the player
	 * @param attacker who did it
	 * @param skill the skill, {@code null} for an auto attack
	 * @param dot damage over time
	 * @param hp the HP lost
	 * @param cp the CP lost
	 */
	public static void onDamageTaken(Player player, Creature attacker, Skill skill, boolean dot, int hp, int cp)
	{
		if ((attacker == null) || (attacker == player) || ((hp <= 0) && (cp <= 0)))
		{
			return;
		}

		RaidLogManager.onPlayerDamaged(player, attacker, Math.max(0, hp) + Math.max(0, cp));
		if (!CombatLogConfig.ENABLED)
		{
			return;
		}

		final long now = System.currentTimeMillis();
		final CombatLog log = getLog(player, attacker, now);
		if (log != null)
		{
			// Regeneration can change CP between the two reads.
			log.addHit(now, attacker.getObjectId(), describe(attacker), isPvp(attacker, player), skill, dot, Math.max(0, hp), Math.max(0, cp), (int) player.getCurrentHp(), (int) player.getCurrentCp());
		}
	}

	/**
	 * Called when a creature took damage: counts what a player dealt to a PvP target.
	 * @param target who took the damage
	 * @param attacker who dealt it
	 * @param skill the skill, {@code null} for an auto attack
	 * @param damage the damage
	 * @param crit a critical hit
	 * @param dot damage over time
	 */
	public static void onDamageDealt(Creature target, Creature attacker, Skill skill, double damage, boolean crit, boolean dot)
	{
		if (FakeSimulationManager.ACTIVE)
		{
			FakeSimulationManager.getInstance().onDamage(target, attacker, skill, damage, crit, dot);
		}
		
		// Team vs Team and Conquest: hurting someone ends the attacker's spawn protection.
		if (TeamEventRules.isActive() && (attacker != null) && (damage > 0))
		{
			TeamEventRules.onDamageDealt(attacker);
		}
		
		// Race Wars: how much the monsters of a match hurt its fighters.
		if (RaceWarsManager.ACTIVE && (attacker != null) && (damage > 0))
		{
			RaceWarsManager.getInstance().onDamage(target, attacker, damage);
		}
		
		if (!CombatLogConfig.ENABLED || (attacker == null) || (damage <= 0) || !(target.isPlayable() || target.isFakePlayer() || (target instanceof FakePlayerPvpServitor) || (target instanceof Trap)))
		{
			return;
		}

		final Creature owner = getPvpOwner(attacker);
		if ((owner == null) || !owner.isPlayer() || (getPvpOwner(target) == null) || (getPvpOwner(target) == owner))
		{
			return;
		}

		final long now = System.currentTimeMillis();
		final CombatLog log = owner.asPlayer().getCombatLog(true);
		log.touchPvp(now, CombatLogConfig.FIGHT_MILLIS);
		final String name = attacker == owner ? describe(target) : describe(target) + " (" + attacker.getName() + ")";
		log.addDealt(now, target.getObjectId(), name, true, skill, (int) damage, crit, dot, CombatLogConfig.TIMELINE_OWN_HITS);
	}

	/**
	 * Called when a skill of the attacker failed to land on the player.
	 * @param attacker who cast it
	 * @param target who resisted
	 * @param skill the skill
	 */
	public static void onResisted(Creature attacker, Creature target, Skill skill)
	{
		if (FakeSimulationManager.ACTIVE)
		{
			FakeSimulationManager.getInstance().onResist(attacker, target, skill);
		}
		
		if ((target == null) || (attacker == null) || (attacker == target))
		{
			return;
		}

		if (target.isRaid())
		{
			RaidLogManager.onDebuff(attacker, target, false);
			return;
		}

		if (!CombatLogConfig.ENABLED || !target.isPlayer())
		{
			return;
		}

		final Player player = target.asPlayer();
		final long now = System.currentTimeMillis();
		final CombatLog log = getLog(player, attacker, now);
		if (log != null)
		{
			log.addResisted(now, attacker.getObjectId(), describe(attacker), isPvp(attacker, player), skill);
		}
	}

	/**
	 * Called when a debuff landed on a creature.
	 * @param info the debuff
	 */
	public static void onDebuffLanded(BuffInfo info)
	{
		if (FakeSimulationManager.ACTIVE)
		{
			FakeSimulationManager.getInstance().onDebuff(info);
		}
		
		final Creature target = info.getEffected();
		final Creature attacker = info.getEffector();
		if ((target == null) || (attacker == null) || (attacker == target))
		{
			return;
		}

		if (target.isRaid())
		{
			RaidLogManager.onDebuff(attacker, target, true);
			return;
		}

		if (!CombatLogConfig.ENABLED || !target.isPlayer())
		{
			return;
		}

		final Player player = target.asPlayer();
		final long now = System.currentTimeMillis();
		final CombatLog log = getLog(player, attacker, now);
		if (log != null)
		{
			log.addDebuff(now, attacker.getObjectId(), describe(attacker), isPvp(attacker, player), info.getSkill());
		}
	}

	/**
	 * Called when buffs of a creature were cancelled or stolen.
	 * @param attacker who did it
	 * @param target whose buffs are gone
	 * @param skill the skill
	 * @param canceled the buffs
	 */
	public static void onCancelled(Creature attacker, Creature target, Skill skill, List<BuffInfo> canceled)
	{
		if (!CombatLogConfig.ENABLED || (target == null) || !target.isPlayer() || (attacker == null) || (attacker == target) || canceled.isEmpty())
		{
			return;
		}

		int count = 0;
		for (BuffInfo info : canceled)
		{
			if (!info.getSkill().isDebuff())
			{
				count++;
			}
		}
		if (count == 0)
		{
			return;
		}

		final Player player = target.asPlayer();
		final long now = System.currentTimeMillis();
		final CombatLog log = getLog(player, attacker, now);
		if (log != null)
		{
			log.addCancel(now, attacker.getObjectId(), describe(attacker), isPvp(attacker, player), skill, count);
		}
	}

	/**
	 * Called when a player was healed (HP or CP) during a fight.
	 * @param healer who healed
	 * @param target who was healed
	 * @param skill the skill or potion
	 * @param hp the HP restored
	 * @param cp the CP restored
	 */
	public static void onHealed(Creature healer, Creature target, Skill skill, double hp, double cp)
	{
		if (FakeSimulationManager.ACTIVE)
		{
			FakeSimulationManager.getInstance().onHeal(healer, target, skill, hp, cp);
		}
		
		// Healing the defenders of a village under siege is contribution (fake players included).
		if (hp >= 1)
		{
			VillageSiegeManager.getInstance().onHealed(healer, target, hp);
		}
		
		if ((target == null) || !target.isPlayer() || ((hp < 1) && (cp < 1)))
		{
			return;
		}

		RaidLogManager.onHealed(healer, target, skill, (int) (hp + cp));
		if (!CombatLogConfig.ENABLED)
		{
			return;
		}

		final Player player = target.asPlayer();
		final CombatLog log = player.getCombatLog(false);
		final long now = System.currentTimeMillis();
		if ((log != null) && log.isActive(now, CombatLogConfig.FIGHT_MILLIS))
		{
			log.addHeal(now, (healer == null) || (healer == player) ? "You" : describe(healer), skill, (int) hp, (int) cp, (int) player.getCurrentHp(), (int) player.getCurrentCp());
		}
	}

	/**
	 * Called just before a player dies, while they still have their debuffs.
	 * @param player the player
	 * @return the debuffs on the player ("name", "icon", "seconds left", "caster"), {@code null} if they are not in a PvP fight
	 */
	public static List<String[]> getDebuffs(Player player)
	{
		if (!CombatLogConfig.ENABLED)
		{
			return null;
		}

		final CombatLog log = player.getCombatLog(false);
		if ((log == null) || !log.isActive(System.currentTimeMillis(), CombatLogConfig.FIGHT_MILLIS))
		{
			return null;
		}

		final List<String[]> debuffs = new ArrayList<>();
		for (BuffInfo info : player.getEffectList().getDebuffs())
		{
			final Skill skill = info.getSkill();
			final Creature effector = info.getEffector();
			debuffs.add(new String[]
			{
				skill.getName(),
				skill.getIcon(),
				String.valueOf(Math.max(0, info.getTime())),
				effector == null ? "" : effector.getName()
			});
		}
		return debuffs;
	}

	/**
	 * Called when a player died: if they died in a PvP fight, the fight is frozen and shown.
	 * @param player the player
	 * @param killer the killer
	 * @param debuffs the debuffs the player had (see {@link #getDebuffs(Player)})
	 */
	public static void onDeath(Player player, Creature killer, List<String[]> debuffs)
	{
		if (!CombatLogConfig.ENABLED)
		{
			return;
		}

		final CombatLog log = player.getCombatLog(false);
		final long now = System.currentTimeMillis();
		if ((log == null) || !log.isActive(now, CombatLogConfig.FIGHT_MILLIS))
		{
			return;
		}

		final Report report = log.freeze(now);
		if (debuffs != null)
		{
			report.activeDebuffs = debuffs;
		}
		fillDeath(report, player, killer);

		if (CombatLogConfig.CHAT_SUMMARY)
		{
			sendChatSummary(player, report);
		}
		if (CombatLogConfig.SHOW_ON_DEATH)
		{
			show(player, "summary");
		}
	}

	// ---------------------------------------------------------------------------
	// Report
	// ---------------------------------------------------------------------------

	private static void fillDeath(Report report, Player player, Creature killer)
	{
		report.maxHp = player.getMaxHp();
		report.maxCp = player.getMaxCp();

		// The killing blow: the last damage taken.
		for (int i = report.entries.size() - 1; i >= 0; i--)
		{
			final Entry e = report.entries.get(i);
			if ((e.type == CombatLog.HIT) || (e.type == CombatLog.DOT))
			{
				report.killingBlow = e.getTotal();
				report.killingBlowCrit = e.crit;
				report.killingBlowSkill = skillName(e.skill, e.type == CombatLog.DOT);
				break;
			}
		}

		if (killer == null)
		{
			return;
		}

		final Creature owner = getPvpOwner(killer);
		final Creature who = owner != null ? owner : killer;
		report.killerPvp = owner != null;
		report.killerName = who.getName();
		report.killerOwner = (owner != null) && (owner != killer) ? killer.getName() : null;
		report.killerLevel = who.getLevel();
		report.killerClass = getClassName(who);
		final Clan clan = who.getClan();
		report.killerClan = clan == null ? null : clan.getName();
		report.killerHp = (int) who.getCurrentHp();
		report.killerMaxHp = who.getMaxHp();
		report.killerCp = (int) who.getCurrentCp();
		report.killerMaxCp = who.getMaxCp();
		report.killerDistance = (int) player.calculateDistance3D(killer);

		final Weapon weapon = who.getActiveWeaponItem();
		if (weapon != null)
		{
			final Item item = who.getActiveWeaponInstance();
			final int enchant = item == null ? 0 : item.getEnchantLevel();
			report.killerWeapon = (enchant > 0 ? "+" + enchant + " " : "") + weapon.getName();
		}
	}

	/**
	 * @param creature a player or fake player
	 * @return the name of its class, {@code null} if it has none
	 */
	public static String getClassName(Creature creature)
	{
		ClassInfoHolder info = null;
		if (creature.isPlayer())
		{
			info = ClassListData.getInstance().getClass(creature.asPlayer().getPlayerClass());
		}
		else if (creature.isNpc())
		{
			final FakePlayerHolder fake = ((Npc) creature).getTemplate().getFakePlayerInfo();
			if ((fake != null) && (fake.getPlayerClass() != null))
			{
				info = ClassListData.getInstance().getClass(fake.getPlayerClass());
			}
		}
		return info == null ? null : info.getClassName();
	}

	private static void sendChatSummary(Player player, Report report)
	{
		final StringBuilder sb = new StringBuilder(160);
		sb.append("Combat log: ");
		if (report.killerName != null)
		{
			sb.append("killed by ").append(report.killerName);
			if (report.killerOwner != null)
			{
				sb.append("'s ").append(report.killerOwner);
			}
			if (report.killerClass != null)
			{
				sb.append(" (").append(report.killerClass).append(' ').append(report.killerLevel).append(')');
			}
			if (report.killingBlowSkill != null)
			{
				sb.append(" with ").append(report.killingBlowSkill).append(" for ").append(report.killingBlow).append(report.killingBlowCrit ? " (crit)" : "");
			}
			sb.append(". ");
		}
		sb.append("You took ").append(report.damageTaken).append(" damage from ").append(report.attackers.size()).append(report.attackers.size() == 1 ? " attacker" : " attackers");
		sb.append(" in ").append(seconds(report.deathTime - report.fightStart)).append("s and dealt ").append(report.damageDealt).append(". Type .combatlog to see it again.");
		player.sendMessage(sb.toString());
	}

	/**
	 * Opens a page of the last PvP death's log.
	 * @param player the player
	 * @param page "summary", "attackers", "skills" or "timeline"
	 */
	public static void show(Player player, String page)
	{
		final CombatLog log = player.getCombatLog(false);
		final Report report = log == null ? null : log.getLastReport();
		if (report == null)
		{
			player.sendMessage("You have no PvP death to show.");
			return;
		}

		final StringBuilder sb = new StringBuilder(8192);
		sb.append("<html><title>Combat Log</title><body><center>");
		sb.append("<table width=" + WIDTH + " border=0 cellspacing=0 cellpadding=0><tr>");
		tab(sb, "Summary", "summary", page);
		tab(sb, "Attackers", "attackers", page);
		tab(sb, "Skills", "skills", page);
		tab(sb, "Timeline", "timeline", page);
		sb.append("</tr></table><img src=\"L2UI.SquareGray\" width=" + WIDTH + " height=1><br>");

		switch (page)
		{
			case "attackers":
			{
				attackersPage(sb, report);
				break;
			}
			case "skills":
			{
				skillsPage(sb, report);
				break;
			}
			case "timeline":
			{
				timelinePage(sb, report);
				break;
			}
			default:
			{
				summaryPage(sb, report);
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
		sb.append("<td align=center><button value=\"").append(label).append("\" action=\"bypass voiced_combatlog ").append(page).append("\" width=66 height=22 back=\"L2UI_CT1.Button_DF_Down\" fore=\"").append(page.equals(current) ? "L2UI_CT1.Button_DF_Down" : "L2UI_CT1.Button_DF").append("\"></td>");
	}

	private static void header(StringBuilder sb, String title)
	{
		sb.append("<table width=" + WIDTH + " height=20 border=0 cellspacing=0 cellpadding=0 bgcolor=000000><tr><td align=center><font color=\"LEVEL\">").append(title).append("</font></td></tr></table>");
		sb.append("<img src=\"L2UI.SquareGray\" width=" + WIDTH + " height=1>");
	}

	/**
	 * Opens a table of rows on a dark background.
	 */
	private static void box(StringBuilder sb)
	{
		sb.append("<table width=" + WIDTH + " border=0 cellspacing=1 cellpadding=1 bgcolor=111111>");
	}

	private static void summaryPage(StringBuilder sb, Report r)
	{
		// The killer.
		header(sb, "KILLED BY");
		box(sb);
		if (r.killerName == null)
		{
			sb.append("<tr><td><font color=\"B0B0B0\">Unknown</font></td></tr>");
		}
		else
		{
			sb.append("<tr><td colspan=2><font color=\"FF6666\">").append(escape(r.killerName)).append("</font>");
			if (r.killerOwner != null)
			{
				sb.append("<font color=\"B0B0B0\"> - ").append(escape(r.killerOwner)).append("</font>");
			}
			sb.append("</td></tr>");
			row(sb, "Class", (r.killerClass != null ? r.killerClass + " " : "") + "Lv. " + r.killerLevel);
			if (r.killerClan != null)
			{
				row(sb, "Clan", escape(r.killerClan));
			}
			if (r.killerWeapon != null)
			{
				row(sb, "Weapon", escape(r.killerWeapon));
			}
			row(sb, "Left with", (r.killerMaxCp > 0 ? "<font color=\"FFCC33\">CP " + percent(r.killerCp, r.killerMaxCp) + "%</font> " : "") + "<font color=\"FF6666\">HP " + percent(r.killerHp, r.killerMaxHp) + "%</font> (" + r.killerHp + "/" + r.killerMaxHp + ")");
			if (r.killingBlowSkill != null)
			{
				row(sb, "Killing blow", r.killingBlowSkill + " <font color=\"FF6666\">" + r.killingBlow + "</font>" + (r.killingBlowCrit ? " <font color=\"FFCC33\">CRIT</font>" : ""));
			}
			row(sb, "Distance", String.valueOf(r.killerDistance));
		}
		sb.append("</table><br>");

		// The fight.
		final long duration = Math.max(1, r.deathTime - r.fightStart);
		header(sb, "THE FIGHT");
		box(sb);
		row(sb, "Lasted", seconds(duration) + " s");
		row(sb, "Damage taken", r.damageTaken + " <font color=\"A0A0A0\">(CP " + r.cpTaken + " / HP " + (r.damageTaken - r.cpTaken) + ")</font>");
		row(sb, "Taken per sec", String.valueOf((r.damageTaken * 1000) / duration));
		row(sb, "Your max", "CP " + r.maxCp + " / HP " + r.maxHp);
		if (r.heals > 0)
		{
			row(sb, "Healed", r.heals + "x: HP " + r.healedHp + " / CP " + r.healedCp);
		}
		if ((r.debuffsTaken > 0) || (r.resists > 0))
		{
			row(sb, "Debuffs", r.debuffsTaken + " landed, " + r.resists + " resisted");
		}
		if (r.cancelled > 0)
		{
			row(sb, "Buffs lost", r.cancelled + " cancelled");
		}
		row(sb, "You dealt", r.damageDealt + " <font color=\"A0A0A0\">(" + r.hitsDealt + " hits, " + r.critsDealt + " crits, " + r.missesDealt + " missed)</font>");
		row(sb, "Per sec", String.valueOf((r.damageDealt * 1000) / duration));
		sb.append("</table>");
	}

	private static void attackersPage(StringBuilder sb, Report r)
	{
		// The attackers.
		header(sb, "ATTACKERS");
		box(sb);
		sb.append("<tr><td width=105><font color=\"A0A0A0\">Name</font></td><td width=60 align=right><font color=\"A0A0A0\">Damage</font></td><td width=35 align=right><font color=\"A0A0A0\">%</font></td><td width=70 align=right><font color=\"A0A0A0\">Hit/Crit</font></td></tr>");
		int shown = 0;
		for (Side side : r.attackers)
		{
			if ((shown++ >= 8) || (sb.length() > (HTML_LIMIT - 4000)))
			{
				break;
			}
			sb.append("<tr><td><font color=\"").append(side.pvp ? "FF9966" : "B0B0B0").append("\">").append(escape(side.name)).append("</font></td>");
			sb.append("<td align=right>").append(side.damage).append("</td>");
			sb.append("<td align=right>").append(percent(side.damage, r.damageTaken)).append("</td>");
			sb.append("<td align=right>").append(side.hits + side.dots).append('/').append(side.crits).append("</td></tr>");

			final StringBuilder extra = new StringBuilder();
			if (side.biggestSkill != null)
			{
				extra.append("max ").append(side.biggest).append(" (").append(escape(side.biggestSkill.getName())).append(')');
			}
			else if (side.biggest > 0)
			{
				extra.append("max ").append(side.biggest).append(" (attack)");
			}
			appendCount(extra, side.evaded, "evaded");
			appendCount(extra, side.debuffs, "debuffs");
			appendCount(extra, side.resisted, "resisted");
			appendCount(extra, side.cancels, "buffs cancelled");
			if (extra.length() > 0)
			{
				sb.append("<tr><td colspan=4><font color=\"808080\">").append(extra).append("</font></td></tr>");
			}
		}
		if (r.attackers.isEmpty())
		{
			sb.append("<tr><td colspan=4><font color=\"808080\">Nobody hit you.</font></td></tr>");
		}
		sb.append("</table><br>");

		// Your targets.
		if (!r.targets.isEmpty())
		{
			header(sb, "YOUR TARGETS");
			box(sb);
			shown = 0;
			for (Side side : r.targets)
			{
				if ((shown++ >= 5) || (sb.length() > (HTML_LIMIT - 2500)))
				{
					break;
				}
				sb.append("<tr><td width=125><font color=\"66CCFF\">").append(escape(side.name)).append("</font></td>");
				sb.append("<td width=65 align=right>").append(side.damage).append("</td>");
				sb.append("<td width=80 align=right>").append(side.hits + side.dots).append('/').append(side.crits).append("</td></tr>");
			}
			sb.append("</table><br>");
		}

		// The debuffs you had.
		if (!r.activeDebuffs.isEmpty())
		{
			header(sb, "DEBUFFS ON YOU");
			box(sb);
			for (String[] debuff : r.activeDebuffs)
			{
				if (sb.length() > (HTML_LIMIT - 300))
				{
					break;
				}
				sb.append("<tr><td width=34 height=36>").append(icon(debuff[1])).append("</td><td width=236>").append(escape(debuff[0])).append(" <font color=\"808080\">").append(debuff[2]).append("s");
				if (!debuff[3].isEmpty())
				{
					sb.append(" - ").append(escape(debuff[3]));
				}
				sb.append("</font></td></tr>");
			}
			sb.append("</table>");
		}
	}

	private static void skillsPage(StringBuilder sb, Report r)
	{
		header(sb, "WHAT HURT YOU");
		box(sb);
		int shown = 0;
		for (SkillUse use : r.skills)
		{
			if ((shown++ >= 20) || (sb.length() > (HTML_LIMIT - 400)))
			{
				break;
			}
			sb.append("<tr><td width=34 height=36>").append(use.skill == null ? "" : icon(use.skill.getIcon())).append("</td><td width=236>");
			sb.append(escape(skillName(use.skill, false))).append(" <font color=\"808080\">- ").append(escape(use.source)).append("</font><br1>");
			sb.append("<font color=\"FF6666\">").append(use.damage).append("</font> <font color=\"A0A0A0\">(").append(percent(use.damage, r.damageTaken)).append("%) ").append(use.hits).append(use.hits == 1 ? " hit" : " hits");
			if (use.crits > 0)
			{
				sb.append(", ").append(use.crits).append(" crit");
			}
			sb.append(", max ").append(use.biggest).append(", avg ").append(use.damage / Math.max(1, use.hits)).append("</font></td></tr>");
		}
		if (r.skills.isEmpty())
		{
			sb.append("<tr><td><font color=\"808080\">You took no damage.</font></td></tr>");
		}
		sb.append("</table>");
	}

	private static void timelinePage(StringBuilder sb, Report r)
	{
		header(sb, "LAST EVENTS (newest first)");
		sb.append("<table width=" + WIDTH + " border=0 cellspacing=0 cellpadding=1>");
		boolean dark = false;
		for (int i = r.entries.size() - 1; i >= 0; i--)
		{
			if (sb.length() > (HTML_LIMIT - 300))
			{
				sb.append("<tr><td colspan=2><font color=\"808080\">...</font></td></tr>");
				break;
			}

			final Entry e = r.entries.get(i);
			sb.append("<tr><td width=44 valign=top").append(dark ? " bgcolor=111111" : "").append("><font color=\"808080\">-").append(seconds(r.deathTime - e.time)).append("s</font></td><td width=226").append(dark ? " bgcolor=111111" : "").append('>');
			dark = !dark;
			timelineText(sb, e, r);
			sb.append("</td></tr>");
		}
		if (r.entries.isEmpty())
		{
			sb.append("<tr><td><font color=\"808080\">Nothing happened.</font></td></tr>");
		}
		sb.append("</table>");
		if (r.droppedEntries > 0)
		{
			sb.append("<font color=\"808080\">").append(r.droppedEntries).append(" older events are only in the totals.</font>");
		}
	}

	private static void timelineText(StringBuilder sb, Entry e, Report r)
	{
		final String name = "<font color=\"" + (e.pvp ? "FF9966" : "B0B0B0") + "\">" + escape(e.name) + "</font>";
		final String skill = escape(skillName(e.skill, e.type == CombatLog.DOT));
		switch (e.type)
		{
			case CombatLog.HIT:
			case CombatLog.DOT:
			{
				sb.append(name).append(' ').append(skill).append(" <font color=\"FF6666\">-").append(e.getTotal()).append("</font>");
				if (e.crit)
				{
					sb.append(" <font color=\"FFCC33\">CRIT</font>");
				}
				sb.append("<br1><font color=\"808080\">");
				if (e.cp > 0)
				{
					sb.append("CP -").append(e.cp).append(' ');
				}
				if (e.hp > 0)
				{
					sb.append("HP -").append(e.hp).append(' ');
				}
				sb.append("left CP ").append(percent(e.cpAfter, r.maxCp)).append("% HP ").append(percent(e.hpAfter, r.maxHp)).append("%</font>");
				break;
			}
			case CombatLog.EVADED:
			{
				sb.append("<font color=\"66FF66\">Evaded</font> ").append(name).append("'s attack");
				break;
			}
			case CombatLog.RESISTED:
			{
				sb.append("<font color=\"66FF66\">Resisted</font> ").append(name).append("'s ").append(skill);
				break;
			}
			case CombatLog.DEBUFF:
			{
				sb.append(name).append(" <font color=\"CC66FF\">").append(skill).append("</font> landed");
				break;
			}
			case CombatLog.CANCEL:
			{
				sb.append(name).append(" cancelled <font color=\"CC66FF\">").append(e.count).append(e.count == 1 ? " buff" : " buffs").append("</font> (").append(skill).append(')');
				break;
			}
			case CombatLog.HEAL:
			{
				sb.append(name).append(' ').append(skill).append(" <font color=\"66FF66\">");
				if (e.hp > 0)
				{
					sb.append("+").append(e.hp).append(" HP ");
				}
				if (e.cp > 0)
				{
					sb.append("+").append(e.cp).append(" CP");
				}
				sb.append("</font>");
				break;
			}
			case CombatLog.DEALT:
			{
				sb.append("You hit <font color=\"66CCFF\">").append(escape(e.name)).append("</font> ").append(skill).append(" <font color=\"66CCFF\">").append(e.hp).append("</font>");
				if (e.crit)
				{
					sb.append(" <font color=\"FFCC33\">CRIT</font>");
				}
				break;
			}
			case CombatLog.YOU_MISSED:
			{
				sb.append("<font color=\"808080\">You missed ").append(escape(e.name)).append("</font>");
				break;
			}
		}
	}

	// ---------------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------------

	/**
	 * @param creature a creature
	 * @param player the player
	 * @return {@code true} if the creature is another PvP fighter
	 */
	private static boolean isPvp(Creature creature, Player player)
	{
		final Creature owner = getPvpOwner(creature);
		return (owner != null) && (owner != player);
	}

	/**
	 * @param creature a creature
	 * @return its name, with its owner's for a summon, trap or servitor
	 */
	private static String describe(Creature creature)
	{
		final Creature owner = getPvpOwner(creature);
		if ((owner != null) && (owner != creature))
		{
			return owner.getName() + "'s " + creature.getName();
		}
		return creature.getName();
	}

	/**
	 * @param skill a skill
	 * @return its name, with its level when above 1
	 */
	public static String getSkillName(Skill skill)
	{
		return skillName(skill, false);
	}

	private static String skillName(Skill skill, boolean dot)
	{
		if (skill == null)
		{
			return dot ? "Bleed" : "Attack";
		}
		return skill.getLevel() > 1 ? skill.getName() + " Lv. " + skill.getLevel() : skill.getName();
	}

	private static String icon(String icon)
	{
		return (icon == null) || icon.isEmpty() ? "" : "<img src=\"" + icon + "\" width=32 height=32>";
	}

	private static void row(StringBuilder sb, String label, String value)
	{
		sb.append("<tr><td width=90><font color=\"A0A0A0\">").append(label).append("</font></td><td width=180>").append(value).append("</td></tr>");
	}

	private static void appendCount(StringBuilder sb, int count, String what)
	{
		if (count > 0)
		{
			if (sb.length() > 0)
			{
				sb.append(", ");
			}
			sb.append(count).append(' ').append(what);
		}
	}

	private static long percent(long value, long max)
	{
		return max <= 0 ? 0 : Math.max(0, Math.min(100, Math.round((value * 100.0) / max)));
	}

	private static String seconds(long millis)
	{
		return String.format(Locale.US, "%.1f", millis / 1000.0);
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
