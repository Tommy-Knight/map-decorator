# Map Decorator

Decorate the game world with any object, entirely client-side. Place, rotate and stack game objects on any tile, save your favourites, and set up clean screenshots — nothing is ever sent to the server, and only you can see your placements.

## Features

- **Place objects anywhere** — hold Shift and right-click a tile to Place, Remove or Rotate objects. Up to 3 objects can be stacked per tile.
- **Rotate to pick** — rotating a placed object also selects it in the panel (object ID and orientation), so you can quickly place more of what you're looking at.
- **Live 3D preview** — the side panel renders the selected object; right-click-drag to orbit, scroll to zoom.
- **Facing controls** — N/E/S/W buttons plus a free rotation slider.
- **Cursor ghost** — optionally show the selected object under your cursor before placing.
- **Favourites** — save up to 28 named objects; click to load, right-click a slot to remove it.
- **Player-owned house support** — placements inside your POH are saved separately and restored when you return.
- **Hide UI** — a screenshot aid that temporarily hides the HUD (minimap, chatbox, inventory) while ticked, and fades it back in when unticked.
- **Clear Area** — remove every placed object in the current area with one confirmation-guarded button.

## Usage

1. Enable the plugin and open the **Map Decorator** side panel.
2. Enter an object ID (or pick a favourite) and choose a facing.
3. **Shift + right-click** any walkable tile and choose **Place**.
4. Shift + right-click an existing placement for **Remove** and **Rotate** options.

Placements persist between sessions per region, and per player for the POH.

## Notes

- Everything is cosmetic and local to your client — no game state is modified and no data leaves your machine.
