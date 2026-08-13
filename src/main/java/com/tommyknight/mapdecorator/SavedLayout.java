package com.tommyknight.mapdecorator;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A named export/import code kept for later, so it doesn't only live on the clipboard. */
@Data
@AllArgsConstructor
@NoArgsConstructor
class SavedLayout
{
	String name;
	/** Base64 code, verbatim (see exportNearby/exportInstanced/importFromClipboard). */
	String code;
}
