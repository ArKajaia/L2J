import java.util.Map;
import java.util.Set;

import org.l2jmobius.gameserver.ai.fight.ClassGroup;
import org.l2jmobius.gameserver.ai.fight.EffectMeaning;
import org.l2jmobius.gameserver.ai.fight.FightRead;
import org.l2jmobius.gameserver.ai.fight.SkillFacts;

public class FightReadTest
{
	/** Knowledge values: -1 is level 0, 1 is level 1. */
	static final double NOVICE = -1;
	static final double EXPERT = 1;
	
	static SkillFacts skill(int id, int reuse)
	{
		return new SkillFacts(id, 1, true, false, 600, 1000, 0, reuse, 0, "NONE", Set.of("MAGICAL_ATTACK"), false, 100, 0, 76, "MAGIC");
	}
	
	static SkillFacts stun(int activateRate)
	{
		return new SkillFacts(100, 1, true, false, 40, 0, 0, 10000, 9000, "STUN", Set.of("STUN"), false, 0, activateRate, 76, "PHYSICAL");
	}
	
	public static void main(String[] args)
	{
		Check.run("FightReadTest", //
			() -> // A seen skill is on cooldown for its reuse time.
			{
				final FightRead read = new FightRead(EXPERT, 1);
				read.onEnemyCast(7, skill(1235, 30000), 1, 1000, 1500);
				Check.isTrue(read.isOnCooldown(7, 1235, 20000), "on cooldown at 20 s");
				Check.isTrue(!read.isOnCooldown(7, 1235, 31001), "ready at 31 s");
			}, //
			() -> // A novice remembers only its last two cooldowns.
			{
				final FightRead read = new FightRead(NOVICE, 1);
				read.onEnemyCast(7, skill(1, 30000), 1, 1000, 1100);
				read.onEnemyCast(7, skill(2, 30000), 1, 2000, 2100);
				read.onEnemyCast(7, skill(3, 30000), 1, 3000, 3100);
				Check.isTrue(!read.isRemembered(7, 1), "the oldest is forgotten");
				Check.isTrue(read.isRemembered(7, 2) && read.isRemembered(7, 3), "the last two are remembered");
			}, //
			() -> // An expert remembers twelve.
			{
				final FightRead read = new FightRead(EXPERT, 1);
				for (int id = 1; id <= 13; id++)
				{
					read.onEnemyCast(7, skill(id, 30000), 1, id * 100, (id * 100) + 50);
				}
				Check.isTrue(!read.isRemembered(7, 1) && read.isRemembered(7, 2) && read.isRemembered(7, 13), "twelve remembered");
			}, //
			() -> // The cast in progress, until its end.
			{
				final FightRead read = new FightRead(EXPERT, 1);
				read.onEnemyCast(7, skill(1235, 3000), 1, 1000, 2500);
				Check.eq(read.currentCast(7, 2000).skill().id(), 1235, "casting at 2 s");
				Check.isTrue(read.currentCast(7, 2600) == null, "done at 2.6 s");
			}, //
			() -> // A novice notices a new icon 1.5 s late, an expert at once.
			{
				final FightRead novice = new FightRead(NOVICE, 1);
				novice.onEffects(7, Map.of(EffectMeaning.STUNNED, 9000L), 1000);
				Check.isTrue(!novice.has(7, EffectMeaning.STUNNED, 1200), "novice: not yet");
				Check.isTrue(novice.has(7, EffectMeaning.STUNNED, 2600), "novice: noticed");
				final FightRead expert = new FightRead(EXPERT, 1);
				expert.onEffects(7, Map.of(EffectMeaning.STUNNED, 9000L), 1000);
				Check.isTrue(expert.has(7, EffectMeaning.STUNNED, 1000), "expert: at once");
				Check.near(expert.remaining(7, EffectMeaning.STUNNED, 4000), 5000, 0, "5 s left");
				Check.isTrue(!expert.has(7, EffectMeaning.STUNNED, 9000), "gone when it expires");
			}, //
			() -> // HP falling 400 in 4 s: 100 per second, 6 s to die.
			{
				final FightRead read = new FightRead(EXPERT, 1);
				read.onHp(0, 1, 1000, 1000, 7, 5000, 5000);
				read.onHp(4000, 1, 600, 1000, 7, 4000, 5000);
				Check.near(read.damageTakenPerSecond(4000), 100, 1, "taken");
				Check.near(read.damageDealtPerSecond(4000), 250, 1, "dealt");
				Check.near(read.timeToDie(4000, 0), 6.0, 0.01, "time to die");
				Check.near(read.timeToKill(4000, 500), 8.0, 0.01, "time to kill");
			}, //
			() -> // No damage yet: infinite times.
			{
				final FightRead read = new FightRead(EXPERT, 1);
				read.onHp(0, 1, 1000, 1000, 7, 5000, 5000);
				Check.isTrue(Double.isInfinite(read.timeToDie(0, 0)), "time to die");
				Check.isTrue(Double.isInfinite(read.timeToKill(0, 0)), "time to kill");
			}, //
			() -> // Land chance: base, class, record, immunity.
			{
				final FightRead read = new FightRead(EXPERT, 1);
				Check.near(read.landChance(7, stun(80), ClassGroup.HEAVY_FIGHTER, 0), 0.56, 0.001, "base");
				for (int i = 0; i < 4; i++)
				{
					read.onCcAttempt(7, 100, true);
				}
				Check.near(read.landChance(7, stun(80), ClassGroup.HEAVY_FIGHTER, 0), 0.78, 0.001, "after four landed");
				read.onEffects(7, Map.of(EffectMeaning.CC_IMMUNE, 60000L), 0);
				Check.near(read.landChance(7, stun(80), ClassGroup.HEAVY_FIGHTER, 0), 0, 0, "immune");
			}, //
			() -> // Forgetting an enemy drops everything about it.
			{
				final FightRead read = new FightRead(EXPERT, 1);
				read.onEnemyCast(7, skill(1235, 30000), 1, 1000, 5000);
				read.forget(7);
				Check.isTrue(!read.isRemembered(7, 1235), "cooldown forgotten");
				Check.isTrue(read.currentCast(7, 2000) == null, "cast forgotten");
			}, //
			() -> // Counts for the log: casts seen in all, cooldowns remembered now.
			{
				final FightRead read = new FightRead(NOVICE, 1);
				read.onEnemyCast(7, skill(1, 30000), 1, 1000, 1100);
				read.onEnemyCast(7, skill(2, 30000), 1, 2000, 2100);
				read.onEnemyCast(8, skill(3, 30000), 1, 3000, 3100);
				Check.eq(read.seenCasts(), 3, "seen");
				Check.eq(read.rememberedCount(), 2, "remembered");
			}, //
			() -> // Skill id 0 starts no cooldown memory.
			{
				final FightRead read = new FightRead(EXPERT, 1);
				read.onEnemyCast(7, skill(0, 30000), 1, 1000, 1100);
				Check.isTrue(!read.isRemembered(7, 0), "id 0 not remembered");
			});
	}
}
