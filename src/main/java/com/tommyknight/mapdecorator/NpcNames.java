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
 * Catalogue of placeable NPCs, loaded from npc_names.tsv (generated from the game cache's
 * npc definitions: id, name, standing animation). Only NPCs that have models and a real
 * name are included. The model parts and recolors themselves come from
 * {@code client.getNpcDefinition(id)} at runtime; the standing animation is bundled here
 * because the runtime NPCComposition doesn't expose it.
 */
final class NpcNames
{
	private static volatile Map<Integer, String> byId;
	private static volatile Map<Integer, Integer> idleAnimById;
	private static volatile Map<Integer, Integer> walkAnimById;

	/** Name for the given npc id, or null if unknown. */
	static String name(int npcId)
	{
		load();
		return byId.get(npcId);
	}

	/** The npc's standing/idle animation id, or -1 if none is known. */
	static int idleAnimation(int npcId)
	{
		load();
		return idleAnimById.getOrDefault(npcId, -1);
	}

	/** The npc's walking animation id, or -1 if none is known. */
	static int walkAnimation(int npcId)
	{
		load();
		return walkAnimById.getOrDefault(npcId, -1);
	}

	/** All known npc ids, sorted ascending. */
	static List<Integer> knownIds()
	{
		load();
		List<Integer> ids = new ArrayList<>(byId.keySet());
		Collections.sort(ids);
		return ids;
	}

	/**
	 * Npc ids whose name contains the given substring (case-insensitive),
	 * ranked prefix-matches-first then by name length, capped at {@code limit} results.
	 */
	static List<Map.Entry<Integer, String>> search(String query, int limit)
	{
		load();
		String q = query.trim().toLowerCase(Locale.ROOT);
		if (q.isEmpty())
		{
			return List.of();
		}
		List<Map.Entry<Integer, String>> results = new ArrayList<>();
		for (Map.Entry<Integer, String> e : byId.entrySet())
		{
			if (e.getValue().toLowerCase(Locale.ROOT).contains(q))
			{
				results.add(new AbstractMap.SimpleEntry<>(e.getKey(), e.getValue()));
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
		Map<Integer, String> names = new HashMap<>();
		Map<Integer, Integer> idles = new HashMap<>();
		Map<Integer, Integer> walks = new HashMap<>();
		try (InputStream in = NpcNames.class.getResourceAsStream("npc_names.tsv");
			BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = br.readLine()) != null)
			{
				String[] parts = line.split("\t", 4);
				if (parts.length < 2)
				{
					continue;
				}
				int id = Integer.parseInt(parts[0]);
				if (id <= 0)
				{
					// id 0 collides with the "no npc" default in saved decorations
					continue;
				}
				names.put(id, parts[1]);
				try
				{
					if (parts.length >= 3)
					{
						idles.put(id, Integer.parseInt(parts[2]));
					}
					if (parts.length >= 4)
					{
						walks.put(id, Integer.parseInt(parts[3]));
					}
				}
				catch (NumberFormatException ignored)
				{
				}
			}
		}
		catch (Exception e)
		{
			// missing or malformed resource: npc selection just finds nothing
		}
		byId = names;
		idleAnimById = idles;
		walkAnimById = walks;
	}

	private NpcNames()
	{
	}
}
