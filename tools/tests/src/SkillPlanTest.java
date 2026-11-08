import java.util.List;
import java.util.Map;
import java.util.Set;

import org.l2jmobius.gameserver.ai.fight.ClassGroup;
import org.l2jmobius.gameserver.ai.fight.EffectMeaning;
import org.l2jmobius.gameserver.ai.fight.FightRead;
import org.l2jmobius.gameserver.ai.fight.ScoreContext;
import org.l2jmobius.gameserver.ai.fight.SkillFacts;
import org.l2jmobius.gameserver.ai.fight.SkillOption;
import org.l2jmobius.gameserver.ai.fight.SkillOption.Kind;
import org.l2jmobius.gameserver.ai.fight.SkillPlan;
import org.l2jmobius.gameserver.ai.fight.SkillScorer;
import org.l2jmobius.gameserver.ai.fight.SkillWeights;

public class SkillPlanTest
{
	static final int TARGET = 7;
	
	static SkillOption frenzy()
	{
		return new SkillOption(Kind.SKILL, new SkillFacts(176, 1, false, true, 0, 1000, 0, 300000, 120000, "BERSERKER", Set.of("BUFF"), false, 0, 0, 76, "NONE"), 0, true);
	}
	
	static SkillOption stun(int rate)
	{
		return new SkillOption(Kind.SKILL, new SkillFacts(100, 1, true, false, 600, 0, 0, 15000, 3000, "STUN", Set.of("STUN"), false, 0, rate, 76, "PHYSICAL"), 0, true);
	}
	
	static SkillOption nuke(int id, double damage)
	{
		return new SkillOption(Kind.SKILL, new SkillFacts(id, 1, true, false, 900, 1000, 0, 3000, 0, "NONE", Set.of("MAGICAL_ATTACK"), false, 100, 0, 76, "MAGIC"), damage, true);
	}
	
	static ScoreContext ctx(long now, double skillLevel)
	{
		return new ScoreContext(1, TARGET, now, 1000, 300, 0.8, 0.8, false, true, true, false, ClassGroup.ROBE_MAGE, skillLevel);
	}
	
	public static void main(String[] args)
	{
		Check.run("SkillPlanTest", //
			() -> // A burst: the buff, the stun, then the nukes biggest first.
			{
				final SkillScorer scorer = new SkillScorer(new FightRead(1, 1), SkillWeights.DEFAULT, 1);
				final SkillPlan plan = scorer.buildBurst(List.of(nuke(1230, 800), frenzy(), stun(80), nuke(1239, 2000)), ctx(0, 1));
				Check.eq(plan.type(), SkillPlan.Type.BURST, "a burst");
				Check.eq(plan.steps(), List.of(176, 100, 1239, 1230), "its steps");
			}, //
			() -> // A stun that would hardly land: no burst on it.
			{
				final SkillScorer scorer = new SkillScorer(new FightRead(1, 1), SkillWeights.DEFAULT, 1);
				Check.isTrue(scorer.buildBurst(List.of(frenzy(), stun(30), nuke(1239, 2000)), ctx(0, 1)) == null, "no burst");
			}, //
			() -> // Steps go in order, a plan runs out after 8 s.
			{
				final SkillPlan plan = new SkillPlan(SkillPlan.Type.BURST, List.of(176, 100, 1239), 1000);
				Check.eq(plan.next(), 176, "first step");
				Check.isTrue(!plan.advance(100), "not the next step");
				Check.isTrue(plan.advance(176), "the next step");
				Check.eq(plan.next(), 100, "second step");
				Check.isTrue(!plan.expired(9000), "not yet");
				Check.isTrue(plan.expired(9001), "expired");
			}, //
			() -> // The plan's next step gets a bonus.
			{
				final SkillScorer scorer = new SkillScorer(new FightRead(1, 1), SkillWeights.DEFAULT, 1);
				Check.isTrue(scorer.startPlan(new SkillPlan(SkillPlan.Type.BURST, List.of(1230, 1239), 0), 0, 1), "started");
				Check.eq(scorer.score(List.of(nuke(1239, 2000), nuke(1230, 800)), ctx(0, 1)).get(0).option().skill().id(), 1230, "the plan's step first");
				scorer.dropPlan();
				Check.isTrue(scorer.currentPlan() == null, "dropped");
			}, //
			() -> // An unskilled fake player doesn't plan.
			{
				final SkillScorer scorer = new SkillScorer(new FightRead(1, 1), SkillWeights.DEFAULT, 1);
				Check.isTrue(!scorer.startPlan(new SkillPlan(SkillPlan.Type.BURST, List.of(1230), 0), 0, 0), "refused");
				Check.isTrue(scorer.currentPlan() == null, "no plan");
				Check.isTrue(scorer.buildBurst(List.of(frenzy(), stun(80), nuke(1239, 2000)), ctx(0, 0)) == null, "no burst built");
			}, //
			() -> // A crowd control chain: the next stun as the held one ends, only for deep planners.
			{
				final FightRead read = new FightRead(1, 1);
				read.onEffects(TARGET, Map.of(EffectMeaning.STUNNED, 1080L), 1000);
				final SkillScorer scorer = new SkillScorer(read, SkillWeights.DEFAULT, 1);
				final SkillPlan chain = scorer.buildCcChain(List.of(stun(80), nuke(1239, 2000)), ctx(1000, 1));
				Check.eq(chain.steps(), List.of(100), "the stun next");
				Check.isTrue(scorer.buildCcChain(List.of(stun(80)), ctx(1000, 0.5)) == null, "depth 2 doesn't chain");
			}, //
			() -> // No crowd control at all: no plans, never a null step.
			{
				final SkillScorer scorer = new SkillScorer(new FightRead(1, 1), SkillWeights.DEFAULT, 1);
				Check.isTrue(scorer.buildBurst(List.of(nuke(1239, 2000)), ctx(0, 1)) == null, "no burst");
				Check.isTrue(scorer.buildCcChain(List.of(nuke(1239, 2000)), ctx(0, 1)) == null, "no chain");
			});
	}
}
