package custom.PassiveSkillTree;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.l2jmobius.gameserver.data.xml.ClassListData;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.OptionData;
import org.l2jmobius.gameserver.managers.PassiveTreeManager;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerHolder;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpPassives;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpProfile;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerPvpWeapon;
import org.l2jmobius.gameserver.model.actor.holders.player.ClassInfoHolder;
import org.l2jmobius.gameserver.model.actor.holders.player.SubClassHolder;
import org.l2jmobius.gameserver.model.clan.Clan;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.holders.Elementals;
import org.l2jmobius.gameserver.model.item.holders.ItemEnchantHolder;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.item.type.CrystalType;
import org.l2jmobius.gameserver.model.itemcontainer.Inventory;
import org.l2jmobius.gameserver.model.options.Options;
import org.l2jmobius.gameserver.model.skill.Skill;

/**
 * The full build of a player or fake player as JSON, for the web planner's ".gear" view: who it is, its combat stats, every item it wears and its passive tree.
 * <p>
 * Made on the game thread when ".gear" is used and kept as it was then (see {@link PassiveTreeApiServer#registerInspect}), so the page still shows it after the target logs off, dies or despawns.
 * <p>
 * A fake player's build reads like a player's: nothing in it says it is a fake one.
 */
public final class BuildSnapshot
{
	/** Paperdoll slots in the order the page lists them, with their names. */
	private static final int[] SLOTS =
	{
		Inventory.PAPERDOLL_RHAND,
		Inventory.PAPERDOLL_LHAND,
		Inventory.PAPERDOLL_HEAD,
		Inventory.PAPERDOLL_CHEST,
		Inventory.PAPERDOLL_LEGS,
		Inventory.PAPERDOLL_GLOVES,
		Inventory.PAPERDOLL_FEET,
		Inventory.PAPERDOLL_CLOAK,
		Inventory.PAPERDOLL_UNDER,
		Inventory.PAPERDOLL_BELT,
		Inventory.PAPERDOLL_NECK,
		Inventory.PAPERDOLL_REAR,
		Inventory.PAPERDOLL_LEAR,
		Inventory.PAPERDOLL_RFINGER,
		Inventory.PAPERDOLL_LFINGER,
		Inventory.PAPERDOLL_RBRACELET,
		Inventory.PAPERDOLL_LBRACELET,
		Inventory.PAPERDOLL_DECO1,
		Inventory.PAPERDOLL_DECO2,
		Inventory.PAPERDOLL_DECO3,
		Inventory.PAPERDOLL_DECO4,
		Inventory.PAPERDOLL_DECO5,
		Inventory.PAPERDOLL_DECO6,
		Inventory.PAPERDOLL_HAIR,
		Inventory.PAPERDOLL_HAIR2
	};
	private static final String[] SLOT_NAMES = new String[Inventory.PAPERDOLL_TOTALSLOTS];
	static
	{
		SLOT_NAMES[Inventory.PAPERDOLL_RHAND] = "Weapon";
		SLOT_NAMES[Inventory.PAPERDOLL_LHAND] = "Off-hand";
		SLOT_NAMES[Inventory.PAPERDOLL_HEAD] = "Helmet";
		SLOT_NAMES[Inventory.PAPERDOLL_CHEST] = "Upper body";
		SLOT_NAMES[Inventory.PAPERDOLL_LEGS] = "Lower body";
		SLOT_NAMES[Inventory.PAPERDOLL_GLOVES] = "Gloves";
		SLOT_NAMES[Inventory.PAPERDOLL_FEET] = "Boots";
		SLOT_NAMES[Inventory.PAPERDOLL_CLOAK] = "Cloak";
		SLOT_NAMES[Inventory.PAPERDOLL_UNDER] = "Shirt";
		SLOT_NAMES[Inventory.PAPERDOLL_BELT] = "Belt";
		SLOT_NAMES[Inventory.PAPERDOLL_NECK] = "Necklace";
		SLOT_NAMES[Inventory.PAPERDOLL_REAR] = "Earring";
		SLOT_NAMES[Inventory.PAPERDOLL_LEAR] = "Earring";
		SLOT_NAMES[Inventory.PAPERDOLL_RFINGER] = "Ring";
		SLOT_NAMES[Inventory.PAPERDOLL_LFINGER] = "Ring";
		SLOT_NAMES[Inventory.PAPERDOLL_RBRACELET] = "Bracelet";
		SLOT_NAMES[Inventory.PAPERDOLL_LBRACELET] = "Bracelet";
		SLOT_NAMES[Inventory.PAPERDOLL_DECO1] = "Talisman";
		SLOT_NAMES[Inventory.PAPERDOLL_DECO2] = "Talisman";
		SLOT_NAMES[Inventory.PAPERDOLL_DECO3] = "Talisman";
		SLOT_NAMES[Inventory.PAPERDOLL_DECO4] = "Talisman";
		SLOT_NAMES[Inventory.PAPERDOLL_DECO5] = "Talisman";
		SLOT_NAMES[Inventory.PAPERDOLL_DECO6] = "Talisman";
		SLOT_NAMES[Inventory.PAPERDOLL_HAIR] = "Hair";
		SLOT_NAMES[Inventory.PAPERDOLL_HAIR2] = "Hair";
	}

	private BuildSnapshot()
	{
	}

	/**
	 * @param player an online player
	 * @return its build as JSON
	 */
	public static String of(Player player)
	{
		final StringBuilder json = new StringBuilder(4096);
		json.append('{');
		header(json, player, className(player.getPlayerClass()), player.getRace().name());
		json.append(",\"baseClass\":").append(quote(className(PlayerClass.getPlayerClass(player.getBaseClass()))));
		final Clan clan = player.getClan();
		json.append(",\"clan\":").append(quote(clan != null ? clan.getName() : ""));
		json.append(",\"noble\":").append(player.isNoble()).append(",\"hero\":").append(player.isHero());

		json.append(",\"subclasses\":[");
		boolean first = true;
		for (SubClassHolder sub : player.getSubClasses().values())
		{
			json.append(first ? "" : ",").append("{\"name\":").append(quote(className(sub.getPlayerClass()))).append(",\"level\":").append(sub.getLevel()).append('}');
			first = false;
		}
		json.append(']');

		stats(json, player, player.getMaxHp(), player.getMaxCp());

		json.append(",\"gear\":[");
		first = true;
		for (int slot : SLOTS)
		{
			final Item item = player.getInventory().getPaperdollItem(slot);
			// A two-handed weapon fills both hands; list it once.
			if ((item == null) || ((slot == Inventory.PAPERDOLL_LHAND) && (item == player.getInventory().getPaperdollItem(Inventory.PAPERDOLL_RHAND))) || ((slot == Inventory.PAPERDOLL_HAIR2) && (item == player.getInventory().getPaperdollItem(Inventory.PAPERDOLL_HAIR))))
			{
				continue;
			}
			json.append(first ? "" : ",");
			item(json, SLOT_NAMES[slot], item.getTemplate(), item.getEnchantLevel(), augment(item), elements(item));
			first = false;
		}
		json.append(']');

		final PassiveTreeManager mgr = PassiveTreeManager.getInstance();
		final List<Integer> nodeIds = mgr.getGrowthOrder(mgr.getAllocatedNodes(player));
		json.append(",\"tree\":{\"allocatedNodeIds\":[").append(join(nodeIds)).append("],\"spentPoints\":").append(mgr.getSpentPoints(player)).append(",\"earnedPoints\":").append(mgr.getEarnedPoints(player)).append(",\"template\":").append(mgr.getActiveTemplate(player)).append("}}");
		return json.toString();
	}

	/**
	 * @param npc a fake player (a roaming PvP one with its own profile, or a town one from the fake player data)
	 * @return its build as JSON
	 */
	public static String of(Npc npc)
	{
		final FakePlayerPvpProfile profile = npc.getTemplate().getFakePlayerPvpProfile();
		final FakePlayerHolder info = npc.getTemplate().getFakePlayerInfo();
		final PlayerClass playerClass = profile != null ? profile.getPlayerClass() : (info != null ? info.getPlayerClass() : null);

		final StringBuilder json = new StringBuilder(4096);
		json.append('{');
		header(json, npc, className(playerClass), npc.getTemplate().getRace().name());
		json.append(",\"noble\":").append((info != null) && (info.getNobleLevel() > 0)).append(",\"hero\":").append((info != null) && info.isHero());
		// A roaming fake player's HP pool can hold its class CP too (FakePvpIncludeCpInHp): show the two apart, like a player's.
		final int pool = npc.getMaxHp();
		final int cp = profile != null ? (int) Math.round(pool * profile.getCpShare()) : 0;
		stats(json, npc, pool - cp, cp);

		json.append(",\"gear\":[");
		if (profile != null)
		{
			final FakePlayerPvpWeapon held = profile.getHeldWeapon();
			final int heldId = held != null ? held.getWeaponId() : 0;
			boolean first = true;
			boolean heldListed = false;
			for (ItemEnchantHolder holder : profile.getEquipment())
			{
				final ItemTemplate template = ItemData.getInstance().getTemplate(holder.getId());
				if (template == null)
				{
					continue;
				}

				String slot = slotName(template);
				// It carries a bow or polearm besides its weapon and swaps to it in a fight.
				if (template.isWeapon())
				{
					if ((template.getId() == heldId) && !heldListed)
					{
						heldListed = true;
					}
					else
					{
						slot = "Spare weapon";
					}
				}
				json.append(first ? "" : ",");
				item(json, slot, template, holder.getEnchantLevel(), "", "");
				first = false;
			}
		}
		else if (info != null)
		{
			final int[][] worn =
			{
				// @formatter:off
				{info.getEquipRHand(), info.getEnchantLevel(Inventory.PAPERDOLL_RHAND)},
				{info.getEquipLHand(), info.getEnchantLevel(Inventory.PAPERDOLL_LHAND)},
				{info.getEquipHead(), info.getEnchantLevel(Inventory.PAPERDOLL_HEAD)},
				{info.getEquipChest(), info.getEnchantLevel(Inventory.PAPERDOLL_CHEST)},
				{info.getEquipLegs(), info.getEnchantLevel(Inventory.PAPERDOLL_LEGS)},
				{info.getEquipGloves(), info.getEnchantLevel(Inventory.PAPERDOLL_GLOVES)},
				{info.getEquipFeet(), info.getEnchantLevel(Inventory.PAPERDOLL_FEET)},
				{info.getEquipCloak(), 0},
				{info.getEquipShirt(), info.getEnchantLevel(Inventory.PAPERDOLL_UNDER)},
				{info.getEquipBelt(), info.getEnchantLevel(Inventory.PAPERDOLL_BELT)},
				{info.getEquipHair(), 0},
				{info.getEquipHair2(), 0}
				// @formatter:on
			};
			boolean first = true;
			for (int[] entry : worn)
			{
				final ItemTemplate template = entry[0] > 0 ? ItemData.getInstance().getTemplate(entry[0]) : null;
				if (template == null)
				{
					continue;
				}
				json.append(first ? "" : ",");
				item(json, slotName(template), template, entry[1], "", "");
				first = false;
			}
		}
		json.append(']');

		final FakePlayerPvpPassives passives = profile != null ? profile.getPassives() : null;
		if (passives != null)
		{
			json.append(",\"tree\":{\"allocatedNodeIds\":[").append(join(passives.getNodeIds())).append("],\"spentPoints\":").append(passives.getPoints()).append(",\"subclasses\":").append(passives.getSubclasses()).append('}');
		}
		else
		{
			json.append(",\"tree\":null");
		}
		json.append('}');
		return json.toString();
	}

	private static void header(StringBuilder json, Creature creature, String className, String race)
	{
		json.append("\"name\":").append(quote(creature.getName()));
		json.append(",\"title\":").append(quote(creature.getTitle()));
		json.append(",\"level\":").append(creature.getLevel());
		json.append(",\"className\":").append(quote(className));
		json.append(",\"race\":").append(quote(capitalize(race)));
		json.append(",\"takenAt\":").append(System.currentTimeMillis());
	}

	private static void stats(StringBuilder json, Creature creature, int maxHp, int maxCp)
	{
		json.append(",\"stats\":{");
		json.append("\"hp\":").append(maxHp);
		json.append(",\"mp\":").append(creature.getMaxMp());
		json.append(",\"cp\":").append(maxCp);
		json.append(",\"pAtk\":").append((int) creature.getPAtk(null));
		json.append(",\"mAtk\":").append((int) creature.getMAtk(null, null));
		json.append(",\"pDef\":").append((int) creature.getPDef(null));
		json.append(",\"mDef\":").append((int) creature.getMDef(null, null));
		json.append(",\"atkSpd\":").append((int) creature.getPAtkSpd());
		json.append(",\"castSpd\":").append(creature.getMAtkSpd());
		json.append(",\"accuracy\":").append(creature.getAccuracy());
		json.append(",\"evasion\":").append(creature.getEvasionRate(null));
		json.append(",\"crit\":").append(creature.getCriticalHit(null, null));
		json.append(",\"mCrit\":").append(creature.getMCriticalHit(null, null));
		json.append(",\"speed\":").append((int) creature.getRunSpeed());
		json.append(",\"str\":").append(creature.getSTR());
		json.append(",\"dex\":").append(creature.getDEX());
		json.append(",\"con\":").append(creature.getCON());
		json.append(",\"int\":").append(creature.getINT());
		json.append(",\"wit\":").append(creature.getWIT());
		json.append(",\"men\":").append(creature.getMEN());
		json.append('}');
	}

	private static void item(StringBuilder json, String slot, ItemTemplate template, int enchant, String augment, String elements)
	{
		final CrystalType grade = template.getCrystalType();
		json.append("{\"slot\":").append(quote(slot));
		json.append(",\"id\":").append(template.getId());
		json.append(",\"name\":").append(quote(template.getName()));
		json.append(",\"enchant\":").append(enchant);
		json.append(",\"grade\":").append(quote((grade == null) || (grade == CrystalType.NONE) ? "" : grade.name()));
		json.append(",\"augment\":").append(quote(augment));
		json.append(",\"elements\":").append(quote(elements));
		json.append('}');
	}

	/**
	 * @param template an item
	 * @return the name of the slot it is worn in
	 */
	private static String slotName(ItemTemplate template)
	{
		final int slot = template.getBodyPart().getPaperdollSlot();
		if ((slot >= 0) && (slot < SLOT_NAMES.length) && (SLOT_NAMES[slot] != null))
		{
			return SLOT_NAMES[slot];
		}
		switch (template.getBodyPart())
		{
			case LR_HAND:
			{
				return "Weapon";
			}
			case FULL_ARMOR:
			case ALLDRESS:
			{
				return "Upper body";
			}
			case LR_EAR:
			{
				return "Earring";
			}
			case LR_FINGER:
			{
				return "Ring";
			}
			case HAIRALL:
			{
				return "Hair";
			}
			default:
			{
				return "Other";
			}
		}
	}

	/**
	 * @param item a worn item
	 * @return the skills its augment gives ("Might (passive), Heal Empower (active)"), "Augmented" when it gives none, or "" when it has no augment
	 */
	private static String augment(Item item)
	{
		if (!item.isAugmented())
		{
			return "";
		}

		final int id = item.getAugmentation().getAugmentationId();
		final List<String> skills = new ArrayList<>();
		for (int optionId : new int[]
		{
			id & 0xFFFF,
			id >> 16
		})
		{
			final Options options = OptionData.getInstance().getOptions(optionId);
			if (options == null)
			{
				continue;
			}
			if (options.hasActiveSkill())
			{
				skills.add(skillName(options.getActiveSkill()) + " (active)");
			}
			if (options.hasPassiveSkill())
			{
				skills.add(skillName(options.getPassiveSkill()) + " (passive)");
			}
			if (options.hasActivationSkills())
			{
				options.getActivationSkills().forEach(holder -> skills.add(skillName(holder.getSkill()) + " (chance)"));
			}
		}
		return skills.isEmpty() ? "Augmented" : String.join(", ", skills);
	}

	private static String skillName(Skill skill)
	{
		return skill == null ? "?" : skill.getName() + " Lv " + skill.getLevel();
	}

	/**
	 * @param item a worn item
	 * @return its attribute: the attack one of a weapon ("Fire 150"), the defence ones of armor ("Water 60, Wind 60"), "" for none
	 */
	private static String elements(Item item)
	{
		if (item.isWeapon())
		{
			final byte type = item.getAttackElementType();
			return (type >= 0) && (item.getAttackElementPower() > 0) ? Elementals.getElementName(type) + " " + item.getAttackElementPower() : "";
		}

		final List<String> parts = new ArrayList<>();
		for (byte element = Elementals.FIRE; element <= Elementals.DARK; element++)
		{
			final int value = item.getElementDefAttr(element);
			if (value > 0)
			{
				parts.add(Elementals.getElementName(element) + " " + value);
			}
		}
		return String.join(", ", parts);
	}

	private static String className(PlayerClass playerClass)
	{
		if (playerClass == null)
		{
			return "";
		}
		final ClassInfoHolder info = ClassListData.getInstance().getClass(playerClass);
		return info != null ? info.getClassName() : capitalize(playerClass.name());
	}

	private static String capitalize(String name)
	{
		final StringBuilder sb = new StringBuilder();
		for (String word : name.toLowerCase(Locale.ROOT).split("_"))
		{
			if (!word.isEmpty())
			{
				sb.append(sb.length() > 0 ? " " : "").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
			}
		}
		return sb.toString();
	}

	private static String join(List<Integer> ids)
	{
		final StringBuilder sb = new StringBuilder();
		for (int id : ids)
		{
			sb.append(sb.length() > 0 ? "," : "").append(id);
		}
		return sb.toString();
	}

	/**
	 * @param text any text
	 * @return it as a JSON string, quotes included
	 */
	static String quote(String text)
	{
		if (text == null)
		{
			return "\"\"";
		}
		final StringBuilder sb = new StringBuilder(text.length() + 2).append('"');
		for (char c : text.toCharArray())
		{
			switch (c)
			{
				case '"':
				{
					sb.append("\\\"");
					break;
				}
				case '\\':
				{
					sb.append("\\\\");
					break;
				}
				default:
				{
					if (c < 0x20)
					{
						sb.append(String.format("\\u%04x", (int) c));
					}
					else
					{
						sb.append(c);
					}
				}
			}
		}
		return sb.append('"').toString();
	}
}
