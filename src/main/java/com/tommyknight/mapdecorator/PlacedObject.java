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
	/** Looping animation sequence id; <= 0 means none. Absent in pre-1.1 saves (defaults to 0). */
	int animationId;
	/** Vertical nudge in scene Z units above ground height; positive = up. Absent in pre-1.1 saves (defaults to 0). */
	int heightOffset;
	/** Sub-tile horizontal nudge in LocalPoint units (128 = 1 tile), east-west. Absent in pre-1.2 saves (defaults to 0). */
	int offsetX;
	/** Sub-tile horizontal nudge in LocalPoint units (128 = 1 tile), north-south. Absent in pre-1.2 saves (defaults to 0). */
	int offsetY;
	/** NPC id whose merged model is placed instead of objectId; <= 0 means a plain model. Absent in pre-1.1 saves (defaults to 0). */
	int npcId;
	/** Size adjustment in percent, -100..100 where 0 = normal size. Absent in pre-1.1 saves (defaults to 0). */
	int scale;
	/** Placed NPCs wander a few tiles from this spot. Absent in pre-1.1 saves (defaults to false). */
	boolean roam;

	WorldPoint toWorldPoint()
	{
		return WorldPoint.fromRegion(regionId, regionX, regionY, plane);
	}

	PlacedObject withStackIndex(int newStackIndex)
	{
		return new PlacedObject(regionId, regionX, regionY, plane, objectId, orientation, newStackIndex, animationId, heightOffset, offsetX, offsetY, npcId, scale, roam);
	}

	/** True if this and other would look identical if placed (ignores stack slot, which is just where they sit). */
	boolean sameDecoration(PlacedObject other)
	{
		return objectId == other.objectId
			&& orientation == other.orientation
			&& animationId == other.animationId
			&& heightOffset == other.heightOffset
			&& offsetX == other.offsetX
			&& offsetY == other.offsetY
			&& npcId == other.npcId
			&& scale == other.scale
			&& roam == other.roam;
	}
}
