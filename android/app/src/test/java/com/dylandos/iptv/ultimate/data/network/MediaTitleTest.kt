package com.dylandos.iptv.ultimate.data.network

import org.junit.Assert.*
import org.junit.Test

class MediaTitleTest {
    @Test fun handlesProviderNamesWithoutLosingNumericMovieTitles() {
        assertEquals(ParsedMediaTitle("2001: A Space Odyssey", "1968"), MediaTitle.parse("EN - 2001: A Space Odyssey (1968) 4K"))
        assertEquals(ParsedMediaTitle("Downbeat", "2026"), MediaTitle.parse("EN - Downbeat - 2026"))
        assertEquals("alien", MediaTitle.normalized("|EN| Alien (1979) FHD"))
        assertEquals(ParsedMediaTitle("A Tale of Two Cities", null), MediaTitle.parse("EN - A Tale of Two Cities"))
    }
}
