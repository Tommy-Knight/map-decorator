package com.tommyknight.mapdecorator;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import lombok.Getter;

/**
 * One glow a placed light can use. 117 HD gives a point light to certain spell projectiles; a
 * light here is a stationary, hidden copy of one of those projectiles, so its colour and radius
 * are whatever 117 HD defines for it. The table is generated from 117 HD's lights.json, widest
 * first, with exact duplicates (same name, colour and reach) left out.
 * <p>
 * {@code code} is the projectile's spotanim id and is what gets saved.
 */
@Getter
final class LightPreset
{
	static final LightPreset NONE = new LightPreset(0, "None", 0, 0, false);

	private static final List<LightPreset> ALL = Collections.unmodifiableList(Arrays.asList(
		NONE,
		new LightPreset(2780, "Blue Moon Arena Brazier Extinguish Attack", 0x3571a7, 1100, false),
		new LightPreset(2781, "Eclipse Projectile", 0xcd7c00, 900, false),
		new LightPreset(2237, "Toa Warden P1 Big Ball Attack", 0xff1919, 700, true),
		new LightPreset(450, "Jaltok Jad Magic", 0xff7b00, 700, false),
		new LightPreset(54, "Metallic Dragonfire Projectile", 0xff7b00, 700, false),
		new LightPreset(2489, "Leviathan mage projectile", 0x0ca5d7, 700, true),
		new LightPreset(2487, "Leviathan range projectile", 0x8fde00, 700, true),
		new LightPreset(2488, "Leviathan melee projectile", 0xc94f2e, 700, true),
		new LightPreset(2204, "Toa Warden P2 Melee Special", 0xff1919, 600, true),
		new LightPreset(2208, "Toa Warden P2 Mage Special", 0x1919ff, 600, true),
		new LightPreset(2327, "Phantom Muspah - Magic attack", 0x351e79, 600, true),
		new LightPreset(2206, "Toa Warden P2 Range Special", 0xffffff, 600, true),
		new LightPreset(4015, "Wyrmscraig Mad Angel Light Tennis Attack", 0xfff0f0, 600, false),
		new LightPreset(3515, "Sailing - Kraken Lighting Attack - Pink - Projectile", 0xd977a0, 500, true),
		new LightPreset(3514, "Sailing - Kraken Lighting Attack - Yellow - Projectile", 0xffff7a, 500, true),
		new LightPreset(1470, "Vorkath Venom Attack", 0x1e6e0a, 450, false),
		new LightPreset(3409, "Doom of Mokhaiotl - Charge Beam Projectile", 0xff5a03, 450, false),
		new LightPreset(2756, "Blue Moon Arena Brazier Disarm", 0x3571a7, 450, false),
		new LightPreset(1183, "God Wars Dungeon - Saradomin Growler Magic Projectile", 0x6464c8, 450, false),
		new LightPreset(3513, "Sailing - Kraken Lighting Attack - Blue - Projectile", 0x00c8ff, 450, true),
		new LightPreset(1459, "Water Surge", 0x008cff, 400, false),
		new LightPreset(1465, "Fire Surge", 0xff0000, 400, false),
		new LightPreset(1462, "Earth Surge", 0x00ff00, 400, false),
		new LightPreset(1040, "Trident Swamp Spell", 0x00a7cc, 400, false),
		new LightPreset(3723, "Trident Sea Spell Leagues", 0xff0000, 400, false),
		new LightPreset(1456, "Wind Surge", 0xffffff, 400, false),
		new LightPreset(1252, "Trident Sea Spell", 0x0000ff, 400, false),
		new LightPreset(2226, "Toa Warden P3 Skull Charge Projectile & Blood Moon Projectile", 0xff1919, 400, true),
		new LightPreset(1481, "Toa Orange Flame Projectile", 0xff8246, 375, true),
		new LightPreset(2225, "Toa Warden P2 Obelisk Lightning Ball", 0xffc819, 350, true),
		new LightPreset(2879, "Guthix Temple - Elemental Water Blast", 0x008cff, 350, false),
		new LightPreset(2224, "Toa Warden P2 Mage Skull Basic", 0xff1919, 350, true),
		new LightPreset(1227, "God Wars Dungeon - Zamorak Balfrug Magic Projectile", 0xfc7803, 350, false),
		new LightPreset(1471, "Vorkath Prayer Corruption Attack", 0xff00ff, 350, false),
		new LightPreset(2181, "Toa Crondis Mage Orb", 0xff1919, 350, true),
		new LightPreset(315, "Corporeal Beast Explosion Projectile", 0xffffff, 350, true),
		new LightPreset(2239, "Toa Warden P2 Core", 0xff1919, 300, true),
		new LightPreset(2238, "Toa Warden P1 Small Ball Attack", 0xff1919, 300, true),
		new LightPreset(393, "Chromatic Dragonfire Breath - Ranged", 0xfc9403, 300, true),
		new LightPreset(1166, "Dragonfire Shield - Projectile", 0xff7b00, 300, true),
		new LightPreset(162, "Water Wave", 0x008cff, 300, false),
		new LightPreset(156, "Fire Wave", 0xff0000, 300, false),
		new LightPreset(1679, "Undead Druid", 0x960000, 300, false),
		new LightPreset(3313, "Metzli Water Projectile", 0x008cff, 300, false),
		new LightPreset(3321, "Metzli Fire Ball", 0xfc9403, 300, false),
		new LightPreset(3378, "Doom of Mokhaiotl - Melee Projectile", 0xff0000, 300, false),
		new LightPreset(165, "Earth Wave", 0x00ff00, 300, false),
		new LightPreset(159, "Wind Wave", 0xffffff, 300, false),
		new LightPreset(2253, "Toa Het Mage Projectile", 0xff1919, 300, true),
		new LightPreset(1482, "Vorkath Rapid Fireballs", 0xff8246, 300, true),
		new LightPreset(1217, "God Wars Dungeon - Bandos Steelwill Magic Projectile", 0xc8ff00, 300, false),
		new LightPreset(146, "Crumble Undead", 0xffffff, 300, false),
		new LightPreset(1479, "Vorkath Magic Attack Projectile", 0xd7ffff, 300, true),
		new LightPreset(2975, "Hueycoatl Mage Projectile", 0x00c8ff, 285, true),
		new LightPreset(3379, "Doom of Mokhaiotl - Magic Projectile", 0x0064ff, 275, false),
		new LightPreset(3380, "Doom of Mokhaiotl - Range Projectile", 0x00ff00, 275, false),
		new LightPreset(2534, "Mysterious Figure's Hideout - Purple Magic Attack - Projectile", 0x7d00ff, 275, true),
		new LightPreset(136, "Water Blast", 0x008cff, 275, false),
		new LightPreset(130, "Fire Blast", 0xff0000, 275, false),
		new LightPreset(139, "Earth Blast", 0x00ff00, 275, false),
		new LightPreset(133, "Wind Blast", 0xffffff, 275, false),
		new LightPreset(314, "Corporeal Beast Magic Projectile", 0xffffff, 275, true),
		new LightPreset(1046, "Zulrah - Fire Attack", 0xfc9403, 265, true),
		new LightPreset(127, "Fire Bolt", 0xff0000, 265, false),
		new LightPreset(1814, "Shadow Wyrm Magic Attack", 0x0032ff, 250, true),
		new LightPreset(2640, "Scurrius Magic Attack", 0x3c6ebe, 250, true),
		new LightPreset(2881, "Guthix Temple - Elemental Fire Arrow", 0xfc9403, 250, true),
		new LightPreset(1764, "Nightmare of Ashihama - Magic Projectile", 0xff00a6, 250, true),
		new LightPreset(374, "Blood Blitz", 0xff0000, 250, false),
		new LightPreset(1304, "Mm2 Gorilla Magic Attack Projectile", 0x00ff32, 250, false),
		new LightPreset(88, "Iban Blast", 0xff0000, 250, false),
		new LightPreset(1640, "Hespori Magic Attack", 0x00ff00, 250, false),
		new LightPreset(1907, "Ghostly thrall projectile", 0x0064ff, 250, true),
		new LightPreset(249, "Magic Bow Spec And Crystal Bow", 0x34eb71, 250, false),
		new LightPreset(1574, "Craws Bow", 0xffc766, 250, false),
		new LightPreset(301, "Armadyl Crossbow Spec", 0xffffff, 250, false),
		new LightPreset(2948, "Frost Nagua Projectile", 0x64afff, 250, false),
		new LightPreset(121, "Water Bolt", 0x008cff, 250, false),
		new LightPreset(168, "Vulnerability", 0xff00ff, 250, true),
		new LightPreset(171, "Enfeeble", 0xff00ff, 250, true),
		new LightPreset(319, "Corporeal Beast Dark Core - Moving", 0x9600ff, 250, true),
		new LightPreset(124, "Earth Bolt", 0x00ff00, 250, false),
		new LightPreset(174, "Stun", 0xffffff, 250, true),
		new LightPreset(360, "Ice Rush", 0xffffff, 250, false),
		new LightPreset(100, "Fire Strike", 0xff0000, 250, false),
		new LightPreset(118, "Wind Bolt", 0xffffff, 250, false),
		new LightPreset(97, "Earth Strike", 0x00ff00, 250, false),
		new LightPreset(91, "Wind Strike", 0xffffff, 250, false),
		new LightPreset(378, "Shadow Rush", 0xffffff, 250, false),
		new LightPreset(380, "Shadow Blitz", 0xffffff, 250, false),
		new LightPreset(384, "Smoke Rush", 0xffffff, 250, false),
		new LightPreset(386, "Smoke Blitz", 0xffffff, 250, false),
		new LightPreset(2972, "Hueycoatl Range Projectile", 0x00ff00, 230, true),
		new LightPreset(94, "Water Strike", 0x008cff, 225, false),
		new LightPreset(2855, "Tormented Demon - Fire Ball Projectile", 0xfc9403, 220, true),
		new LightPreset(2969, "Hueycoatl Melee Projectile", 0xff0000, 220, true),
		new LightPreset(1735, "Basalisk Knight - Magic Attack", 0xdc00ff, 215, false),
		new LightPreset(1437, "Grotesque Guardians Dawn Ranged Attack", 0x3200ff, 210, true),
		new LightPreset(1737, "the Jormungand - Magic Projectile", 0xfc0000, 200, true),
		new LightPreset(2126, "Tumeken's Shadow Projectile", 0xffb570, 200, true),
		new LightPreset(1634, "Wyrm Magic Attack - Projectile", 0x00c8ff, 200, true),
		new LightPreset(3730, "Mage Imp Thrall - Projectile", 0x0064ff, 200, true),
		new LightPreset(2642, "Scurrius Range Attack", 0x83fa3f, 200, false),
		new LightPreset(2853, "Tormented Demon - Fire Bolt Projectile", 0xfc9403, 200, true),
		new LightPreset(698, "Bone Crossbow Special Attack", 0xff0000, 200, false),
		new LightPreset(554, "Chaos Elemental Tele Projectile", 0xff0000, 200, true),
		new LightPreset(557, "Chaos Elemental Damage Projectile", 0xff0000, 200, true),
		new LightPreset(1906, "Skeletal thrall projectile", 0x90f542, 200, true),
		new LightPreset(328, "Magic Dart", 0xa8ffce, 200, false),
		new LightPreset(178, "Bind Snare Entangle", 0x00ff00, 200, true),
		new LightPreset(17, "Fire Arrows", 0xfc7a03, 200, true),
		new LightPreset(103, "Confuse", 0xff00ff, 200, true),
		new LightPreset(109, "Curse", 0xff00ff, 200, true),
		new LightPreset(344, "Tele Block", 0xffffff, 200, true),
		new LightPreset(106, "Weaken", 0xffffff, 200, true),
		new LightPreset(1445, "Grotesque Guardians Dawn Freeze", 0x878787, 195, true),
		new LightPreset(2569, "Warped Scepter Spell", 0x460aff, 180, true),
		new LightPreset(1637, "Drake Flame Attack", 0xff8c00, 175, true),
		new LightPreset(2807, "Scorching Bow Special Attack - Arrow", 0xfc9403, 175, true),
		new LightPreset(551, "Chaos Elemental Unequip Projectile", 0x00ff00, 175, true),
		new LightPreset(1883, "Dark Lure", 0x4d0dff, 150, true),
		new LightPreset(2018, "Shattered Cannon - Ball", 0x00ffff, 150, true),
		new LightPreset(1636, "Drake Ranged Attack", 0xff0000, 150, true),
		new LightPreset(3303, "Metzli Fire Arrow", 0xfc9403, 150, true),
		new LightPreset(647, "Pest Control Torcher Projectile", 0xfc9403, 150, true),
		new LightPreset(3734, "Ranged Imp Thrall - Projectile", 0x90f542, 150, true),
		new LightPreset(143, "Telegrab Projectile", 0xaa14ff, 125, true)
	));

	private final int code;
	private final String label;
	private final int rgb;
	/** 117 HD radius in scene units, 128 = one tile. */
	private final int radius;
	/** Pulses or flickers rather than holding steady. */
	private final boolean animated;

	private LightPreset(int code, String label, int rgb, int radius, boolean animated)
	{
		this.code = code;
		this.label = label;
		this.rgb = rgb;
		this.radius = radius;
		this.animated = animated;
	}

	static List<LightPreset> all()
	{
		return ALL;
	}

	static LightPreset fromCode(int code)
	{
		for (LightPreset p : ALL)
		{
			if (p.code == code)
			{
				return p;
			}
		}
		return NONE;
	}

	@Override
	public String toString()
	{
		if (this == NONE)
		{
			return label;
		}
		return String.format(animated ? "%s (%.1f, animated)" : "%s (%.1f)", label, radius / 128f);
	}
}
