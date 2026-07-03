package com.tommyknight.mapdecorator;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import net.runelite.api.ModelData;

/**
 * Software-renders a RuneLite Model to a BufferedImage.
 *
 * Uses unlit face HSL colors + flat shading from a fixed light direction.
 * getFaceColors1/2/3() are palette indices, not usable without the game palette,
 * so getUnlitFaceColors() (short[] Jagex HSL) is used instead.
 */
class ModelRenderer
{
	private static final Color BG = new Color(28, 28, 36);

	// Fixed light direction (normalized)
	private static final float LX = 0.5f, LY = -0.8f, LZ = 0.35f;
	private static final float LLEN = (float) Math.sqrt(LX * LX + LY * LY + LZ * LZ);

	/** Immutable per-model data, swapped atomically so render() never sees a half-loaded model. */
	private static final class Snapshot
	{
		final float[] vx, vy, vz;
		final int[] fi1, fi2, fi3;
		final int faceCount;
		final int[] baseRgb; // per-face base RGB from unlit HSL

		Snapshot(float[] vx, float[] vy, float[] vz, int[] fi1, int[] fi2, int[] fi3, int faceCount, int[] baseRgb)
		{
			this.vx = vx;
			this.vy = vy;
			this.vz = vz;
			this.fi1 = fi1;
			this.fi2 = fi2;
			this.fi3 = fi3;
			this.faceCount = faceCount;
			this.baseRgb = baseRgb;
		}
	}

	private volatile Snapshot snapshot;

	boolean hasModel()
	{
		return snapshot != null;
	}

	/** Extract data from the model. Must be called on the client thread. */
	void loadModel(ModelData model)
	{
		float[] vx = model.getVerticesX();
		float[] vy = model.getVerticesY();
		float[] vz = model.getVerticesZ();
		int[] fi1 = model.getFaceIndices1();
		int[] fi2 = model.getFaceIndices2();
		int[] fi3 = model.getFaceIndices3();
		int faceCount = model.getFaceCount();

		short[] unlitColors = model.getFaceColors();
		int[] baseRgb = new int[faceCount];
		for (int i = 0; i < faceCount; i++)
		{
			int hsl = (unlitColors != null && i < unlitColors.length)
				? (unlitColors[i] & 0xFFFF)
				: 0x3F60; // neutral grey fallback
			baseRgb[i] = jagexHslToRgb(hsl);
		}

		snapshot = new Snapshot(vx, vy, vz, fi1, fi2, fi3, faceCount, baseRgb);
	}

	void clear()
	{
		snapshot = null;
	}

	/**
	 * Render the loaded model to a new BufferedImage.
	 * Safe to call on any thread after loadModel() has finished.
	 *
	 * @param yaw rotation around the Y axis, in radians
	 */
	BufferedImage render(int w, int h, float yaw, float pitch, float zoom, float offsetX, float offsetY)
	{
		final Snapshot s = snapshot;
		float cosPitch = (float) Math.cos(pitch);
		float sinPitch = (float) Math.sin(pitch);
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		if (s == null)
		{
			return img;
		}

		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(BG);
		g.fillRect(0, 0, w, h);

		// Auto-scale: fit the model's bounding sphere to 80% of the smaller dimension
		float maxR = 1;
		for (int i = 0; i < s.vx.length; i++)
		{
			maxR = Math.max(maxR, Math.abs(s.vx[i]));
			maxR = Math.max(maxR, Math.abs(s.vy[i]));
			maxR = Math.max(maxR, Math.abs(s.vz[i]));
		}
		float scale = Math.min(w, h) * 0.4f / maxR * zoom;
		float cx = w * 0.5f + offsetX;
		float cy = h * 0.5f + offsetY;

		float cosY = (float) Math.cos(yaw);
		float sinY = (float) Math.sin(yaw);

		// Project all vertices
		int n = s.vx.length;
		float[] sx = new float[n];
		float[] sy = new float[n];
		float[] sz = new float[n];
		float[] wx = new float[n]; // world-space X after yaw only (for normals)
		float[] wy = new float[n];
		float[] wz = new float[n];

		for (int i = 0; i < n; i++)
		{
			// Y-axis rotation (yaw)
			float rx = s.vx[i] * cosY + s.vz[i] * sinY;
			float ry = s.vy[i];
			float rz = -s.vx[i] * sinY + s.vz[i] * cosY;

			// X-axis rotation (pitch)
			float rx2 = rx;
			float ry2 = ry * cosPitch - rz * sinPitch;
			float rz2 = ry * sinPitch + rz * cosPitch;

			wx[i] = rx2;
			wy[i] = ry2;
			wz[i] = rz2;

			// OSRS model Y is positive-downward, map directly (no negation).
			sx[i] = cx + rx2 * scale;
			sy[i] = cy + ry2 * scale;
			sz[i] = rz2;
		}

		// Painter's algorithm: sort faces back-to-front by average Z
		Integer[] order = new Integer[s.faceCount];
		for (int i = 0; i < s.faceCount; i++)
		{
			order[i] = i;
		}
		Arrays.sort(order, (a, b) ->
		{
			float za = (sz[s.fi1[a]] + sz[s.fi2[a]] + sz[s.fi3[a]]);
			float zb = (sz[s.fi1[b]] + sz[s.fi2[b]] + sz[s.fi3[b]]);
			return Float.compare(zb, za); // largest Z (furthest back) drawn first
		});

		int[] px = new int[3];
		int[] py = new int[3];

		for (int fi : order)
		{
			int a = s.fi1[fi], b = s.fi2[fi], c = s.fi3[fi];

			// Screen-space signed area — skip backfaces
			float area = (sx[b] - sx[a]) * (sy[c] - sy[a]) - (sy[b] - sy[a]) * (sx[c] - sx[a]);
			if (area >= 0)
			{
				continue; // back-facing
			}

			// World-space face normal (for lighting)
			float e1x = wx[b] - wx[a], e1y = wy[b] - wy[a], e1z = wz[b] - wz[a];
			float e2x = wx[c] - wx[a], e2y = wy[c] - wy[a], e2z = wz[c] - wz[a];
			float nx = e1y * e2z - e1z * e2y;
			float ny = e1z * e2x - e1x * e2z;
			float nz = e1x * e2y - e1y * e2x;
			float nlen = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (nlen == 0)
			{
				continue;
			}
			nx /= nlen;
			ny /= nlen;
			nz /= nlen;

			float dot = Math.max(0, (nx * LX + ny * LY + nz * LZ) / LLEN);
			float brightness = 0.30f + 0.70f * dot; // 30% ambient + 70% diffuse

			int base = s.baseRgb[fi];
			int r = clamp((int) (((base >> 16) & 0xFF) * brightness));
			int gr = clamp((int) (((base >> 8) & 0xFF) * brightness));
			int bl = clamp((int) ((base & 0xFF) * brightness));

			px[0] = (int) sx[a]; px[1] = (int) sx[b]; px[2] = (int) sx[c];
			py[0] = (int) sy[a]; py[1] = (int) sy[b]; py[2] = (int) sy[c];

			g.setColor(new Color(r, gr, bl));
			g.fillPolygon(px, py, 3);
		}

		g.dispose();
		return img;
	}

	// ── Color conversion ─────────────────────────────────────────────────

	/**
	 * Convert Jagex packed HSL (6-bit hue, 3-bit saturation, 7-bit luminance) to sRGB.
	 * JagexColor only provides rgbToHSL; this is the inverse.
	 */
	static int jagexHslToRgb(int hsl)
	{
		float h = ((hsl >> 10) & 63) / 63f;
		float s = ((hsl >> 7) & 7) / 7f;
		float l = (hsl & 127) / 127f;
		return hslToRgb(h, s, l);
	}

	private static int hslToRgb(float h, float s, float l)
	{
		float c = (1 - Math.abs(2 * l - 1)) * s;
		float x = c * (1 - Math.abs((h * 6 % 2) - 1));
		float m = l - c / 2;

		float r, g, b;
		int hi = (int) (h * 6) % 6;
		switch (hi)
		{
			case 0: r = c; g = x; b = 0; break;
			case 1: r = x; g = c; b = 0; break;
			case 2: r = 0; g = c; b = x; break;
			case 3: r = 0; g = x; b = c; break;
			case 4: r = x; g = 0; b = c; break;
			default: r = c; g = 0; b = x; break;
		}

		return clamp((int) ((r + m) * 255)) << 16
			| clamp((int) ((g + m) * 255)) << 8
			| clamp((int) ((b + m) * 255));
	}

	private static int clamp(int v)
	{
		return Math.max(0, Math.min(255, v));
	}
}
