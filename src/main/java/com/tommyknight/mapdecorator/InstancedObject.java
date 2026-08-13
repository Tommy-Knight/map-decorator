package com.tommyknight.mapdecorator;

import lombok.EqualsAndHashCode;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/**
 * A placed object in an instanced region (e.g. POH).
 *
 * Stored by the region/plane a {@code WorldPoint.fromLocalInstance} resolves the room's
 * template chunk to, not by the scene tile it currently renders at. The template chunk
 * identifies the actual room design and is stable across layout edits; the scene tile it
 * renders to, and even the plane Jagex assigns to that floor (which shifts, e.g. when a
 * dungeon is added), are not. This mirrors how the overworld's PlacedObject stores
 * regionId/regionX/regionY rather than a snapshot of the currently loaded scene.
 *
 * Two rooms with identical furniture resolve to the exact same template chunk, and so the
 * exact same regionId/regionX/regionY/plane — the template chunk identifies a room's
 * geometry, not its physical slot. occurrence disambiguates them: it's this room's index
 * among however many rooms currently share that template, in a fixed deterministic order
 * (ascending WorldPoint x, then y) recomputed fresh each time. Stable as long as the set of
 * colliding rooms doesn't change; adding or removing another identically-furnished room can
 * shift the ordering.
 */
@Value
@EqualsAndHashCode(of = {"regionId", "regionX", "regionY", "plane", "occurrence", "stackIndex"})
class InstancedObject
{
	int regionId;
	int regionX;
	int regionY;
	int plane;
	int occurrence;
	int objectId;
	int orientation;
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
	/** Flipped left-to-right. Absent in pre-1.2 saves (defaults to false). */
	boolean mirror;
	/** Tint colour with alpha as strength; alpha 0 = no tint. Absent in pre-1.2 saves (defaults to 0). */
	int tintArgb;

	WorldPoint toWorldPoint()
	{
		return WorldPoint.fromRegion(regionId, regionX, regionY, plane);
	}

	InstancedObject withStackIndex(int newStackIndex)
	{
		return new InstancedObject(regionId, regionX, regionY, plane, occurrence, objectId, orientation,
			newStackIndex, animationId, heightOffset, offsetX, offsetY, npcId, scale, roam, mirror, tintArgb);
	}

	/** True if this and other would look identical if placed (ignores stack slot, which is just where they sit). */
	boolean sameDecoration(InstancedObject other)
	{
		return objectId == other.objectId
			&& orientation == other.orientation
			&& animationId == other.animationId
			&& heightOffset == other.heightOffset
			&& offsetX == other.offsetX
			&& offsetY == other.offsetY
			&& npcId == other.npcId
			&& scale == other.scale
			&& roam == other.roam
			&& mirror == other.mirror
			&& tintArgb == other.tintArgb;
	}
}
