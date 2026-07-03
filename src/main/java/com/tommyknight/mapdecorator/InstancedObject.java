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

	InstancedObject withOrientation(int newOrientation)
	{
		return new InstancedObject(sceneX, sceneY, plane, objectId, newOrientation, stackIndex);
	}

	InstancedObject rotated()
	{
		return withOrientation((orientation + 512) % 2048);
	}
}
