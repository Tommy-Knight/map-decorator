package com.tommyknight.mapdecorator;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
class Favourite
{
	int objectId;
	String name;
	/** Looping animation sequence id; <= 0 means none. Absent in pre-1.1 saves (defaults to 0). */
	int animationId;
	/** NPC id placed instead of objectId; <= 0 means a plain model. Absent in pre-1.1 saves (defaults to 0). */
	int npcId;
	/** Placement orientation (S=0, W=512, N=1024, E=1536). Absent in pre-1.1 saves (defaults to 0 = south). */
	int orientation;
	/** Vertical nudge; positive = up. Absent in pre-1.1 saves (defaults to 0). */
	int heightOffset;
	/** Sub-tile nudge, east-west. Absent in pre-1.1 saves (defaults to 0). */
	int offsetX;
	/** Sub-tile nudge, north-south. Absent in pre-1.1 saves (defaults to 0). */
	int offsetY;
	/** Size adjustment in percent, 0 = normal. Absent in pre-1.1 saves (defaults to 0). */
	int scale;
	/** Placed NPCs wander a few tiles from where they are put. Absent in pre-1.1 saves (defaults to false). */
	boolean roam;
}
