package com.tommyknight.mapdecorator;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import net.runelite.api.ModelData;

/**
 * Swing component that displays a software-rendered 3D model preview.
 * Drag left/right to rotate. Updates asynchronously when the object ID changes.
 */
class ModelPreviewComponent extends JPanel
{
	private static final int SIZE = 200;

	/** Background options by menu label; insertion order is menu order. */
	private static final Map<String, Color> BACKGROUNDS = new LinkedHashMap<>();
	static
	{
		BACKGROUNDS.put("None", ModelRenderer.DEFAULT_BG);
		BACKGROUNDS.put("Black", Color.BLACK);
		BACKGROUNDS.put("White", Color.WHITE);
		BACKGROUNDS.put("Green", new Color(0, 200, 0));
		BACKGROUNDS.put("Skybox", new Color(135, 206, 235));
	}

	private final ModelRenderer renderer = new ModelRenderer();
	private final JButton menuButton;
	private final JButton undoButton;
	private final JButton redoButton;
	private boolean canUndo;
	private boolean canRedo;
	private Runnable undoAction;
	private Runnable redoAction;
	private BufferedImage currentImage;
	private Consumer<String> backgroundSaver;
	// Resolves a texture id to its average RGB (client-thread only, like setModel itself)
	private IntFunction<Integer> textureColorResolver;

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
		setBackground(ModelRenderer.DEFAULT_BG);
		setLayout(null);

		// Background-color menu, overlaid in the top-left corner
		menuButton = new JButton()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				Graphics2D g2 = (Graphics2D) g;
				g2.setColor(new Color(0, 0, 0, 140));
				g2.fillRect(0, 0, getWidth(), getHeight());
				g2.setColor(Color.WHITE);
				int barWidth = getWidth() - 8;
				int x = 4;
				g2.fillRect(x, getHeight() / 2 - 5, barWidth, 2);
				g2.fillRect(x, getHeight() / 2 - 1, barWidth, 2);
				g2.fillRect(x, getHeight() / 2 + 3, barWidth, 2);
			}
		};
		menuButton.setContentAreaFilled(false);
		menuButton.setBorderPainted(false);
		menuButton.setFocusPainted(false);
		menuButton.setOpaque(false);
		menuButton.setBounds(0, 0, 20, 18);
		menuButton.setToolTipText("Preview background colour");
		menuButton.addActionListener(e -> showBackgroundMenu());
		add(menuButton);

		// Undo / redo squares in the bottom-left corner
		undoButton = overlayArrowButton(true);
		undoButton.setBounds(0, SIZE - 18, 20, 18);
		undoButton.setToolTipText("Undo your last change");
		undoButton.addActionListener(e ->
		{
			if (canUndo && undoAction != null)
			{
				undoAction.run();
			}
		});
		add(undoButton);

		redoButton = overlayArrowButton(false);
		redoButton.setBounds(24, SIZE - 18, 20, 18);
		redoButton.setToolTipText("Redo what you just undid");
		redoButton.addActionListener(e ->
		{
			if (canRedo && redoAction != null)
			{
				redoAction.run();
			}
		});
		add(redoButton);

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

	/** A small translucent square with a bold left/right arrow, greyed out while unavailable. */
	private JButton overlayArrowButton(boolean left)
	{
		JButton btn = new JButton()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				Graphics2D g2 = (Graphics2D) g;
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(new Color(0, 0, 0, 140));
				g2.fillRect(0, 0, getWidth(), getHeight());
				boolean available = left ? canUndo : canRedo;
				g2.setColor(available ? Color.WHITE : new Color(255, 255, 255, 80));
				g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				int cy = getHeight() / 2;
				int tip = left ? 6 : getWidth() - 6;
				int tail = left ? getWidth() - 6 : 6;
				int barb = left ? tip + 4 : tip - 4;
				g2.drawLine(tail, cy, tip, cy);
				g2.drawLine(tip, cy, barb, cy - 4);
				g2.drawLine(tip, cy, barb, cy + 4);
			}
		};
		btn.setContentAreaFilled(false);
		btn.setBorderPainted(false);
		btn.setFocusPainted(false);
		btn.setOpaque(false);
		return btn;
	}

	/** Wires the undo/redo squares to the plugin. */
	void initUndoRedo(Runnable undo, Runnable redo)
	{
		undoAction = undo;
		redoAction = redo;
	}

	/** Updates which of the undo/redo squares draw as available. Call on the EDT. */
	void setUndoRedoState(boolean canUndo, boolean canRedo)
	{
		this.canUndo = canUndo;
		this.canRedo = canRedo;
		undoButton.repaint();
		redoButton.repaint();
	}

	/** Restores the saved background choice and registers where future choices are persisted. */
	void initBackground(String savedName, Consumer<String> saver)
	{
		Color color = savedName != null ? BACKGROUNDS.get(savedName) : null;
		if (color != null)
		{
			setBackgroundColor(color);
		}
		backgroundSaver = saver;
	}

	void setTextureColorResolver(IntFunction<Integer> resolver)
	{
		textureColorResolver = resolver;
	}

	private void showBackgroundMenu()
	{
		JPopupMenu menu = new JPopupMenu();
		for (Map.Entry<String, Color> entry : BACKGROUNDS.entrySet())
		{
			JMenuItem item = new JMenuItem(entry.getKey());
			item.addActionListener(e ->
			{
				setBackgroundColor(entry.getValue());
				if (backgroundSaver != null)
				{
					backgroundSaver.accept(entry.getKey());
				}
			});
			menu.add(item);
		}
		menu.show(menuButton, 0, menuButton.getHeight());
	}

	/** Changes the preview backdrop. Safe to call on the EDT (menu clicks always are). */
	private void setBackgroundColor(Color color)
	{
		setBackground(color);
		renderer.setBackgroundColor(color);
		if (renderer.hasModel())
		{
			rerender();
		}
		else
		{
			repaint();
		}
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
		renderer.loadModel(model, textureColorResolver);
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
			g.setColor(getBackground());
			g.fillRect(0, 0, getWidth(), getHeight());
			g.setColor(Color.DARK_GRAY);
			String msg = "No model";
			int mx = (getWidth() - g.getFontMetrics().stringWidth(msg)) / 2;
			int my = getHeight() / 2;
			g.drawString(msg, mx, my);
		}
	}
}
