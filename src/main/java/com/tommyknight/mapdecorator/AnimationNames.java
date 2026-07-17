package com.tommyknight.mapdecorator;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lookup of animation sequence ids to their gameval constant names, loaded from
 * animation_names.tsv (generated from net.runelite.api.gameval.AnimationID).
 */
final class AnimationNames
{
	private static volatile Map<Integer, String> byId;
	private static volatile Map<String, Integer> byName;
	private static volatile Map<Integer, Integer> modelByAnimation;

	/** Name for the given animation id, or null if unknown. */
	static String name(int id)
	{
		load();
		return byId.get(id);
	}

	/** Animation id for the given constant name (case-insensitive), or null if unknown. */
	static Integer id(String name)
	{
		load();
		return byName.get(name.trim().toUpperCase(Locale.ROOT));
	}

	/**
	 * A model id known to use this animation as its ambient scenery animation
	 * (e.g. a fire's flame model), or null if the animation has no known scenery model.
	 * Sourced from a dev-time dump of the game cache's object definitions, so
	 * only covers scenery animations, not combat/player animations.
	 */
	static Integer modelFor(int animationId)
	{
		loadModels();
		return modelByAnimation.get(animationId);
	}

	/**
	 * Human-friendly name for the animation, matching what the search dropdown displays: the
	 * paired object's name when known (e.g. "Huge Spider"), falling back to the gameval constant
	 * name (e.g. "SPIDER_READY"), or null if neither is known.
	 */
	static String displayName(int animationId)
	{
		Integer modelId = modelFor(animationId);
		String objectName = modelId != null ? ObjectModelNames.name(modelId) : null;
		return objectName != null ? objectName : name(animationId);
	}

	/** All animation ids with a known scenery model (see {@link #modelFor}), sorted ascending. */
	static List<Integer> attachableIds()
	{
		loadModels();
		List<Integer> ids = new ArrayList<>(modelByAnimation.keySet());
		Collections.sort(ids);
		return ids;
	}

	/**
	 * Animation ids with a known scenery model (see {@link #modelFor}), matched against either the
	 * animation's own gameval constant name OR its paired object's human name (via {@link ObjectModelNames}).
	 * The constant names are things like "FIREBIRD_WALK" â€” rarely what someone would type â€” so matching
	 * the paired object's name too means searching "fire" actually finds fire animations. Displays the
	 * object name when known (falling back to the constant name), ranked prefix-matches-first then by
	 * display length, capped at {@code limit} results.
	 */
	static List<Map.Entry<Integer, String>> searchWithModel(String query, int limit)
	{
		load();
		loadModels();
		String q = query.trim().toLowerCase(Locale.ROOT);
		if (q.isEmpty())
		{
			return List.of();
		}
		List<Map.Entry<Integer, String>> results = new ArrayList<>();
		for (Map.Entry<Integer, Integer> e : modelByAnimation.entrySet())
		{
			int animId = e.getKey();
			int modelId = e.getValue();
			String animName = byId.getOrDefault(animId, String.valueOf(animId));
			String objectName = ObjectModelNames.name(modelId);
			boolean matches = animName.toLowerCase(Locale.ROOT).contains(q)
				|| (objectName != null && objectName.toLowerCase(Locale.ROOT).contains(q));
			if (matches)
			{
				String display = objectName != null ? objectName : animName;
				results.add(new AbstractMap.SimpleEntry<>(animId, display));
			}
		}
		results.sort((a, b) ->
		{
			boolean aStarts = a.getValue().toLowerCase(Locale.ROOT).startsWith(q);
			boolean bStarts = b.getValue().toLowerCase(Locale.ROOT).startsWith(q);
			if (aStarts != bStarts)
			{
				return aStarts ? -1 : 1;
			}
			int lenCompare = Integer.compare(a.getValue().length(), b.getValue().length());
			return lenCompare != 0 ? lenCompare : a.getValue().compareToIgnoreCase(b.getValue());
		});
		return results.size() > limit ? results.subList(0, limit) : results;
	}

	private static synchronized void load()
	{
		if (byId != null)
		{
			return;
		}
		Map<Integer, String> m = new HashMap<>();
		Map<String, Integer> r = new HashMap<>();
		try (InputStream in = AnimationNames.class.getResourceAsStream("animation_names.tsv");
			BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = br.readLine()) != null)
			{
				int tab = line.indexOf('\t');
				if (tab <= 0)
				{
					continue;
				}
				int id = Integer.parseInt(line.substring(0, tab));
				String name = line.substring(tab + 1);
				m.put(id, name);
				r.putIfAbsent(name, id);
			}
		}
		catch (Exception e)
		{
			// missing or malformed resource: fall back to numeric display
		}
		byId = m;
		byName = r;
	}

	private static synchronized void loadModels()
	{
		if (modelByAnimation != null)
		{
			return;
		}
		Map<Integer, Integer> m = new HashMap<>();
		try (InputStream in = AnimationNames.class.getResourceAsStream("animation_models.tsv");
			BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = br.readLine()) != null)
			{
				String[] parts = line.split("\t", 3);
				if (parts.length < 2)
				{
					continue;
				}
				m.put(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
			}
		}
		catch (Exception e)
		{
			// missing or malformed resource: no auto-select available
		}
		modelByAnimation = m;
	}

	private AnimationNames()
	{
	}
}
