package com.tommyknight.mapdecorator;

import lombok.Value;

/**
 * A snapshot of everything the panel has selected, so an accidental bulk overwrite
 * (e.g. clicking a favourite while a hard-won find is loaded) can be undone.
 */
@Value
class PanelSelection
{
	int objectId;
	int npcId;
	int animationId;
	int orientation;
	int heightOffset;
	int offsetX;
	int offsetY;
	int scale;
	boolean roam;
}
