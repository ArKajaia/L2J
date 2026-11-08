import java.util.List;
import java.util.Map;
import java.util.Set;

import org.l2jmobius.gameserver.ai.fight.ClassGroup;
import org.l2jmobius.gameserver.ai.fight.EffectMeaning;
import org.l2jmobius.gameserver.ai.fight.FightRead;
import org.l2jmobius.gameserver.ai.fight.ScoreContext;
import org.l2jmobius.gameserver.ai.fight.Scored;
import org.l2jmobius.gameserver.ai.fight.SkillFacts;
import org.l2jmobius.gameserver.ai.fight.SkillOption;
import org.l2jmobius.gameserver.ai.fight.SkillOption.Kind;
import org.l2jmobius.gameserver.ai.fight.SkillScorer;
import org.l2jmobius.gameserver.ai.fight.SkillWeights;

public class SkillScorerTest
{
	static final int SELF = 1;
	static final int TARGET = 7;
	
	static SkillFacts stun(int hitTime)
	{
		return new SkillFacts(100, 1, true, false, 600, hitTime, 0, 15000, 3000, "STUN", Set.of("STUN"), false, 0, 80, 76, "PHYSICAL");
	}
	
	static SkillFacts nuke(int id, int reuse)
	{
		return new SkillFacts(id, 1, true, false, 900, 1000, 0, reuse, 0, "NONE", Set.of("MAGICAL_ATTACK"), false, 100, 0, 76, "MAGIC");
	}
	
	static SkillOption skill(SkillFacts facts, double damage)
	{
		return new SkillOption(Kind.SKILL, facts, damage, true);
	}
	
	static final SkillOption AUTO = new SkillOption(Kind.AUTO_ATTACK, null, 0, true);
	static final SkillOption HOLD = new SkillOption(Kind.HOLD, null, 0, true);
	
	static ScoreContext ctx(long now, double skillLevel, boolean meleeNear, boolean ranged)
	{
		return new ScoreContext(SELF, TARGET, now, 1000, 300, 0.8, 0.8, meleeNear, ranged, false, false, ClassGroup.LIGHT_FIGHTER, skillLevel);
	}
	
	static Scored best(List<Scored> scored)
	{
		return scored.get(0);
	}
	
	public static void main(String[] args)
	{
		Check.run("SkillScorerTest", //
			() -> // An instant stun interrupts a cast in progress: better than a nuke.
			{
				final FightRead read = new FightRead(1, 1);
				read.onEnemyCast(TARGET, nuke(1235, 3000), SELF, 1000, 2500);
				final SkillScorer scorer = new SkillScorer(read, SkillWeights.DEFAULT, 1);
				Check.eq(best(scorer.score(List.of(skill(nuke(1230, 3000), 1000), skill(stun(0), 0)), ctx(1000, 1, false, true))).option().skill().id(), 100, "the stun");
			}, //
			() -> // Already stunned for 4 s: no value in another stun.
			{
				final FightRead read = new FightRead(1, 1);
				read.onEffects(TARGET, Map.of(EffectMeaning.STUNNED, 5000L), 1000);
				final List<Scored> scored = new SkillScorer(read, SkillWeights.DEFAULT, 1).score(List.of(skill(stun(600), 0), skill(nuke(1230, 3000), 1000)), ctx(1000, 1, false, true));
				Check.eq(best(scored).option().skill().id(), 1230, "the nuke");
				for (Scored s : scored)
				{
					if (s.option().skill().id() == 100)
					{
						Check.near(s.terms().getOrDefault("control", 0.0), 0, 0, "no control value");
					}
				}
			}, //
			() -> // An invulnerable target: no damage anywhere, nothing spent, never a null skill.
			{
				final FightRead read = new FightRead(1, 1);
				read.onEffects(TARGET, Map.of(EffectMeaning.INVULNERABLE, 30000L), 1000);
				final Scored top = best(new SkillScorer(read, SkillWeights.DEFAULT, 1).score(List.of(skill(nuke(1230, 3000), 1000), AUTO, HOLD), ctx(1000, 1, false, true)));
				Check.isTrue((top.option().kind() == Kind.HOLD) || (top.option().kind() == Kind.AUTO_ATTACK), "hold or auto attack: " + top.option().kind());
			}, //
			() -> // Winning the race: a long cooldown nuke costs nothing.
			{
				final FightRead read = new FightRead(1, 1);
				read.onHp(0, SELF, 6000, 10000, TARGET, 2000, 20000);
				read.onHp(4000, SELF, 5800, 10000, TARGET, 1000, 20000);
				final SkillScorer scorer = new SkillScorer(read, SkillWeights.DEFAULT, 1);
				final ScoreContext context = ctx(4000, 1, false, true);
				Check.isTrue(scorer.raceWon(context), "race won");
				final Scored top = best(scorer.score(List.of(skill(nuke(1239, 120000), 3000), AUTO), context));
				Check.eq(top.option().skill().id(), 1239, "the big nuke");
				Check.near(top.terms().getOrDefault("cooldownCost", 0.0), 0, 0, "no cooldown cost");
			}, //
			() -> // Not winning (no damage seen): the long cooldown costs.
			{
				final FightRead read = new FightRead(1, 1);
				final SkillScorer scorer = new SkillScorer(read, SkillWeights.DEFAULT, 1);
				Check.isTrue(!scorer.raceWon(ctx(0, 1, false, true)), "no race with infinite times");
				final Scored only = scorer.score(List.of(skill(nuke(1239, 120000), 3000)), ctx(0, 1, false, true)).get(0);
				Check.isTrue(only.terms().get("cooldownCost") < 0, "cooldown cost");
			}, //
			() -> // A support with nothing to cast: auto attack or hold.
			{
				final List<Scored> scored = new SkillScorer(new FightRead(1, 1), SkillWeights.DEFAULT, 1).score(List.of(AUTO, HOLD), ctx(0, 1, false, false));
				Check.eq(scored.size(), 2, "both scored");
				Check.isTrue(best(scored).option().skill() == null, "no skill");
			}, //
			() -> // A melee near: a long cast risks an interrupt, unless its stun is known on cooldown.
			{
				final FightRead read = new FightRead(1, 1);
				final SkillScorer scorer = new SkillScorer(read, SkillWeights.DEFAULT, 1);
				Check.isTrue(scorer.score(List.of(skill(nuke(1230, 3000), 1000)), ctx(1000, 1, true, true)).get(0).terms().get("risk") < 0, "risk");
				read.onEnemyCast(TARGET, stun(0), SELF, 1000, 1000);
				Check.near(scorer.score(List.of(skill(nuke(1230, 3000), 1000)), ctx(2000, 1, true, true)).get(0).terms().getOrDefault("risk", 0.0), 0, 0, "its stun is down");
			}, //
			() -> // The caster's real cast time counts: half the time, twice the damage per second.
			{
				final SkillScorer scorer = new SkillScorer(new FightRead(1, 1), SkillWeights.DEFAULT, 1);
				final SkillFacts slow = nuke(1230, 3000);
				final double base = scorer.score(List.of(new SkillOption(Kind.SKILL, slow, 1000, true, 2000)), ctx(0, 1, false, true)).get(0).terms().get("damage");
				final double fast = scorer.score(List.of(new SkillOption(Kind.SKILL, slow, 1000, true, 1000)), ctx(0, 1, false, true)).get(0).terms().get("damage");
				Check.near(fast, base * 2, 1e-9, "twice as fast");
			}, //
			() -> // A melee near costs a share of a skill's value, never more than the skill is worth.
			{
				final SkillScorer scorer = new SkillScorer(new FightRead(1, 1), SkillWeights.DEFAULT, 1);
				final List<Scored> scored = scorer.score(List.of(skill(nuke(1230, 3000), 1000), HOLD), ctx(1000, 1, true, false));
				Check.eq(scored.get(0).option().skill().id(), 1230, "the nuke still beats holding");
				Check.isTrue(scored.get(0).terms().get("risk") > -scored.get(0).terms().get("damage"), "risk below the damage");
			}, //
			() -> // Skill level 1 plays the same on any seed; level 0 strays.
			{
				final List<SkillOption> options = List.of(skill(nuke(1230, 3000), 1000), skill(nuke(1231, 3000), 900), AUTO);
				final List<Scored> a = new SkillScorer(new FightRead(1, 1), SkillWeights.DEFAULT, 1).score(options, ctx(0, 1, false, true));
				final List<Scored> b = new SkillScorer(new FightRead(1, 1), SkillWeights.DEFAULT, 2).score(options, ctx(0, 1, false, true));
				Check.near(a.get(0).value(), b.get(0).value(), 1e-9, "skilled: same values");
				final List<Scored> c = new SkillScorer(new FightRead(1, 1), SkillWeights.DEFAULT, 1).score(options, ctx(0, 0, false, true));
				final List<Scored> d = new SkillScorer(new FightRead(1, 1), SkillWeights.DEFAULT, 2).score(options, ctx(0, 0, false, true));
				Check.isTrue(Math.abs(c.get(0).value() - d.get(0).value()) > 1e-6, "unskilled: values stray");
			});
	}
}
