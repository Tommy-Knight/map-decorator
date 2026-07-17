package com.tommyknight.mapdecorator;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lookup of model ids to the name of a game object known to use them, loaded from
 * object_models.tsv (generated from the Wiki/weirdgloop object database, which maps
 * object ids to names and their objectModels; here re-keyed by model id since that is
 * what the plugin places).
 */
final class ObjectModelNames
{
	private static volatile Map<Integer, String> byId;
	private static volatile Map<String, Integer> byName;

	/** Name of a known object using this model id, or null if unknown. */
	static String name(int modelId)
	{
		load();
		return byId.get(modelId);
	}

	/** Model id for the given object name (case-insensitive), or null if unknown. */
	static Integer id(String name)
	{
		load();
		return byName.get(name.trim().toUpperCase(Locale.ROOT));
	}

	/**
	 * Model ids whose object name contains the given substring (case-insensitive),
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
				results.add(e);
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
		try (InputStream in = ObjectModelNames.class.getResourceAsStream("object_models.tsv");
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
				// Some wiki names carry game menu markup, e.g. "<col=ffff00>Man</col>"
				String name = line.substring(tab + 1).replaceAll("<[^>]*>", "").trim();
				if (name.isEmpty())
				{
					continue;
				}
				m.put(id, name);
				r.putIfAbsent(name.toUpperCase(Locale.ROOT), id);
			}
		}
		catch (Exception e)
		{
			// missing or malformed resource: fall back to numeric display
		}
		byId = m;
		byName = r;
	}

	private ObjectModelNames()
	{
	}
}
