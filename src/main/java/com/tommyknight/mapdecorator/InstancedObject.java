package com.tommyknight.mapdecorator;

import lombok.EqualsAndHashCode;
import lombok.Value;

/**
 * A placed object in an instanced region (e.g. POH).
 * Stored by scene tile coordinates, which are consistent across sessions in the same house.
 */
@Value
@EqualsAndHashCode(of = {"sceneX", "sceneY", "plane", "stackIndex"})
class InstancedObject
{
	int sceneX;
	int sceneY;
	int plane;
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
			&& roam == other.roam;
	}
}
