package com.tommyknight.mapdecorator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Perspective;
import net.runelite.api.Projectile;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.Scene;
import net.runelite.api.TileObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.RenderCallback;
import net.runelite.client.callback.RenderCallbackManager;

/**
 * Draws placed lights (decorations with no model) under the 117 HD plugin.
 * <p>
 * 117 HD lights spell projectiles but offers no way for another plugin to add a light. So each
 * light gets stationary client-side projectiles of its {@link LightPreset}, kept alive
 * indefinitely. The client announces a new projectile with {@code ProjectileMoved}, which is
 * where 117 HD picks it up. The projectile's own model is hidden through {@code drawObject};
 * 117 HD gates the light on {@code addEntity} instead, so the glow stays. Without 117 HD the
 * hidden projectiles do nothing.
 * <p>
 * Anchors are added and removed where their carrier is spawned and despawned, not found by a
 * per-tick sweep. The only periodic work is {@link #tick}, once per game tick.
 */
@Singleton
class LightAnchors
{
	/** Projectile lifetime in client cycles (20 ms); refreshed at half life. */
	private static final int LIFETIME_CYCLES = 3000;
	/** A carrier that drifts further than this (the cursor ghost) gets its light re-anchored. */
	private static final int REANCHOR_DISTANCE = 128;

	@Value
	static class Spec
	{
		LightPreset preset;
		/** Identical lights stacked on the spot; more copies = brighter, not wider. */
		int copies;

		/** A light's Scale above 0 stacks copies of it for brightness, 1 to 5; its reach is fixed by 117 HD. */
		static Spec of(int lightPreset, int scale)
		{
			return new Spec(LightPreset.fromCode(lightPreset), 1 + Math.max(0, Math.min(100, scale)) / 25);
		}
	}

	private static final class Anchor
	{
		final Spec spec;
		final LocalPoint location;
		final int level;
		final int z;
		final List<Projectile> projectiles = new ArrayList<>();

		Anchor(Spec spec, LocalPoint location, int level, int z)
		{
			this.spec = spec;
			this.location = location;
			this.level = level;
			this.z = z;
		}
	}

	@Inject
	private Client client;

	@Inject
	private RenderCallbackManager renderCallbackManager;

	private final Map<RuneLiteObject, Anchor> anchors = new IdentityHashMap<>();
	/** Read from render threads, so a concurrent set. */
	private final Set<Projectile> hidden = ConcurrentHashMap.newKeySet();

	private final RenderCallback hideProjectileModels = new RenderCallback()
	{
		@Override
		public boolean drawObject(Scene scene, TileObject object)
		{
			return !(object instanceof GameObject && hidden.contains(((GameObject) object).getRenderable()));
		}
	};

	void startUp()
	{
		renderCallbackManager.register(hideProjectileModels);
	}

	/** Client thread. */
	void shutDown()
	{
		for (Anchor a : anchors.values())
		{
			kill(a);
		}
		anchors.clear();
		renderCallbackManager.unregister(hideProjectileModels);
	}

	/** Client thread. Lights {@code carrier}, replacing its light if the spec changed or it moved. */
	void put(RuneLiteObject carrier, Spec spec)
	{
		Anchor a = anchors.get(carrier);
		if (a != null && a.spec.equals(spec) && !moved(a, carrier))
		{
			return;
		}
		remove(carrier);
		if (spec.getPreset() == LightPreset.NONE)
		{
			return;
		}
		a = spawn(carrier, spec);
		if (a != null)
		{
			anchors.put(carrier, a);
		}
	}

	/** Client thread. Safe to call for any object, lit or not. */
	void remove(RuneLiteObject carrier)
	{
		Anchor a = anchors.remove(carrier);
		if (a != null)
		{
			kill(a);
		}
	}

	/**
	 * Client thread, once per game tick. Keeps every light's projectiles alive, and respawns a
	 * light whose projectiles the client dropped (a scene rebuild clears them).
	 */
	void tick()
	{
		if (anchors.isEmpty())
		{
			return;
		}
		Set<Projectile> live = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Projectile p : client.getProjectiles())
		{
			live.add(p);
		}

		int now = client.getGameCycle();
		List<Map.Entry<RuneLiteObject, Anchor>> dead = new ArrayList<>();
		for (Iterator<Map.Entry<RuneLiteObject, Anchor>> it = anchors.entrySet().iterator(); it.hasNext(); )
		{
			Map.Entry<RuneLiteObject, Anchor> e = it.next();
			Anchor a = e.getValue();
			if (!live.containsAll(a.projectiles))
			{
				kill(a);
				it.remove();
				dead.add(e);
				continue;
			}
			for (Projectile p : a.projectiles)
			{
				if (p.getRemainingCycles() < LIFETIME_CYCLES / 2)
				{
					p.setEndCycle(now + LIFETIME_CYCLES);
				}
			}
		}
		for (Map.Entry<RuneLiteObject, Anchor> e : dead)
		{
			put(e.getKey(), e.getValue().spec);
		}
	}

	private Anchor spawn(RuneLiteObject carrier, Spec spec)
	{
		LocalPoint lp = carrier.getLocation();
		if (lp == null)
		{
			return null;
		}
		int level = carrier.getLevel();
		// The carrier already sits at the decoration's Height; scene Z grows downward
		int heightAboveTile = Perspective.getTileHeight(client, lp, level) - carrier.getZ();
		WorldPoint wp = WorldPoint.fromLocal(client.getTopLevelWorldView(), lp.getX(), lp.getY(), level);
		int now = client.getGameCycle();

		Anchor a = new Anchor(spec, lp, level, carrier.getZ());
		for (int i = 0; i < spec.getCopies(); i++)
		{
			Projectile p = client.createProjectile(spec.getPreset().getCode(), wp, heightAboveTile, null,
				wp, heightAboveTile, null, now, now + LIFETIME_CYCLES, 0, 0);
			hidden.add(p);
			a.projectiles.add(p);
		}
		return a;
	}

	private void kill(Anchor a)
	{
		for (Projectile p : a.projectiles)
		{
			p.setEndCycle(0);
			hidden.remove(p);
		}
		a.projectiles.clear();
	}

	private static boolean moved(Anchor a, RuneLiteObject carrier)
	{
		LocalPoint lp = carrier.getLocation();
		return lp == null || carrier.getLevel() != a.level || Math.abs(carrier.getZ() - a.z) > 64
			|| lp.distanceTo(a.location) > REANCHOR_DISTANCE;
	}
}
