package com.tommyknight.mapdecorator;

import java.util.List;
import javax.swing.AbstractSpinnerModel;

/**
 * Spinner model that cycles only through a curated, sorted list of ids â€” so arrow-key/button
 * stepping jumps between actually-known entries (a named object, an animation with a model)
 * instead of every raw integer, most of which are unnamed junk between real ones. The value can
 * still be set to any integer via {@link #setValue} â€” manual entry, search selection, favourites,
 * and restoring a previously placed object/animation must all keep working even for ids outside
 * the curated list.
 *
 * An optional {@code floor} sentinel (e.g. -1 for the Animation box's "none") is treated as an
 * extra stop below the curated list; pass null if every value should be a real entry.
 */
class CuratedIdSpinnerModel extends AbstractSpinnerModel
{
	private final List<Integer> knownIds; // sorted ascending
	private final Integer floor;
	private int value;

	CuratedIdSpinnerModel(List<Integer> knownIds, Integer floor, int initialValue)
	{
		this.knownIds = knownIds;
		this.floor = floor;
		this.value = initialValue;
	}

	@Override
	public Object getValue()
	{
		return value;
	}

	@Override
	public void setValue(Object value)
	{
		// Always fire, even for a same-value commit, so the JFormattedTextField editor
		// re-syncs its displayed text after every commit attempt (typed or programmatic).
		this.value = (Integer) value;
		fireStateChanged();
	}

	@Override
	public Object getNextValue()
	{
		if (knownIds.isEmpty())
		{
			return null;
		}
		for (int id : knownIds)
		{
			if (id > value)
			{
				return id;
			}
		}
		// Past the last known id (or value is some stale/unrecognised number above them all,
		// e.g. from an old favourite) â€” wrap to the floor/start instead of permanently disabling
		// the up arrow.
		return floor != null ? floor : knownIds.get(0);
	}

	@Override
	public Object getPreviousValue()
	{
		if (knownIds.isEmpty())
		{
			return null;
		}
		if (floor != null && value <= floor)
		{
			return knownIds.get(knownIds.size() - 1);
		}
		Integer candidate = null;
		for (int id : knownIds)
		{
			if (id >= value)
			{
				break;
			}
			candidate = id;
		}
		if (candidate != null)
		{
			return candidate;
		}
		// value is below every known id (or a stale/unrecognised number smaller than all of
		// them) â€” step to the floor/end rather than disabling the down arrow.
		return floor != null ? floor : knownIds.get(knownIds.size() - 1);
	}
}
