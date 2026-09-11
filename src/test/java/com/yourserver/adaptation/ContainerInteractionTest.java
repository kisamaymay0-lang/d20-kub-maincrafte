package com.yourserver.adaptation;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContainerInteractionTest {
    @ParameterizedTest
    @CsvSource({
            "false,true,true,false", "false,false,true,false", "false,true,false,false", "false,false,false,false",
            "true,true,true,false", "true,false,true,true", "true,true,false,true", "true,false,false,true"
    })
    void matchesTheFurnaceSecondaryUseRule(boolean sneaking, boolean mainEmpty, boolean offEmpty, boolean bypass) {
        assertEquals(bypass, ContainerInteraction.bypassMenu(sneaking, mainEmpty, offEmpty));
    }
}
