package com.sakiv.cpi.snapshot.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CpiClientKeyTest {

    @Test
    void quotesAreDoubledAndEncoded() {
        assertEquals("%27Pkg%27", CpiClient.key("Pkg"));
        assertEquals("%27O%27%27Brien%27", CpiClient.key("O'Brien"));
        assertEquals("%27a%20b%27", CpiClient.key("a b"));
    }
}
