package com.songmute;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.ui.components.PluginErrorPanel;
import net.runelite.client.util.SwingUtil;

/**
 * Sidebar panel listing all music tracks with mute toggles.
 */
public class SongMutePanel extends PluginPanel
{
	private static final String LIST_CARD = "list";
	private static final String ERROR_CARD = "error";
	private static final EmptyBorder PANEL_PADDING = new EmptyBorder(10, 10, 10, 10);
	private static final EmptyBorder ROW_PADDING = new EmptyBorder(2, 0, 2, 0);
	private static final Color CURRENT_TRACK = new Color(66, 227, 17);
	private static final Color ODD_ROW = new Color(44, 44, 44);

	private final Client client;
	private final ClientThread clientThread;
	private final Provider<SongMutePlugin> pluginProvider;

	private final JPanel trackListPanel = new JPanel(new DynamicGridLayout(0, 1, 0, 0));
	private final JLabel summaryLabel = new JLabel("Log in to load the music track list.");
	private final IconTextField searchField = new IconTextField();
	private final JComboBox<TrackFilterMode> filterDropdown = new JComboBox<>(TrackFilterMode.values());
	private final JButton clearButton = new JButton("Clear muted");
	private final PluginErrorPanel errorPanel = new PluginErrorPanel();
	private final JPanel centerCardPanel = new JPanel(new CardLayout());

	private String searchFilter = "";
	private boolean trackListLoaded = false;
	private boolean showingCachedList = false;
	private int currentTrackId = -1;
	private List<SongMuteTracks.Track> allTracks = Collections.emptyList();

	@Inject
	public SongMutePanel(Client client, ClientThread clientThread, Provider<SongMutePlugin> pluginProvider)
	{
		super();

		this.client = client;
		this.clientThread = clientThread;
		this.pluginProvider = pluginProvider;

		setBorder(null);
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel topPanel = new JPanel(new DynamicGridLayout(0, 1, 0, BORDER_OFFSET));
		topPanel.setBorder(PANEL_PADDING);
		topPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel controlsPanel = new JPanel(new BorderLayout(8, 0));
		controlsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		filterDropdown.setFocusable(false);
		filterDropdown.setMaximumRowCount(TrackFilterMode.values().length);
		filterDropdown.setSelectedItem(TrackFilterMode.ALL);
		filterDropdown.addActionListener(e -> updateFilters(true));
		controlsPanel.add(filterDropdown, BorderLayout.WEST);

		SwingUtil.removeButtonDecorations(clearButton);
		clearButton.setFocusable(false);
		clearButton.setForeground(ColorScheme.BRAND_ORANGE);
		clearButton.addActionListener(e ->
		{
			if (!clearButton.isVisible())
			{
				return;
			}

			int result = JOptionPane.showConfirmDialog(
				SwingUtilities.getWindowAncestor(this),
				"Clear every muted track?",
				"Clear muted tracks",
				JOptionPane.YES_NO_OPTION,
				JOptionPane.WARNING_MESSAGE
			);

			if (result == JOptionPane.YES_OPTION)
			{
				pluginProvider.get().clearAllMutedTracks();
			}
		});
		controlsPanel.add(clearButton, BorderLayout.EAST);

		searchField.setIcon(IconTextField.Icon.SEARCH);
		searchField.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 30));
		searchField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchField.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		searchField.addClearListener(this::updateSearch);
		searchField.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				updateSearch();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				updateSearch();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				updateSearch();
			}
		});

		topPanel.add(searchField);
		JPanel summaryPanel = new JPanel(new BorderLayout());
		summaryPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		summaryLabel.setForeground(ColorScheme.TEXT_COLOR);
		summaryPanel.add(summaryLabel, BorderLayout.WEST);
		topPanel.add(summaryPanel);
		topPanel.add(controlsPanel);
		add(topPanel, BorderLayout.NORTH);

		trackListPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel listWrapper = new JPanel(new BorderLayout());
		listWrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		listWrapper.add(trackListPanel, BorderLayout.NORTH);

		JPanel errorWrapper = new JPanel(new BorderLayout());
		errorWrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		errorWrapper.add(errorPanel, BorderLayout.NORTH);

		centerCardPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		centerCardPanel.add(listWrapper, LIST_CARD);
		centerCardPanel.add(errorWrapper, ERROR_CARD);
		add(centerCardPanel, BorderLayout.CENTER);

		showErrorState("Unable to load", "Log in to the game to load the music track list.");
		updateSummary(0, 0);
	}

	@Override
	public void onActivate()
	{
		clientThread.invokeLater(this::refreshTrackLists);
	}

	/**
	 * Call when the player logs in so the track list is refreshed with current game data.
	 */
	void refreshWhenLoggedIn()
	{
		clientThread.invokeLater(this::refreshTrackLists);
	}

	void refreshFromConfig()
	{
		SwingUtilities.invokeLater(this::rebuildUI);
	}

	void refreshCurrentTrack(int trackId)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (currentTrackId == trackId)
			{
				return;
			}

			currentTrackId = trackId;
			rebuildUI();
		});
	}

	private void updateSearch()
	{
		searchFilter = searchField.getText().trim().toLowerCase(Locale.ENGLISH);
		updateFilters(true);
	}

	private void updateFilters(boolean resetScroll)
	{
		rebuildUI();
		if (resetScroll)
		{
			getScrollPane().getVerticalScrollBar().setValue(0);
		}
	}

	private void showCenterCard(String cardName)
	{
		((CardLayout) centerCardPanel.getLayout()).show(centerCardPanel, cardName);
	}

	private void showErrorState(String title, String message)
	{
		errorPanel.setContent(title, message);
		showCenterCard(ERROR_CARD);
	}

	private void refreshTrackLists()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			showingCachedList = trackListLoaded && !allTracks.isEmpty();
			SwingUtilities.invokeLater(() ->
			{
				if (allTracks.isEmpty())
				{
					showErrorState("Unable to load", "Log in to the game to load the music track list.");
					updateSummary(0, 0);
				}
				else
				{
					rebuildUI();
				}
			});
			return;
		}

		try
		{
			List<SongMuteTracks.Track> tracks = SongMuteTracks.loadTracks(client);
			if (tracks.isEmpty())
			{
				SwingUtilities.invokeLater(() ->
				{
					if (!allTracks.isEmpty())
					{
						showingCachedList = true;
						rebuildUI();
						return;
					}

					trackListLoaded = true;
					allTracks = Collections.emptyList();
					showErrorState("No tracks available", "No music tracks were found in the current client data.");
					updateSummary(0, 0);
				});
				return;
			}

			showingCachedList = false;
			trackListLoaded = true;
			allTracks = tracks;

			SwingUtilities.invokeLater(this::rebuildUI);
		}
		catch (Exception e)
		{
			String message = e.getMessage();
			SwingUtilities.invokeLater(() ->
			{
				if (!allTracks.isEmpty())
				{
					showingCachedList = true;
					rebuildUI();
					return;
				}

				showErrorState("Unable to load", message != null ? message : "An unexpected error occurred.");
				updateSummary(0, 0);
			});
		}
	}

	private void rebuildUI()
	{
		trackListPanel.removeAll();

		Set<Integer> visibleTrackIds = allTracks.stream()
			.map(SongMuteTracks.Track::getId)
			.collect(Collectors.toSet());
		Set<Integer> mutedTrackIds = pluginProvider.get().getMutedTrackIds().stream()
			.filter(visibleTrackIds::contains)
			.collect(Collectors.toSet());
		boolean mutedOnly = isMutedOnlySelected();
		List<SongMuteTracks.Track> visibleTracks = allTracks.stream()
			.filter(track -> !mutedOnly || mutedTrackIds.contains(track.getId()))
			.filter(track -> searchFilter.isEmpty()
				|| track.getName().toLowerCase(Locale.ENGLISH).contains(searchFilter))
			.collect(Collectors.toList());

		updateSummary(visibleTracks.size(), mutedTrackIds.size());

		if (!trackListLoaded && allTracks.isEmpty())
		{
			showErrorState("Unable to load", "Log in to the game to load the music track list.");
		}
		else if (allTracks.isEmpty())
		{
			showErrorState("No tracks available", "No music tracks were found in the current client data.");
		}
		else if (visibleTracks.isEmpty())
		{
			showEmptyFilteredState(mutedOnly, mutedTrackIds.isEmpty());
		}
		else
		{
			for (int i = 0; i < visibleTracks.size(); i++)
			{
				SongMuteTracks.Track track = visibleTracks.get(i);
				trackListPanel.add(new TrackRow(
					track,
					mutedTrackIds.contains(track.getId()),
					track.getId() == currentTrackId,
					i
				));
			}

			showCenterCard(LIST_CARD);
		}

		trackListPanel.revalidate();
		trackListPanel.repaint();
	}

	private void showEmptyFilteredState(boolean mutedOnly, boolean noMutedTracks)
	{
		if (mutedOnly)
		{
			if (noMutedTracks)
			{
				showErrorState("No muted tracks", "Mute songs from the list, or switch the filter to All tracks.");
			}
			else if (searchFilter.isEmpty())
			{
				showErrorState("No muted tracks", "Switch the filter to All tracks to browse every song.");
			}
			else
			{
				showErrorState("No muted tracks found", "Try a different search term or switch the filter to All tracks.");
			}
			return;
		}

		showErrorState("No tracks found", "Try a different search term.");
	}

	private void updateSummary(int visibleCount, int mutedCount)
	{
		boolean hasTrackData = !allTracks.isEmpty();
		boolean canShowMuteState = hasTrackData || showingCachedList;

		if (!canShowMuteState)
		{
			if (!trackListLoaded)
			{
				summaryLabel.setText("Log in to load the music track list.");
			}
			else
			{
				summaryLabel.setText("No music tracks available.");
			}
			clearButton.setEnabled(false);
			clearButton.setVisible(false);
			return;
		}

		boolean mutedOnly = isMutedOnlySelected();
		String summary;
		if (mutedOnly)
		{
			summary = searchFilter.isEmpty()
				? formatTrackCount(mutedCount, "muted track")
				: visibleCount + " of " + formatTrackCount(mutedCount, "muted track");
		}
		else if (searchFilter.isEmpty())
		{
			summary = formatTrackCount(allTracks.size(), "track");
		}
		else
		{
			summary = visibleCount + " of " + formatTrackCount(allTracks.size(), "track");
		}

		if (showingCachedList && !allTracks.isEmpty())
		{
			summary = summary + " (cached)";
		}

		if (!mutedOnly && mutedCount > 0)
		{
			summary = summary + " - " + mutedCount + " muted";
		}

		summaryLabel.setText(summary);
		clearButton.setEnabled(mutedCount > 0);
		clearButton.setVisible(mutedCount > 0);
	}

	private boolean isMutedOnlySelected()
	{
		return filterDropdown.getSelectedItem() == TrackFilterMode.MUTED;
	}

	private void setTrackMuted(int trackId, boolean muted)
	{
		if (muted)
		{
			pluginProvider.get().addMutedTrack(trackId);
		}
		else
		{
			pluginProvider.get().removeMutedTrack(trackId);
		}
	}

	private final class TrackRow extends JPanel
	{
		private TrackRow(SongMuteTracks.Track track, boolean muted, boolean current, int index)
		{
			setLayout(new BorderLayout(3, 0));
			setBackground(index % 2 == 0 ? ODD_ROW : ColorScheme.DARK_GRAY_COLOR);
			setBorder(ROW_PADDING);
			setAlignmentX(Component.LEFT_ALIGNMENT);

			JLabel nameLabel = new JLabel(track.getName());
			nameLabel.setForeground(current ? CURRENT_TRACK : ColorScheme.TEXT_COLOR);
			nameLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			add(nameLabel, BorderLayout.CENTER);

			JCheckBox muteCheckbox = new JCheckBox();
			muteCheckbox.setOpaque(false);
			muteCheckbox.setSelected(muted);
			muteCheckbox.addActionListener(e -> setTrackMuted(track.getId(), muteCheckbox.isSelected()));
			add(muteCheckbox, BorderLayout.EAST);

			nameLabel.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					if (SwingUtilities.isLeftMouseButton(e))
					{
						muteCheckbox.doClick();
					}
				}
			});
		}
	}

	private static String formatTrackCount(int count, String singular)
	{
		return count + " " + singular + (count == 1 ? "" : "s");
	}

	private enum TrackFilterMode
	{
		ALL("All tracks"),
		MUTED("Muted tracks");

		private final String label;

		TrackFilterMode(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}
}
