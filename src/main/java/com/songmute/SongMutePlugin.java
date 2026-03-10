package com.songmute;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Provider;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.MidiRequest;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.PostClientTick;
import net.runelite.api.events.VolumeChanged;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

@PluginDescriptor(
	name = "Song Mute",
	description = "Mute specific in-game music tracks (songs) so selected songs never play.",
	tags = {"music", "mute", "sounds"}
)
public class SongMutePlugin extends Plugin
{
	// The generated ComponentID names for the music interface do not map cleanly to the
	// old WidgetInfo.MUSIC_TRACK_LIST packed ID, so use the exact music list child ID.
	private static final int MUSIC_TRACK_LIST_CHILD = 11;
	private static final int MUSIC_TRACK_LIST_ID = InterfaceID.MUSIC << 16 | MUSIC_TRACK_LIST_CHILD;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	@Inject
	private SongMuteConfig config;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private Provider<SongMutePanel> songMutePanelProvider;

	private SongMutePanel songMutePanel;

	private NavigationButton navButton;
	private boolean currentlyMuting = false;
	private int displayedTrackId = -1;
	private Set<Integer> availableTrackIds = Collections.emptySet();
	private Map<String, Integer> trackIdsByName = Collections.emptyMap();
	private final Map<Integer, WidgetColorState> trackTextColorStates = new HashMap<>();

	@Override
	protected void startUp()
	{
		clientThread.invokeLater(() ->
		{
			refreshTrackLookup();
			pruneUnavailableMutedTracks();
			applyMuteForCurrentTrack();
			updateMusicListMutedStyles();
		});
		songMutePanel = songMutePanelProvider.get();
		BufferedImage icon = ImageUtil.loadImageResource(SongMutePlugin.class, "icon.png");
		icon = ImageUtil.resizeImage(icon, 16, 16);
		navButton = NavigationButton.builder()
			.tooltip("Song Mute")
			.icon(icon)
			.panel(songMutePanel)
			.build();
		clientToolbar.addNavigation(navButton);
	}

	@Override
	protected void shutDown()
	{
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		displayedTrackId = -1;
		availableTrackIds = Collections.emptySet();
		trackIdsByName = Collections.emptyMap();
		trackTextColorStates.clear();
		songMutePanel = null;
		clientThread.invoke(() ->
		{
			redrawMusicList();
			if (currentlyMuting)
			{
				restoreVolume();
			}
		});
	}

	@Provides
	SongMuteConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SongMuteConfig.class);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(() ->
			{
				refreshTrackLookup();
				pruneUnavailableMutedTracks();
				applyMuteForCurrentTrack();
				updateMusicListMutedStyles();
			});
			if (songMutePanel != null)
			{
				songMutePanel.refreshWhenLoggedIn();
			}
			return;
		}

		availableTrackIds = Collections.emptySet();
		trackIdsByName = Collections.emptyMap();
		trackTextColorStates.clear();

		if (currentlyMuting)
		{
			clientThread.invokeLater(this::applyMuteForCurrentTrack);
		}
	}

	@Subscribe
	public void onPostClientTick(PostClientTick event)
	{
		clientThread.invoke(() ->
		{
			applyMuteForCurrentTrack();
			updateMusicListMutedStyles();
		});
	}

	@Subscribe
	public void onVolumeChanged(VolumeChanged event)
	{
		if (event.getType() == VolumeChanged.Type.MUSIC && !currentlyMuting)
		{
			int vol = client.getMusicVolume();
			configManager.setConfiguration(SongMuteConfig.GROUP, "savedMusicVolume", String.valueOf(vol));
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!SongMuteConfig.GROUP.equals(event.getGroup()) || !"mutedTrackIds".equals(event.getKey()))
		{
			return;
		}

		clientThread.invokeLater(() ->
		{
			refreshTrackLookup();
			pruneUnavailableMutedTracks();
			applyMuteForCurrentTrack();
			redrawMusicList();
			updateMusicListMutedStyles();
		});

		if (songMutePanel != null)
		{
			songMutePanel.refreshFromConfig();
		}
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		MenuEntry menuEntry = event.getMenuEntry();
		MenuAction action = menuEntry.getType();
		if (action != MenuAction.CC_OP && action != MenuAction.CC_OP_LOW_PRIORITY)
		{
			return;
		}

		Widget widget = menuEntry.getWidget();
		if (!isMusicTrackWidget(widget, menuEntry.getParam1()))
		{
			return;
		}

		String menuTarget = getMusicMenuTarget(menuEntry, widget);
		if (menuTarget == null || menuTarget.isBlank() || hasExistingMuteMenu(menuTarget))
		{
			return;
		}

		Integer trackId = findTrackIdByName(menuTarget);
		if (trackId == null)
		{
			return;
		}

		boolean shouldMute = !isTrackMuted(trackId);
		client.getMenu().createMenuEntry(-1)
			.setOption(shouldMute ? "Mute" : "Unmute")
			.setTarget(menuTarget)
			.setType(MenuAction.RUNELITE)
			.setParam0(menuEntry.getParam0())
			.setParam1(menuEntry.getParam1())
			.onClick(entry -> setTrackMuted(trackId, shouldMute));
	}

	private void applyMuteForCurrentTrack()
	{
		GameState gameState = client.getGameState();
		int currentTrackId = gameState == GameState.LOGGED_IN ? getCurrentMusicTrackId() : -1;
		Set<Integer> muted = gameState == GameState.LOGGED_IN ? getMutedTrackIds() : Set.of();
		int currentVolume = client.getMusicVolume();

		if (currentTrackId != displayedTrackId)
		{
			displayedTrackId = currentTrackId;
			if (songMutePanel != null)
			{
				songMutePanel.refreshCurrentTrack(currentTrackId);
			}
		}

		switch (SongMuteLogic.determineAction(gameState, currentTrackId, muted, currentlyMuting, currentVolume))
		{
			case SAVE_AND_MUTE:
				configManager.setConfiguration(SongMuteConfig.GROUP, "savedMusicVolume", String.valueOf(currentVolume));
				currentlyMuting = true;
				client.setMusicVolume(0);
				break;
			case MUTE_ONLY:
				client.setMusicVolume(0);
				break;
			case RESTORE:
				restoreVolume();
				break;
			case NONE:
			default:
				break;
		}
	}

	private void restoreVolume()
	{
		int saved = config.savedMusicVolume();
		client.setMusicVolume(SongMuteLogic.clampVolume(saved));
		currentlyMuting = false;
	}

	/**
	 * Gets the currently playing music track ID (archive id), or -1 if none or only jingles.
	 */
	private int getCurrentMusicTrackId()
	{
		List<MidiRequest> active = client.getActiveMidiRequests();
		if (active == null || active.isEmpty())
		{
			return -1;
		}
		for (MidiRequest req : active)
		{
			if (!req.isJingle())
			{
				return req.getArchiveId();
			}
		}
		return -1;
	}

	/**
	 * Parses the muted track IDs string from config into a set.
	 */
	static Set<Integer> getMutedTrackIdsFromString(String mutedTrackIds)
	{
		if (mutedTrackIds == null || mutedTrackIds.isBlank())
		{
			return Set.of();
		}
		return Arrays.stream(mutedTrackIds.split(","))
			.map(String::trim)
			.filter(s -> !s.isEmpty())
			.mapToInt(s ->
			{
				try
				{
					return Integer.parseInt(s);
				}
				catch (NumberFormatException e)
				{
					return -1;
				}
			})
			.filter(id -> id >= 0)
			.boxed()
			.collect(Collectors.toSet());
	}

	Set<Integer> getMutedTrackIds()
	{
		return getMutedTrackIdsFromString(config.mutedTrackIds());
	}

	boolean isTrackMuted(int trackId)
	{
		return getMutedTrackIds().contains(trackId);
	}

	/**
	 * Add a track ID to the muted set (persisted to config).
	 */
	void addMutedTrack(int trackId)
	{
		setTrackMuted(trackId, true);
	}

	/**
	 * Remove a track ID from the muted set (persisted to config).
	 */
	void removeMutedTrack(int trackId)
	{
		setTrackMuted(trackId, false);
	}

	void setTrackMuted(int trackId, boolean mutedState)
	{
		Set<Integer> muted = new HashSet<>(getMutedTrackIds());
		boolean changed = mutedState ? muted.add(trackId) : muted.remove(trackId);
		if (!changed)
		{
			return;
		}

		persistMutedTrackIds(muted);
	}

	/**
	 * Clear all muted tracks (persisted to config).
	 */
	void clearAllMutedTracks()
	{
		if (config.mutedTrackIds().isBlank())
		{
			return;
		}

		persistMutedTrackIds(Set.of());
	}

	private void persistMutedTrackIds(Set<Integer> mutedTrackIds)
	{
		configManager.setConfiguration(SongMuteConfig.GROUP, "mutedTrackIds",
			mutedTrackIds.stream().sorted().map(String::valueOf).collect(Collectors.joining(",")));
		if (songMutePanel != null)
		{
			songMutePanel.refreshFromConfig();
		}
		clientThread.invokeLater(() ->
		{
			applyMuteForCurrentTrack();
			redrawMusicList();
			updateMusicListMutedStyles();
		});
	}

	private void refreshTrackLookup()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			availableTrackIds = Collections.emptySet();
			trackIdsByName = Collections.emptyMap();
			return;
		}

		List<SongMuteTracks.Track> tracks = SongMuteTracks.loadTracks(client);
		availableTrackIds = tracks.stream()
			.map(SongMuteTracks.Track::getId)
			.collect(Collectors.toSet());
		trackIdsByName = SongMuteTracks.loadTrackIdsByNormalizedName(tracks);
	}

	private void pruneUnavailableMutedTracks()
	{
		if (availableTrackIds.isEmpty())
		{
			return;
		}

		Set<Integer> mutedTrackIds = getMutedTrackIds();
		Set<Integer> filteredMutedTrackIds = mutedTrackIds.stream()
			.filter(availableTrackIds::contains)
			.collect(Collectors.toSet());
		if (filteredMutedTrackIds.size() != mutedTrackIds.size())
		{
			persistMutedTrackIds(filteredMutedTrackIds);
		}
	}

	private Integer findTrackIdByName(String trackName)
	{
		String normalizedTrackName = SongMuteTracks.normalizeTrackName(trackName);
		if (normalizedTrackName.isEmpty())
		{
			return null;
		}

		Integer trackId = trackIdsByName.get(normalizedTrackName);
		if (trackId != null || !trackIdsByName.isEmpty())
		{
			return trackId;
		}

		refreshTrackLookup();
		return trackIdsByName.get(normalizedTrackName);
	}

	private boolean isOnMusicTab()
	{
		return client.getVarcIntValue(171) == 13;
	}

	private void redrawMusicList()
	{
		if (!isOnMusicTab())
		{
			return;
		}

		Widget trackList = client.getWidget(MUSIC_TRACK_LIST_ID);
		if (trackList == null)
		{
			return;
		}

		trackTextColorStates.clear();
		Object[] onVarTransmitListener = trackList.getOnVarTransmitListener();
		if (onVarTransmitListener != null)
		{
			client.runScript(onVarTransmitListener);
		}
	}

	private void updateMusicListMutedStyles()
	{
		if (client.getGameState() != GameState.LOGGED_IN || !isOnMusicTab())
		{
			return;
		}

		Set<Integer> mutedTrackIds = getMutedTrackIds();
		if (mutedTrackIds.isEmpty() && trackTextColorStates.isEmpty())
		{
			return;
		}

		if (trackIdsByName.isEmpty())
		{
			refreshTrackLookup();
			if (trackIdsByName.isEmpty())
			{
				return;
			}
		}

		Widget trackList = client.getWidget(MUSIC_TRACK_LIST_ID);
		if (trackList == null)
		{
			return;
		}

		applyMutedTextStyle(trackList, mutedTrackIds);
	}

	private void applyMutedTextStyle(Widget widget, Set<Integer> mutedTrackIds)
	{
		if (widget == null || widget.isHidden())
		{
			return;
		}

		int widgetId = widget.getId();
		String normalizedText = SongMuteTracks.normalizeTrackName(widget.getText());
		Integer trackId = normalizedText.isEmpty() ? null : findTrackIdByName(normalizedText);
		if (trackId != null)
		{
			WidgetColorState colorState = trackTextColorStates.get(widgetId);
			if (colorState == null || !normalizedText.equals(colorState.normalizedTrackName))
			{
				colorState = new WidgetColorState(normalizedText, widget.getTextColor());
				trackTextColorStates.put(widgetId, colorState);
			}

			int mutedColor = toMutedTextColor(colorState.originalColor);
			if (mutedTrackIds.contains(trackId))
			{
				if (widget.getTextColor() != mutedColor)
				{
					widget.setTextColor(mutedColor);
				}
			}
			else if (widget.getTextColor() == mutedColor)
			{
				widget.setTextColor(colorState.originalColor);
			}
			else if (widget.getTextColor() != colorState.originalColor)
			{
				colorState.originalColor = widget.getTextColor();
			}
		}
		else
		{
			trackTextColorStates.remove(widgetId);
		}

		Widget[] children = widget.getChildren();
		if (children == null)
		{
			return;
		}

		for (Widget child : children)
		{
			applyMutedTextStyle(child, mutedTrackIds);
		}
	}

	private static int toMutedTextColor(int originalColor)
	{
		int red = originalColor >> 16 & 0xFF;
		int green = originalColor >> 8 & 0xFF;
		int blue = originalColor & 0xFF;
		int gray = Math.min(255, (red * 30 + green * 59 + blue * 11) / 100);
		gray = Math.max(70, gray * 3 / 4);
		return gray << 16 | gray << 8 | gray;
	}

	private static final class WidgetColorState
	{
		private final String normalizedTrackName;
		private int originalColor;

		private WidgetColorState(String normalizedTrackName, int originalColor)
		{
			this.normalizedTrackName = normalizedTrackName;
			this.originalColor = originalColor;
		}
	}

	private boolean isMusicTrackWidget(Widget widget, int widgetId)
	{
		Widget current = widget;
		while (current != null)
		{
			if (current.getId() == MUSIC_TRACK_LIST_ID)
			{
				return true;
			}

			current = current.getParent();
		}

		return widgetId >> 16 == InterfaceID.MUSIC;
	}

	private String getMusicMenuTarget(MenuEntry menuEntry, Widget widget)
	{
		if (menuEntry.getTarget() != null && !SongMuteTracks.normalizeTrackName(menuEntry.getTarget()).isEmpty())
		{
			return menuEntry.getTarget();
		}

		if (widget != null && !SongMuteTracks.normalizeTrackName(widget.getText()).isEmpty())
		{
			return widget.getText();
		}

		return null;
	}

	private boolean hasExistingMuteMenu(String menuTarget)
	{
		return Arrays.stream(client.getMenu().getMenuEntries())
			.anyMatch(entry -> Objects.equals(entry.getTarget(), menuTarget)
				&& ("Mute".equals(entry.getOption()) || "Unmute".equals(entry.getOption())));
	}
}
