package com.tommyknight.mapdecorator;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Type;
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
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
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
import net.runelite.api.JagexColor;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
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
import net.runelite.api.events.GameTick;
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

	// Gson list types, resolved once â€” each `new TypeToken<...>(){}` is an anonymous class
	private static final Type PLACED_OBJECT_LIST = new TypeToken<List<PlacedObject>>() {}.getType();
	private static final Type INSTANCED_OBJECT_LIST = new TypeToken<List<InstancedObject>>() {}.getType();
	private static final Type SAVED_LAYOUT_LIST = new TypeToken<List<SavedLayout>>() {}.getType();

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

	@Subscribe
	public void onGameTick(GameTick event)
	{
		tickInstancedVisibility();
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

	/**
	 * Deactivates a spawned decoration and drops any roaming state bound to it. Every removal
	 * path goes through here so a Roamer can't outlive the object it animates.
	 * <p>
	 * This used to be a per-tick sweep over the active-object maps instead, which was both a
	 * linear scan fifty times a second and â€” because it only ever consulted the instanced map â€”
	 * silently culled every overworld roamer on the tick after it spawned, so Roam did nothing
	 * outside a house.
	 */
	private void despawn(RuneLiteObject rlo)
	{
		if (rlo == null)
		{
			return;
		}
		rlo.setActive(false);
		roamers.removeIf(r -> r.rlo == rlo);
	}

	private void tickRoamers()
	{
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
		// Once the right-click menu is open, the mouse is over the menu itself, not the game
		// world â€” recomputing the cursor offset against menu coordinates would make the ghost
		// jitter around the tile instead of holding still while the menu is navigated.
		if (client.isMenuOpen())
		{
			return;
		}
		ghostObject.setOrientation(panel.getSelectedOrientation());
		LocalPoint base = tile.getLocalLocation();
		int offsetX = panel.getSelectedOffsetX();
		int offsetY = panel.getSelectedOffsetY();
		if (!getSnapEnabled())
		{
			Point mousePos = client.getMouseCanvasPosition();
			if (mousePos != null)
			{
				int[] cursorOffset = computeCursorOffset(base, mousePos);
				if (cursorOffset != null)
				{
					offsetX = cursorOffset[0];
					offsetY = cursorOffset[1];
				}
			}
		}
		LocalPoint lp = new LocalPoint(base.getX() + offsetX, base.getY() + offsetY, base.getWorldView());
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
		int objectId = panel.getSelectedObjectId();
		int npcId = panel.getSelectedNpcId();
		int scale = panel.getSelectedScale();
		boolean mirror = panel.getSelectedMirror();
		int tintArgb = panel.getSelectedTintArgb();
		withDecorationModel(objectId, npcId, scale, mirror, tintArgb, null, md -> applyBeObjectModel(md, npcId));
	}

	/** Applies an already-loaded model to the Be Object stand-in (no-op while disabled). Must be called on the client thread. */
	void applyBeObjectModel(ModelData md, int npcId)
	{
		if (!beObjectEnabled)
		{
			return;
		}
		if (beObject != null)
		{
			beObject.setActive(false);
		}
		if (md == null)
		{
			beObject = null;
			return;
		}
		RuneLiteObject rlo = client.createRuneLiteObject();
		rlo.setModel(md.light());
		applyAnimation(rlo, effectiveAnimation(panel.getSelectedAnimationId(), npcId));
		rlo.setActive(false);
		beObject = rlo;
		beObjectMoving = false;
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
			updateGhostModel(panel.getSelectedObjectId(), panel.getSelectedNpcId(), panel.getSelectedScale(), panel.getSelectedMirror(), panel.getSelectedTintArgb());
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

	void updateGhostModel(int objectId, int npcId, int scalePercent, boolean mirror, int tintArgb)
	{
		if (!ghostEnabled)
		{
			return;
		}
		withDecorationModel(objectId, npcId, scalePercent, mirror, tintArgb, null, md -> applyGhostModel(md, npcId));
	}

	/** Applies an already-loaded model to the cursor ghost (no-op while disabled). Must be called on the client thread. */
	void applyGhostModel(ModelData md, int npcId)
	{
		if (!ghostEnabled)
		{
			return;
		}
		if (ghostObject != null)
		{
			ghostObject.setActive(false);
		}
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
	 * otherwise the plain model id, resized by scalePercent (-100..100, 0 = normal) and optionally
	 * mirrored. Must be called on the client thread. Null when unavailable.
	 */
	/** Ticks to keep retrying a model load before treating the id as one with no model at all. */
	private static final int MODEL_LOAD_RETRY_TICKS = 20;

	/**
	 * Loads a decoration model on the client thread and hands it to {@code action}, retrying on
	 * later ticks for as long as the game hasn't finished pulling it out of its cache.
	 * <p>
	 * {@link #loadDecorationModelData} returns null both for an id that genuinely has no model
	 * and for one the game simply hasn't loaded yet â€” and the second case is the common one the
	 * first time an id is used, because the request itself is what triggers the load. Giving up
	 * after a single attempt is what leaves a blank preview or a decoration that never spawns,
	 * so keep asking for a few ticks before concluding the id has nothing behind it.
	 *
	 * @param cancelled polled before each attempt; return true to abandon a request that has
	 *                  since been superseded. May be null.
	 * @param action    receives the model, or null once the retry budget runs out
	 */
	void withDecorationModel(int objectId, int npcId, int scalePercent, boolean mirror, int tintArgb,
		BooleanSupplier cancelled, Consumer<ModelData> action)
	{
		// Only ever read/written on the client thread, so a plain holder is enough
		int[] attempts = {0};
		clientThread.invoke(() ->
		{
			if (cancelled != null && cancelled.getAsBoolean())
			{
				return true;
			}
			ModelData md = loadDecorationModelData(objectId, npcId, scalePercent, mirror, tintArgb);
			if (md == null && ++attempts[0] < MODEL_LOAD_RETRY_TICKS)
			{
				// Returning false asks ClientThread to run this again on a later tick
				return false;
			}
			action.accept(md);
			return true;
		});
	}

	ModelData loadDecorationModelData(int objectId, int npcId, int scalePercent, boolean mirror, int tintArgb)
	{
		ModelData md = loadUnscaledModelData(objectId, npcId);
		if (md == null)
		{
			return null;
		}
		if (mirror)
		{
			// loadModelData shares its face-index arrays with the game's cache; a mirror has to
			// rewrite those arrays (below), so merge first to get private copies. Merging remaps
			// every face into a fresh combined array, which is exactly the copy we need.
			md = client.mergeModels(md);
			if (md == null)
			{
				return null;
			}
		}
		if (scalePercent != 0 || mirror)
		{
			// Mesh.scale uses 128 = 1.0; floor keeps a -100% model tiny rather than degenerate.
			// A negative X factor mirrors the model across its centre.
			int s = Math.max(6, Math.round(128 * (100 + scalePercent) / 100f));
			md = md.cloneVertices().scale(mirror ? -s : s, s, s);
		}
		if (mirror)
		{
			// The negative scale reversed every triangle's winding, so the game culls the faces
			// that should show and draws the ones that shouldn't (inside out). Swapping two of
			// each face's three vertices restores the original winding.
			int[] b = md.getFaceIndices2();
			int[] c = md.getFaceIndices3();
			for (int i = 0; i < md.getFaceCount(); i++)
			{
				int t = b[i];
				b[i] = c[i];
				c[i] = t;
			}
		}
		int tintAlpha = (tintArgb >>> 24) & 0xFF;
		if (tintAlpha > 0)
		{
			// cloneColors makes the face-colour array safe to rewrite without touching the cache
			short[] colors = md.cloneColors().getFaceColors();
			short[] textures = md.getFaceTextures();
			float strength = tintAlpha / 255f;
			for (int i = 0; i < colors.length; i++)
			{
				// Textured faces draw from an image, not an HSL value, so a tint can't touch them
				if (textures != null && textures[i] != -1)
				{
					continue;
				}
				colors[i] = tintFaceColor(colors[i], tintArgb, strength);
			}
		}
		return md;
	}

	/**
	 * Blends a face's colour toward the tint by {@code strength} (0..1), keeping the face's own
	 * luminance so shading survives even a full-strength tint. Blends through RGB so hue and
	 * saturation interpolate naturally without wrapping around the colour wheel.
	 */
	private static short tintFaceColor(short faceHsl, int tintRgb, float strength)
	{
		int lum = JagexColor.unpackLuminance(faceHsl);
		Color face = Color.getHSBColor(
			JagexColor.unpackHue(faceHsl) / (float) JagexColor.HUE_MAX,
			JagexColor.unpackSaturation(faceHsl) / (float) JagexColor.SATURATION_MAX,
			lum / (float) JagexColor.LUMINANCE_MAX);
		int r = Math.round(face.getRed() * (1 - strength) + ((tintRgb >> 16) & 0xFF) * strength);
		int g = Math.round(face.getGreen() * (1 - strength) + ((tintRgb >> 8) & 0xFF) * strength);
		int b = Math.round(face.getBlue() * (1 - strength) + (tintRgb & 0xFF) * strength);
		short blended = JagexColor.rgbToHSL((r << 16) | (g << 8) | b, 1.0);
		return JagexColor.packHSL(JagexColor.unpackHue(blended), JagexColor.unpackSaturation(blended), lum);
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
		updateGhostModel(panel.getSelectedObjectId(), panel.getSelectedNpcId(), panel.getSelectedScale(), panel.getSelectedMirror(), panel.getSelectedTintArgb());
	}

	@Subscribe
	public void onWorldViewUnloaded(WorldViewUnloaded event)
	{
		WorldView wv = event.getWorldView();
		if ((editTarget != null && wv == editWorldView)
			|| (editInstancedTarget != null && wv == editInstancedWorldView))
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
			despawn(activeObjects.remove(obj));
		}
		loadedObjects.removeAll(wv);
		// Roamers hold scene-local coordinates that die with the scene
		roamers.clear();
		// Clear instanced objects â€” reloaded on GameStateChanged if re-entering instance
		for (RuneLiteObject rlo : activeInstancedObjects.values())
		{
			despawn(rlo);
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
		// Hashed rather than scanning the worldview's list per candidate â€” PlacedObject's equality
		// is its position and stack index, which is exactly the "already loaded?" question here.
		// The set tracks additions too, so a region listed twice can't spawn a duplicate.
		Set<PlacedObject> current = new HashSet<>(loadedObjects.get(wv));
		for (int regionId : regions)
		{
			for (PlacedObject obj : getStoredObjects(regionId))
			{
				if (current.add(obj))
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
		// Captured now, before the context menu appears and the cursor moves onto it â€”
		// this is the last point at which the mouse position still reflects where the
		// player actually right-clicked in the game world.
		Point clickPos = client.getMouseCanvasPosition();

		if (wv.isInstance())
		{
			if (!isInPoh())
			{
				return;
			}
			InstancedIdentity id = resolveInstancedIdentity(tile);
			List<InstancedObject> atTile = findAllAtInstancedTile(id.stable.getRegionID(), id.stable.getRegionX(), id.stable.getRegionY(), id.stable.getPlane(), id.occurrence);

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
					.onClick(e -> startEditInstanced(wv, obj));
			}
			// While editing, the panel controls belong to the edited object â€” placing a copy
			// mid-edit is almost never what was meant, so the option is hidden until Done.
			if (atTile.size() < 3 && !isEditing())
			{
				client.getMenu().createMenuEntry(menuIdx)
					.setOption("Place")
					.setTarget("Object")
					.setType(MenuAction.RUNELITE)
					.onClick(e -> placeInstancedObject(tile, clickPos));
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
					.onClick(e -> placeObject(wv, wp, tile.getLocalLocation(), clickPos));
			}
		}
	}

	private void placeObject(WorldView wv, WorldPoint wp, LocalPoint directLp, Point clickPos)
	{
		// Snapshot the panel up front: the model check below can defer this by a tick or two
		// while a cold model finishes loading, and the placed object should reflect what was
		// selected at click time rather than whatever the panel drifted to since.
		int objectId = panel.getSelectedObjectId();
		int npcId = panel.getSelectedNpcId();
		int scale = panel.getSelectedScale();
		int animationId = panel.getSelectedAnimationId();
		int heightOffset = panel.getSelectedHeightOffset();
		boolean roam = panel.getSelectedRoam();
		int tintArgb = panel.getSelectedTintArgb();

		int rawOffsetX = panel.getSelectedOffsetX();
		int rawOffsetY = panel.getSelectedOffsetY();
		int panelOrientation = panel.getSelectedOrientation();
		boolean panelMirror = panel.getSelectedMirror();

		// The model has to resolve before anything is persisted, or a bogus typed id would leave
		// an invisible decoration still occupying one of the tile's three slots. A cold-cache
		// miss isn't a bogus id though, so this retries rather than giving up on the first null
		// (see withDecorationModel); on a warm cache it runs synchronously, as it always did.
		withDecorationModel(objectId, npcId, scale, panelMirror, tintArgb, null, md ->
		{
			if (md == null)
			{
				log.debug("No model for object id {} / npc id {}, not placing", objectId, npcId);
				return;
			}
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

			int offsetX = rawOffsetX;
			int offsetY = rawOffsetY;
			if (!getSnapEnabled() && clickPos != null)
			{
				int[] cursorOffset = computeCursorOffset(directLp, clickPos);
				if (cursorOffset != null)
				{
					offsetX = cursorOffset[0];
					offsetY = cursorOffset[1];
				}
			}

			int orientation = panelOrientation;
			boolean mirror = panelMirror;
			if (getRandomEnabled())
			{
				orientation = roamRandom.nextInt(2048);
				mirror = roamRandom.nextBoolean();
			}

			PlacedObject obj = new PlacedObject(
				wp.getRegionID(),
				wp.getRegionX(),
				wp.getRegionY(),
				wp.getPlane(),
				objectId,
				orientation,
				stackIndex,
				animationId,
				heightOffset,
				offsetX,
				offsetY,
				npcId,
				scale,
				roam,
				mirror,
				tintArgb
			);

			loadedObjects.put(wv, obj);
			spawnObject(wv, obj, directLp);
			saveRegion(wp.getRegionID(), wv);
			recordOverworldAction(List.of(), List.of(obj));
		});
	}

	/**
	 * Resolves where within the tile the player actually clicked, as an offsetX/offsetY pair in
	 * the same units as the panel's offset spinners (range -64..64, 128 = one full tile). Treats
	 * the tile's projected screen quad as a parallelogram (using its origin corner and two edge
	 * vectors) rather than solving a true perspective-correct inverse â€” at OSRS's typical camera
	 * pitch/zoom the difference is negligible for a tile-sized area, and this avoids the
	 * degenerate-quad/root-selection edge cases a full bilinear inverse would need. Returns null
	 * if the tile isn't currently projectable (e.g. off-screen), so callers fall back to the
	 * default tile-center offset.
	 */
	private int[] computeCursorOffset(LocalPoint tileLp, Point clickPos)
	{
		Polygon quad = Perspective.getCanvasTilePoly(client, tileLp);
		if (quad == null || quad.npoints < 4)
		{
			return null;
		}

		double originX = quad.xpoints[0];
		double originY = quad.ypoints[0];
		double eUx = quad.xpoints[1] - originX;
		double eUy = quad.ypoints[1] - originY;
		double eVx = quad.xpoints[3] - originX;
		double eVy = quad.ypoints[3] - originY;

		double det = eUx * eVy - eUy * eVx;
		if (Math.abs(det) < 1e-6)
		{
			return null;
		}

		double hx = clickPos.getX() - originX;
		double hy = clickPos.getY() - originY;
		double u = (hx * eVy - hy * eVx) / det;
		double v = (eUx * hy - eUy * hx) / det;

		u = Math.max(0, Math.min(1, u));
		v = Math.max(0, Math.min(1, v));

		int offsetX = (int) Math.round((u - 0.5) * 128);
		int offsetY = (int) Math.round((v - 0.5) * 128);
		return new int[]{offsetX, offsetY};
	}

	private void removeObject(WorldView wv, PlacedObject obj)
	{
		if (obj.equals(editTarget))
		{
			endEdit();
		}
		loadedObjects.remove(wv, obj);
		despawn(activeObjects.remove(obj));
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
		// Before polling: an in-flight edit must be recorded (or discarded as unchanged) first,
		// so a click here always undoes your last actual action, not something older that was
		// on top of the stack before your unsaved edit even started.
		endEdit();
		UndoAction action = undoStack.poll();
		if (action == null)
		{
			return;
		}
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
		// See undo(): must happen before polling, for the same reason.
		endEdit();
		UndoAction action = redoStack.poll();
		if (action == null)
		{
			return;
		}
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
				despawn(activeObjects.remove(obj));
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
				despawn(activeInstancedObjects.remove(obj));
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
	private WorldView editInstancedWorldView;
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
			obj.getHeightOffset(), obj.getOffsetX(), obj.getOffsetY(), obj.getNpcId(), obj.getScale(), obj.isRoam(),
			obj.isMirror(), obj.getTintArgb());
	}

	private void startEditInstanced(WorldView wv, InstancedObject obj)
	{
		recordEditIfChanged();
		editTarget = null;
		editOriginal = null;
		editWorldView = null;
		editInstancedWorldView = wv;
		editInstancedTarget = obj;
		editInstancedOriginal = obj;
		loadEditIntoPanel(obj.getObjectId(), obj.getOrientation(), obj.getAnimationId(),
			obj.getHeightOffset(), obj.getOffsetX(), obj.getOffsetY(), obj.getNpcId(), obj.getScale(), obj.isRoam(),
			obj.isMirror(), obj.getTintArgb());
	}

	private void loadEditIntoPanel(int objectId, int orientation, int animationId, int heightOffset, int offsetX, int offsetY,
		int npcId, int scale, boolean roam, boolean mirror, int tintArgb)
	{
		SwingUtilities.invokeLater(() ->
		{
			// Mirror/tint first: setObjectId's own change listener rebuilds the ghost/preview
			// using whatever mirror/tint is already in the panel's fields at that point.
			panel.setMirror(mirror);
			panel.setTintArgb(tintArgb);
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
		editInstancedWorldView = null;
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
				panel.setMirror(original.isMirror());
				panel.setTintArgb(original.getTintArgb());
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
					despawn(activeObjects.remove(cur));
					spawnObject(wv, original);
					saveRegion(original.getRegionId(), wv);
				});
			}
		}
		else if (instCur != null && instOriginal != null && !instCur.sameDecoration(instOriginal))
		{
			clientThread.invoke(() ->
			{
				despawn(activeInstancedObjects.remove(instCur));
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
		boolean mirror = panel.getSelectedMirror();
		int tintArgb = panel.getSelectedTintArgb();
		clientThread.invoke(() ->
		{
			if (editTarget != null && editWorldView != null)
			{
				PlacedObject old = editTarget;
				PlacedObject updated = new PlacedObject(old.getRegionId(), old.getRegionX(), old.getRegionY(),
					old.getPlane(), objectId, orientation, old.getStackIndex(), animationId, heightOffset, offsetX, offsetY, npcId, scale, roam, mirror, tintArgb);
				if (old.sameDecoration(updated))
				{
					return;
				}
				editTarget = updated;
				loadedObjects.remove(editWorldView, old);
				loadedObjects.put(editWorldView, updated);
				despawn(activeObjects.remove(old));
				spawnObject(editWorldView, updated);
				saveRegion(updated.getRegionId(), editWorldView);
			}
			else if (editInstancedTarget != null)
			{
				InstancedObject old = editInstancedTarget;
				InstancedObject updated = new InstancedObject(old.getRegionId(), old.getRegionX(), old.getRegionY(), old.getPlane(), old.getOccurrence(),
					objectId, orientation, old.getStackIndex(), animationId, heightOffset, offsetX, offsetY, npcId, scale, roam, mirror, tintArgb);
				if (old.sameDecoration(updated))
				{
					return;
				}
				editInstancedTarget = updated;
				despawn(activeInstancedObjects.remove(old));
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
		withDecorationModel(obj.getObjectId(), obj.getNpcId(), obj.getScale(), obj.isMirror(), obj.getTintArgb(), null, md ->
		{
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
				despawn(rlo);
			}
			activeObjects.clear();
			for (RuneLiteObject rlo : activeInstancedObjects.values())
			{
				despawn(rlo);
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
			List<PlacedObject> list = gson.fromJson(json, PLACED_OBJECT_LIST);
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

	/**
	 * Copies decorations to the clipboard as a Base64 code: every placed object within
	 * SHARE_RADIUS tiles of the player normally, or the player's entire current house layout
	 * while standing in a POH (a house has no useful "radius" â€” the whole thing is already
	 * what's loaded).
	 */
	void exportNearby()
	{
		clientThread.invoke(() ->
		{
			if (isInPoh())
			{
				exportInstanced();
				return;
			}
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
				panel.promptSaveLayout(code, "My layout");
				JOptionPane.showMessageDialog(panel, "Exported " + nearby.size()
					+ (nearby.size() == 1 ? " object" : " objects") + " to your clipboard.");
			});
		});
	}

	/** Copies every decoration in the current house to the clipboard. Must run on the client thread. */
	private void exportInstanced()
	{
		List<InstancedObject> all = new ArrayList<>(activeInstancedObjects.keySet());
		SwingUtilities.invokeLater(() ->
		{
			if (all.isEmpty())
			{
				JOptionPane.showMessageDialog(panel, "No placed objects in this house to export.");
				return;
			}
			String json = gson.toJson(all);
			String code = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(code), null);
			panel.promptSaveLayout(code, "My layout");
			JOptionPane.showMessageDialog(panel, "Exported " + all.size()
				+ (all.size() == 1 ? " object" : " objects") + " to your clipboard.");
		});
	}

	/**
	 * Reads a code from the clipboard (see exportNearby/exportInstanced) and merges its objects
	 * into the current world or house. A house code only ever lands in whichever house the
	 * importer is currently standing in, and only into rooms whose furniture actually matches
	 * the exporter's â€” a friend with a differently laid-out house will see nothing (or only a
	 * partial match) until they build matching rooms, the same graceful no-op used everywhere
	 * else in this plugin for "not part of the current layout yet."
	 */
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
		importCode(clipboardText.trim());
	}

	/**
	 * Decodes and applies an export/import code (see exportNearby/exportInstanced), whether it
	 * came from the clipboard just now or from a previously saved layout.
	 */
	void importCode(String code)
	{
		JsonArray array;
		try
		{
			String json = new String(Base64.getDecoder().decode(code), StandardCharsets.UTF_8);
			array = gson.fromJson(json, JsonArray.class);
		}
		catch (IllegalArgumentException | JsonSyntaxException e)
		{
			JOptionPane.showMessageDialog(panel, "That doesn't look like a Map Decorator code.");
			return;
		}

		if (array == null || array.size() == 0)
		{
			JOptionPane.showMessageDialog(panel, "Nothing to import.");
			return;
		}

		// Only InstancedObject (a house layout) has an "occurrence" field (see its class doc)
		boolean instancedCode = array.get(0).getAsJsonObject().has("occurrence");
		clientThread.invoke(() ->
		{
			boolean inPoh = isInPoh();
			SwingUtilities.invokeLater(() ->
			{
				if (instancedCode != inPoh)
				{
					JOptionPane.showMessageDialog(panel, instancedCode
						? "That's a player-owned house layout. Stand in a house to import it."
						: "That's an overworld layout. Leave your house to import it.");
					return;
				}
				if (instancedCode)
				{
					importInstanced(array, code);
				}
				else
				{
					importOverworld(array, code);
				}
			});
		});
	}

	/** Merges an overworld layout code (see importFromClipboard) into the current world. */
	/**
	 * Clamps an imported object's scale to the same range the panel's own spinner enforces
	 * (-100..100). loadDecorationModelData already floors scale at a sane minimum but has no
	 * upper cap, so a hand-edited or malicious shared code could otherwise produce a hugely
	 * oversized model.
	 */
	private static PlacedObject sanitizeScale(PlacedObject obj)
	{
		int clamped = Math.max(-100, Math.min(100, obj.getScale()));
		if (clamped == obj.getScale())
		{
			return obj;
		}
		return new PlacedObject(obj.getRegionId(), obj.getRegionX(), obj.getRegionY(), obj.getPlane(),
			obj.getObjectId(), obj.getOrientation(), obj.getStackIndex(), obj.getAnimationId(), obj.getHeightOffset(),
			obj.getOffsetX(), obj.getOffsetY(), obj.getNpcId(), clamped, obj.isRoam(), obj.isMirror(), obj.getTintArgb());
	}

	/** See the PlacedObject overload. */
	private static InstancedObject sanitizeScale(InstancedObject obj)
	{
		int clamped = Math.max(-100, Math.min(100, obj.getScale()));
		if (clamped == obj.getScale())
		{
			return obj;
		}
		return new InstancedObject(obj.getRegionId(), obj.getRegionX(), obj.getRegionY(), obj.getPlane(), obj.getOccurrence(),
			obj.getObjectId(), obj.getOrientation(), obj.getStackIndex(), obj.getAnimationId(), obj.getHeightOffset(),
			obj.getOffsetX(), obj.getOffsetY(), obj.getNpcId(), clamped, obj.isRoam(), obj.isMirror(), obj.getTintArgb());
	}

	private void importOverworld(JsonArray array, String code)
	{
		List<PlacedObject> imports = gson.fromJson(array, PLACED_OBJECT_LIST);
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
			for (PlacedObject rawIncoming : entry.getValue())
			{
				PlacedObject incoming = sanitizeScale(rawIncoming);
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
		if (imported > 0)
		{
			panel.promptSaveLayout(code, "Imported layout");
		}
	}

	/**
	 * Merges a house layout code (see importFromClipboard) into the current house. Each
	 * incoming object's stable room identity is matched against the current house exactly as
	 * spawnInstancedObject already does: it only ever lands in a room whose furniture matches,
	 * and only in whichever house the importer happens to be standing in right now â€” importing
	 * a friend's code never touches their house, or any house besides this one.
	 */
	private void importInstanced(JsonArray array, String code)
	{
		List<InstancedObject> imports = gson.fromJson(array, INSTANCED_OBJECT_LIST);
		if (imports == null || imports.isEmpty())
		{
			JOptionPane.showMessageDialog(panel, "Nothing to import.");
			return;
		}

		int result = JOptionPane.showConfirmDialog(
			panel,
			"Import " + imports.size() + (imports.size() == 1 ? " object?" : " objects?")
				+ "\nOnly rooms with matching furniture will receive anything.",
			"Import",
			JOptionPane.YES_NO_OPTION
		);
		if (result != JOptionPane.YES_OPTION)
		{
			return;
		}

		clientThread.invoke(() ->
		{
			WorldView wv = client.getTopLevelWorldView();
			if (wv == null)
			{
				return;
			}
			List<InstancedObject> existing = new ArrayList<>(activeInstancedObjects.keySet());
			List<InstancedObject> added = new ArrayList<>();
			int imported = 0;
			int skipped = 0;
			for (InstancedObject rawIncoming : imports)
			{
				InstancedObject incoming = sanitizeScale(rawIncoming);
				// occurrence is relative to however many rooms shared that template in the
				// exporter's house. If this house has fewer, don't let it clamp onto the wrong
				// room (see resolveLiveInstancedPoint) â€” treat it as "not part of this layout."
				int matchesHere = WorldPoint.toLocalInstance(wv, incoming.toWorldPoint()).size();
				if (incoming.getOccurrence() >= matchesHere)
				{
					skipped++;
					continue;
				}
				List<InstancedObject> atTile = existing.stream()
					.filter(o -> o.getRegionId() == incoming.getRegionId()
						&& o.getRegionX() == incoming.getRegionX()
						&& o.getRegionY() == incoming.getRegionY()
						&& o.getPlane() == incoming.getPlane()
						&& o.getOccurrence() == incoming.getOccurrence())
					.collect(Collectors.toList());

				if (atTile.stream().anyMatch(o -> o.sameDecoration(incoming)))
				{
					skipped++;
					continue;
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
				if (stackIndex >= 3)
				{
					skipped++;
					continue;
				}

				InstancedObject placed = incoming.withStackIndex(stackIndex);
				existing.add(placed);
				activeInstancedObjects.put(placed, null);
				spawnInstancedObject(placed);
				added.add(placed);
				imported++;
			}
			if (!added.isEmpty())
			{
				recordInstancedAction(List.of(), added);
			}
			saveInstancedObjects();

			int finalImported = imported;
			int finalSkipped = skipped;
			SwingUtilities.invokeLater(() ->
			{
				JOptionPane.showMessageDialog(panel, "Imported " + finalImported
					+ (finalImported == 1 ? " object" : " objects")
					+ (finalSkipped > 0 ? " (" + finalSkipped + " skipped, duplicates or full tiles)." : "."));
				if (finalImported > 0)
				{
					panel.promptSaveLayout(code, "Imported layout");
				}
			});
		});
	}

	// â”€â”€ Instanced region (POH) support â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

	private void placeInstancedObject(Tile tile, Point clickPos)
	{
		// Snapshotted for the same reason as placeObject's â€” the load below may defer this.
		int objectId = panel.getSelectedObjectId();
		int npcId = panel.getSelectedNpcId();
		int scale = panel.getSelectedScale();
		int animationId = panel.getSelectedAnimationId();
		int heightOffset = panel.getSelectedHeightOffset();
		boolean roam = panel.getSelectedRoam();
		int tintArgb = panel.getSelectedTintArgb();

		int rawOffsetX = panel.getSelectedOffsetX();
		int rawOffsetY = panel.getSelectedOffsetY();
		int panelOrientation = panel.getSelectedOrientation();
		boolean panelMirror = panel.getSelectedMirror();

		// See placeObject's matching check for why this must happen before persisting.
		withDecorationModel(objectId, npcId, scale, panelMirror, tintArgb, null, md ->
		{
			if (md == null)
			{
				log.debug("No model for object id {} / npc id {}, not placing", objectId, npcId);
				return;
			}
			InstancedIdentity id = resolveInstancedIdentity(tile);
			List<InstancedObject> atTile = findAllAtInstancedTile(id.stable.getRegionID(), id.stable.getRegionX(), id.stable.getRegionY(), id.stable.getPlane(), id.occurrence);
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

			int offsetX = rawOffsetX;
			int offsetY = rawOffsetY;
			if (!getSnapEnabled() && clickPos != null)
			{
				int[] cursorOffset = computeCursorOffset(tile.getLocalLocation(), clickPos);
				if (cursorOffset != null)
				{
					offsetX = cursorOffset[0];
					offsetY = cursorOffset[1];
				}
			}

			int orientation = panelOrientation;
			boolean mirror = panelMirror;
			if (getRandomEnabled())
			{
				orientation = roamRandom.nextInt(2048);
				mirror = roamRandom.nextBoolean();
			}

			InstancedObject obj = new InstancedObject(id.stable.getRegionID(), id.stable.getRegionX(), id.stable.getRegionY(), id.stable.getPlane(), id.occurrence,
				objectId, orientation, stackIndex,
				animationId,
				heightOffset, offsetX, offsetY,
				npcId, scale, roam,
				mirror, tintArgb);

			activeInstancedObjects.put(obj, null);
			spawnInstancedObject(obj);
			saveInstancedObjects();
			recordInstancedAction(List.of(), List.of(obj));
		});
	}

	/** A room's full stable identity: its template chunk WorldPoint, plus which occurrence it is among any rooms currently sharing that same template (see InstancedObject). */
	private static final class InstancedIdentity
	{
		final WorldPoint stable;
		final int occurrence;

		InstancedIdentity(WorldPoint stable, int occurrence)
		{
			this.stable = stable;
			this.occurrence = occurrence;
		}
	}

	/**
	 * Resolves a clicked tile to the stable identity of its house room: the WorldPoint that
	 * {@code WorldPoint.fromLocalInstance} derives from the room's template chunk, immune to
	 * layout edits and to Jagex reassigning which scene plane a floor renders on (e.g. adding
	 * a dungeon shifts ground floor from plane 0 to plane 1). If another room right now shares
	 * this exact template (identical furniture), {@code toLocalInstance} returns every match;
	 * sorting them deterministically and finding this tile's own position among them gives a
	 * stable occurrence index to tell the rooms apart.
	 */
	private InstancedIdentity resolveInstancedIdentity(Tile tile)
	{
		int plane = tile.getWorldLocation().getPlane();
		LocalPoint localPoint = tile.getLocalLocation();
		WorldPoint stable = WorldPoint.fromLocalInstance(client, localPoint, plane);

		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return new InstancedIdentity(stable, 0);
		}
		WorldPoint live = WorldPoint.fromLocal(wv, localPoint.getX(), localPoint.getY(), plane);
		List<WorldPoint> occurrences = new ArrayList<>(WorldPoint.toLocalInstance(wv, stable));
		occurrences.sort(Comparator.comparingInt(WorldPoint::getX).thenComparingInt(WorldPoint::getY));
		int occurrence = Math.max(occurrences.indexOf(live), 0);
		return new InstancedIdentity(stable, occurrence);
	}

	/**
	 * Resolves the room's stable identity to wherever it currently renders this session.
	 * Null means that room isn't part of the current layout â€” it respawns once rebuilt, same
	 * as an overworld object waiting for its region to reload. If another room shares this
	 * exact template, sorts the matches the same deterministic way as at placement time and
	 * picks this object's own recorded occurrence.
	 */
	private WorldPoint resolveLiveInstancedPoint(InstancedObject obj, WorldView wv)
	{
		List<WorldPoint> current = new ArrayList<>(WorldPoint.toLocalInstance(wv, obj.toWorldPoint()));
		if (current.isEmpty())
		{
			return null;
		}
		current.sort(Comparator.comparingInt(WorldPoint::getX).thenComparingInt(WorldPoint::getY));
		return current.get(Math.min(obj.getOccurrence(), current.size() - 1));
	}

	/**
	 * POH floors share a single scene, stacked at the same scene footprint â€” without this, an
	 * object placed upstairs renders at its correct (much higher) height regardless of which
	 * floor the player is currently standing on, so it bleeds through into the room stacked
	 * beneath it. Hides every instanced object whose current room isn't on the player's plane.
	 */
	private void tickInstancedVisibility()
	{
		if (activeInstancedObjects.isEmpty())
		{
			return;
		}
		WorldView wv = client.getTopLevelWorldView();
		Player player = client.getLocalPlayer();
		if (wv == null || player == null)
		{
			return;
		}
		int playerPlane = player.getWorldLocation().getPlane();
		for (Map.Entry<InstancedObject, RuneLiteObject> entry : activeInstancedObjects.entrySet())
		{
			RuneLiteObject rlo = entry.getValue();
			if (rlo == null)
			{
				continue;
			}
			WorldPoint liveWp = resolveLiveInstancedPoint(entry.getKey(), wv);
			boolean shouldShow = liveWp != null && liveWp.getPlane() == playerPlane;
			if (rlo.isActive() != shouldShow)
			{
				rlo.setActive(shouldShow);
			}
		}
	}

	private void removeInstancedObject(InstancedObject obj)
	{
		if (obj.equals(editInstancedTarget))
		{
			endEdit();
		}
		despawn(activeInstancedObjects.remove(obj));
		saveInstancedObjects();
		recordInstancedAction(List.of(obj), List.of());
	}

	private void spawnInstancedObject(InstancedObject obj)
	{
		withDecorationModel(obj.getObjectId(), obj.getNpcId(), obj.getScale(), obj.isMirror(), obj.getTintArgb(), null, md ->
		{
			WorldView wv = client.getTopLevelWorldView();
			if (wv == null)
			{
				return;
			}
			if (md == null)
			{
				log.debug("No model data for object id {} / npc id {}", obj.getObjectId(), obj.getNpcId());
				return;
			}
			WorldPoint liveWp = resolveLiveInstancedPoint(obj, wv);
			if (liveWp == null)
			{
				return;
			}
			LocalPoint base = LocalPoint.fromWorld(wv, liveWp);
			if (base == null)
			{
				return;
			}
			LocalPoint lp = new LocalPoint(base.getX() + obj.getOffsetX(), base.getY() + obj.getOffsetY(), base.getWorldView());
			Model model = md.light();
			RuneLiteObject rlo = client.createRuneLiteObject();
			rlo.setModel(model);
			rlo.setOrientation(obj.getOrientation());
			rlo.setLocation(lp, liveWp.getPlane());
			rlo.setZ(rlo.getZ() - obj.getHeightOffset());
			applyAnimation(rlo, effectiveAnimation(obj.getAnimationId(), obj.getNpcId()));
			rlo.setActive(true);
			activeInstancedObjects.put(obj, rlo);
			if (obj.isRoam() && obj.getNpcId() > 0)
			{
				registerRoamer(rlo, lp, liveWp.getPlane(), obj.getOrientation(), obj.getHeightOffset(), obj.getNpcId(), obj.getAnimationId());
			}
		});
	}

	private void loadInstancedObjects()
	{
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return;
		}
		long accountHash = client.getAccountHash();
		if (accountHash <= 0)
		{
			return;
		}
		String json = configManager.getConfiguration(CONFIG_GROUP, "instance_" + accountHash);
		// Old saves were keyed by display name, which an OSRS name change orphans permanently.
		// Fall back once, then always write under the account hash from here on.
		boolean migratedFromNameKey = false;
		if ((json == null || json.isEmpty()) && player.getName() != null)
		{
			json = configManager.getConfiguration(CONFIG_GROUP, "instance_" + player.getName());
			migratedFromNameKey = json != null && !json.isEmpty();
		}
		if (json == null || json.isEmpty())
		{
			return;
		}
		try
		{
			JsonArray array = gson.fromJson(json, JsonArray.class);
			if (array == null || array.size() == 0)
			{
				return;
			}

			boolean legacyFormat = array.get(0).getAsJsonObject().has("sceneX");
			List<InstancedObject> list;
			if (legacyFormat)
			{
				WorldView wv = client.getTopLevelWorldView();
				if (wv == null)
				{
					// Try again on the next load call once a scene is available to resolve against
					return;
				}
				list = migrateLegacyInstancedObjects(array, wv);
			}
			else
			{
				list = gson.fromJson(array, INSTANCED_OBJECT_LIST);
			}
			if (list == null)
			{
				return;
			}
			for (InstancedObject obj : list)
			{
				// LOGGED_IN fires on every scene rebuild in the house â€” don't respawn objects
				// that are already live. A present-but-null entry means a previous attempt
				// couldn't find its room yet (not built at the time) â€” retry those, since the
				// room may exist now.
				if (activeInstancedObjects.get(obj) != null)
				{
					continue;
				}
				activeInstancedObjects.put(obj, null);
				spawnInstancedObject(obj);
			}
			if (legacyFormat || migratedFromNameKey)
			{
				// Persist immediately under the current key/format so each migration only ever runs once
				saveInstancedObjects();
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to load instanced objects", e);
		}
	}

	/**
	 * One-time upgrade from the old scene-tile-coordinate save format to stable region
	 * coordinates. Reads each legacy entry's scene position as if it were still valid in the
	 * current session (the only scene we have to resolve against), so this is a best-effort
	 * pass: it fixes storage going forward but can't undo a layout that already drifted under
	 * the old format.
	 */
	private List<InstancedObject> migrateLegacyInstancedObjects(JsonArray array, WorldView wv)
	{
		List<InstancedObject> result = new ArrayList<>();
		for (JsonElement el : array)
		{
			JsonObject o = el.getAsJsonObject();
			int sceneX = o.get("sceneX").getAsInt();
			int sceneY = o.get("sceneY").getAsInt();
			int plane = o.get("plane").getAsInt();
			LocalPoint lp = LocalPoint.fromScene(sceneX, sceneY, wv);
			WorldPoint stable = WorldPoint.fromLocalInstance(client, lp, plane);
			WorldPoint live = WorldPoint.fromLocal(wv, lp.getX(), lp.getY(), plane);
			List<WorldPoint> occurrences = new ArrayList<>(WorldPoint.toLocalInstance(wv, stable));
			occurrences.sort(Comparator.comparingInt(WorldPoint::getX).thenComparingInt(WorldPoint::getY));
			int occurrence = Math.max(occurrences.indexOf(live), 0);
			result.add(new InstancedObject(
				stable.getRegionID(), stable.getRegionX(), stable.getRegionY(), stable.getPlane(), occurrence,
				o.get("objectId").getAsInt(),
				o.get("orientation").getAsInt(),
				o.get("stackIndex").getAsInt(),
				o.has("animationId") ? o.get("animationId").getAsInt() : 0,
				o.has("heightOffset") ? o.get("heightOffset").getAsInt() : 0,
				o.has("offsetX") ? o.get("offsetX").getAsInt() : 0,
				o.has("offsetY") ? o.get("offsetY").getAsInt() : 0,
				o.has("npcId") ? o.get("npcId").getAsInt() : 0,
				o.has("scale") ? o.get("scale").getAsInt() : 0,
				o.has("roam") && o.get("roam").getAsBoolean(),
				o.has("mirror") && o.get("mirror").getAsBoolean(),
				o.has("tintArgb") ? o.get("tintArgb").getAsInt() : 0
			));
		}
		log.info("Migrated {} POH decorations to stable room coordinates", result.size());
		return result;
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
					despawn(rlo);
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
					despawn(activeObjects.remove(obj));
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
		long accountHash = client.getAccountHash();
		if (accountHash <= 0)
		{
			return;
		}
		List<InstancedObject> list = new ArrayList<>(activeInstancedObjects.keySet());
		String key = "instance_" + accountHash;
		if (list.isEmpty())
		{
			configManager.unsetConfiguration(CONFIG_GROUP, key);
		}
		else
		{
			configManager.setConfiguration(CONFIG_GROUP, key, gson.toJson(list));
		}
	}

	private List<InstancedObject> findAllAtInstancedTile(int regionId, int regionX, int regionY, int plane, int occurrence)
	{
		List<InstancedObject> result = new ArrayList<>();
		for (InstancedObject obj : activeInstancedObjects.keySet())
		{
			if (obj.getRegionId() == regionId && obj.getRegionX() == regionX
				&& obj.getRegionY() == regionY && obj.getPlane() == plane
				&& obj.getOccurrence() == occurrence)
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

	static final int MAX_FAV_SLOTS = 256;

	/**
	 * Reads the first {@code count} favourite slots. By construction (shiftFavouritesDown +
	 * unset-last-slot on removal) nothing beyond the visible slot count is ever populated, so
	 * callers pass their current visibleSlots rather than always scanning all MAX_FAV_SLOTS.
	 */
	Favourite[] getFavourites(int count)
	{
		Favourite[] favs = new Favourite[count];
		for (int i = 0; i < count; i++)
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
			return Math.max(5, Math.min(MAX_FAV_SLOTS, Integer.parseInt(val)));
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

	boolean getFavouritesExpanded()
	{
		String val = configManager.getConfiguration(CONFIG_GROUP, "favs_expanded");
		return val == null || Boolean.parseBoolean(val);
	}

	void setFavouritesExpanded(boolean expanded)
	{
		configManager.setConfiguration(CONFIG_GROUP, "favs_expanded", String.valueOf(expanded));
	}

	/** Whether new placements snap to tile-center instead of landing exactly under the cursor. Off by default. */
	boolean getSnapEnabled()
	{
		return Boolean.parseBoolean(configManager.getConfiguration(CONFIG_GROUP, "snap_enabled"));
	}

	void setSnapEnabled(boolean enabled)
	{
		configManager.setConfiguration(CONFIG_GROUP, "snap_enabled", String.valueOf(enabled));
	}

	/** Whether new placements randomize rotation and mirror. Off by default. */
	boolean getRandomEnabled()
	{
		return Boolean.parseBoolean(configManager.getConfiguration(CONFIG_GROUP, "random_enabled"));
	}

	void setRandomEnabled(boolean enabled)
	{
		configManager.setConfiguration(CONFIG_GROUP, "random_enabled", String.valueOf(enabled));
	}

	// â”€â”€ Saves (named export/import codes, kept for later) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

	private static final int MAX_SAVED_LAYOUTS = 256;

	boolean getSavesExpanded()
	{
		String val = configManager.getConfiguration(CONFIG_GROUP, "saves_expanded");
		return val == null || Boolean.parseBoolean(val);
	}

	void setSavesExpanded(boolean expanded)
	{
		configManager.setConfiguration(CONFIG_GROUP, "saves_expanded", String.valueOf(expanded));
	}

	List<SavedLayout> getSavedLayouts()
	{
		String json = configManager.getConfiguration(CONFIG_GROUP, "saved_layouts");
		if (json == null || json.isEmpty())
		{
			return new ArrayList<>();
		}
		try
		{
			List<SavedLayout> list = gson.fromJson(json, SAVED_LAYOUT_LIST);
			return list != null ? list : new ArrayList<>();
		}
		catch (Exception e)
		{
			return new ArrayList<>();
		}
	}

	private void setSavedLayouts(List<SavedLayout> layouts)
	{
		configManager.setConfiguration(CONFIG_GROUP, "saved_layouts", gson.toJson(layouts));
	}

	/** Returns false (and saves nothing) once MAX_SAVED_LAYOUTS is reached. */
	boolean addSavedLayout(String name, String code)
	{
		List<SavedLayout> layouts = getSavedLayouts();
		if (layouts.size() >= MAX_SAVED_LAYOUTS)
		{
			return false;
		}
		layouts.add(new SavedLayout(name, code));
		setSavedLayouts(layouts);
		return true;
	}

	void renameSavedLayout(int index, String newName)
	{
		List<SavedLayout> layouts = getSavedLayouts();
		if (index < 0 || index >= layouts.size())
		{
			return;
		}
		layouts.get(index).setName(newName);
		setSavedLayouts(layouts);
	}

	void removeSavedLayout(int index)
	{
		List<SavedLayout> layouts = getSavedLayouts();
		if (index < 0 || index >= layouts.size())
		{
			return;
		}
		layouts.remove(index);
		setSavedLayouts(layouts);
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
