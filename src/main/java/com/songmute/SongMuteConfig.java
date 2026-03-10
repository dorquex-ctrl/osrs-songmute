package com.songmute;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(SongMuteConfig.GROUP)
public interface SongMuteConfig extends Config
{
	String GROUP = "songmute";

	@ConfigSection(
		name = "Muted tracks",
		description = "Tracks you have chosen to mute. Use the Song Mute sidebar panel to manage this list; you can also edit the IDs here if needed.",
		position = 1
	)
	String mutedTracksSection = "mutedTracksSection";

	@ConfigItem(
		keyName = "mutedTrackIds",
		name = "Muted track IDs",
		description = "Comma-separated list of music track IDs (archive IDs) that should never play. Best managed via the Song Mute sidebar panel.",
		position = 0,
		section = mutedTracksSection,
		warning = "Editing this list manually can break mute behavior. Prefer using the Song Mute sidebar panel."
	)
	default String mutedTrackIds()
	{
		return "";
	}

	@ConfigItem(
		keyName = "savedMusicVolume",
		name = "Saved music volume",
		description = "Stored music volume to restore when a non-muted track plays. Do not edit manually.",
		hidden = true
	)
	default int savedMusicVolume()
	{
		return 255;
	}
}
