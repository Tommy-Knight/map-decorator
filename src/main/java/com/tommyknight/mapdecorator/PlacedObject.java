package com.tommyknight.mapdecorator;

import lombok.EqualsAndHashCode;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/**
 * A single user-placed object. Up to 3 may share the same tile, distinguished by stackIndex.
 */
@Value
@EqualsAndHashCode(of = {"regionId", "regionX", "regionY", "plane", "stackIndex"})
class PlacedObject
{
	int regionId;
	int regionX;
	int regionY;
	int plane;
	int objectId;
	/** RuneLiteObject orientation. S=0, W=512, N=1024, E=1536. */
	int orientation;
	/** Stack slot on this tile: 0 = first placed, 1 = second, 2 = third. */
	int stackIndex;

	WorldPoint toWorldPoint()
	{
		return WorldPoint.fromRegion(regionId, regionX, regionY, plane);
	}

	PlacedObject withOrientation(int newOrientation)
	{
		return new PlacedObject(regionId, regionX, regionY, plane, objectId, newOrientation, stackIndex);
	}

	PlacedObject rotated()
	{
		return withOrientation((orientation + 512) % 2048);
	}
}
