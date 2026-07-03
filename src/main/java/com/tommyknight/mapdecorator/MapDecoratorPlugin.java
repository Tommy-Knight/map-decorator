package com.tommyknight.mapdecorator;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.Player;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.Tile;
import net.runelite.api.widgets.Widget;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.WorldViewLoaded;
import net.runelite.api.events.WorldViewUnloaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

@Slf4j
@PluginDescriptor(
	name = "Map Decorator",
	description = "Place game objects on any tile, client-side only. Shift-right-click to place/remove.",
	tags = {"map", "object", "place", "decorator", "editor"}
)
public class MapDecoratorPlugin extends Plugin
{
	static final String CONFIG_GROUP = "mapDecorator";
	private static final String REGION_PREFIX = "region_";

	/** POH instance template regions, as used by DiscordGameEventType.REGION_POH. */
	private static final Set<Integer> POH_REGIONS = ImmutableSet.of(7534, 7535, 7790, 7791, 8046, 8047, 8302, 8303);

	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private ConfigManager configManager;
	@Inject private ClientToolbar clientToolbar;
	@Inject private Gson gson;

	private MapDecoratorPanel panel;
	private NavigationButton navButton;

	/** Active RuneLiteObjects keyed by the PlacedObject they represent. */
	private final Map<PlacedObject, RuneLiteObject> activeObjects = new HashMap<>();

	/** Active RuneLiteObjects in instanced regions (POH), keyed by scene tile. */
	private final Map<InstancedObject, RuneLiteObject> activeInstancedObjects = new HashMap<>();

	private RuneLiteObject ghostObject;
	private boolean ghostEnabled;
	private boolean hideUiEnabled;
	private volatile long fadeStartTime = -1L;
	private static final long FADE_DURATION_MS = 1000L;

	/** In-memory view of all loaded placed objects, keyed by WorldView. */
	private final ListMultimap<WorldView, PlacedObject> loadedObjects = ArrayListMultimap.create();

	@Override
	protected void startUp()
	{
		panel = injector.getInstance(MapDecoratorPanel.class);
		panel.init(this);

		navButton = NavigationButton.builder()
			.tooltip("Map Decorator")
			.icon(buildNavIcon())
			.priority(8)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);
		clientThread.invoke(this::loadAll);
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
		hideUiEnabled = false;
		fadeStartTime = -1L;
		clientThread.invoke(() ->
		{
			applyToUiWidgets(false, 0);
			if (ghostObject != null)
			{
				ghostObject.setActive(false);
				ghostObject = null;
			}
			despawnAll();
			loadedObjects.clear();
		});
	}

	// ── Cursor ghost ──────────────────────────────────────────────────────

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		if (!ghostEnabled || ghostObject == null)
		{
			return;
		}
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return;
		}
		Tile tile = wv.getSelectedSceneTile();
		if (tile == null)
		{
			ghostObject.setActive(false);
			return;
		}
		ghostObject.setOrientation(panel.getSelectedOrientation());
		ghostObject.setLocation(tile.getLocalLocation(), tile.getWorldLocation().getPlane());
		ghostObject.setActive(true);
	}

	void setGhostEnabled(boolean enabled)
	{
		ghostEnabled = enabled;
		if (enabled)
		{
			updateGhostModel(panel.getSelectedObjectId());
		}
		else
		{
			clientThread.invoke(() ->
			{
				if (ghostObject != null)
				{
					ghostObject.setActive(false);
					ghostObject = null;
				}
			});
		}
	}

	void setHideUiEnabled(boolean enabled)
	{
		hideUiEnabled = enabled;
		fadeStartTime = enabled ? -1L : System.currentTimeMillis();
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		if (hideUiEnabled)
		{
			applyToUiWidgets(true, 0);
			return;
		}
		long start = fadeStartTime;
		if (start < 0)
		{
			return;
		}
		long elapsed = System.currentTimeMillis() - start;
		if (elapsed >= FADE_DURATION_MS)
		{
			fadeStartTime = -1L;
			applyToUiWidgets(false, 0);
			return;
		}
		int alpha = (int) (255 * (1.0 - (double) elapsed / FADE_DURATION_MS));
		applyToUiWidgets(false, alpha);
	}

	private void applyToUiWidgets(boolean hidden, int opacity)
	{
		applyWidget(InterfaceID.ToplevelOsrsStretch.CHAT_CONTAINER, hidden, opacity);
		applyWidget(InterfaceID.ToplevelOsrsStretch.SIDE_CONTAINER, hidden, opacity);
		applyWidget(InterfaceID.ToplevelOsrsStretch.MAP_CONTAINER, hidden, opacity);
		applyWidget(InterfaceID.ToplevelOsrsStretch.ORBS, hidden, opacity);
		applyWidget(InterfaceID.ToplevelPreEoc.CHAT_CONTAINER, hidden, opacity);
		applyWidget(InterfaceID.ToplevelPreEoc.SIDE_CONTAINER, hidden, opacity);
		applyWidget(InterfaceID.ToplevelPreEoc.MAP_CONTAINER, hidden, opacity);
		applyWidget(InterfaceID.ToplevelPreEoc.ORBS, hidden, opacity);
		applyWidget(InterfaceID.Toplevel.CHAT_CONTAINER, hidden, opacity);
		applyWidget(InterfaceID.Toplevel.MAPCONTAINER, hidden, opacity);
		applyWidget(InterfaceID.Toplevel.SIDE, hidden, opacity);
		applyWidget(InterfaceID.Toplevel.SIDE_TOP_CONTAINER, hidden, opacity);
		applyWidget(InterfaceID.Toplevel.SIDE_BOTTOM_CONTAINER, hidden, opacity);
		applyWidget(InterfaceID.Toplevel.ORBS, hidden, opacity);
	}

	private void applyWidget(int componentId, boolean hidden, int opacity)
	{
		Widget w = client.getWidget(componentId);
		if (w == null)
		{
			return;
		}
		w.setHidden(hidden);
		if (!hidden)
		{
			w.setOpacity(opacity);
		}
	}

	void updateGhostModel(int objectId)
	{
		if (!ghostEnabled)
		{
			return;
		}
		clientThread.invoke(() ->
		{
			if (ghostObject != null)
			{
				ghostObject.setActive(false);
			}
			ModelData md = client.loadModelData(objectId);
			if (md == null)
			{
				ghostObject = null;
				return;
			}
			RuneLiteObject rlo = client.createRuneLiteObject();
			rlo.setModel(md.light());
			rlo.setActive(false);
			ghostObject = rlo;
		});
	}

	// ── Region loading ────────────────────────────────────────────────────

	@Subscribe
	public void onWorldViewLoaded(WorldViewLoaded event)
	{
		loadRegions(event.getWorldView());
	}

	@Subscribe
	public void onWorldViewUnloaded(WorldViewUnloaded event)
	{
		WorldView wv = event.getWorldView();
		for (PlacedObject obj : loadedObjects.get(wv))
		{
			RuneLiteObject rlo = activeObjects.remove(obj);
			if (rlo != null)
			{
				rlo.setActive(false);
			}
		}
		loadedObjects.removeAll(wv);
		// Clear instanced objects — reloaded on GameStateChanged if re-entering instance
		for (RuneLiteObject rlo : activeInstancedObjects.values())
		{
			if (rlo != null)
			{
				rlo.setActive(false);
			}
		}
		activeInstancedObjects.clear();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN && isInPoh())
		{
			loadInstancedObjects();
		}
	}

	private void loadAll()
	{
		WorldView wv = client.getTopLevelWorldView();
		if (wv != null)
		{
			loadRegions(wv);
		}
		if (isInPoh())
		{
			loadInstancedObjects();
		}
	}

	/** True when the player is inside their (or a visited) player-owned house. */
	private boolean isInPoh()
	{
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null || !wv.isInstance())
		{
			return false;
		}
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return false;
		}
		WorldPoint template = WorldPoint.fromLocalInstance(client, player.getLocalLocation());
		return POH_REGIONS.contains(template.getRegionID());
	}

	private void loadRegions(WorldView wv)
	{
		int[] regions = wv.getMapRegions();
		if (regions == null)
		{
			return;
		}
		for (int regionId : regions)
		{
			for (PlacedObject obj : getStoredObjects(regionId))
			{
				if (!loadedObjects.containsValue(obj))
				{
					loadedObjects.put(wv, obj);
					spawnObject(wv, obj);
				}
			}
		}
	}

	// ── Placement ─────────────────────────────────────────────────────────

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (!client.isKeyPressed(KeyCode.KC_SHIFT))
		{
			return;
		}

		MenuAction type = event.getMenuEntry().getType();
		if (type != MenuAction.WALK && type != MenuAction.SET_HEADING)
		{
			return;
		}

		int worldId = event.getMenuEntry().getWorldViewId();
		WorldView wv = client.getWorldView(worldId);
		if (wv == null)
		{
			return;
		}

		Tile tile = wv.getSelectedSceneTile();
		if (tile == null)
		{
			return;
		}

		if (wv.isInstance())
		{
			if (!isInPoh())
			{
				return;
			}
			int sceneX = tile.getLocalLocation().getSceneX();
			int sceneY = tile.getLocalLocation().getSceneY();
			int plane = tile.getWorldLocation().getPlane();
			List<InstancedObject> atTile = findAllAtInstancedTile(sceneX, sceneY, plane);

			int menuIdx = -1;
			for (InstancedObject obj : atTile)
			{
				String target = stackLabel(obj.getStackIndex());
				client.getMenu().createMenuEntry(menuIdx--)
					.setOption("Remove")
					.setTarget(target)
					.setType(MenuAction.RUNELITE)
					.onClick(e -> removeInstancedObject(obj));
				client.getMenu().createMenuEntry(menuIdx--)
					.setOption("Rotate")
					.setTarget(target)
					.setType(MenuAction.RUNELITE)
					.onClick(e -> rotateInstancedObject(obj));
			}
			if (atTile.size() < 3)
			{
				client.getMenu().createMenuEntry(menuIdx)
					.setOption("Place")
					.setTarget("Object")
					.setType(MenuAction.RUNELITE)
					.onClick(e -> placeInstancedObject(tile));
			}
		}
		else
		{
			WorldPoint wp = WorldPoint.fromLocalInstance(client, tile.getLocalLocation());
			List<PlacedObject> atTile = findAllAtTile(wv, wp);

			int menuIdx = -1;
			for (PlacedObject obj : atTile)
			{
				String target = stackLabel(obj.getStackIndex());
				client.getMenu().createMenuEntry(menuIdx--)
					.setOption("Remove")
					.setTarget(target)
					.setType(MenuAction.RUNELITE)
					.onClick(e -> removeObject(wv, obj));
				client.getMenu().createMenuEntry(menuIdx--)
					.setOption("Rotate")
					.setTarget(target)
					.setType(MenuAction.RUNELITE)
					.onClick(e -> rotateObject(wv, obj));
			}
			if (atTile.size() < 3)
			{
				client.getMenu().createMenuEntry(menuIdx)
					.setOption("Place")
					.setTarget("Object")
					.setType(MenuAction.RUNELITE)
					.onClick(e -> placeObject(wv, wp, tile.getLocalLocation()));
			}
		}
	}

	private void placeObject(WorldView wv, WorldPoint wp, LocalPoint directLp)
	{
		List<PlacedObject> atTile = findAllAtTile(wv, wp);
		if (atTile.size() >= 3)
		{
			return;
		}

		Set<Integer> used = new HashSet<>();
		for (PlacedObject o : atTile)
		{
			used.add(o.getStackIndex());
		}
		int stackIndex = 0;
		while (used.contains(stackIndex))
		{
			stackIndex++;
		}

		PlacedObject obj = new PlacedObject(
			wp.getRegionID(),
			wp.getRegionX(),
			wp.getRegionY(),
			wp.getPlane(),
			panel.getSelectedObjectId(),
			panel.getSelectedOrientation(),
			stackIndex
		);

		loadedObjects.put(wv, obj);
		spawnObject(wv, obj, directLp);
		saveRegion(wp.getRegionID(), wv);
	}

	private void removeObject(WorldView wv, PlacedObject obj)
	{
		loadedObjects.remove(wv, obj);
		RuneLiteObject rlo = activeObjects.remove(obj);
		if (rlo != null)
		{
			rlo.setActive(false);
		}
		saveRegion(obj.getRegionId(), wv);
	}

	private void rotateObject(WorldView wv, PlacedObject obj)
	{
		PlacedObject rotated = obj.rotated();
		loadedObjects.remove(wv, obj);
		loadedObjects.put(wv, rotated);

		RuneLiteObject rlo = activeObjects.remove(obj);
		if (rlo != null)
		{
			rlo.setOrientation(rotated.getOrientation());
			activeObjects.put(rotated, rlo);
		}

		saveRegion(obj.getRegionId(), wv);
		SwingUtilities.invokeLater(() ->
		{
			panel.setObjectId(obj.getObjectId());
			panel.setOrientationControls(rotated.getOrientation());
		});
	}

	// ── RuneLiteObject management ─────────────────────────────────────────

	private void spawnObject(WorldView wv, PlacedObject obj)
	{
		spawnObject(wv, obj, null);
	}

	private void spawnObject(WorldView wv, PlacedObject obj, LocalPoint fallbackLp)
	{
		clientThread.invoke(() ->
		{
			ModelData md = client.loadModelData(obj.getObjectId());
			if (md == null)
			{
				log.debug("No model data for object id {}", obj.getObjectId());
				return;
			}

			LocalPoint lp = LocalPoint.fromWorld(wv, obj.toWorldPoint());
			if (lp == null)
			{
				lp = fallbackLp;
			}
			if (lp == null)
			{
				return;
			}

			Model model = md.light();
			RuneLiteObject rlo = client.createRuneLiteObject();
			rlo.setModel(model);
			rlo.setOrientation(obj.getOrientation());
			rlo.setLocation(lp, obj.getPlane());
			rlo.setActive(true);
			activeObjects.put(obj, rlo);
		});
	}

	private void despawnAll()
	{
		clientThread.invoke(() ->
		{
			for (RuneLiteObject rlo : activeObjects.values())
			{
				rlo.setActive(false);
			}
			activeObjects.clear();
			for (RuneLiteObject rlo : activeInstancedObjects.values())
			{
				if (rlo != null)
				{
					rlo.setActive(false);
				}
			}
			activeInstancedObjects.clear();
		});
	}

	// ── Persistence ───────────────────────────────────────────────────────

	private void saveRegion(int regionId, WorldView wv)
	{
		List<PlacedObject> regionObjects = loadedObjects.get(wv).stream()
			.filter(o -> o.getRegionId() == regionId)
			.collect(Collectors.toList());

		if (regionObjects.isEmpty())
		{
			configManager.unsetConfiguration(CONFIG_GROUP, REGION_PREFIX + regionId);
		}
		else
		{
			configManager.setConfiguration(CONFIG_GROUP, REGION_PREFIX + regionId, gson.toJson(regionObjects));
		}
	}

	Collection<PlacedObject> getStoredObjects(int regionId)
	{
		String json = configManager.getConfiguration(CONFIG_GROUP, REGION_PREFIX + regionId);
		if (json == null || json.isEmpty())
		{
			return Collections.emptyList();
		}
		try
		{
			List<PlacedObject> list = gson.fromJson(json, new TypeToken<List<PlacedObject>>() {}.getType());
			return list != null ? list : Collections.emptyList();
		}
		catch (Exception e)
		{
			log.warn("Failed to load placed objects for region {}", regionId, e);
			return Collections.emptyList();
		}
	}

	// ── Instanced region (POH) support ───────────────────────────────────

	private void placeInstancedObject(Tile tile)
	{
		int sceneX = tile.getLocalLocation().getSceneX();
		int sceneY = tile.getLocalLocation().getSceneY();
		int plane = tile.getWorldLocation().getPlane();

		List<InstancedObject> atTile = findAllAtInstancedTile(sceneX, sceneY, plane);
		if (atTile.size() >= 3)
		{
			return;
		}

		Set<Integer> used = new HashSet<>();
		for (InstancedObject o : atTile)
		{
			used.add(o.getStackIndex());
		}
		int stackIndex = 0;
		while (used.contains(stackIndex))
		{
			stackIndex++;
		}

		InstancedObject obj = new InstancedObject(sceneX, sceneY, plane,
			panel.getSelectedObjectId(), panel.getSelectedOrientation(), stackIndex);

		activeInstancedObjects.put(obj, null);
		spawnInstancedObject(obj);
		saveInstancedObjects();
	}

	private void removeInstancedObject(InstancedObject obj)
	{
		RuneLiteObject rlo = activeInstancedObjects.remove(obj);
		if (rlo != null)
		{
			rlo.setActive(false);
		}
		saveInstancedObjects();
	}

	private void rotateInstancedObject(InstancedObject obj)
	{
		InstancedObject rotated = obj.rotated();
		RuneLiteObject rlo = activeInstancedObjects.remove(obj);
		if (rlo != null)
		{
			rlo.setOrientation(rotated.getOrientation());
		}
		activeInstancedObjects.put(rotated, rlo);
		saveInstancedObjects();
		SwingUtilities.invokeLater(() ->
		{
			panel.setObjectId(obj.getObjectId());
			panel.setOrientationControls(rotated.getOrientation());
		});
	}

	private void spawnInstancedObject(InstancedObject obj)
	{
		clientThread.invoke(() ->
		{
			WorldView wv = client.getTopLevelWorldView();
			if (wv == null)
			{
				return;
			}
			ModelData md = client.loadModelData(obj.getObjectId());
			if (md == null)
			{
				return;
			}
			LocalPoint lp = LocalPoint.fromScene(obj.getSceneX(), obj.getSceneY(), wv);
			Model model = md.light();
			RuneLiteObject rlo = client.createRuneLiteObject();
			rlo.setModel(model);
			rlo.setOrientation(obj.getOrientation());
			rlo.setLocation(lp, obj.getPlane());
			rlo.setActive(true);
			activeInstancedObjects.put(obj, rlo);
		});
	}

	private void loadInstancedObjects()
	{
		Player player = client.getLocalPlayer();
		if (player == null || player.getName() == null)
		{
			return;
		}
		String json = configManager.getConfiguration(CONFIG_GROUP, "instance_" + player.getName());
		if (json == null || json.isEmpty())
		{
			return;
		}
		try
		{
			List<InstancedObject> list = gson.fromJson(json, new TypeToken<List<InstancedObject>>(){}.getType());
			if (list == null)
			{
				return;
			}
			for (InstancedObject obj : list)
			{
				// LOGGED_IN fires on every scene rebuild in the house — don't respawn existing objects
				if (activeInstancedObjects.containsKey(obj))
				{
					continue;
				}
				activeInstancedObjects.put(obj, null);
				spawnInstancedObject(obj);
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to load instanced objects", e);
		}
	}

	void clearArea()
	{
		clientThread.invoke(() ->
		{
			WorldView wv = client.getTopLevelWorldView();
			if (wv == null)
			{
				return;
			}
			if (isInPoh())
			{
				for (RuneLiteObject rlo : activeInstancedObjects.values())
				{
					if (rlo != null)
					{
						rlo.setActive(false);
					}
				}
				activeInstancedObjects.clear();
				saveInstancedObjects();
			}
			else
			{
				List<PlacedObject> toRemove = new ArrayList<>(loadedObjects.get(wv));
				Set<Integer> regions = new HashSet<>();
				for (PlacedObject obj : toRemove)
				{
					loadedObjects.remove(wv, obj);
					regions.add(obj.getRegionId());
					RuneLiteObject rlo = activeObjects.remove(obj);
					if (rlo != null)
					{
						rlo.setActive(false);
					}
				}
				for (int regionId : regions)
				{
					saveRegion(regionId, wv);
				}
			}
		});
	}

	private void saveInstancedObjects()
	{
		Player player = client.getLocalPlayer();
		if (player == null || player.getName() == null)
		{
			return;
		}
		List<InstancedObject> list = new ArrayList<>(activeInstancedObjects.keySet());
		String key = "instance_" + player.getName();
		if (list.isEmpty())
		{
			configManager.unsetConfiguration(CONFIG_GROUP, key);
		}
		else
		{
			configManager.setConfiguration(CONFIG_GROUP, key, gson.toJson(list));
		}
	}

	private List<InstancedObject> findAllAtInstancedTile(int sceneX, int sceneY, int plane)
	{
		List<InstancedObject> result = new ArrayList<>();
		for (InstancedObject obj : activeInstancedObjects.keySet())
		{
			if (obj.getSceneX() == sceneX && obj.getSceneY() == sceneY && obj.getPlane() == plane)
			{
				result.add(obj);
			}
		}
		result.sort(Comparator.comparingInt(InstancedObject::getStackIndex));
		return result;
	}

	/** Find all placed objects at the given tile, sorted by stack index. Uses loadedObjects so entries whose model failed to spawn are still removable. */
	private List<PlacedObject> findAllAtTile(WorldView wv, WorldPoint wp)
	{
		List<PlacedObject> result = new ArrayList<>();
		for (PlacedObject obj : loadedObjects.get(wv))
		{
			if (obj.getRegionId() == wp.getRegionID()
				&& obj.getRegionX() == wp.getRegionX()
				&& obj.getRegionY() == wp.getRegionY()
				&& obj.getPlane() == wp.getPlane())
			{
				result.add(obj);
			}
		}
		result.sort(Comparator.comparingInt(PlacedObject::getStackIndex));
		return result;
	}

	private static String stackLabel(int stackIndex)
	{
		return stackIndex == 0 ? "Object" : "Object " + (stackIndex + 1);
	}

	// ── Favourites ────────────────────────────────────────────────────────

	Favourite[] getFavourites()
	{
		Favourite[] favs = new Favourite[28];
		for (int i = 0; i < 28; i++)
		{
			String json = configManager.getConfiguration(CONFIG_GROUP, "fav_" + i);
			if (json != null && !json.isEmpty())
			{
				try
				{
					favs[i] = gson.fromJson(json, Favourite.class);
				}
				catch (Exception ignored)
				{
				}
			}
		}
		return favs;
	}

	int getVisibleFavSlots()
	{
		String val = configManager.getConfiguration(CONFIG_GROUP, "fav_slots");
		if (val == null)
		{
			return 5;
		}
		try
		{
			return Math.max(5, Math.min(28, Integer.parseInt(val)));
		}
		catch (NumberFormatException e)
		{
			return 5;
		}
	}

	void setVisibleFavSlots(int count)
	{
		configManager.setConfiguration(CONFIG_GROUP, "fav_slots", String.valueOf(count));
	}

	void setFavourite(int slot, int objectId, String name)
	{
		configManager.setConfiguration(CONFIG_GROUP, "fav_" + slot, gson.toJson(new Favourite(objectId, name)));
	}

	void clearFavourite(int slot)
	{
		configManager.unsetConfiguration(CONFIG_GROUP, "fav_" + slot);
	}

	void shiftFavouritesDown(int removedSlot, int totalVisible)
	{
		for (int i = removedSlot; i < totalVisible - 1; i++)
		{
			String json = configManager.getConfiguration(CONFIG_GROUP, "fav_" + (i + 1));
			if (json != null && !json.isEmpty())
			{
				configManager.setConfiguration(CONFIG_GROUP, "fav_" + i, json);
			}
			else
			{
				configManager.unsetConfiguration(CONFIG_GROUP, "fav_" + i);
			}
		}
		configManager.unsetConfiguration(CONFIG_GROUP, "fav_" + (totalVisible - 1));
	}

	// ── Helpers ───────────────────────────────────────────────────────────

	/** Called on the client thread from the panel to load unlit model data for the preview renderer. */
	ModelData loadPreviewModelData(int objectId)
	{
		try
		{
			return client.loadModelData(objectId);
		}
		catch (Exception e)
		{
			log.debug("Failed to load preview model for id {}", objectId, e);
			return null;
		}
	}

	private static BufferedImage buildNavIcon()
	{
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		// simple 3-tile grid icon
		g.setColor(new Color(100, 180, 255));
		g.fillRect(1, 1, 6, 6);
		g.fillRect(9, 1, 6, 6);
		g.fillRect(1, 9, 6, 6);
		g.setColor(new Color(180, 220, 255));
		g.fillRect(9, 9, 6, 6);
		g.setColor(new Color(60, 130, 200));
		g.drawRect(1, 1, 6, 6);
		g.drawRect(9, 1, 6, 6);
		g.drawRect(1, 9, 6, 6);
		g.drawRect(9, 9, 6, 6);
		g.dispose();
		return img;
	}
}
