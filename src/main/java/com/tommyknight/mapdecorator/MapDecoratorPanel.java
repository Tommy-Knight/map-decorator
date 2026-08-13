package com.tommyknight.mapdecorator;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import javax.inject.Inject;
import javax.swing.AbstractSpinnerModel;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFormattedTextField;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import java.awt.Rectangle;
import javax.swing.Scrollable;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.border.MatteBorder;
import javax.swing.colorchooser.AbstractColorChooserPanel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultFormatterFactory;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

class MapDecoratorPanel extends PluginPanel
{
	static final int FACING_S = 0;
	static final int FACING_W = 512;
	static final int FACING_N = 1024;
	static final int FACING_E = 1536;
	private static final int MAX_FAV_SLOTS = MapDecoratorPlugin.MAX_FAV_SLOTS;

	private static final Color SLOT_EMPTY_FG = ColorScheme.MEDIUM_GRAY_COLOR;
	private static final Color SLOT_FILLED_FG = Color.WHITE;
	private static final Color STAR_COLOR = new Color(255, 200, 50);

	private MapDecoratorPlugin plugin;

	private final JSpinner objectIdSpinner;
	private final JSpinner animationSpinner;
	private final JSpinner npcSpinner;
	private final JCheckBox roamChk;
	private final JSpinner offsetXSpinner;
	private final JSpinner offsetYSpinner;
	private final JSpinner heightSpinner;
	private final JSpinner rotationSpinner;
	private final JSpinner scaleSpinner;
	private final ButtonGroup facingGroup = new ButtonGroup();
	private final Map<Integer, JToggleButton> facingButtonsByOrientation = new HashMap<>();
	private final ModelPreviewComponent preview = new ModelPreviewComponent();
	private final List<JButton> slotButtons = new ArrayList<>();
	// Mirrored from the Swing controls so the client thread never touches Swing state
	private volatile int selectedObjectId = 1570;
	private volatile int selectedAnimationId = -1;
	private volatile int selectedNpcId = -1;
	private volatile int selectedOrientation = FACING_S;
	private volatile int selectedHeightOffset = 0;
	private volatile int selectedOffsetX = 0;
	private volatile int selectedOffsetY = 0;
	private volatile int selectedScale = 0;
	private volatile boolean selectedRoam = false;
	private volatile boolean selectedMirror = false;
	// Tint colour with alpha as strength; alpha 0 = no tint (default)
	private volatile int selectedTintArgb = 0x00C83C3C;
	// Set while the animation-selector is programmatically switching the Object box to a paired
	// model, so that sync doesn't immediately trip the "object changed" auto-clear below.
	private volatile boolean suppressAnimationClear = false;
	// Bumped on every refreshPreviewAndGhost() call; a completed async load only applies its
	// result if it's still the latest request, so rapid spinner scrolling can't paint a stale,
	// already-superseded model over whatever's actually selected now.
	private final AtomicLong previewRequestGeneration = new AtomicLong();

	// Set while a placed object is bound to the controls (right-click â†’ Edit); every control
	// change is then applied to that object live instead of just the ghost.
	private volatile boolean editing = false;
	private final JPanel editBanner;
	private final JLabel editLabel;
	private JCheckBox mirrorChk;
	private JCheckBox snapChk;
	private JCheckBox randomChk;
	private JButton tintSwatch;

	private final JPanel favsRows;
	private int visibleSlots = 5;
	private JButton addSlotBtn;
	private JLabel favCountLabel;
	private CollapsibleSection favsSection;

	private final JPanel savesRows;
	private CollapsibleSection savesSection;

	@Inject
	MapDecoratorPanel()
	{
		super(false);

		setLayout(new BorderLayout(0, 0));
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		// â”€â”€ Preview (very top) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
		JPanel previewWrapper = new JPanel(new BorderLayout(0, 4));
		previewWrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		previewWrapper.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
		preview.setToolTipText("Drag to rotate â€¢ Scroll to zoom â€¢ Right-drag to pan");
		previewWrapper.add(preview, BorderLayout.CENTER);

		// Edit banner â€” shown while a placed object is bound to the controls
		editLabel = new JLabel();
		editLabel.setForeground(STAR_COLOR);
		editLabel.setFont(FontManager.getRunescapeSmallFont());

		JButton doneBtn = new JButton("Done");
		doneBtn.setBackground(new Color(30, 130, 55));
		doneBtn.setForeground(Color.WHITE);
		doneBtn.setFont(FontManager.getRunescapeSmallFont());
		doneBtn.setFocusPainted(false);
		doneBtn.setMargin(new Insets(2, 10, 2, 10));
		doneBtn.setToolTipText("Keep these changes and finish editing");
		doneBtn.addActionListener(e ->
		{
			if (plugin != null)
			{
				plugin.endEdit();
			}
		});

		JButton cancelBtn = new JButton("Cancel");
		cancelBtn.setBackground(new Color(160, 30, 30));
		cancelBtn.setForeground(Color.WHITE);
		cancelBtn.setFont(FontManager.getRunescapeSmallFont());
		cancelBtn.setFocusPainted(false);
		cancelBtn.setMargin(new Insets(2, 10, 2, 10));
		cancelBtn.setToolTipText("Undo this edit and put the object back how it was");
		cancelBtn.addActionListener(e ->
		{
			if (plugin != null)
			{
				plugin.cancelEdit();
			}
		});

		JPanel editButtons = new JPanel(new GridLayout(1, 2, 4, 0));
		editButtons.setOpaque(false);
		editButtons.add(cancelBtn);
		editButtons.add(doneBtn);

		editBanner = new JPanel(new BorderLayout(6, 0));
		editBanner.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		editBanner.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 3));
		editBanner.add(editLabel, BorderLayout.CENTER);
		editBanner.add(editButtons, BorderLayout.EAST);
		editBanner.setVisible(false);
		previewWrapper.add(editBanner, BorderLayout.SOUTH);

		// â”€â”€ Controls (scrollable middle) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
		ControlsPanel controls = new ControlsPanel();
		controls.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Object â€” name search + arrow/typed model id entry; arrows step through every id
		// numerically (unnamed ids display as the bare number)
		objectIdSpinner = new JSpinner(new AbstractSpinnerModel()
		{
			private int value = 1570;

			@Override
			public Object getValue()
			{
				return value;
			}

			@Override
			public void setValue(Object v)
			{
				// Always fire, even for a same-value commit, so the editor re-syncs its text
				this.value = (Integer) v;
				fireStateChanged();
			}

			@Override
			public Object getNextValue()
			{
				return value + 1;
			}

			@Override
			public Object getPreviousValue()
			{
				return value > 0 ? value - 1 : null;
			}
		});
		objectIdSpinner.setPreferredSize(new Dimension(150, 24));
		JFormattedTextField idField = ((JSpinner.DefaultEditor) objectIdSpinner.getEditor()).getTextField();
		idField.setFormatterFactory(new DefaultFormatterFactory(nameIdFormatter(0, ObjectModelNames::name, ObjectModelNames::id)));
		objectIdSpinner.addChangeListener(e -> onObjectIdChanged());
		installSearchPopup(idField, objectIdSpinner, ObjectModelNames::search);
		JPanel idRow = createFormRow("Object", objectIdSpinner);

		// Animation â€” steps through sequence ids known to attach to a model, displays gameval names
		animationSpinner = new JSpinner(new CuratedIdSpinnerModel(AnimationNames.attachableIds(), -1, -1));
		animationSpinner.setPreferredSize(new Dimension(150, 24));
		JFormattedTextField animField = ((JSpinner.DefaultEditor) animationSpinner.getEditor()).getTextField();
		animField.setFormatterFactory(new DefaultFormatterFactory(nameIdFormatter(1, AnimationNames::displayName, AnimationNames::id)));
		animationSpinner.addChangeListener(e -> onAnimationChanged());
		installSearchPopup(animField, animationSpinner, AnimationNames::searchWithModel);
		JPanel animRow = createFormRow("Animation", animationSpinner);

		// NPC â€” places a living npc (merged model parts + recolors + idle animation) instead of
		// a single object model; mutually exclusive with the Object box
		npcSpinner = new JSpinner(new CuratedIdSpinnerModel(NpcNames.knownIds(), -1, -1));
		npcSpinner.setPreferredSize(new Dimension(150, 24));
		JFormattedTextField npcField = ((JSpinner.DefaultEditor) npcSpinner.getEditor()).getTextField();
		npcField.setFormatterFactory(new DefaultFormatterFactory(nameIdFormatter(1, NpcNames::name, null)));
		npcSpinner.addChangeListener(e -> onNpcChanged());
		installSearchPopup(npcField, npcSpinner, NpcNames::search);

		roamChk = new JCheckBox();
		roamChk.setToolTipText("Placed NPCs wander up to 3 tiles in the direction they face");
		roamChk.setBackground(ColorScheme.DARK_GRAY_COLOR);
		roamChk.addActionListener(e ->
		{
			selectedRoam = roamChk.isSelected();
			applyEditIfActive();
		});

		JPanel npcInput = new JPanel(new BorderLayout(2, 0));
		npcInput.setBackground(ColorScheme.DARK_GRAY_COLOR);
		npcInput.add(roamChk, BorderLayout.WEST);
		npcInput.add(npcSpinner, BorderLayout.CENTER);
		JPanel npcRow = createFormRow("NPC", npcInput);

		// Offset â€” sub-tile position nudge, so objects can sit between tiles or against an edge
		offsetXSpinner = new JSpinner(new SpinnerNumberModel(0, -64, 64, 1));
		offsetXSpinner.setPreferredSize(new Dimension(70, 24));
		offsetXSpinner.addChangeListener(e ->
		{
			selectedOffsetX = (Integer) offsetXSpinner.getValue();
			applyEditIfActive();
		});
		JPanel offsetXRow = createFormRow("Offset X", createArrowExtremeButtons(offsetXSpinner), offsetXSpinner);

		offsetYSpinner = new JSpinner(new SpinnerNumberModel(0, -64, 64, 1));
		offsetYSpinner.setPreferredSize(new Dimension(70, 24));
		offsetYSpinner.addChangeListener(e ->
		{
			selectedOffsetY = (Integer) offsetYSpinner.getValue();
			applyEditIfActive();
		});
		JPanel offsetYRow = createFormRow("Offset Y", createArrowExtremeButtons(offsetYSpinner), offsetYSpinner);

		// Height â€” vertical nudge, applied to the ghost/placed object's Z above ground
		heightSpinner = new JSpinner(new SpinnerNumberModel(0, -256, 256, 1));
		heightSpinner.setPreferredSize(new Dimension(70, 24));
		heightSpinner.addChangeListener(e ->
		{
			selectedHeightOffset = (Integer) heightSpinner.getValue();
			applyEditIfActive();
		});
		JPanel heightRow = createFormRow("Height", createArrowExtremeButtons(heightSpinner), heightSpinner);

		// Rotation â€” 0-360Â°; the NESW buttons below select themselves when it lands on a multiple of 90
		rotationSpinner = new JSpinner(new WrappingDegreeSpinnerModel(180));
		rotationSpinner.setPreferredSize(new Dimension(70, 24));
		rotationSpinner.addChangeListener(e ->
		{
			int deg = (Integer) rotationSpinner.getValue();
			selectedOrientation = degToOrientation(deg);
			preview.setYaw(selectedOrientation / 2048f * 2f * (float) Math.PI);
			updateFacingSelection(deg);
			applyEditIfActive();
		});
		JPanel rotationRow = createFormRow("Rotation", createRotationStepButtons(rotationSpinner), rotationSpinner);

		// Scale â€” size adjustment in percent; 0 = the model's normal size
		scaleSpinner = new JSpinner(new SpinnerNumberModel(0, -100, 100, 1));
		scaleSpinner.setPreferredSize(new Dimension(70, 24));
		scaleSpinner.addChangeListener(e ->
		{
			selectedScale = (Integer) scaleSpinner.getValue();
			refreshPreviewAndGhost();
			applyEditIfActive();
		});
		JPanel scaleRow = createFormRow("Scale", createArrowExtremeButtons(scaleSpinner), scaleSpinner);

		for (JSpinner spinner : new JSpinner[]{offsetXSpinner, offsetYSpinner, heightSpinner, rotationSpinner, scaleSpinner})
		{
			selectAllOnFocus(((JSpinner.DefaultEditor) spinner.getEditor()).getTextField());
		}

		setSpinnerTooltip(objectIdSpinner, "Object to place. Type a name to search, arrows browse every model id");
		setSpinnerTooltip(animationSpinner, "Looping animation. Search by the name of the thing that animates, like fire");
		setSpinnerTooltip(npcSpinner, "Place a living NPC instead of an object. Type a name to search");
		setSpinnerTooltip(offsetXSpinner, "Nudge east or west within the tile (128 is one full tile)");
		setSpinnerTooltip(offsetYSpinner, "Nudge north or south within the tile (128 is one full tile)");
		setSpinnerTooltip(heightSpinner, "Raise or lower the object off the ground");
		setSpinnerTooltip(rotationSpinner, "Facing in degrees. Arrow keys for fine control");
		setSpinnerTooltip(scaleSpinner, "Size adjustment in percent, 0 is normal size");

		// Facing buttons â€” clicking one jumps the rotation spinner to that exact heading
		JPanel facingButtons = new JPanel(new GridLayout(1, 4, 4, 0));
		facingButtons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		facingButtons.setAlignmentX(Component.LEFT_ALIGNMENT);
		facingButtons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));

		for (int orientation : new int[]{FACING_N, FACING_E, FACING_S, FACING_W})
		{
			String label = orientation == FACING_N ? "N"
				: orientation == FACING_E ? "E"
				: orientation == FACING_S ? "S" : "W";
			JToggleButton btn = new JToggleButton(label);
			btn.setToolTipText("Face " + (orientation == FACING_N ? "north"
				: orientation == FACING_E ? "east"
				: orientation == FACING_S ? "south" : "west"));
			btn.setFocusPainted(false);
			btn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			btn.setForeground(Color.WHITE);
			btn.setFont(FontManager.getRunescapeSmallFont());
			btn.addActionListener(e -> rotationSpinner.setValue(orientationToDeg(orientation)));
			if (orientation == FACING_S)
			{
				btn.setSelected(true);
			}
			facingGroup.add(btn);
			facingButtonsByOrientation.put(orientation, btn);
			facingButtons.add(btn);
		}

		// Mirror (prototype) â€” flips the model across its vertical axis
		mirrorChk = new JCheckBox("Mirror");
		mirrorChk.setToolTipText("Flip the object left-to-right (prototype)");
		mirrorChk.setForeground(Color.WHITE);
		mirrorChk.setBackground(ColorScheme.DARK_GRAY_COLOR);
		mirrorChk.setFont(FontManager.getRunescapeSmallFont());
		mirrorChk.addActionListener(e ->
		{
			selectedMirror = mirrorChk.isSelected();
			refreshPreviewAndGhost();
			applyEditIfActive();
		});

		// Tint (prototype) â€” a colour swatch; the picker's opacity is the tint strength, 0 = off
		JLabel tintLabel = new JLabel("Tint");
		tintLabel.setForeground(Color.WHITE);
		tintLabel.setFont(FontManager.getRunescapeSmallFont());

		tintSwatch = new JButton();
		tintSwatch.setFocusPainted(false);
		tintSwatch.setPreferredSize(new Dimension(30, 18));
		tintSwatch.setToolTipText("Paint the object a colour; raise the picker's opacity from zero to apply");
		applyTintSwatch(tintSwatch);
		tintSwatch.addActionListener(e ->
		{
			Color picked = openTintChooser(new Color(selectedTintArgb, true));
			if (picked != null)
			{
				selectedTintArgb = picked.getRGB();
				applyTintSwatch(tintSwatch);
				refreshPreviewAndGhost();
				applyEditIfActive();
			}
		});

		JPanel tintCell = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		tintCell.setBackground(ColorScheme.DARK_GRAY_COLOR);
		tintCell.add(tintSwatch);
		tintCell.add(tintLabel);

		// Cursor ghost / Hide UI checkboxes
		JCheckBox ghostToggle = new JCheckBox("Cursor ghost");
		ghostToggle.setToolTipText("Show a ghost of your selection on the tile under the cursor");
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
		hideUiChk.setToolTipText("Hide the resizable game interface");
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

		JCheckBox beObjectChk = new JCheckBox("Be Object");
		beObjectChk.setToolTipText("Turn yourself into the selected object. Your player is hidden and the object walks in your place");
		beObjectChk.setForeground(Color.WHITE);
		beObjectChk.setBackground(ColorScheme.DARK_GRAY_COLOR);
		beObjectChk.setFont(FontManager.getRunescapeSmallFont());
		beObjectChk.addActionListener(e ->
		{
			if (plugin != null)
			{
				plugin.setBeObjectEnabled(beObjectChk.isSelected());
			}
		});

		JCheckBox hideMenuChk = new JCheckBox("Hide Menu");
		hideMenuChk.setToolTipText("Hide the plugin's Place/Edit/Remove options from the right-click menu");
		hideMenuChk.setForeground(Color.WHITE);
		hideMenuChk.setBackground(ColorScheme.DARK_GRAY_COLOR);
		hideMenuChk.setFont(FontManager.getRunescapeSmallFont());
		hideMenuChk.addActionListener(e ->
		{
			if (plugin != null)
			{
				plugin.setMenusHidden(hideMenuChk.isSelected());
			}
		});

		// Snap â€” off by default, so placement lands exactly under the cursor instead of
		// dead-center on the tile; toggling this on restores the old tile-center behaviour.
		snapChk = new JCheckBox("Snap");
		snapChk.setToolTipText("Place dead-center on the tile instead of exactly under your cursor");
		snapChk.setForeground(Color.WHITE);
		snapChk.setBackground(ColorScheme.DARK_GRAY_COLOR);
		snapChk.setFont(FontManager.getRunescapeSmallFont());
		snapChk.addActionListener(e ->
		{
			if (plugin != null)
			{
				plugin.setSnapEnabled(snapChk.isSelected());
			}
		});

		// Random â€” randomizes rotation and mirror on every placement (offset/scale left alone
		// to avoid clipping/oversized-model issues with scatter placement).
		randomChk = new JCheckBox("Random");
		randomChk.setToolTipText("Randomize rotation and mirror on every placement, for natural-looking scatter");
		randomChk.setForeground(Color.WHITE);
		randomChk.setBackground(ColorScheme.DARK_GRAY_COLOR);
		randomChk.setFont(FontManager.getRunescapeSmallFont());
		randomChk.addActionListener(e ->
		{
			boolean enabled = randomChk.isSelected();
			if (plugin != null)
			{
				plugin.setRandomEnabled(enabled);
			}
			setGeometryControlsEnabled(!enabled);
		});

		// Left column: Tint, Cursor ghost, Be Object, Snap â€” right column: Mirror, Random, Hide UI, Hide Menu
		// Random sits under Mirror because it overrides it; the two Hide toggles pair at the bottom.
		JPanel togglesGrid = new JPanel(new GridLayout(4, 2, 6, 2));
		togglesGrid.setBackground(ColorScheme.DARK_GRAY_COLOR);
		togglesGrid.setAlignmentX(Component.LEFT_ALIGNMENT);
		togglesGrid.setMaximumSize(new Dimension(Integer.MAX_VALUE, 98));
		togglesGrid.add(tintCell);
		togglesGrid.add(mirrorChk);
		togglesGrid.add(ghostToggle);
		togglesGrid.add(randomChk);
		togglesGrid.add(beObjectChk);
		togglesGrid.add(hideUiChk);
		togglesGrid.add(snapChk);
		togglesGrid.add(hideMenuChk);

		// Favourites section
		favsRows = new JPanel();
		favsRows.setLayout(new BoxLayout(favsRows, BoxLayout.Y_AXIS));
		favsRows.setBackground(ColorScheme.DARK_GRAY_COLOR);
		favsRows.setAlignmentX(Component.LEFT_ALIGNMENT);

		addSlotBtn = new JButton("+");
		addSlotBtn.setToolTipText("Add another favourite slot");
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

		JPanel favsBody = new JPanel();
		favsBody.setLayout(new BoxLayout(favsBody, BoxLayout.Y_AXIS));
		favsBody.setBackground(ColorScheme.DARK_GRAY_COLOR);
		favsBody.setAlignmentX(Component.LEFT_ALIGNMENT);
		favsBody.add(favsRows);
		favsBody.add(Box.createVerticalStrut(3));
		favsBody.add(addRow);

		favsSection = new CollapsibleSection("Favourites",
			"Saved object recipes you can load with one click. Click to expand or collapse", favsBody);
		favsSection.setOnToggle(() ->
		{
			if (plugin != null)
			{
				plugin.setFavouritesExpanded(favsSection.isExpanded());
			}
		});

		// Saves section (named export/import codes, kept for later)
		savesRows = new JPanel();
		savesRows.setLayout(new BoxLayout(savesRows, BoxLayout.Y_AXIS));
		savesRows.setBackground(ColorScheme.DARK_GRAY_COLOR);
		savesRows.setAlignmentX(Component.LEFT_ALIGNMENT);

		savesSection = new CollapsibleSection("Layouts",
			"Named export/import codes kept for later. Click to expand or collapse", savesRows);
		savesSection.setOnToggle(() ->
		{
			if (plugin != null)
			{
				plugin.setSavesExpanded(savesSection.isExpanded());
			}
		});

		// Assemble controls panel
		controls.add(idRow);
		controls.add(Box.createVerticalStrut(4));
		controls.add(animRow);
		controls.add(Box.createVerticalStrut(4));
		controls.add(npcRow);
		controls.add(Box.createVerticalStrut(4));
		controls.add(offsetXRow);
		controls.add(Box.createVerticalStrut(4));
		controls.add(offsetYRow);
		controls.add(Box.createVerticalStrut(4));
		controls.add(heightRow);
		controls.add(Box.createVerticalStrut(4));
		controls.add(scaleRow);
		controls.add(Box.createVerticalStrut(4));
		// Rotation sits last so it's adjacent to the NESW buttons, which drive the same value
		controls.add(rotationRow);
		controls.add(Box.createVerticalStrut(4));
		controls.add(facingButtons);
		controls.add(Box.createVerticalStrut(12));
		controls.add(togglesGrid);
		controls.add(Box.createVerticalStrut(14));
		controls.add(favsSection.header);
		controls.add(Box.createVerticalStrut(4));
		controls.add(favsBody);
		controls.add(Box.createVerticalStrut(14));
		controls.add(savesSection.header);
		controls.add(Box.createVerticalStrut(4));
		controls.add(savesRows);
		controls.add(Box.createVerticalStrut(8));

		JScrollPane scrollPane = new JScrollPane(controls,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
			JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setBorder(null);
		scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);

		// â”€â”€ Share (pinned bottom, above Clear Area) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
		JButton exportBtn = new JButton("Export Nearby");
		exportBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		exportBtn.setForeground(Color.WHITE);
		exportBtn.setFont(FontManager.getRunescapeSmallFont());
		exportBtn.setFocusPainted(false);
		exportBtn.setBorderPainted(false);
		exportBtn.setToolTipText("Copy every placed object within 100 tiles to your clipboard");
		exportBtn.addActionListener(e ->
		{
			if (plugin != null)
			{
				plugin.exportNearby();
			}
		});

		JButton importBtn = new JButton("Import");
		importBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		importBtn.setForeground(Color.WHITE);
		importBtn.setFont(FontManager.getRunescapeSmallFont());
		importBtn.setFocusPainted(false);
		importBtn.setBorderPainted(false);
		importBtn.setToolTipText("Add the objects from a code copied to your clipboard");
		importBtn.addActionListener(e ->
		{
			if (plugin != null)
			{
				plugin.importFromClipboard();
			}
		});

		JPanel sharePanel = new JPanel(new GridLayout(1, 2, 4, 0));
		sharePanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		sharePanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
		sharePanel.add(exportBtn);
		sharePanel.add(importBtn);

		// â”€â”€ Clear Area (pinned bottom) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
		JButton clearBtn = new JButton("Clear Area");
		clearBtn.setToolTipText("Remove every placed object in the loaded area (undoable)");
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
		clearPanel.add(sharePanel, BorderLayout.NORTH);
		clearPanel.add(clearBtn, BorderLayout.CENTER);

		add(previewWrapper, BorderLayout.NORTH);
		add(scrollPane, BorderLayout.CENTER);
		add(clearPanel, BorderLayout.SOUTH);
	}

	private static final int TOOLTIP_DELAY_MS = 900;

	// Swing's tooltip delay is a single global setting, so it's raised only while the
	// cursor is inside this panel and restored on the way out.
	private final MouseAdapter tooltipDelayer = new MouseAdapter()
	{
		private int savedDelay = -1;

		@Override
		public void mouseEntered(MouseEvent e)
		{
			if (savedDelay < 0)
			{
				savedDelay = ToolTipManager.sharedInstance().getInitialDelay();
			}
			ToolTipManager.sharedInstance().setInitialDelay(TOOLTIP_DELAY_MS);
		}

		@Override
		public void mouseExited(MouseEvent e)
		{
			if (savedDelay >= 0)
			{
				ToolTipManager.sharedInstance().setInitialDelay(savedDelay);
			}
		}
	};

	private void applyTooltipDelay(Component c)
	{
		c.addMouseListener(tooltipDelayer);
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				applyTooltipDelay(child);
			}
		}
	}

	/** Paints the swatch its current colour, or a muted "off" look when the tint is fully transparent. */
	private void applyTintSwatch(JButton swatch)
	{
		if (((selectedTintArgb >>> 24) & 0xFF) > 0)
		{
			swatch.setBackground(new Color(selectedTintArgb & 0xFFFFFF));
			swatch.setText("");
		}
		else
		{
			swatch.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			swatch.setText("â€“");
		}
	}

	/** A colour picker whose opacity slider is the tint strength (0 = no tint). Returns null on cancel. */
	private Color openTintChooser(Color initial)
	{
		JColorChooser chooser = new JColorChooser(new Color(initial.getRed(), initial.getGreen(), initial.getBlue()));
		keepOnlyHsvPanel(chooser);
		JSlider alpha = new JSlider(0, 255, initial.getAlpha());
		JLabel alphaVal = new JLabel();
		Runnable update = () -> alphaVal.setText("Opacity " + (alpha.getValue() * 100 / 255) + "%   ");
		update.run();
		alpha.addChangeListener(ev -> update.run());

		JPanel alphaPanel = new JPanel(new BorderLayout(6, 0));
		alphaPanel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		alphaPanel.add(alphaVal, BorderLayout.WEST);
		alphaPanel.add(alpha, BorderLayout.CENTER);
		chooser.setPreviewPanel(alphaPanel);

		Color[] result = {null};
		JDialog dialog = JColorChooser.createDialog(this, "Tint colour", true, chooser,
			ev ->
			{
				Color c = chooser.getColor();
				result[0] = new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha.getValue());
			},
			null);
		dialog.setVisible(true);
		return result[0];
	}

	/**
	 * Strips the colour chooser down to its HSV tab. Swatches/RGB/CMYK are noise for picking a
	 * tint â€” hue and saturation are what actually read as "paint it this colour", and opacity
	 * (the tint strength) lives on our own slider in the preview panel instead.
	 * <p>
	 * Tab names are localised, so if no HSV panel is found this leaves the chooser untouched
	 * rather than stripping it down to nothing.
	 */
	private static void keepOnlyHsvPanel(JColorChooser chooser)
	{
		for (AbstractColorChooserPanel p : chooser.getChooserPanels())
		{
			if ("HSV".equalsIgnoreCase(p.getDisplayName()))
			{
				chooser.setChooserPanels(new AbstractColorChooserPanel[]{p});
				return;
			}
		}
	}

	/** Tooltip on the spinner, its arrows, and its text field alike. */
	private static void setSpinnerTooltip(JSpinner spinner, String tip)
	{
		spinner.setToolTipText(tip);
		((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setToolTipText(tip);
	}

	/**
	 * Clicking into the field highlights all its text, so typing immediately replaces the value
	 * (instead of needing a triple-click). A second click still places the caret normally.
	 */
	private static void selectAllOnFocus(JFormattedTextField field)
	{
		field.addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusGained(FocusEvent e)
			{
				// invokeLater: the formatted field re-sets its own text on focus gain,
				// which would immediately clear a plain selectAll
				SwingUtilities.invokeLater(field::selectAll);
			}
		});
	}

	/**
	 * Formatter for the searchable id boxes. Displays "Name (id)", the bare number when
	 * unnamed, or "none" below {@code minValid}; parses the "(id)" suffix back out first so
	 * the display always round-trips to the exact same id (BasicSpinnerUI commits the
	 * displayed text before every arrow step, and names are shared by many ids, so a lossy
	 * round-trip would snap the value back to the canonical id). Also accepts a typed bare
	 * number, "none", or a full name via {@code idByName} (may be null).
	 */
	private static JFormattedTextField.AbstractFormatter nameIdFormatter(
		int minValid, IntFunction<String> displayName, Function<String, Integer> idByName)
	{
		return new JFormattedTextField.AbstractFormatter()
		{
			@Override
			public Object stringToValue(String text) throws ParseException
			{
				String t = text.trim();
				if (t.isEmpty() || t.equalsIgnoreCase("none"))
				{
					return -1;
				}
				Integer suffixId = trailingId(t);
				if (suffixId != null)
				{
					return suffixId;
				}
				try
				{
					return Integer.parseInt(t.replace(",", ""));
				}
				catch (NumberFormatException ignored)
				{
				}
				Integer id = idByName != null ? idByName.apply(t) : null;
				if (id != null)
				{
					return id;
				}
				throw new ParseException(t, 0);
			}

			@Override
			public String valueToString(Object value)
			{
				int id = value == null ? -1 : (Integer) value;
				if (id < minValid)
				{
					return "none";
				}
				String name = displayName.apply(id);
				return name != null ? name + " (" + id + ")" : String.valueOf(id);
			}
		};
	}

	/** A label-left, input-right form row, used for every numeric/text control in the panel. */
	private JPanel createFormRow(String labelText, Component input)
	{
		JLabel label = new JLabel(labelText);
		label.setForeground(Color.WHITE);
		label.setFont(FontManager.getRunescapeSmallFont());

		JPanel row = new JPanel(new BorderLayout());
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		row.add(label, BorderLayout.WEST);
		row.add(input, BorderLayout.EAST);
		return row;
	}

	/**
	 * Same as {@link #createFormRow(String, Component)}, but with a min/max quick-jump control
	 * placed immediately to the left of the spinner instead of leaving that space empty.
	 * A/B comparison scaffold â€” each of the five rows using this overload currently wires a
	 * different button style so they can be compared live before settling on one.
	 */
	private JPanel createFormRow(String labelText, JComponent leading, JSpinner spinner)
	{
		JLabel label = new JLabel(labelText);
		label.setForeground(Color.WHITE);
		label.setFont(FontManager.getRunescapeSmallFont());

		JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		east.setBackground(ColorScheme.DARK_GRAY_COLOR);
		east.add(leading);
		east.add(spinner);

		JPanel row = new JPanel(new BorderLayout());
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		row.add(label, BorderLayout.WEST);
		row.add(east, BorderLayout.EAST);
		return row;
	}

	/** A small bordered button with the given text, styled to match the panel's other small buttons. */
	/**
	 * A small translucent square with a bold double-chevron, for "jump to min/max" (not a Â±1 nudge).
	 * left=true points left/toward the minimum ("&lt;&lt;"), left=false points right/toward the
	 * maximum ("&gt;&gt;") â€” each chevron's vertex sits closer to that edge of the button, with its
	 * two open ends trailing away from it.
	 */
	private JButton doubleArrowButton(boolean left, Runnable action)
	{
		JButton btn = new JButton()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				Graphics2D g2 = (Graphics2D) g;
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(ColorScheme.DARKER_GRAY_COLOR);
				g2.fillRect(0, 0, getWidth(), getHeight());
				g2.setColor(Color.WHITE);
				g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				int cy = getHeight() / 2;
				int dir = left ? 1 : -1;
				int[] vertices = left ? new int[]{5, 10} : new int[]{getWidth() - 5, getWidth() - 10};
				for (int vx : vertices)
				{
					int openX = vx + 4 * dir;
					g2.drawLine(vx, cy, openX, cy - 4);
					g2.drawLine(vx, cy, openX, cy + 4);
				}
			}
		};
		btn.setPreferredSize(new Dimension(20, 20));
		btn.setContentAreaFilled(false);
		btn.setBorderPainted(false);
		btn.setFocusPainted(false);
		btn.setFocusable(false);
		btn.addActionListener(e -> action.run());
		return btn;
	}

	/**
	 * Two double-arrow buttons that jump a spinner to its min/max, placed left of the spinner.
	 * Clicking again from an extreme returns the field to 0, so each button doubles as a
	 * per-field reset without a third button competing for the row's space.
	 */
	private JPanel createArrowExtremeButtons(JSpinner spinner)
	{
		SpinnerNumberModel model = (SpinnerNumberModel) spinner.getModel();
		JButton min = doubleArrowButton(true, () -> toggleExtreme(spinner, model.getMinimum()));
		min.setToolTipText("Jump to the minimum, or back to 0 if already there");
		JButton max = doubleArrowButton(false, () -> toggleExtreme(spinner, model.getMaximum()));
		max.setToolTipText("Jump to the maximum, or back to 0 if already there");
		return arrowButtonRow(min, max);
	}

	/** Sets the spinner to {@code extreme}, or back to 0 when it is already sitting there. */
	private static void toggleExtreme(JSpinner spinner, Comparable<?> extreme)
	{
		Object target = extreme.equals(spinner.getValue()) ? Integer.valueOf(0) : extreme;
		spinner.setValue(target);
	}

	/**
	 * Rotation's pair steps 90 degrees per click rather than jumping to an extreme: 0 and 360 are
	 * the same heading, so min/max would both just mean "north". Stepping is relative to the
	 * current angle, which complements the absolute N/E/S/W buttons instead of duplicating them.
	 */
	private JPanel createRotationStepButtons(JSpinner spinner)
	{
		JButton ccw = doubleArrowButton(true, () -> stepDegrees(spinner, -90));
		ccw.setToolTipText("Turn 90 degrees anticlockwise");
		JButton cw = doubleArrowButton(false, () -> stepDegrees(spinner, 90));
		cw.setToolTipText("Turn 90 degrees clockwise");
		return arrowButtonRow(ccw, cw);
	}

	/** Turns by {@code delta} degrees, wrapping through 0/360 in either direction. */
	private static void stepDegrees(JSpinner spinner, int delta)
	{
		int deg = ((Integer) spinner.getValue() + delta) % 360;
		spinner.setValue(deg < 0 ? deg + 360 : deg);
	}

	private static JPanel arrowButtonRow(JButton left, JButton right)
	{
		JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.add(left);
		panel.add(right);
		return panel;
	}

	/** Extracts the id from our "Name (id)" display format, or null if the text isn't in that shape. */
	private static Integer trailingId(String text)
	{
		int open = text.lastIndexOf('(');
		if (open >= 0 && text.endsWith(")"))
		{
			try
			{
				return Integer.parseInt(text.substring(open + 1, text.length() - 1).trim());
			}
			catch (NumberFormatException ignored)
			{
			}
		}
		return null;
	}

	private static int degToOrientation(int deg)
	{
		return (Math.round(deg / 360f * 2048f) + 1024) % 2048;
	}

	private static int orientationToDeg(int orientation)
	{
		return Math.round((orientation - 1024 + 2048) % 2048 / 2048f * 360f);
	}

	/** Selects the NESW button for whichever 90Â° quadrant deg falls in (0-89=N, 90-179=E, 180-269=S, 270-359=W). */
	private void updateFacingSelection(int deg)
	{
		int zone = (deg % 360) / 90;
		int orientation = zone == 0 ? FACING_N : zone == 1 ? FACING_E : zone == 2 ? FACING_S : FACING_W;
		facingButtonsByOrientation.get(orientation).setSelected(true);
	}

	/** Greys out the facing/rotation/mirror controls while Random is on, since it overrides all three per-placement. */
	private void setGeometryControlsEnabled(boolean enabled)
	{
		for (JToggleButton btn : facingButtonsByOrientation.values())
		{
			btn.setEnabled(enabled);
		}
		rotationSpinner.setEnabled(enabled);
		mirrorChk.setEnabled(enabled);
	}

	void init(MapDecoratorPlugin plugin)
	{
		this.plugin = plugin;
		preview.initBackground(plugin.getPreviewBackground(), plugin::setPreviewBackground);
		preview.setTextureColorResolver(plugin::textureAverageRgb);
		preview.initUndoRedo(plugin::undo, plugin::redo);
		preview.initClear(this::clearGeometry);
		visibleSlots = plugin.getVisibleFavSlots();
		for (int i = 0; i < visibleSlots; i++)
		{
			addFavouriteRow(i);
		}
		addSlotBtn.setVisible(visibleSlots < MAX_FAV_SLOTS);
		refreshFavouriteButtons();
		favsRows.revalidate();
		favsSection.setExpanded(plugin.getFavouritesExpanded());
		savesSection.setExpanded(plugin.getSavesExpanded());
		refreshSavesRows();
		snapChk.setSelected(plugin.getSnapEnabled());
		boolean randomEnabled = plugin.getRandomEnabled();
		randomChk.setSelected(randomEnabled);
		setGeometryControlsEnabled(!randomEnabled);
		applyTooltipDelay(this);
		onObjectIdChanged();
	}

	private void addFavouriteRow(int slot)
	{
		JButton nameBtn = new JButton("â€” empty â€”");
		nameBtn.setToolTipText("Left-click to load this favourite, right-click to remove the slot");
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

		JButton starBtn = new JButton("â˜…");
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
		Favourite[] favs = plugin.getFavourites(visibleSlots + 1);
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

	int getSelectedAnimationId()
	{
		return selectedAnimationId;
	}

	void setAnimationId(int id)
	{
		animationSpinner.setValue(id <= 0 ? -1 : id);
	}

	int getSelectedNpcId()
	{
		return selectedNpcId;
	}

	void setNpcId(int id)
	{
		npcSpinner.setValue(id <= 0 ? -1 : id);
	}

	/** Sync the rotation input (and thus selection + preview + NESW selection) to the given orientation. */
	void setOrientationControls(int orientation)
	{
		rotationSpinner.setValue(orientationToDeg(orientation));
	}

	int getSelectedHeightOffset()
	{
		return selectedHeightOffset;
	}

	void setHeightOffset(int heightOffset)
	{
		heightSpinner.setValue(heightOffset);
	}

	int getSelectedOffsetX()
	{
		return selectedOffsetX;
	}

	int getSelectedOffsetY()
	{
		return selectedOffsetY;
	}

	void setOffset(int offsetX, int offsetY)
	{
		offsetXSpinner.setValue(offsetX);
		offsetYSpinner.setValue(offsetY);
	}

	int getSelectedScale()
	{
		return selectedScale;
	}

	void setScale(int scale)
	{
		scaleSpinner.setValue(Math.max(-100, Math.min(100, scale)));
	}

	boolean getSelectedMirror()
	{
		return selectedMirror;
	}

	void setMirror(boolean mirror)
	{
		selectedMirror = mirror;
		mirrorChk.setSelected(mirror);
	}

	int getSelectedTintArgb()
	{
		return selectedTintArgb;
	}

	void setTintArgb(int argb)
	{
		selectedTintArgb = argb;
		applyTintSwatch(tintSwatch);
	}

	boolean getSelectedRoam()
	{
		return selectedRoam;
	}

	void setRoam(boolean roam)
	{
		selectedRoam = roam;
		roamChk.setSelected(roam);
	}

	/** The panel's entire current selection, for selection-level undo. */
	PanelSelection snapshotSelection()
	{
		return new PanelSelection(selectedObjectId, selectedNpcId, selectedAnimationId, selectedOrientation,
			selectedHeightOffset, selectedOffsetX, selectedOffsetY, selectedScale, selectedRoam,
			selectedMirror, selectedTintArgb);
	}

	/** Restores a selection snapshot, same ordering rules as favourite/edit loads. */
	void applySelection(PanelSelection sel)
	{
		objectIdSpinner.setValue(sel.getObjectId());
		setNpcId(sel.getNpcId());
		if (sel.getNpcId() <= 0)
		{
			setAnimationId(sel.getAnimationId());
		}
		setOrientationControls(sel.getOrientation());
		setHeightOffset(sel.getHeightOffset());
		setOffset(sel.getOffsetX(), sel.getOffsetY());
		setScale(sel.getScale());
		setRoam(sel.isRoam());
		setMirror(sel.isMirror());
		setTintArgb(sel.getTintArgb());
		refreshPreviewAndGhost();
	}

	/** Resets offset, scale, rotation, height, tint and mirror back to defaults; leaves the object/NPC/animation alone. */
	private void clearGeometry()
	{
		PanelSelection before = snapshotSelection();
		offsetXSpinner.setValue(0);
		offsetYSpinner.setValue(0);
		heightSpinner.setValue(0);
		rotationSpinner.setValue(0);
		scaleSpinner.setValue(0);
		setMirror(false);
		setTintArgb(0x00C83C3C);
		refreshPreviewAndGhost();
		applyEditIfActive();
		PanelSelection after = snapshotSelection();
		if (plugin != null && !after.equals(before))
		{
			plugin.recordSelectionAction(before, after);
		}
	}

	// â”€â”€ Favourite helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

	private void refreshFavouriteButtons()
	{
		if (plugin == null)
		{
			return;
		}
		Favourite[] favs = plugin.getFavourites(slotButtons.size());
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
			btn.setText("â€” empty â€”");
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
		Favourite[] favs = plugin.getFavourites(slot + 1);
		Favourite fav = favs[slot];
		if (fav != null)
		{
			// Snapshot first so a misclicked favourite can be undone back to whatever
			// selection was loaded before it
			PanelSelection before = snapshotSelection();
			applySelection(new PanelSelection(fav.getObjectId(), fav.getNpcId(), fav.getAnimationId(),
				fav.getOrientation(), fav.getHeightOffset(), fav.getOffsetX(), fav.getOffsetY(),
				fav.getScale(), fav.isRoam(), fav.isMirror(), fav.getTintArgb()));
			PanelSelection after = snapshotSelection();
			if (!after.equals(before))
			{
				plugin.recordSelectionAction(before, after);
			}
		}
	}

	private void saveFavourite(int slot)
	{
		if (plugin == null)
		{
			return;
		}
		int id = getSelectedObjectId();
		Favourite[] favs = plugin.getFavourites(slot + 1);
		String defaultName;
		if (favs[slot] != null)
		{
			defaultName = favs[slot].getName();
		}
		else if (selectedNpcId > 0 && NpcNames.name(selectedNpcId) != null)
		{
			defaultName = NpcNames.name(selectedNpcId);
		}
		else
		{
			String objName = ObjectModelNames.name(id);
			defaultName = objName != null ? objName : "Object " + id;
		}
		String name = JOptionPane.showInputDialog(this, "Name this favourite:", defaultName);
		if (name == null || name.trim().isEmpty())
		{
			return;
		}
		// The full recipe, so a tuned decoration (height, nudge, size and all) is one click away
		Favourite fav = new Favourite(id, name.trim(), selectedAnimationId, selectedNpcId,
			selectedOrientation, selectedHeightOffset, selectedOffsetX, selectedOffsetY, selectedScale, selectedRoam,
			selectedMirror, selectedTintArgb);
		plugin.setFavourite(slot, fav);
		applyFavToButton(slotButtons.get(slot), fav);
		updateFavCount();
	}

	private void showSlotMenu(MouseEvent e, int slot)
	{
		if (plugin == null)
		{
			return;
		}
		JPopupMenu menu = new JPopupMenu();
		JMenuItem rename = new JMenuItem("Rename");
		rename.addActionListener(ev -> renameFavourite(slot));
		JMenuItem remove = new JMenuItem("Remove");
		remove.addActionListener(ev -> removeSlotAndRebuild(slot));
		menu.add(rename);
		menu.add(remove);
		menu.show(e.getComponent(), e.getX(), e.getY());
	}

	private void renameFavourite(int slot)
	{
		if (plugin == null)
		{
			return;
		}
		Favourite[] favs = plugin.getFavourites(slot + 1);
		Favourite fav = favs[slot];
		if (fav == null)
		{
			return;
		}
		String name = JOptionPane.showInputDialog(this, "Rename this favourite:", fav.getName());
		if (name == null || name.trim().isEmpty())
		{
			return;
		}
		fav.setName(name.trim());
		plugin.setFavourite(slot, fav);
		applyFavToButton(slotButtons.get(slot), fav);
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
		Favourite[] favs = plugin.getFavourites(visibleSlots);
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

	// â”€â”€ Edit mode (a placed object bound to the controls) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

	/** Call after the edited object's properties are loaded into the controls (loads don't count as edits). */
	void enterEditMode()
	{
		editing = true;
		updateEditLabel();
		editBanner.setVisible(true);
		revalidate();
		repaint();
	}

	void exitEditMode()
	{
		editing = false;
		editBanner.setVisible(false);
		revalidate();
		repaint();
	}

	private void updateEditLabel()
	{
		String name = selectedNpcId > 0 ? NpcNames.name(selectedNpcId) : ObjectModelNames.name(selectedObjectId);
		int id = selectedNpcId > 0 ? selectedNpcId : selectedObjectId;
		editLabel.setText("Editing: " + (name != null ? name : "#" + id));
	}

	private void applyEditIfActive()
	{
		if (editing && plugin != null)
		{
			plugin.applyEditFromPanel();
		}
	}

	/** Forwards undo/redo availability to the preview's overlay squares. Call on the EDT. */
	void setUndoRedoState(boolean canUndo, boolean canRedo)
	{
		preview.setUndoRedoState(canUndo, canRedo);
	}

	// â”€â”€ Object / Animation change â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

	private void onNpcChanged()
	{
		selectedNpcId = (Integer) npcSpinner.getValue();
		if (selectedNpcId > 0)
		{
			// The npc replaces both: it brings its own idle animation, and the Object box
			// goes to "none" so it's obvious the npc is what will be placed.
			suppressAnimationClear = true;
			if (selectedAnimationId > 0)
			{
				animationSpinner.setValue(-1);
			}
			if (selectedObjectId >= 0)
			{
				objectIdSpinner.setValue(-1);
			}
			suppressAnimationClear = false;
		}
		refreshPreviewAndGhost();
		if (editing)
		{
			updateEditLabel();
		}
		applyEditIfActive();
	}

	private void onAnimationChanged()
	{
		selectedAnimationId = (Integer) animationSpinner.getValue();
		if (selectedAnimationId > 0 && selectedNpcId > 0)
		{
			// Animations belong to the Object box's world â€” picking one deselects the npc
			suppressAnimationClear = true;
			npcSpinner.setValue(-1);
			suppressAnimationClear = false;
		}
		Integer modelId = selectedAnimationId > 0 ? AnimationNames.modelFor(selectedAnimationId) : null;
		if (modelId != null && !modelId.equals(selectedObjectId))
		{
			// Switch to the model this animation is known to belong to (e.g. a fire's flame model).
			// Rebuilds the ghost with the new model, which already picks up this animation.
			suppressAnimationClear = true;
			objectIdSpinner.setValue(modelId);
			suppressAnimationClear = false;
			return;
		}
		if (plugin != null)
		{
			plugin.updateGhostAnimation(selectedAnimationId);
		}
		applyEditIfActive();
	}

	private void onObjectIdChanged()
	{
		selectedObjectId = (Integer) objectIdSpinner.getValue();
		// Manually picking a different object no longer matches whatever animation or npc was
		// selected (unless this change came from the animation auto-pairing itself, above).
		if (!suppressAnimationClear && selectedAnimationId > 0)
		{
			animationSpinner.setValue(-1);
		}
		if (!suppressAnimationClear && selectedNpcId > 0)
		{
			npcSpinner.setValue(-1);
		}
		refreshPreviewAndGhost();
		if (editing)
		{
			updateEditLabel();
		}
		applyEditIfActive();
	}

	/**
	 * Wires a name-search dropdown onto a spinner's text field: type part of a name to see every match.
	 * Highlighting an entry â€” by mouse hover or arrow keys â€” immediately sets {@code spinner}'s value,
	 * so the 3D preview, the cursor ghost, and what a Place puts down are always the same thing.
	 */
	private void installSearchPopup(JFormattedTextField field, JSpinner spinner,
		BiFunction<String, Integer, List<Map.Entry<Integer, String>>> search)
	{
		// JSpinner.DefaultEditor (used because these spinners have custom models, unlike
		// SpinnerNumberModel's NumberEditor) constructs its text field with setEditable(false).
		// Without this line nothing can ever be typed into the box.
		field.setEditable(true);

		JPopupMenu popup = new JPopupMenu();
		popup.setFocusable(false);
		DefaultListModel<Map.Entry<Integer, String>> model = new DefaultListModel<>();
		JList<Map.Entry<Integer, String>> list = new JList<>(model);
		list.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		list.setFocusable(false);
		list.setFixedCellWidth(220);
		list.setFixedCellHeight(20);
		list.setCellRenderer((l, entry, index, isSelected, hasFocus) ->
		{
			JLabel label = new JLabel(entry.getValue() + "  (" + entry.getKey() + ")");
			label.setOpaque(true);
			label.setFont(FontManager.getRunescapeSmallFont());
			label.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
			label.setForeground(isSelected ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
			label.setBackground(isSelected ? ColorScheme.MEDIUM_GRAY_COLOR : ColorScheme.DARKER_GRAY_COLOR);
			return label;
		});
		JScrollPane scroll = new JScrollPane(list,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		scroll.setFocusable(false);
		scroll.getViewport().setFocusable(false);
		popup.add(scroll);

		MouseAdapter mouse = new MouseAdapter()
		{
			@Override
			public void mouseMoved(MouseEvent e)
			{
				int index = list.locationToIndex(e.getPoint());
				if (index < 0 || index == list.getSelectedIndex())
				{
					return;
				}
				list.setSelectedIndex(index);
				spinner.setValue(model.getElementAt(index).getKey());
			}

			@Override
			public void mouseClicked(MouseEvent e)
			{
				int index = list.locationToIndex(e.getPoint());
				if (index < 0)
				{
					return;
				}
				popup.setVisible(false);
				spinner.setValue(model.getElementAt(index).getKey());
			}
		};
		list.addMouseListener(mouse);
		list.addMouseMotionListener(mouse);

		// A Document mutation fires identically whether it came from a real keystroke or from the
		// spinner rewriting the field's text after an arrow-click/setValue (e.g. cycling to a value
		// that happens to have a name). Only the former should ever open a search popup â€” otherwise
		// arrow-cycling through named values spuriously reopens/steals the box. keyPressed on a
		// character, backspace, or delete key marks the *next* document change as real typing;
		// anything else (programmatic updates) leaves the flag unset and gets ignored below.
		boolean[] realKeystroke = {false};
		field.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyPressed(KeyEvent e)
			{
				int code = e.getKeyCode();
				if (popup.isVisible())
				{
					if (code == KeyEvent.VK_DOWN || code == KeyEvent.VK_UP)
					{
						int size = model.getSize();
						if (size > 0)
						{
							int delta = code == KeyEvent.VK_DOWN ? 1 : -1;
							int next = Math.max(0, Math.min(size - 1, list.getSelectedIndex() + delta));
							list.setSelectedIndex(next);
							list.ensureIndexIsVisible(next);
							// Commit, don't just preview: whatever the ghost/preview shows while
							// arrowing must be exactly what a shift-right-click Place puts down.
							spinner.setValue(model.getElementAt(next).getKey());
						}
						e.consume();
						return;
					}
					if (code == KeyEvent.VK_ENTER)
					{
						// Nothing highlighted yet (typed but never arrowed/hovered) â€” take the top result
						int idx = Math.max(0, list.getSelectedIndex());
						if (model.getSize() > 0)
						{
							popup.setVisible(false);
							spinner.setValue(model.getElementAt(idx).getKey());
						}
						e.consume();
						return;
					}
					if (code == KeyEvent.VK_ESCAPE)
					{
						popup.setVisible(false);
						e.consume();
						return;
					}
				}

				char c = e.getKeyChar();
				if ((c != KeyEvent.CHAR_UNDEFINED && !Character.isISOControl(c))
					|| code == KeyEvent.VK_BACK_SPACE
					|| code == KeyEvent.VK_DELETE)
				{
					realKeystroke[0] = true;
				}
			}
		});

		// Clicking away (e.g. into the game world to place) closes the popup.
		field.addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusLost(FocusEvent e)
			{
				popup.setVisible(false);
			}
		});
		selectAllOnFocus(field);

		field.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				refresh();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				refresh();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				refresh();
			}

			private void refresh()
			{
				if (!realKeystroke[0])
				{
					return;
				}
				// One keystroke can raise two document events (a selection-replace is a remove
				// then an insert), so clear the flag only after this whole EDT event finishes.
				SwingUtilities.invokeLater(() -> realKeystroke[0] = false);

				String text = field.getText() == null ? "" : field.getText().trim();
				if (text.length() < 2 || text.matches("[0-9,]+"))
				{
					popup.setVisible(false);
					return;
				}
				List<Map.Entry<Integer, String>> results = search.apply(text, 500);
				if (results.isEmpty())
				{
					popup.setVisible(false);
					return;
				}
				model.clear();
				for (Map.Entry<Integer, String> entry : results)
				{
					model.addElement(entry);
				}
				list.setVisibleRowCount(Math.min(results.size(), 14));
				popup.pack();
				if (!popup.isVisible())
				{
					popup.show(field, 0, field.getHeight());
				}
			}
		});
	}

	/** Rebuilds the preview and cursor ghost for the current object id. */
	private void refreshPreviewAndGhost()
	{
		if (plugin == null)
		{
			return;
		}
		int id = selectedObjectId;
		int npcId = selectedNpcId;
		int scale = selectedScale;
		boolean mirror = selectedMirror;
		int tint = selectedTintArgb;
		long generation = previewRequestGeneration.incrementAndGet();
		// Retries while the model is still loading (a first-visit id usually isn't in the game's
		// cache yet), and drops the request entirely once a newer selection supersedes it â€” so
		// scrolling through ids can neither blank out on a cold load nor paint a stale model.
		// Loaded once and reused for the preview, cursor ghost, and Be Object stand-in â€” each is
		// a merge+scale+tint pass, not worth repeating three times per change.
		plugin.withDecorationModel(id, npcId, scale, mirror, tint,
			() -> previewRequestGeneration.get() != generation,
			md ->
			{
				preview.setModel(md);
				plugin.applyGhostModel(md, npcId);
				plugin.applyBeObjectModel(md, npcId);
			});
	}

	// â”€â”€ Saves (named export/import codes) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

	/** After an export/import completes, offers to keep this code as a named save for later. */
	void promptSaveLayout(String code, String defaultName)
	{
		JTextField nameField = new JTextField(defaultName);
		JPanel message = new JPanel(new BorderLayout(0, 6));
		message.add(new JLabel("Save this layout for later?"), BorderLayout.NORTH);
		message.add(nameField, BorderLayout.CENTER);

		int result = JOptionPane.showOptionDialog(this, message, "Save layout",
			JOptionPane.YES_NO_OPTION, JOptionPane.PLAIN_MESSAGE, null,
			new Object[]{"Save", "Don't Save"}, "Don't Save");
		if (result != 0)
		{
			return;
		}
		String name = nameField.getText().trim();
		if (name.isEmpty() || plugin == null)
		{
			return;
		}
		if (!plugin.addSavedLayout(name, code))
		{
			JOptionPane.showMessageDialog(this, "You've hit the save limit â€” remove one first.");
			return;
		}
		refreshSavesRows();
	}

	private void refreshSavesRows()
	{
		savesRows.removeAll();
		if (plugin != null)
		{
			List<SavedLayout> layouts = plugin.getSavedLayouts();
			if (layouts.isEmpty())
			{
				JLabel empty = new JLabel("â€” no saved layouts yet â€”");
				empty.setForeground(SLOT_EMPTY_FG);
				empty.setFont(FontManager.getRunescapeSmallFont());
				empty.setAlignmentX(Component.LEFT_ALIGNMENT);
				savesRows.add(empty);
			}
			else
			{
				for (int i = 0; i < layouts.size(); i++)
				{
					savesRows.add(createSaveRow(i, layouts.get(i)));
					savesRows.add(Box.createVerticalStrut(3));
				}
			}
		}
		savesRows.revalidate();
		savesRows.repaint();
	}

	private JButton createSaveRow(int index, SavedLayout layout)
	{
		JButton btn = new JButton(layout.getName());
		btn.setToolTipText("Left-click for options, right-click to rename or remove");
		btn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		btn.setForeground(SLOT_FILLED_FG);
		btn.setFont(FontManager.getRunescapeSmallFont());
		btn.setBorderPainted(false);
		btn.setFocusPainted(false);
		btn.setHorizontalAlignment(SwingConstants.LEFT);
		btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		btn.setAlignmentX(Component.LEFT_ALIGNMENT);
		btn.addActionListener(e -> showSaveActionMenu(btn, index));
		btn.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					showSaveManageMenu(e, index);
				}
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					showSaveManageMenu(e, index);
				}
			}
		});
		return btn;
	}

	/** Left-click menu: act on this saved code right now. */
	private void showSaveActionMenu(Component invoker, int index)
	{
		if (plugin == null)
		{
			return;
		}
		List<SavedLayout> layouts = plugin.getSavedLayouts();
		if (index >= layouts.size())
		{
			return;
		}
		SavedLayout layout = layouts.get(index);
		JPopupMenu menu = new JPopupMenu();
		JMenuItem copy = new JMenuItem("Copy to clipboard");
		copy.addActionListener(e ->
		{
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(layout.getCode()), null);
			JOptionPane.showMessageDialog(this, "Copied to your clipboard.");
		});
		JMenuItem importNow = new JMenuItem("Import into game now");
		importNow.addActionListener(e -> plugin.importCode(layout.getCode()));
		menu.add(copy);
		menu.add(importNow);
		menu.show(invoker, 0, invoker.getHeight());
	}

	/** Right-click menu: manage the saved entry itself. */
	private void showSaveManageMenu(MouseEvent e, int index)
	{
		if (plugin == null)
		{
			return;
		}
		JPopupMenu menu = new JPopupMenu();
		JMenuItem rename = new JMenuItem("Rename");
		rename.addActionListener(ev -> renameSave(index));
		JMenuItem remove = new JMenuItem("Remove");
		remove.addActionListener(ev ->
		{
			plugin.removeSavedLayout(index);
			refreshSavesRows();
		});
		menu.add(rename);
		menu.add(remove);
		menu.show(e.getComponent(), e.getX(), e.getY());
	}

	private void renameSave(int index)
	{
		List<SavedLayout> layouts = plugin.getSavedLayouts();
		if (index >= layouts.size())
		{
			return;
		}
		String name = JOptionPane.showInputDialog(this, "Rename this layout:", layouts.get(index).getName());
		if (name == null || name.trim().isEmpty())
		{
			return;
		}
		plugin.renameSavedLayout(index, name.trim());
		refreshSavesRows();
	}

	// â”€â”€ Scrollable controls container â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

	// â”€â”€ Collapsible section header â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

	/** A clickable header with a chevron that shows/hides a body component below it. */
	private static final class CollapsibleSection
	{
		final JPanel header;
		private final JLabel chevron;
		private final JComponent body;
		private Runnable onToggle = () -> {};

		CollapsibleSection(String title, String tooltip, JComponent body)
		{
			this.body = body;

			chevron = new JLabel(body.isVisible() ? "-" : "+");
			chevron.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			chevron.setFont(FontManager.getRunescapeSmallFont());

			JLabel titleLabel = new JLabel(title);
			titleLabel.setForeground(ColorScheme.BRAND_ORANGE);
			titleLabel.setFont(FontManager.getRunescapeBoldFont());

			header = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
			header.setBackground(ColorScheme.DARK_GRAY_COLOR);
			header.setAlignmentX(Component.LEFT_ALIGNMENT);
			header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
			header.setBorder(new MatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR));
			header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			header.setToolTipText(tooltip);
			chevron.setToolTipText(tooltip);
			titleLabel.setToolTipText(tooltip);
			header.add(chevron);
			header.add(titleLabel);

			// Clicks land on whichever child is under the cursor (chevron or title text cover
			// almost the whole header), so the listener has to be on all three, not just header.
			MouseAdapter click = new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					setExpanded(!body.isVisible());
					onToggle.run();
				}
			};
			header.addMouseListener(click);
			chevron.addMouseListener(click);
			titleLabel.addMouseListener(click);
		}

		void setExpanded(boolean expanded)
		{
			body.setVisible(expanded);
			chevron.setText(expanded ? "-" : "+");
			body.revalidate();
			body.repaint();
		}

		boolean isExpanded()
		{
			return body.isVisible();
		}

		void setOnToggle(Runnable onToggle)
		{
			this.onToggle = onToggle;
		}
	}
}
