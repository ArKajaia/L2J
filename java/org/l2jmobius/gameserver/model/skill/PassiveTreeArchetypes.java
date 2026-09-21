package org.l2jmobius.gameserver.model.skill;

import java.util.HashMap;
import java.util.Map;

import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;

/**
 * Maps a character's class to the sector (Vanguard/Juggernaut/Shadowblade/
 * Deadeye/Arcanist/Hierophant) their passive tree opens on by default.
 * <p>
 * Built directly against {@link PlayerClass}, using the actual enum
 * constants rather than raw numeric ids, so a typo here is a compile
 * error instead of a silently-wrong id.
 * <p>
 * A few placements are genuine design calls rather than obvious 1:1
 * mappings - marked below - since some archetypes (Orc, Kamael) don't
 * cleanly split into the 6 sectors the way Human/Elf/Dark Elf do:
 * <ul>
 * <li>Orc has no pure damage-caster line - both Overlord and Warcryer
 * (and their 3rd-class forms) are support/buff casters, so the whole
 * Orc mage branch routes to Hierophant, not Arcanist.</li>
 * <li>Dwarf Warsmith/Maestro (fist fighters with summoned golems) are
 * placed in Juggernaut rather than a separate crafting-only bucket.</li>
 * <li>Kamael Warder is placed in Vanguard (tanky dex fighter); Inspector/
 * Judicator (debuff/support Kamael) are placed in Hierophant.</li>
 * </ul>
 * Adjust freely - this is a starting point, not a fixed ruling.
 */
public class PassiveTreeArchetypes
{
	public static final String VANGUARD = "Vanguard";
	public static final String JUGGERNAUT = "Juggernaut";
	public static final String SHADOWBLADE = "Shadowblade";
	public static final String DEADEYE = "Deadeye";
	public static final String ARCANIST = "Arcanist";
	public static final String HIEROPHANT = "Hierophant";

	private static final Map<Integer, String> CLASS_TO_SECTOR = new HashMap<>();

	static
	{
		// ===================== VANGUARD (Tank) =====================
		map(VANGUARD,
			PlayerClass.KNIGHT, PlayerClass.PALADIN, PlayerClass.DARK_AVENGER,
			PlayerClass.PHOENIX_KNIGHT, PlayerClass.HELL_KNIGHT,
			PlayerClass.ELVEN_KNIGHT, PlayerClass.TEMPLE_KNIGHT, PlayerClass.SWORDSINGER,
			PlayerClass.EVA_TEMPLAR, PlayerClass.SWORD_MUSE,
			PlayerClass.PALUS_KNIGHT, PlayerClass.SHILLIEN_KNIGHT, PlayerClass.BLADEDANCER,
			PlayerClass.SHILLIEN_TEMPLAR, PlayerClass.SPECTRAL_DANCER,
			PlayerClass.WARDER // Kamael - dex tank, design call
		);

		// ===================== JUGGERNAUT (Warrior/melee DPS) =====================
		map(JUGGERNAUT,
			PlayerClass.WARRIOR, PlayerClass.GLADIATOR, PlayerClass.WARLORD,
			PlayerClass.DUELIST, PlayerClass.DREADNOUGHT,
			PlayerClass.ORC_FIGHTER, PlayerClass.ORC_RAIDER, PlayerClass.DESTROYER, PlayerClass.TITAN,
			PlayerClass.ORC_MONK, PlayerClass.TYRANT, PlayerClass.GRAND_KHAVATARI,
			PlayerClass.DWARVEN_FIGHTER, PlayerClass.ARTISAN, PlayerClass.WARSMITH, PlayerClass.MAESTRO, // design call
			PlayerClass.MALE_SOLDIER, PlayerClass.TROOPER, PlayerClass.BERSERKER, PlayerClass.DOOMBRINGER,
			PlayerClass.MALE_SOULBREAKER, PlayerClass.MALE_SOUL_HOUND
		);

		// ===================== SHADOWBLADE (Rogue/dagger DPS) =====================
		map(SHADOWBLADE,
			PlayerClass.ROGUE, PlayerClass.TREASURE_HUNTER, PlayerClass.ADVENTURER,
			PlayerClass.ELVEN_SCOUT, PlayerClass.PLAINS_WALKER, PlayerClass.WIND_RIDER,
			PlayerClass.ASSASSIN, PlayerClass.ABYSS_WALKER, PlayerClass.GHOST_HUNTER,
			PlayerClass.SCAVENGER, PlayerClass.BOUNTY_HUNTER, PlayerClass.FORTUNE_SEEKER,
			PlayerClass.FEMALE_SOLDIER, PlayerClass.FEMALE_SOULBREAKER, PlayerClass.FEMALE_SOUL_HOUND
		);

		// ===================== DEADEYE (Archer) =====================
		map(DEADEYE,
			PlayerClass.HAWKEYE, PlayerClass.SAGITTARIUS,
			PlayerClass.SILVER_RANGER, PlayerClass.MOONLIGHT_SENTINEL,
			PlayerClass.PHANTOM_RANGER, PlayerClass.GHOST_SENTINEL,
			PlayerClass.ARBALESTER, PlayerClass.TRICKSTER
		);

		// ===================== ARCANIST (Mage DPS/summoner) =====================
		map(ARCANIST,
			PlayerClass.MAGE, PlayerClass.WIZARD, PlayerClass.SORCERER, PlayerClass.ARCHMAGE,
			PlayerClass.NECROMANCER, PlayerClass.SOULTAKER,
			PlayerClass.WARLOCK, PlayerClass.ARCANA_LORD,
			PlayerClass.ELVEN_MAGE, PlayerClass.ELVEN_WIZARD, PlayerClass.SPELLSINGER, PlayerClass.MYSTIC_MUSE,
			PlayerClass.ELEMENTAL_SUMMONER, PlayerClass.ELEMENTAL_MASTER,
			PlayerClass.DARK_MAGE, PlayerClass.DARK_WIZARD, PlayerClass.SPELLHOWLER, PlayerClass.STORM_SCREAMER,
			PlayerClass.PHANTOM_SUMMONER, PlayerClass.SPECTRAL_MASTER
			// Note: Orc has no damage-caster line - Orc mage branch is entirely Hierophant below.
		);

		// ===================== HIEROPHANT (Support/Healer) =====================
		map(HIEROPHANT,
			PlayerClass.CLERIC, PlayerClass.BISHOP, PlayerClass.CARDINAL,
			PlayerClass.PROPHET, PlayerClass.HIEROPHANT,
			PlayerClass.ORACLE, PlayerClass.ELDER, PlayerClass.EVA_SAINT,
			PlayerClass.SHILLIEN_ORACLE, PlayerClass.SHILLIEN_ELDER, PlayerClass.SHILLIEN_SAINT,
			PlayerClass.ORC_MAGE, PlayerClass.ORC_SHAMAN, PlayerClass.OVERLORD, PlayerClass.DOMINATOR,
			PlayerClass.WARCRYER, PlayerClass.DOOMCRYER,
			PlayerClass.INSPECTOR, PlayerClass.JUDICATOR // Kamael support/debuff, design call
		);
	}

	private static void map(String sector, PlayerClass... classes)
	{
		for (PlayerClass pc : classes)
		{
			if (pc != null)
			{
				CLASS_TO_SECTOR.put(pc.getId(), sector);
			}
		}
	}

	/**
	 * @param classId the character's class id (e.g. from {@code player.getPlayerClass().getId()})
	 * @return the sector this class opens the passive tree browser on by
	 *         default, or {@link #VANGUARD} if the class id is unmapped
	 *         (e.g. base Fighter/Mage roots that shouldn't normally still
	 *         be active at the passive-tree start level).
	 */
	public static String sectorFor(int classId)
	{
		return CLASS_TO_SECTOR.getOrDefault(classId, VANGUARD);
	}
}
