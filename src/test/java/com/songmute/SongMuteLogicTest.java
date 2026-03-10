package com.songmute;

import java.util.Set;
import net.runelite.api.GameState;
import org.junit.Assert;
import org.junit.Test;

public class SongMuteLogicTest
{
	@Test
	public void parsesMutedTrackIdsAndDropsInvalidValues()
	{
		Set<Integer> muted = SongMutePlugin.getMutedTrackIdsFromString(" 42,7,abc,,42,-1, 9 ");

		Assert.assertEquals(Set.of(42, 7, 9), muted);
	}

	@Test
	public void newlyMutedCurrentTrackIsMutedImmediately()
	{
		SongMuteLogic.Action action = SongMuteLogic.determineAction(
			GameState.LOGGED_IN,
			42,
			Set.of(42),
			false,
			120);

		Assert.assertEquals(SongMuteLogic.Action.SAVE_AND_MUTE, action);
	}

	@Test
	public void unmutingCurrentTrackRestoresVolumeImmediately()
	{
		SongMuteLogic.Action action = SongMuteLogic.determineAction(
			GameState.LOGGED_IN,
			42,
			Set.of(),
			true,
			0);

		Assert.assertEquals(SongMuteLogic.Action.RESTORE, action);
	}

	@Test
	public void leavingLoggedInStateRestoresVolume()
	{
		SongMuteLogic.Action action = SongMuteLogic.determineAction(
			GameState.LOGIN_SCREEN,
			-1,
			Set.of(),
			true,
			0);

		Assert.assertEquals(SongMuteLogic.Action.RESTORE, action);
	}

	@Test
	public void alreadyZeroVolumeDoesNotOverwriteSavedVolume()
	{
		SongMuteLogic.Action action = SongMuteLogic.determineAction(
			GameState.LOGGED_IN,
			42,
			Set.of(42),
			false,
			0);

		Assert.assertEquals(SongMuteLogic.Action.NONE, action);
	}
}
