package com.songmute;

import java.util.Set;
import net.runelite.api.GameState;

final class SongMuteLogic
{
	enum Action
	{
		NONE,
		MUTE_ONLY,
		SAVE_AND_MUTE,
		RESTORE
	}

	private SongMuteLogic()
	{
	}

	static Action determineAction(
		GameState gameState,
		int currentTrackId,
		Set<Integer> mutedTrackIds,
		boolean currentlyMuting,
		int currentVolume)
	{
		if (gameState != GameState.LOGGED_IN)
		{
			return currentlyMuting ? Action.RESTORE : Action.NONE;
		}

		boolean shouldMute = currentTrackId >= 0 && mutedTrackIds.contains(currentTrackId);
		if (shouldMute)
		{
			if (currentVolume > 0)
			{
				return currentlyMuting ? Action.MUTE_ONLY : Action.SAVE_AND_MUTE;
			}

			return Action.NONE;
		}

		return currentlyMuting ? Action.RESTORE : Action.NONE;
	}

	static int clampVolume(int volume)
	{
		return Math.min(255, Math.max(0, volume));
	}
}
