package com.songmute;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.gameval.DBTableID;
import net.runelite.client.util.Text;

final class SongMuteTracks
{
	private SongMuteTracks()
	{
	}

	static List<Track> loadTracks(Client client)
	{
		List<Integer> rows = client.getDBTableRows(DBTableID.Music.ID);
		if (rows == null)
		{
			return List.of();
		}

		List<Track> entries = new ArrayList<>();
		for (Integer rowId : rows)
		{
			Object[] midiField = client.getDBTableField(rowId, DBTableID.Music.COL_MIDI, 0);
			int archiveId = -1;
			if (midiField != null && midiField.length > 0 && midiField[0] instanceof Number)
			{
				archiveId = ((Number) midiField[0]).intValue();
			}
			if (archiveId < 0)
			{
				continue;
			}

			if (isHiddenTrack(client, rowId))
			{
				continue;
			}

			Object[] sortNameField = client.getDBTableField(rowId, DBTableID.Music.COL_SORTNAME, 0);
			String sortName = sortNameField != null && sortNameField.length > 0 && sortNameField[0] != null
				? sortNameField[0].toString()
				: "";

			Object[] displayNameField = client.getDBTableField(rowId, DBTableID.Music.COL_DISPLAYNAME, 0);
			String rawName = displayNameField != null && displayNameField.length > 0 && displayNameField[0] != null
				? displayNameField[0].toString()
				: null;
			if (rawName == null || rawName.isBlank())
			{
				continue;
			}

			entries.add(new Track(archiveId, rawName, sortName));
		}

		entries.sort((a, b) ->
		{
			int sortComparison = String.CASE_INSENSITIVE_ORDER.compare(a.getSortName(), b.getSortName());
			return sortComparison != 0 ? sortComparison : String.CASE_INSENSITIVE_ORDER.compare(a.getName(), b.getName());
		});

		return entries;
	}

	private static boolean isHiddenTrack(Client client, int rowId)
	{
		Object[] hiddenField = client.getDBTableField(rowId, DBTableID.Music.COL_HIDDEN, 0);
		return hiddenField != null
			&& hiddenField.length > 0
			&& hiddenField[0] instanceof Number
			&& ((Number) hiddenField[0]).intValue() != 0;
	}

	static Integer findTrackIdByName(Client client, String trackName)
	{
		String normalizedTrackName = normalizeTrackName(trackName);
		if (normalizedTrackName.isEmpty())
		{
			return null;
		}

		return loadTrackIdsByNormalizedName(client).get(normalizedTrackName);
	}

	static Map<String, Integer> loadTrackIdsByNormalizedName(Client client)
	{
		return loadTrackIdsByNormalizedName(loadTracks(client));
	}

	static Map<String, Integer> loadTrackIdsByNormalizedName(List<Track> tracks)
	{
		Map<String, Integer> trackIds = new LinkedHashMap<>();
		for (Track track : tracks)
		{
			trackIds.putIfAbsent(normalizeTrackName(track.getName()), track.getId());
		}
		return trackIds;
	}

	static String normalizeTrackName(String trackName)
	{
		if (trackName == null)
		{
			return "";
		}

		return Text.removeTags(trackName)
			.trim()
			.toLowerCase(Locale.ENGLISH);
	}

	static final class Track
	{
		private final int id;
		private final String name;
		private final String sortName;

		Track(int id, String name, String sortName)
		{
			this.id = id;
			this.name = name;
			this.sortName = sortName != null ? sortName : "";
		}

		int getId()
		{
			return id;
		}

		String getName()
		{
			return name;
		}

		String getSortName()
		{
			return sortName;
		}
	}
}
