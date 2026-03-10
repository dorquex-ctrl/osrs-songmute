package com.songmute;

import org.junit.Assert;
import org.junit.Test;

public class SongMuteTracksTest
{
	@Test
	public void normalizeTrackNameRemovesTagsAndWhitespace()
	{
		String normalized = SongMuteTracks.normalizeTrackName("  <col=ff9040>Adventure</col>  ");

		Assert.assertEquals("adventure", normalized);
	}
}
