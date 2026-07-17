package com.tommyknight.mapdecorator;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Animation;
import net.runelite.api.AnimationController;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Renderable;
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
import net.runelite.client.callback.Hooks;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

@Slf4j
@PluginDescriptor(
	name = "Map Decorator",
	description = "Place objects and NPCs on any tile, client-side only. Shift right-click a tile to start.",
	tags = {"map", "object", "npc", "place", "decorator", "editor"}
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
	@Inject private Hooks hooks;
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
	// "Be Object": the local player is hidden from rendering and this stand-in follows them
	private final Hooks.RenderableDrawListener drawListener = this::shouldDraw;
	private volatile boolean beObjectEnabled;
	private RuneLiteObject beObject;
	private boolean beObjectMoving;
	// Hides the plugin's own Place/Edit/Remove right-click entries
	private volatile boolean menusHidden;

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
		hooks.registerRenderableDrawListener(drawListener);
		clientThread.invoke(this::loadAll);
	}

	/** Skips rendering the local player while "Be Object" stands in for them. */
	private boolean shouldDraw(Renderable renderable, boolean drawingUI)
	{
		return !(beObjectEnabled && renderable == client.getLocalPlayer());
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
		hooks.unregisterRenderableDrawListener(drawListener);
		endEdit();
		undoStack.clear();
		redoStack.clear();
		hideUiEnabled = false;
		beObjectEnabled = false;
		menusHidden = false;
		clientThread.invoke(() ->
		{
			applyToUiWidgets(false, 0);
			if (ghostObject != null)
			{
				ghostObject.setActive(false);
				ghostObject = null;
			}
			if (beObject != null)
			{
				beObject.setActive(false);
				beObject = null;
			}
			despawnAll();
			loadedObjects.clear();
		});
	}

	// â”€â”€ Cursor ghost â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		tickGhost();
		tickBeObject();
		tickRoamers();
	}

	// â”€â”€ Roaming npcs â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

	// Walk speed of one tile (128 local units) per 0.6s game tick, in local units per ~20ms client tick
	private static final int ROAM_STEP_UNITS = 4;
	private static final int ROAM_MAX_TILES = 3;

	/** Live wander state for one placed npc with roam enabled. */
	private static final class Roamer
	{
		final RuneLiteObject rlo;
		final LocalPoint origin;
		final int plane;
		final int baseOrientation;
		final int heightOffset;
		final int walkAnim;
		final int idleAnim;
		// Distance from origin along the faced direction, in local units
		int currentUnits;
		int targetUnits;
		boolean walking;
		int pauseTicks;

		Roamer(RuneLiteObject rlo, LocalPoint origin, int plane, int baseOrientation, int heightOffset, int walkAnim, int idleAnim)
		{
			this.rlo = rlo;
			this.origin = origin;
			this.plane = plane;
			this.baseOrientation = baseOrientation;
			this.heightOffset = heightOffset;
			this.walkAnim = walkAnim;
			this.idleAnim = idleAnim;
		}
	}

	private final List<Roamer> roamers = new ArrayList<>();
	private final Random roamRandom = new Random();

	/** Registers a spawned npc for wandering. Must be called on the client thread. */
	private void registerRoamer(RuneLiteObject rlo, LocalPoint origin, int plane, int orientation, int heightOffset, int npcId, int animationId)
	{
		roamers.add(new Roamer(rlo, origin, plane, orientation, heightOffset,
			NpcNames.walkAnimation(npcId), effectiveAnimation(animationId, npcId)));
	}

	private void tickRoamers()
	{
		if (roamers.isEmpty())
		{
			return;
		}
		roamers.removeIf(r -> !r.rlo.isActive());
		for (Roamer r : roamers)
		{
			tickRoamer(r);
		}
	}

	private void tickRoamer(Roamer r)
	{
		if (r.walking)
		{
			int step = Math.min(ROAM_STEP_UNITS, Math.abs(r.targetUnits - r.currentUnits));
			r.currentUnits += r.targetUnits > r.currentUnits ? step : -step;
			if (r.currentUnits == r.targetUnits)
			{
				r.walking = false;
				r.pauseTicks = 30 + roamRandom.nextInt(200);
				applyAnimation(r.rlo, r.idleAnim);
				r.rlo.setOrientation(r.baseOrientation);
			}
		}
		else if (--r.pauseTicks <= 0)
		{
			int target = 128 * roamRandom.nextInt(ROAM_MAX_TILES + 1);
			if (target == r.currentUnits)
			{
				r.pauseTicks = 30 + roamRandom.nextInt(200);
			}
			else
			{
				r.targetUnits = target;
				r.walking = true;
				applyAnimation(r.rlo, r.walkAnim > 0 ? r.walkAnim : r.idleAnim);
				// Face the way it's about to walk; walking back means turning around
				r.rlo.setOrientation(target > r.currentUnits ? r.baseOrientation : (r.baseOrientation + 1024) % 2048);
			}
		}

		// Position along the faced axis: S=0, W=512, N=1024, E=1536 maps to (-sin, -cos)
		double rad = r.baseOrientation / 2048.0 * 2 * Math.PI;
		int dx = (int) Math.round(-Math.sin(rad) * r.currentUnits);
		int dy = (int) Math.round(-Math.cos(rad) * r.currentUnits);
		LocalPoint lp = new LocalPoint(r.origin.getX() + dx, r.origin.getY() + dy, r.origin.getWorldView());
		r.rlo.setLocation(lp, r.plane);
		r.rlo.setZ(r.rlo.getZ() - r.heightOffset);
	}

	private void tickGhost()
	{
		if (!ghostEnabled || ghostObject == null)
		{
			return;
		}
		// While editing a placed object the panel controls drive that object, not the ghost â€”
		// a second model tracking the cursor with the same values would only mislead.
		if (isEditing())
		{
			ghostObject.setActive(false);
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
		LocalPoint base = tile.getLocalLocation();
		LocalPoint lp = new LocalPoint(base.getX() + panel.getSelectedOffsetX(), base.getY() + panel.getSelectedOffsetY(), base.getWorldView());
		ghostObject.setLocation(lp, tile.getWorldLocation().getPlane());
		// Scene Z is negative-upward; subtract so a positive height offset raises the object
		ghostObject.setZ(ghostObject.getZ() - panel.getSelectedHeightOffset());
		ghostObject.setActive(true);
	}

	private void tickBeObject()
	{
		if (!beObjectEnabled || beObject == null)
		{
			return;
		}
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			beObject.setActive(false);
			return;
		}
		// Swap between the npc's walk and idle animations as the player starts/stops moving
		int npcId = panel.getSelectedNpcId();
		boolean moving = player.getPoseAnimation() != player.getIdlePoseAnimation();
		if (moving != beObjectMoving)
		{
			beObjectMoving = moving;
			if (npcId > 0)
			{
				int walk = NpcNames.walkAnimation(npcId);
				int idle = effectiveAnimation(panel.getSelectedAnimationId(), npcId);
				applyAnimation(beObject, moving && walk > 0 ? walk : idle);
			}
		}
		beObject.setOrientation(player.getCurrentOrientation());
		beObject.setLocation(player.getLocalLocation(), player.getWorldLocation().getPlane());
		beObject.setZ(beObject.getZ() - panel.getSelectedHeightOffset());
		beObject.setActive(true);
	}

	void setBeObjectEnabled(boolean enabled)
	{
		beObjectEnabled = enabled;
		if (enabled)
		{
			updateBeObjectModel();
		}
		else
		{
			clientThread.invoke(() ->
			{
				if (beObject != null)
				{
					beObject.setActive(false);
					beObject = null;
				}
			});
		}
	}

	/** Rebuilds the stand-in model from the panel's current selection (no-op while disabled). */
	void updateBeObjectModel()
	{
		if (!beObjectEnabled)
		{
			return;
		}
		clientThread.invoke(() ->
		{
			if (beObject != null)
			{
				beObject.setActive(false);
			}
			ModelData md = loadDecorationModelData(panel.getSelectedObjectId(), panel.getSelectedNpcId(), panel.getSelectedScale());
			if (md == null)
			{
				beObject = null;
				return;
			}
			RuneLiteObject rlo = client.createRuneLiteObject();
			rlo.setModel(md.light());
			applyAnimation(rlo, effectiveAnimation(panel.getSelectedAnimationId(), panel.getSelectedNpcId()));
			rlo.setActive(false);
			beObject = rlo;
			beObjectMoving = false;
		});
	}

	void setMenusHidden(boolean hidden)
	{
		menusHidden = hidden;
	}

	void setGhostEnabled(boolean enabled)
	{
		ghostEnabled = enabled;
		if (enabled)
		{
			updateGhostModel(panel.getSelectedObjectId(), panel.getSelectedNpcId(), panel.getSelectedScale());
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
		// Instant restore: a fade only works on widgets that honor opacity, and the
		// chatbox/minimap layers don't, so they'd stay dimmed while others faded.
		clientThread.invoke(() -> applyToUiWidgets(enabled, 0));
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		// Interface rebuilds (tab switches, dialogs) un-hide widgets; keep them hidden
		if (hideUiEnabled)
		{
			applyToUiWidgets(true, 0);
		}
	}

	private void applyToUiWidgets(boolean hidden, int opacity)
	{
		// Resizable classic: SIDE_CONTAINER is only the tab-content layer; SIDE_MENU is the whole sidebar
		applyWidget(InterfaceID.ToplevelOsrsStretch.CHAT_CONTAINER, hidden, opacity);
		applyWidget(InterfaceID.ToplevelOsrsStretch.SIDE_MENU, hidden, opacity);
		applyWidget(InterfaceID.ToplevelOsrsStretch.SIDE_CONTAINER, hidden, opacity);
		applyWidget(InterfaceID.ToplevelOsrsStretch.SIDE_TOP, hidden, opacity);
		applyWidget(InterfaceID.ToplevelOsrsStretch.SIDE_BOTTOM, hidden, opacity);
		applyWidget(InterfaceID.ToplevelOsrsStretch.SIDE_BACKGROUND, hidden, opacity);
		applyWidget(InterfaceID.ToplevelOsrsStretch.MAP_CONTAINER, hidden, opacity);
		applyWidget(InterfaceID.ToplevelOsrsStretch.ORBS, hidden, opacity);
		// Resizable modern: static/movable layers hold the tab-stone bars
		applyWidget(InterfaceID.ToplevelPreEoc.CHAT_CONTAINER, hidden, opacity);
		applyWidget(InterfaceID.ToplevelPreEoc.SIDE_CONTAINER, hidden, opacity);
		applyWidget(InterfaceID.ToplevelPreEoc.SIDE_STATIC_LAYER, hidden, opacity);
		applyWidget(InterfaceID.ToplevelPreEoc.SIDE_MOVABLE_LAYER, hidden, opacity);
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

	void updateGhostModel(int objectId, int npcId, int scalePercent)
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
			ModelData md = loadDecorationModelData(objectId, npcId, scalePercent);
			if (md == null)
			{
				ghostObject = null;
				return;
			}
			RuneLiteObject rlo = client.createRuneLiteObject();
			rlo.setModel(md.light());
			applyAnimation(rlo, effectiveAnimation(panel.getSelectedAnimationId(), npcId));
			rlo.setActive(false);
			ghostObject = rlo;
		});
	}

	/** Re-apply the selected animation to the live cursor ghost and stand-in (called when the panel spinner changes). */
	void updateGhostAnimation(int animationId)
	{
		clientThread.invoke(() ->
		{
			int effective = effectiveAnimation(animationId, panel.getSelectedNpcId());
			if (ghostEnabled && ghostObject != null)
			{
				applyAnimation(ghostObject, effective);
			}
			if (beObject != null)
			{
				applyAnimation(beObject, effective);
			}
		});
	}

	/** Attach a looping animation to the object, or clear it. Unloadable ids are ignored. */
	private void applyAnimation(RuneLiteObject rlo, int animationId)
	{
		Animation anim = null;
		if (animationId > 0)
		{
			try
			{
				anim = client.loadAnimation(animationId);
			}
			catch (Exception e)
			{
				log.debug("Failed to load animation {}", animationId, e);
			}
		}
		rlo.setAnimationController(anim != null ? new AnimationController(client, anim) : null);
	}

	/** The animation a decoration should play: an explicit selection wins, else the npc's idle, else none. */
	private static int effectiveAnimation(int animationId, int npcId)
	{
		if (animationId > 0)
		{
			return animationId;
		}
		return npcId > 0 ? NpcNames.idleAnimation(npcId) : -1;
	}

	/**
	 * Loads the model for a decoration: an npc's merged + recolored model parts when npcId > 0,
	 * otherwise the plain model id, resized by scalePercent (-100..100, 0 = normal).
	 * Must be called on the client thread. Null when unavailable.
	 */
	ModelData loadDecorationModelData(int objectId, int npcId, int scalePercent)
	{
		ModelData md = loadUnscaledModelData(objectId, npcId);
		if (md != null && scalePercent != 0)
		{
			// Mesh.scale uses 128 = 1.0; floor keeps a -100% model tiny rather than degenerate
			int s = Math.max(6, Math.round(128 * (100 + scalePercent) / 100f));
			md = md.cloneVertices().scale(s, s, s);
		}
		return md;
	}

	private ModelData loadUnscaledModelData(int objectId, int npcId)
	{
		if (npcId <= 0)
		{
			try
			{
				return client.loadModelData(objectId);
			}
			catch (Exception e)
			{
				log.debug("Failed to load model for id {}", objectId, e);
				return null;
			}
		}
		try
		{
			NPCComposition comp = client.getNpcDefinition(npcId);
			if (comp == null || comp.getModels() == null || comp.getModels().length == 0)
			{
				return null;
			}
			List<ModelData> parts = new ArrayList<>();
			for (int modelId : comp.getModels())
			{
				ModelData part = client.loadModelData(modelId);
				if (part != null)
				{
					parts.add(part);
				}
			}
			if (parts.isEmpty())
			{
				return null;
			}
			ModelData merged = client.mergeModels(parts.toArray(new ModelData[0]));
			short[] find = comp.getColorToReplace();
			short[] replace = comp.getColorToReplaceWith();
			if (find != null && replace != null)
			{
				merged.cloneColors();
				for (int i = 0; i < Math.min(find.length, replace.length); i++)
				{
					merged.recolor(find[i], replace[i]);
				}
			}
			return merged;
		}
		catch (Exception e)
		{
			log.debug("Failed to build npc model for id {}", npcId, e);
			return null;
		}
	}

	// â”€â”€ Region loading â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

	@Subscribe
	public void onWorldViewLoaded(WorldViewLoaded event)
	{
		loadRegions(event.getWorldView());
		// Scene reloads (teleports, dungeons) kill live RuneLiteObjects; rebuild the
		// stand-in and ghost so their toggles survive. Both no-op while disabled.
		updateBeObjectModel();
		updateGhostModel(panel.getSelectedObjectId(), panel.getSelectedNpcId(), panel.getSelectedScale());
	}

	@Subscribe
	public void onWorldViewUnloaded(WorldViewUnloaded event)
	{
		WorldView wv = event.getWorldView();
		if ((editTarget != null && wv == editWorldView) || editInstancedTarget != null)
		{
			endEdit();
		}
		// Instanced undo history references this house's scene tiles â€” meaningless once it unloads.
		// Overworld actions survive: they re-apply through region config, loaded or not.
		undoStack.removeIf(UndoAction::isInstanced);
		redoStack.removeIf(UndoAction::isInstanced);
		notifyUndoState();
		for (PlacedObject obj : loadedObjects.get(wv))
		{
			RuneLiteObject rlo = activeObjects.remove(obj);
			if (rlo != null)
			{
				rlo.setActive(false);
			}
		}
		loadedObjects.removeAll(wv);
		// Roamers hold scene-local coordinates that die with the scene
		roamers.clear();
		// Clear instanced objects â€” reloaded on GameStateChanged if re-entering instance
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

	// â”€â”€ Placement â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (menusHidden || !client.isKeyPressed(KeyCode.KC_SHIFT))
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
					.setOption("Edit")
					.setTarget(target)
					.setType(MenuAction.RUNELITE)
					.onClick(e -> startEditInstanced(obj));
			}
			// While editing, the panel controls belong to the edited object â€” placing a copy
			// mid-edit is almost never what was meant, so the option is hidden until Done.
			if (atTile.size() < 3 && !isEditing())
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
					.setOption("Edit")
					.setTarget(target)
					.setType(MenuAction.RUNELITE)
					.onClick(e -> startEdit(wv, obj));
			}
			if (atTile.size() < 3 && !isEditing())
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
			stackIndex,
			panel.getSelectedAnimationId(),
			panel.getSelectedHeightOffset(),
			panel.getSelectedOffsetX(),
			panel.getSelectedOffsetY(),
			panel.getSelectedNpcId(),
			panel.getSelectedScale(),
			panel.getSelectedRoam()
		);

		loadedObjects.put(wv, obj);
		spawnObject(wv, obj, directLp);
		saveRegion(wp.getRegionID(), wv);
		recordOverworldAction(List.of(), List.of(obj));
	}

	private void removeObject(WorldView wv, PlacedObject obj)
	{
		if (obj.equals(editTarget))
		{
			endEdit();
		}
		loadedObjects.remove(wv, obj);
		RuneLiteObject rlo = activeObjects.remove(obj);
		if (rlo != null)
		{
			rlo.setActive(false);
		}
		saveRegion(obj.getRegionId(), wv);
		recordOverworldAction(List.of(obj), List.of());
	}

	// â”€â”€ Undo / redo â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

	private static final int MAX_UNDO = 50;

	/**
	 * One user action. World actions: undoing removes the {@code after} objects and restores
	 * the {@code before} ones. Selection actions (a favourite overwriting the panel) instead
	 * swap the panel between two selection snapshots.
	 */
	private static final class UndoAction
	{
		final List<PlacedObject> before;
		final List<PlacedObject> after;
		final List<InstancedObject> beforeInst;
		final List<InstancedObject> afterInst;
		final PanelSelection selectionBefore;
		final PanelSelection selectionAfter;

		UndoAction(List<PlacedObject> before, List<PlacedObject> after,
			List<InstancedObject> beforeInst, List<InstancedObject> afterInst)
		{
			this.before = before;
			this.after = after;
			this.beforeInst = beforeInst;
			this.afterInst = afterInst;
			this.selectionBefore = null;
			this.selectionAfter = null;
		}

		UndoAction(PanelSelection selectionBefore, PanelSelection selectionAfter)
		{
			this.before = null;
			this.after = null;
			this.beforeInst = null;
			this.afterInst = null;
			this.selectionBefore = selectionBefore;
			this.selectionAfter = selectionAfter;
		}

		boolean isSelection()
		{
			return selectionBefore != null;
		}

		boolean isInstanced()
		{
			return beforeInst != null;
		}
	}

	private final Deque<UndoAction> undoStack = new ConcurrentLinkedDeque<>();
	private final Deque<UndoAction> redoStack = new ConcurrentLinkedDeque<>();

	private void recordOverworldAction(List<PlacedObject> before, List<PlacedObject> after)
	{
		pushAction(new UndoAction(before, after, null, null));
	}

	private void recordInstancedAction(List<InstancedObject> before, List<InstancedObject> after)
	{
		pushAction(new UndoAction(null, null, before, after));
	}

	/** Called by the panel when a bulk load (a favourite) overwrites the current selection. */
	void recordSelectionAction(PanelSelection before, PanelSelection after)
	{
		pushAction(new UndoAction(before, after));
	}

	private void pushAction(UndoAction action)
	{
		undoStack.push(action);
		while (undoStack.size() > MAX_UNDO)
		{
			undoStack.pollLast();
		}
		redoStack.clear();
		notifyUndoState();
	}

	private void notifyUndoState()
	{
		boolean canUndo = !undoStack.isEmpty();
		boolean canRedo = !redoStack.isEmpty();
		SwingUtilities.invokeLater(() -> panel.setUndoRedoState(canUndo, canRedo));
	}

	void undo()
	{
		UndoAction action = undoStack.poll();
		if (action == null)
		{
			return;
		}
		// An in-flight edit and an undo would fight over the same object
		endEdit();
		if (action.isSelection())
		{
			SwingUtilities.invokeLater(() -> panel.applySelection(action.selectionBefore));
		}
		else if (action.isInstanced())
		{
			applyInstancedChange(action.afterInst, action.beforeInst);
		}
		else
		{
			applyOverworldChange(action.after, action.before);
		}
		redoStack.push(action);
		notifyUndoState();
	}

	void redo()
	{
		UndoAction action = redoStack.poll();
		if (action == null)
		{
			return;
		}
		endEdit();
		if (action.isSelection())
		{
			SwingUtilities.invokeLater(() -> panel.applySelection(action.selectionAfter));
		}
		else if (action.isInstanced())
		{
			applyInstancedChange(action.beforeInst, action.afterInst);
		}
		else
		{
			applyOverworldChange(action.before, action.after);
		}
		undoStack.push(action);
		notifyUndoState();
	}

	/**
	 * Removes one set of overworld objects and restores another, in config first (so the
	 * change is durable even for regions that aren't loaded any more) and then in the live
	 * scene for whatever is currently loaded.
	 */
	private void applyOverworldChange(List<PlacedObject> remove, List<PlacedObject> add)
	{
		clientThread.invoke(() ->
		{
			Set<Integer> regions = new HashSet<>();
			remove.forEach(o -> regions.add(o.getRegionId()));
			add.forEach(o -> regions.add(o.getRegionId()));
			for (int regionId : regions)
			{
				List<PlacedObject> stored = new ArrayList<>(getStoredObjects(regionId));
				for (PlacedObject obj : remove)
				{
					if (obj.getRegionId() == regionId)
					{
						stored.remove(obj);
					}
				}
				for (PlacedObject obj : add)
				{
					if (obj.getRegionId() == regionId && !stored.contains(obj))
					{
						stored.add(obj);
					}
				}
				saveObjectsToRegionConfig(regionId, stored);
			}

			WorldView wv = client.getTopLevelWorldView();
			for (PlacedObject obj : remove)
			{
				if (wv != null)
				{
					loadedObjects.remove(wv, obj);
				}
				RuneLiteObject rlo = activeObjects.remove(obj);
				if (rlo != null)
				{
					rlo.setActive(false);
				}
			}
			if (wv != null)
			{
				loadRegions(wv);
			}
		});
	}

	private void applyInstancedChange(List<InstancedObject> remove, List<InstancedObject> add)
	{
		clientThread.invoke(() ->
		{
			if (!isInPoh())
			{
				return;
			}
			for (InstancedObject obj : remove)
			{
				RuneLiteObject rlo = activeInstancedObjects.remove(obj);
				if (rlo != null)
				{
					rlo.setActive(false);
				}
			}
			for (InstancedObject obj : add)
			{
				if (!activeInstancedObjects.containsKey(obj))
				{
					activeInstancedObjects.put(obj, null);
					spawnInstancedObject(obj);
				}
			}
			saveInstancedObjects();
		});
	}

	// â”€â”€ Editing placed objects â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

	// Object being edited via the panel controls; at most one of the two targets is non-null.
	// The originals are what the object looked like when the edit began, for Cancel.
	private WorldView editWorldView;
	private PlacedObject editTarget;
	private PlacedObject editOriginal;
	private InstancedObject editInstancedTarget;
	private InstancedObject editInstancedOriginal;

	private boolean isEditing()
	{
		return editTarget != null || editInstancedTarget != null;
	}

	private void startEdit(WorldView wv, PlacedObject obj)
	{
		// Switching targets mid-edit finishes (and records) the previous edit first
		recordEditIfChanged();
		editInstancedTarget = null;
		editInstancedOriginal = null;
		editWorldView = wv;
		editTarget = obj;
		editOriginal = obj;
		loadEditIntoPanel(obj.getObjectId(), obj.getOrientation(), obj.getAnimationId(),
			obj.getHeightOffset(), obj.getOffsetX(), obj.getOffsetY(), obj.getNpcId(), obj.getScale(), obj.isRoam());
	}

	private void startEditInstanced(InstancedObject obj)
	{
		recordEditIfChanged();
		editTarget = null;
		editOriginal = null;
		editWorldView = null;
		editInstancedTarget = obj;
		editInstancedOriginal = obj;
		loadEditIntoPanel(obj.getObjectId(), obj.getOrientation(), obj.getAnimationId(),
			obj.getHeightOffset(), obj.getOffsetX(), obj.getOffsetY(), obj.getNpcId(), obj.getScale(), obj.isRoam());
	}

	private void loadEditIntoPanel(int objectId, int orientation, int animationId, int heightOffset, int offsetX, int offsetY, int npcId, int scale, boolean roam)
	{
		SwingUtilities.invokeLater(() ->
		{
			// Load values before flipping the panel into edit mode so the loads themselves
			// don't count as user edits. The boxes clear each other (object clears npc/animation,
			// npc clears object/animation, animation clears npc), so the order matters:
			// object first, then npc; animation only applies to non-npc decorations.
			panel.setObjectId(objectId);
			panel.setNpcId(npcId);
			if (npcId <= 0)
			{
				panel.setAnimationId(animationId);
			}
			panel.setOrientationControls(orientation);
			panel.setHeightOffset(heightOffset);
			panel.setOffset(offsetX, offsetY);
			panel.setScale(scale);
			panel.setRoam(roam);
			panel.enterEditMode();
		});
	}

	void endEdit()
	{
		recordEditIfChanged();
		clearEditState();
	}

	/** A whole edit session is one undoable action; per-spinner-tick entries would bury the stack. */
	private void recordEditIfChanged()
	{
		if (editTarget != null && editOriginal != null && !editTarget.sameDecoration(editOriginal))
		{
			recordOverworldAction(List.of(editOriginal), List.of(editTarget));
		}
		else if (editInstancedTarget != null && editInstancedOriginal != null
			&& !editInstancedTarget.sameDecoration(editInstancedOriginal))
		{
			recordInstancedAction(List.of(editInstancedOriginal), List.of(editInstancedTarget));
		}
	}

	private void clearEditState()
	{
		editTarget = null;
		editOriginal = null;
		editInstancedTarget = null;
		editInstancedOriginal = null;
		editWorldView = null;
		SwingUtilities.invokeLater(panel::exitEditMode);
	}

	/** Reverts the edited object to how it was when the edit began, then ends the edit. */
	void cancelEdit()
	{
		PlacedObject cur = editTarget;
		PlacedObject original = editOriginal;
		InstancedObject instCur = editInstancedTarget;
		InstancedObject instOriginal = editInstancedOriginal;
		WorldView wv = editWorldView;
		// Cancel reverts, so nothing is recorded for undo â€” clear without recording
		clearEditState();

		if (cur != null && original != null && wv != null)
		{
			// Queued after endEdit's exitEditMode, so these loads can't re-apply as edits
			SwingUtilities.invokeLater(() ->
			{
				panel.setObjectId(original.getObjectId());
				panel.setNpcId(original.getNpcId());
				panel.setAnimationId(original.getAnimationId());
				panel.setOrientationControls(original.getOrientation());
				panel.setHeightOffset(original.getHeightOffset());
				panel.setOffset(original.getOffsetX(), original.getOffsetY());
				panel.setScale(original.getScale());
				panel.setRoam(original.isRoam());
			});
			if (!cur.sameDecoration(original))
			{
				clientThread.invoke(() ->
				{
					loadedObjects.remove(wv, cur);
					loadedObjects.put(wv, original);
					RuneLiteObject rlo = activeObjects.remove(cur);
					if (rlo != null)
					{
						rlo.setActive(false);
					}
					spawnObject(wv, original);
					saveRegion(original.getRegionId(), wv);
				});
			}
		}
		else if (instCur != null && instOriginal != null && !instCur.sameDecoration(instOriginal))
		{
			clientThread.invoke(() ->
			{
				RuneLiteObject rlo = activeInstancedObjects.remove(instCur);
				if (rlo != null)
				{
					rlo.setActive(false);
				}
				spawnInstancedObject(instOriginal);
				saveInstancedObjects();
			});
		}
	}

	/** Applies the panel's current values to the object being edited â€” live in the scene and persisted. */
	void applyEditFromPanel()
	{
		int objectId = panel.getSelectedObjectId();
		int orientation = panel.getSelectedOrientation();
		int animationId = panel.getSelectedAnimationId();
		int heightOffset = panel.getSelectedHeightOffset();
		int offsetX = panel.getSelectedOffsetX();
		int offsetY = panel.getSelectedOffsetY();
		int npcId = panel.getSelectedNpcId();
		int scale = panel.getSelectedScale();
		boolean roam = panel.getSelectedRoam();
		clientThread.invoke(() ->
		{
			if (editTarget != null && editWorldView != null)
			{
				PlacedObject old = editTarget;
				PlacedObject updated = new PlacedObject(old.getRegionId(), old.getRegionX(), old.getRegionY(),
					old.getPlane(), objectId, orientation, old.getStackIndex(), animationId, heightOffset, offsetX, offsetY, npcId, scale, roam);
				if (old.sameDecoration(updated))
				{
					return;
				}
				editTarget = updated;
				loadedObjects.remove(editWorldView, old);
				loadedObjects.put(editWorldView, updated);
				RuneLiteObject rlo = activeObjects.remove(old);
				if (rlo != null)
				{
					rlo.setActive(false);
				}
				spawnObject(editWorldView, updated);
				saveRegion(updated.getRegionId(), editWorldView);
			}
			else if (editInstancedTarget != null)
			{
				InstancedObject old = editInstancedTarget;
				InstancedObject updated = new InstancedObject(old.getSceneX(), old.getSceneY(), old.getPlane(),
					objectId, orientation, old.getStackIndex(), animationId, heightOffset, offsetX, offsetY, npcId, scale, roam);
				if (old.sameDecoration(updated))
				{
					return;
				}
				editInstancedTarget = updated;
				RuneLiteObject rlo = activeInstancedObjects.remove(old);
				if (rlo != null)
				{
					rlo.setActive(false);
				}
				spawnInstancedObject(updated);
				saveInstancedObjects();
			}
		});
	}

	// â”€â”€ RuneLiteObject management â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

	private void spawnObject(WorldView wv, PlacedObject obj)
	{
		spawnObject(wv, obj, null);
	}

	private void spawnObject(WorldView wv, PlacedObject obj, LocalPoint fallbackLp)
	{
		clientThread.invoke(() ->
		{
			ModelData md = loadDecorationModelData(obj.getObjectId(), obj.getNpcId(), obj.getScale());
			if (md == null)
			{
				log.debug("No model data for object id {} / npc id {}", obj.getObjectId(), obj.getNpcId());
				return;
			}

			LocalPoint base = LocalPoint.fromWorld(wv, obj.toWorldPoint());
			if (base == null)
			{
				base = fallbackLp;
			}
			if (base == null)
			{
				return;
			}
			LocalPoint lp = new LocalPoint(base.getX() + obj.getOffsetX(), base.getY() + obj.getOffsetY(), base.getWorldView());

			Model model = md.light();
			RuneLiteObject rlo = client.createRuneLiteObject();
			rlo.setModel(model);
			rlo.setOrientation(obj.getOrientation());
			rlo.setLocation(lp, obj.getPlane());
			rlo.setZ(rlo.getZ() - obj.getHeightOffset());
			applyAnimation(rlo, effectiveAnimation(obj.getAnimationId(), obj.getNpcId()));
			rlo.setActive(true);
			activeObjects.put(obj, rlo);
			if (obj.isRoam() && obj.getNpcId() > 0)
			{
				registerRoamer(rlo, lp, obj.getPlane(), obj.getOrientation(), obj.getHeightOffset(), obj.getNpcId(), obj.getAnimationId());
			}
		});
	}

	private void despawnAll()
	{
		clientThread.invoke(() ->
		{
			roamers.clear();
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

	// â”€â”€ Persistence â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

	private void saveRegion(int regionId, WorldView wv)
	{
		List<PlacedObject> regionObjects = loadedObjects.get(wv).stream()
			.filter(o -> o.getRegionId() == regionId)
			.collect(Collectors.toList());
		saveObjectsToRegionConfig(regionId, regionObjects);
	}

	private void saveObjectsToRegionConfig(int regionId, List<PlacedObject> objects)
	{
		if (objects.isEmpty())
		{
			configManager.unsetConfiguration(CONFIG_GROUP, REGION_PREFIX + regionId);
		}
		else
		{
			configManager.setConfiguration(CONFIG_GROUP, REGION_PREFIX + regionId, gson.toJson(objects));
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

	// â”€â”€ Sharing â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

	private static final int SHARE_RADIUS = 100;

	/** Copies every placed object within SHARE_RADIUS tiles of the player to the clipboard as a Base64 code. */
	void exportNearby()
	{
		clientThread.invoke(() ->
		{
			Player player = client.getLocalPlayer();
			if (player == null)
			{
				return;
			}
			WorldPoint center = player.getWorldLocation();

			// Sample a grid across the bounding box (region size is 64 tiles) plus its corners,
			// so every region the radius touches is covered even when the radius isn't a multiple of 64.
			Set<Integer> regionIds = new HashSet<>();
			for (int dx = -SHARE_RADIUS; dx <= SHARE_RADIUS; dx += 64)
			{
				for (int dy = -SHARE_RADIUS; dy <= SHARE_RADIUS; dy += 64)
				{
					regionIds.add(center.dx(dx).dy(dy).getRegionID());
				}
			}
			regionIds.add(center.dx(SHARE_RADIUS).dy(SHARE_RADIUS).getRegionID());
			regionIds.add(center.dx(-SHARE_RADIUS).dy(SHARE_RADIUS).getRegionID());
			regionIds.add(center.dx(SHARE_RADIUS).dy(-SHARE_RADIUS).getRegionID());
			regionIds.add(center.dx(-SHARE_RADIUS).dy(-SHARE_RADIUS).getRegionID());

			List<PlacedObject> nearby = new ArrayList<>();
			for (int regionId : regionIds)
			{
				for (PlacedObject obj : getStoredObjects(regionId))
				{
					if (center.distanceTo(obj.toWorldPoint()) <= SHARE_RADIUS)
					{
						nearby.add(obj);
					}
				}
			}

			SwingUtilities.invokeLater(() ->
			{
				if (nearby.isEmpty())
				{
					JOptionPane.showMessageDialog(panel, "No placed objects within " + SHARE_RADIUS + " tiles to export.");
					return;
				}
				String json = gson.toJson(nearby);
				String code = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
				Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(code), null);
				JOptionPane.showMessageDialog(panel, "Exported " + nearby.size()
					+ (nearby.size() == 1 ? " object" : " objects") + " to your clipboard.");
			});
		});
	}

	/** Reads a code from the clipboard (see exportNearby) and merges its objects into the current world. */
	void importFromClipboard()
	{
		String clipboardText;
		try
		{
			clipboardText = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
		}
		catch (IOException | UnsupportedFlavorException e)
		{
			JOptionPane.showMessageDialog(panel, "Unable to read your clipboard.");
			return;
		}

		List<PlacedObject> imports;
		try
		{
			String json = new String(Base64.getDecoder().decode(clipboardText.trim()), StandardCharsets.UTF_8);
			imports = gson.fromJson(json, new TypeToken<List<PlacedObject>>() {}.getType());
		}
		catch (IllegalArgumentException | JsonSyntaxException e)
		{
			JOptionPane.showMessageDialog(panel, "That doesn't look like a Map Decorator code.");
			return;
		}

		if (imports == null || imports.isEmpty())
		{
			JOptionPane.showMessageDialog(panel, "Nothing to import.");
			return;
		}

		int result = JOptionPane.showConfirmDialog(
			panel,
			"Import " + imports.size() + (imports.size() == 1 ? " object?" : " objects?"),
			"Import",
			JOptionPane.YES_NO_OPTION
		);
		if (result != JOptionPane.YES_OPTION)
		{
			return;
		}

		Map<Integer, List<PlacedObject>> byRegion = imports.stream()
			.collect(Collectors.groupingBy(PlacedObject::getRegionId));

		List<PlacedObject> added = new ArrayList<>();
		int imported = 0;
		int skipped = 0;
		for (Map.Entry<Integer, List<PlacedObject>> entry : byRegion.entrySet())
		{
			int regionId = entry.getKey();
			List<PlacedObject> existing = new ArrayList<>(getStoredObjects(regionId));
			for (PlacedObject incoming : entry.getValue())
			{
				List<PlacedObject> atTile = existing.stream()
					.filter(o -> o.getRegionX() == incoming.getRegionX()
						&& o.getRegionY() == incoming.getRegionY()
						&& o.getPlane() == incoming.getPlane())
					.collect(Collectors.toList());

				if (atTile.stream().anyMatch(o -> o.sameDecoration(incoming)))
				{
					skipped++;
					continue;
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
				if (stackIndex >= 3)
				{
					skipped++;
					continue;
				}

				PlacedObject placed = incoming.withStackIndex(stackIndex);
				existing.add(placed);
				added.add(placed);
				imported++;
			}
			saveObjectsToRegionConfig(regionId, existing);
		}
		if (!added.isEmpty())
		{
			// One bulk entry: undoing an import removes everything it brought in
			recordOverworldAction(List.of(), added);
		}

		clientThread.invoke(() ->
		{
			WorldView wv = client.getTopLevelWorldView();
			if (wv != null)
			{
				loadRegions(wv);
			}
		});

		JOptionPane.showMessageDialog(panel, "Imported " + imported
			+ (imported == 1 ? " object" : " objects")
			+ (skipped > 0 ? " (" + skipped + " skipped, duplicates or full tiles)." : "."));
	}

	// â”€â”€ Instanced region (POH) support â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
			panel.getSelectedObjectId(), panel.getSelectedOrientation(), stackIndex,
			panel.getSelectedAnimationId(),
			panel.getSelectedHeightOffset(), panel.getSelectedOffsetX(), panel.getSelectedOffsetY(),
			panel.getSelectedNpcId(), panel.getSelectedScale(), panel.getSelectedRoam());

		activeInstancedObjects.put(obj, null);
		spawnInstancedObject(obj);
		saveInstancedObjects();
		recordInstancedAction(List.of(), List.of(obj));
	}

	private void removeInstancedObject(InstancedObject obj)
	{
		if (obj.equals(editInstancedTarget))
		{
			endEdit();
		}
		RuneLiteObject rlo = activeInstancedObjects.remove(obj);
		if (rlo != null)
		{
			rlo.setActive(false);
		}
		saveInstancedObjects();
		recordInstancedAction(List.of(obj), List.of());
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
			ModelData md = loadDecorationModelData(obj.getObjectId(), obj.getNpcId(), obj.getScale());
			if (md == null)
			{
				return;
			}
			LocalPoint base = LocalPoint.fromScene(obj.getSceneX(), obj.getSceneY(), wv);
			LocalPoint lp = new LocalPoint(base.getX() + obj.getOffsetX(), base.getY() + obj.getOffsetY(), base.getWorldView());
			Model model = md.light();
			RuneLiteObject rlo = client.createRuneLiteObject();
			rlo.setModel(model);
			rlo.setOrientation(obj.getOrientation());
			rlo.setLocation(lp, obj.getPlane());
			rlo.setZ(rlo.getZ() - obj.getHeightOffset());
			applyAnimation(rlo, effectiveAnimation(obj.getAnimationId(), obj.getNpcId()));
			rlo.setActive(true);
			activeInstancedObjects.put(obj, rlo);
			if (obj.isRoam() && obj.getNpcId() > 0)
			{
				registerRoamer(rlo, lp, obj.getPlane(), obj.getOrientation(), obj.getHeightOffset(), obj.getNpcId(), obj.getAnimationId());
			}
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
				// LOGGED_IN fires on every scene rebuild in the house â€” don't respawn existing objects
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
			if (isEditing())
			{
				endEdit();
			}
			WorldView wv = client.getTopLevelWorldView();
			if (wv == null)
			{
				return;
			}
			if (isInPoh())
			{
				if (!activeInstancedObjects.isEmpty())
				{
					recordInstancedAction(new ArrayList<>(activeInstancedObjects.keySet()), List.of());
				}
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
				if (!toRemove.isEmpty())
				{
					// One bulk entry, so a misclicked Clear Area is a single undo away
					recordOverworldAction(toRemove, List.of());
				}
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

	// â”€â”€ Preview background â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

	String getPreviewBackground()
	{
		return configManager.getConfiguration(CONFIG_GROUP, "preview_bg");
	}

	void setPreviewBackground(String name)
	{
		configManager.setConfiguration(CONFIG_GROUP, "preview_bg", name);
	}

	// â”€â”€ Favourites â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

	void setFavourite(int slot, Favourite favourite)
	{
		configManager.setConfiguration(CONFIG_GROUP, "fav_" + slot, gson.toJson(favourite));
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

	// â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

	/** Average colors of game textures, keyed by texture id. Written only on the client thread. */
	private final Map<Integer, Integer> textureAvgCache = new HashMap<>();

	/**
	 * Average RGB of a game texture, for flat-shading textured faces in the preview.
	 * Must be called on the client thread. Returns null when the texture isn't loaded yet
	 * (not cached, so a later call can succeed).
	 */
	Integer textureAverageRgb(int textureId)
	{
		Integer cached = textureAvgCache.get(textureId);
		if (cached != null)
		{
			return cached;
		}
		int[] pixels;
		try
		{
			pixels = client.getTextureProvider().load(textureId);
		}
		catch (Exception e)
		{
			return null;
		}
		if (pixels == null)
		{
			return null;
		}
		long r = 0, g = 0, b = 0;
		int n = 0;
		for (int p : pixels)
		{
			if (p == 0)
			{
				continue; // transparent texel
			}
			r += (p >> 16) & 0xFF;
			g += (p >> 8) & 0xFF;
			b += p & 0xFF;
			n++;
		}
		if (n == 0)
		{
			return null;
		}
		int avg = (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
		textureAvgCache.put(textureId, avg);
		return avg;
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
