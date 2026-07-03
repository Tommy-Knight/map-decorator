package com.tommyknight.mapdecorator;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.api.ModelData;

/**
 * Swing component that displays a software-rendered 3D model preview.
 * Drag left/right to rotate. Updates asynchronously when the object ID changes.
 */
class ModelPreviewComponent extends JPanel
{
	private static final int SIZE = 200;
	private static final Color EMPTY_BG = new Color(28, 28, 36);

	private final ModelRenderer renderer = new ModelRenderer();
	private BufferedImage currentImage;

	private int dragStartX;
	private int dragStartY;
	private float yaw = 0.8f;
	private float pitch = (float) Math.toRadians(22);
	private float zoom = 1.0f;
	private float offsetX = 0;
	private float offsetY = 0;
	private boolean isPanning = false;

	ModelPreviewComponent()
	{
		setPreferredSize(new Dimension(SIZE, SIZE));
		setMinimumSize(new Dimension(SIZE, SIZE));
		setBackground(EMPTY_BG);

		MouseAdapter drag = new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				dragStartX = e.getX();
				dragStartY = e.getY();
				isPanning = SwingUtilities.isRightMouseButton(e) || e.isShiftDown();
			}

			@Override
			public void mouseDragged(MouseEvent e)
			{
				int dx = e.getX() - dragStartX;
				int dy = e.getY() - dragStartY;
				if (isPanning)
				{
					offsetX += dx;
					offsetY += dy;
				}
				else
				{
					yaw += dx * 0.015f;
					pitch += dy * 0.015f;
					pitch = Math.max(-(float) Math.PI / 2, Math.min((float) Math.PI / 2, pitch));
				}
				dragStartX = e.getX();
				dragStartY = e.getY();
				rerender();
			}
		};

		addMouseListener(drag);
		addMouseMotionListener(drag);
		addMouseWheelListener((MouseWheelEvent e) ->
		{
			zoom *= (float) Math.pow(1.1, -e.getPreciseWheelRotation());
			zoom = Math.max(0.2f, Math.min(5.0f, zoom));
			rerender();
		});
	}

	/** Update yaw and re-render. Safe to call from any thread. */
	void setYaw(float yaw)
	{
		this.yaw = yaw;
		rerender();
	}

	/**
	 * Load model data and render the first frame.
	 * Must be called on the client thread (model data extraction requirement).
	 * Schedules an EDT repaint when done.
	 */
	void setModel(ModelData model)
	{
		if (model == null)
		{
			renderer.clear();
			currentImage = null;
			SwingUtilities.invokeLater(this::repaint);
			return;
		}
		offsetX = 0;
		offsetY = 0;
		renderer.loadModel(model);
		BufferedImage img = renderer.render(SIZE, SIZE, yaw, pitch, zoom, offsetX, offsetY);
		SwingUtilities.invokeLater(() ->
		{
			currentImage = img;
			repaint();
		});
	}

	/** Redraw with the current yaw. Safe to call on any thread. */
	private void rerender()
	{
		if (!renderer.hasModel())
		{
			return;
		}
		BufferedImage img = renderer.render(SIZE, SIZE, yaw, pitch, zoom, offsetX, offsetY);
		SwingUtilities.invokeLater(() ->
		{
			currentImage = img;
			repaint();
		});
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		super.paintComponent(g);
		if (currentImage != null)
		{
			g.drawImage(currentImage, 0, 0, null);
		}
		else
		{
			g.setColor(EMPTY_BG);
			g.fillRect(0, 0, getWidth(), getHeight());
			g.setColor(Color.DARK_GRAY);
			String msg = "No model";
			int mx = (getWidth() - g.getFontMetrics().stringWidth(msg)) / 2;
			int my = getHeight() / 2;
			g.drawString(msg, mx, my);
		}
	}
}
