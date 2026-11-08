import java.util.List;
import java.util.Random;
import java.util.Set;

import org.l2jmobius.gameserver.ai.fight.ClassGroup;
import org.l2jmobius.gameserver.ai.fight.FightRead;
import org.l2jmobius.gameserver.ai.fight.Reaction;
import org.l2jmobius.gameserver.ai.fight.ReflexEvent;
import org.l2jmobius.gameserver.ai.fight.Reflexes;
import org.l2jmobius.gameserver.ai.fight.ScoreContext;
import org.l2jmobius.gameserver.ai.fight.SkillFacts;
import org.l2jmobius.gameserver.ai.fight.SkillOption;
import org.l2jmobius.gameserver.ai.fight.SkillOption.Kind;
import org.l2jmobius.gameserver.ai.fight.SkillScorer;
import org.l2jmobius.gameserver.ai.fight.SkillWeights;

public class ReflexesTest
{
	static final int SELF = 1;
	static final int TARGET = 7;
	
	static SkillFacts nukeFacts(int id)
	{
		return new SkillFacts(id, 1, true, false, 900, 1000, 0, 3000, 0, "NONE", Set.of("MAGICAL_ATTACK"), false, 100, 0, 76, "MAGIC");
	}
	
	static SkillOption nuke(int id, double damage)
	{
		return new SkillOption(Kind.SKILL, nukeFacts(id), damage, true);
	}
	
	static SkillOption stun(int hitTime)
	{
		return new SkillOption(Kind.SKILL, new SkillFacts(100, 1, true, false, 600, hitTime, 0, 15000, 3000, "STUN", Set.of("STUN"), false, 0, 80, 76, "PHYSICAL"), 0, true);
	}
	
	static SkillOption ultimateDefense()
	{
		return new SkillOption(Kind.SKILL, new SkillFacts(110, 1, false, true, 0, 1000, 0, 1800000, 30000, "PD_UP_SPECIAL", Set.of("BUFF"), false, 0, 0, 76, "NONE"), 0, true);
	}
	
	static final SkillOption AUTO = new SkillOption(Kind.AUTO_ATTACK, null, 0, true);
	
	static ScoreContext ctx(long now, boolean disabled)
	{
		return new ScoreContext(SELF, TARGET, now, 1000, 300, 0.5, 0.5, false, true, false, disabled, ClassGroup.LIGHT_FIGHTER, 1);
	}
	
	static Reflexes reflexes(FightRead read)
	{
		return new Reflexes(read, new SkillScorer(read, SkillWeights.DEFAULT, 1), SkillWeights.DEFAULT);
	}
	
	public static void main(String[] args)
	{
		Check.run("ReflexesTest", //
			() -> // Reaction delays: 75-125 ms skilled, 300-500 ms unskilled, in between halfway.
			{
				final Random random = new Random(1);
				for (int i = 0; i < 200; i++)
				{
					final long fast = Reflexes.reactionDelay(1, random);
					final long slow = Reflexes.reactionDelay(0, random);
					final long mid = Reflexes.reactionDelay(0.5, random);
					Check.isTrue((fast >= 75) && (fast <= 125), "skilled " + fast);
					Check.isTrue((slow >= 300) && (slow <= 500), "unskilled " + slow);
					Check.isTrue((mid >= 187) && (mid <= 312), "halfway " + mid);
				}
			}, //
			() -> // A nuke coming at it: an instant stun interrupts it.
			{
				final FightRead read = new FightRead(1, 1);
				read.onEnemyCast(TARGET, nukeFacts(1235), SELF, 1000, 2500);
				final Reaction reaction = reflexes(read).decide(List.of(new ReflexEvent(ReflexEvent.Kind.ENEMY_CAST_AT_ME, TARGET, nukeFacts(1235), 1000)), ctx(1000, false), List.of(nuke(1230, 1000), stun(0)), nuke(1230, 1000));
				Check.eq(reaction.option().skill().id(), 100, "the stun");
			}, //
			() -> // Winning the race: it keeps nuking rather than spend time on a stun with a cast time; an instant one is still worth it.
			{
				final FightRead read = new FightRead(1, 1);
				read.onHp(0, SELF, 6000, 10000, TARGET, 2000, 20000);
				read.onHp(4000, SELF, 5800, 10000, TARGET, 1000, 20000);
				read.onEnemyCast(TARGET, nukeFacts(1235), SELF, 4000, 6000);
				final ReflexEvent event = new ReflexEvent(ReflexEvent.Kind.ENEMY_CAST_AT_ME, TARGET, nukeFacts(1235), 4000);
				Check.isTrue(reflexes(read).decide(List.of(event), ctx(4000, false), List.of(nuke(1230, 1000), stun(600)), nuke(1230, 1000)) == null, "keeps nuking");
				Check.eq(reflexes(read).decide(List.of(event), ctx(4000, false), List.of(nuke(1230, 1000), stun(0)), nuke(1230, 1000)).option().skill().id(), 100, "instant stun");
			}, //
			() -> // Two events at once: one reaction, the better one; the same events again: none.
			{
				final FightRead read = new FightRead(1, 1);
				read.onHp(0, SELF, 1000, 10000, TARGET, 5000, 20000);
				read.onHp(4000, SELF, 200, 10000, TARGET, 5000, 20000);
				read.onEnemyCast(TARGET, nukeFacts(1235), SELF, 4000, 5500);
				final Reflexes reflexes = reflexes(read);
				final List<ReflexEvent> events = List.of(new ReflexEvent(ReflexEvent.Kind.ENEMY_CAST_AT_ME, TARGET, nukeFacts(1235), 4000), new ReflexEvent(ReflexEvent.Kind.HP_CRASH, 0, null, 4000));
				final List<SkillOption> options = List.of(nuke(1230, 1000), stun(0), ultimateDefense());
				Check.eq(reflexes.decide(events, ctx(4000, false), options, nuke(1230, 1000)).option().skill().id(), 110, "the defensive");
				Check.isTrue(reflexes.decide(events, ctx(4000, false), options, nuke(1230, 1000)) == null, "nothing twice");
			}, //
			() -> // HP crashing with no defensive: never a reaction without an option.
			{
				final FightRead read = new FightRead(1, 1);
				read.onHp(0, SELF, 1000, 10000, TARGET, 5000, 20000);
				read.onHp(4000, SELF, 200, 10000, TARGET, 5000, 20000);
				final Reaction reaction = reflexes(read).decide(List.of(new ReflexEvent(ReflexEvent.Kind.HP_CRASH, 0, null, 4000)), ctx(4000, false), List.of(nuke(1230, 1000), AUTO), nuke(1230, 1000));
				Check.isTrue((reaction == null) || (reaction.option() != null), "no empty reaction");
			}, //
			() -> // Stunned or silenced: no reflex.
			{
				final FightRead read = new FightRead(1, 1);
				read.onEnemyCast(TARGET, nukeFacts(1235), SELF, 1000, 2500);
				Check.isTrue(reflexes(read).decide(List.of(new ReflexEvent(ReflexEvent.Kind.ENEMY_CAST_AT_ME, TARGET, nukeFacts(1235), 1000)), ctx(1000, true), List.of(stun(0)), null) == null, "disabled");
			});
	}
}
