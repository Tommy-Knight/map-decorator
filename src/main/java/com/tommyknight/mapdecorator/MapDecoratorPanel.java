package com.tommyknight.mapdecorator;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JToggleButton;
import java.awt.Rectangle;
import javax.swing.Scrollable;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

class MapDecoratorPanel extends PluginPanel
{
	static final int FACING_S = 0;
	static final int FACING_W = 512;
	static final int FACING_N = 1024;
	static final int FACING_E = 1536;
	private static final int MAX_FAV_SLOTS = 28;

	private static final Color SLOT_EMPTY_FG = ColorScheme.MEDIUM_GRAY_COLOR;
	private static final Color SLOT_FILLED_FG = Color.WHITE;
	private static final Color STAR_COLOR = new Color(255, 200, 50);

	private final ClientThread clientThread;
	private MapDecoratorPlugin plugin;

	private final JSpinner objectIdSpinner;
	private final JSlider rotateSlider;
	private final ModelPreviewComponent preview = new ModelPreviewComponent();
	private final List<JButton> slotButtons = new ArrayList<>();
	// Mirrored from the Swing controls so the client thread never touches Swing state
	private volatile int selectedObjectId = 37893;
	private volatile int selectedOrientation = FACING_S;

	private final JPanel favsRows;
	private int visibleSlots = 5;
	private JButton addSlotBtn;
	private JLabel favCountLabel;

	@Inject
	MapDecoratorPanel(ClientThread clientThread)
	{
		super(false);
		this.clientThread = clientThread;

		setLayout(new BorderLayout(0, 0));
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		// ── Preview (very top) ────────────────────────────────────────────
		JLabel previewLabel = new JLabel("Preview  (right-click to drag or scroll to zoom)");
		previewLabel.setForeground(Color.WHITE);
		previewLabel.setFont(FontManager.getRunescapeSmallFont());

		JPanel previewWrapper = new JPanel(new BorderLayout(0, 4));
		previewWrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		previewWrapper.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
		previewWrapper.add(previewLabel, BorderLayout.NORTH);
		previewWrapper.add(preview, BorderLayout.CENTER);

		// ── Controls (scrollable middle) ──────────────────────────────────
		ControlsPanel controls = new ControlsPanel();
		controls.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Object ID
		JLabel idLabel = new JLabel("Object ID");
		idLabel.setForeground(Color.WHITE);
		idLabel.setFont(FontManager.getRunescapeSmallFont());

		objectIdSpinner = new JSpinner(new SpinnerNumberModel(37893, 0, Integer.MAX_VALUE, 1));
		objectIdSpinner.setPreferredSize(new Dimension(90, 24));
		objectIdSpinner.addChangeListener(e -> onObjectIdChanged());

		JPanel idRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		idRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		idRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		idRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		idRow.add(idLabel);
		idRow.add(objectIdSpinner);

		// Facing label + buttons
		JLabel facingLabel = new JLabel("Facing");
		facingLabel.setForeground(Color.WHITE);
		facingLabel.setFont(FontManager.getRunescapeSmallFont());
		facingLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel rotateLabel = new JLabel("Rotate");
		rotateLabel.setForeground(Color.WHITE);
		rotateLabel.setFont(FontManager.getRunescapeSmallFont());
		rotateLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

		int initialDeg = 180; // S = 180° when N is 0°
		rotateSlider = new JSlider(0, 360, initialDeg);
		rotateSlider.setBackground(ColorScheme.DARK_GRAY_COLOR);
		rotateSlider.setAlignmentX(Component.LEFT_ALIGNMENT);
		rotateSlider.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
		rotateSlider.addChangeListener(e ->
		{
			float deg = rotateSlider.getValue();
			selectedOrientation = (Math.round(deg / 360f * 2048f) + 1024) % 2048;
			preview.setYaw(selectedOrientation / 2048f * 2f * (float) Math.PI);
		});

		JPanel facingButtons = new JPanel(new GridLayout(1, 4, 4, 0));
		facingButtons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		facingButtons.setAlignmentX(Component.LEFT_ALIGNMENT);
		facingButtons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		ButtonGroup facingGroup = new ButtonGroup();

		for (int orientation : new int[]{FACING_N, FACING_E, FACING_S, FACING_W})
		{
			String label = orientation == FACING_N ? "N"
				: orientation == FACING_E ? "E"
				: orientation == FACING_S ? "S" : "W";
			JToggleButton btn = new JToggleButton(label);
			btn.setFocusPainted(false);
			btn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			btn.setForeground(Color.WHITE);
			btn.setFont(FontManager.getRunescapeSmallFont());
			btn.addActionListener(e ->
			{
				selectedOrientation = orientation;
				rotateSlider.setValue(Math.round((orientation - 1024 + 2048) % 2048 / 2048f * 360f));
			});
			if (orientation == FACING_S)
			{
				btn.setSelected(true);
			}
			facingGroup.add(btn);
			facingButtons.add(btn);
		}

		// Cursor ghost checkbox
		JCheckBox ghostToggle = new JCheckBox("Cursor ghost");
		ghostToggle.setForeground(Color.WHITE);
		ghostToggle.setBackground(ColorScheme.DARK_GRAY_COLOR);
		ghostToggle.setFont(FontManager.getRunescapeSmallFont());
		ghostToggle.addActionListener(e ->
		{
			if (plugin != null)
			{
				plugin.setGhostEnabled(ghostToggle.isSelected());
			}
		});

		JCheckBox hideUiChk = new JCheckBox("Hide UI");
		hideUiChk.setForeground(Color.WHITE);
		hideUiChk.setBackground(ColorScheme.DARK_GRAY_COLOR);
		hideUiChk.setFont(FontManager.getRunescapeSmallFont());
		hideUiChk.addActionListener(e ->
		{
			if (plugin != null)
			{
				plugin.setHideUiEnabled(hideUiChk.isSelected());
			}
		});

		JPanel ghostRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		ghostRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		ghostRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		ghostRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		ghostRow.add(ghostToggle);
		ghostRow.add(hideUiChk);

		// Favourites section
		JLabel favsLabel = new JLabel("Favourites");
		favsLabel.setForeground(Color.WHITE);
		favsLabel.setFont(FontManager.getRunescapeSmallFont());
		favsLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

		favsRows = new JPanel();
		favsRows.setLayout(new BoxLayout(favsRows, BoxLayout.Y_AXIS));
		favsRows.setBackground(ColorScheme.DARK_GRAY_COLOR);
		favsRows.setAlignmentX(Component.LEFT_ALIGNMENT);

		addSlotBtn = new JButton("+");
		addSlotBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		addSlotBtn.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		addSlotBtn.setFont(FontManager.getRunescapeSmallFont());
		addSlotBtn.setBorderPainted(false);
		addSlotBtn.setFocusPainted(false);
		addSlotBtn.setPreferredSize(new Dimension(24, 22));
		addSlotBtn.setMaximumSize(new Dimension(24, 22));
		addSlotBtn.addActionListener(e -> addSlot());

		favCountLabel = new JLabel("0/" + MAX_FAV_SLOTS);
		favCountLabel.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		favCountLabel.setFont(FontManager.getRunescapeSmallFont());

		JPanel addRow = new JPanel();
		addRow.setLayout(new BoxLayout(addRow, BoxLayout.X_AXIS));
		addRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		addRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		addRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		addRow.add(addSlotBtn);
		addRow.add(Box.createHorizontalGlue());
		addRow.add(favCountLabel);

		// Assemble controls panel
		controls.add(idRow);
		controls.add(Box.createVerticalStrut(8));
		controls.add(facingLabel);
		controls.add(Box.createVerticalStrut(4));
		controls.add(facingButtons);
		controls.add(Box.createVerticalStrut(8));
		controls.add(rotateLabel);
		controls.add(Box.createVerticalStrut(3));
		controls.add(rotateSlider);
		controls.add(Box.createVerticalStrut(14));
		controls.add(ghostRow);
		controls.add(Box.createVerticalStrut(14));
		controls.add(favsLabel);
		controls.add(Box.createVerticalStrut(4));
		controls.add(favsRows);
		controls.add(Box.createVerticalStrut(3));
		controls.add(addRow);
		controls.add(Box.createVerticalStrut(8));

		JScrollPane scrollPane = new JScrollPane(controls,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
			JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setBorder(null);
		scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);

		// ── Clear Area (pinned bottom) ────────────────────────────────────
		JButton clearBtn = new JButton("Clear Area");
		clearBtn.setBackground(new Color(160, 30, 30));
		clearBtn.setForeground(Color.WHITE);
		clearBtn.setFont(FontManager.getRunescapeSmallFont());
		clearBtn.setFocusPainted(false);
		clearBtn.setBorderPainted(false);
		clearBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		clearBtn.addActionListener(e ->
		{
			if (plugin == null)
			{
				return;
			}
			int result = JOptionPane.showConfirmDialog(
				this,
				"Remove all placed objects in the current area?",
				"Clear Area",
				JOptionPane.YES_NO_OPTION,
				JOptionPane.WARNING_MESSAGE
			);
			if (result == JOptionPane.YES_OPTION)
			{
				plugin.clearArea();
			}
		});

		JPanel clearPanel = new JPanel(new BorderLayout());
		clearPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		clearPanel.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		clearPanel.add(clearBtn, BorderLayout.CENTER);

		add(previewWrapper, BorderLayout.NORTH);
		add(scrollPane, BorderLayout.CENTER);
		add(clearPanel, BorderLayout.SOUTH);
	}

	void init(MapDecoratorPlugin plugin)
	{
		this.plugin = plugin;
		visibleSlots = plugin.getVisibleFavSlots();
		for (int i = 0; i < visibleSlots; i++)
		{
			addFavouriteRow(i);
		}
		addSlotBtn.setVisible(visibleSlots < MAX_FAV_SLOTS);
		refreshFavouriteButtons();
		favsRows.revalidate();
		onObjectIdChanged();
	}

	private void addFavouriteRow(int slot)
	{
		JButton nameBtn = new JButton("— empty —");
		nameBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		nameBtn.setForeground(SLOT_EMPTY_FG);
		nameBtn.setFont(FontManager.getRunescapeSmallFont());
		nameBtn.setBorderPainted(false);
		nameBtn.setFocusPainted(false);
		nameBtn.setHorizontalAlignment(SwingConstants.LEFT);
		nameBtn.addActionListener(e -> loadFavourite(slot));
		nameBtn.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					showSlotMenu(e, slot);
				}
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					showSlotMenu(e, slot);
				}
			}
		});
		slotButtons.add(nameBtn);

		JButton starBtn = new JButton("★");
		starBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		starBtn.setForeground(STAR_COLOR);
		starBtn.setFont(FontManager.getRunescapeSmallFont());
		starBtn.setBorderPainted(false);
		starBtn.setFocusPainted(false);
		starBtn.setPreferredSize(new Dimension(26, 22));
		starBtn.setMaximumSize(new Dimension(26, 22));
		starBtn.setToolTipText("Save current object to slot " + (slot + 1));
		starBtn.addActionListener(e -> saveFavourite(slot));

		JPanel row = new JPanel(new BorderLayout(3, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.add(nameBtn, BorderLayout.CENTER);
		row.add(starBtn, BorderLayout.EAST);

		favsRows.add(row);
		favsRows.add(Box.createVerticalStrut(3));
	}

	private void addSlot()
	{
		if (visibleSlots >= MAX_FAV_SLOTS || plugin == null)
		{
			return;
		}
		addFavouriteRow(visibleSlots);
		Favourite[] favs = plugin.getFavourites();
		applyFavToButton(slotButtons.get(visibleSlots), favs[visibleSlots]);
		visibleSlots++;
		plugin.setVisibleFavSlots(visibleSlots);
		if (visibleSlots >= MAX_FAV_SLOTS)
		{
			addSlotBtn.setVisible(false);
		}
		updateFavCount();
		favsRows.revalidate();
		favsRows.repaint();
	}

	int getSelectedObjectId()
	{
		return selectedObjectId;
	}

	void setObjectId(int id)
	{
		objectIdSpinner.setValue(id);
	}

	int getSelectedOrientation()
	{
		return selectedOrientation;
	}

	/** Sync the rotate slider (and thus selection + preview) to the given orientation. */
	void setOrientationControls(int orientation)
	{
		rotateSlider.setValue(Math.round((orientation - 1024 + 2048) % 2048 / 2048f * 360f));
	}

	// ── Favourite helpers ──────────────────────────────────────────────────

	private void refreshFavouriteButtons()
	{
		if (plugin == null)
		{
			return;
		}
		Favourite[] favs = plugin.getFavourites();
		for (int i = 0; i < slotButtons.size(); i++)
		{
			applyFavToButton(slotButtons.get(i), favs[i]);
		}
		updateFavCount();
	}

	private void applyFavToButton(JButton btn, Favourite fav)
	{
		if (fav == null)
		{
			btn.setText("— empty —");
			btn.setForeground(SLOT_EMPTY_FG);
		}
		else
		{
			btn.setText(fav.getName() + "  (" + fav.getObjectId() + ")");
			btn.setForeground(SLOT_FILLED_FG);
		}
	}

	private void loadFavourite(int slot)
	{
		if (plugin == null)
		{
			return;
		}
		Favourite[] favs = plugin.getFavourites();
		if (favs[slot] != null)
		{
			objectIdSpinner.setValue(favs[slot].getObjectId());
		}
	}

	private void saveFavourite(int slot)
	{
		if (plugin == null)
		{
			return;
		}
		int id = getSelectedObjectId();
		Favourite[] favs = plugin.getFavourites();
		String defaultName = favs[slot] != null ? favs[slot].getName() : "Object " + id;
		String name = JOptionPane.showInputDialog(this, "Name this favourite:", defaultName);
		if (name == null || name.trim().isEmpty())
		{
			return;
		}
		plugin.setFavourite(slot, id, name.trim());
		applyFavToButton(slotButtons.get(slot), new Favourite(id, name.trim()));
		updateFavCount();
	}

	private void showSlotMenu(MouseEvent e, int slot)
	{
		if (plugin == null)
		{
			return;
		}
		JPopupMenu menu = new JPopupMenu();
		JMenuItem remove = new JMenuItem("Remove");
		remove.addActionListener(ev -> removeSlotAndRebuild(slot));
		menu.add(remove);
		menu.show(e.getComponent(), e.getX(), e.getY());
	}

	private void removeSlotAndRebuild(int slot)
	{
		if (plugin == null)
		{
			return;
		}
		plugin.shiftFavouritesDown(slot, visibleSlots);
		favsRows.removeAll();
		slotButtons.clear();
		visibleSlots--;
		plugin.setVisibleFavSlots(visibleSlots);
		for (int i = 0; i < visibleSlots; i++)
		{
			addFavouriteRow(i);
		}
		addSlotBtn.setVisible(visibleSlots < MAX_FAV_SLOTS);
		refreshFavouriteButtons();
		favsRows.revalidate();
		favsRows.repaint();
	}

	private void updateFavCount()
	{
		if (favCountLabel == null || plugin == null)
		{
			return;
		}
		Favourite[] favs = plugin.getFavourites();
		int count = 0;
		for (Favourite f : favs)
		{
			if (f != null)
			{
				count++;
			}
		}
		favCountLabel.setText(count + "/" + MAX_FAV_SLOTS);
	}

	// ── Object ID change ───────────────────────────────────────────────────

	private void onObjectIdChanged()
	{
		selectedObjectId = (Integer) objectIdSpinner.getValue();
		if (plugin == null)
		{
			return;
		}
		int id = selectedObjectId;
		clientThread.invoke(() ->
		{
			net.runelite.api.ModelData md = plugin.loadPreviewModelData(id);
			plugin.updateGhostModel(id);
			preview.setModel(md);
		});
	}

	// ── Scrollable controls container ──────────────────────────────────────

	private static final class ControlsPanel extends JPanel implements Scrollable
	{
		ControlsPanel()
		{
			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return 64;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}
}
