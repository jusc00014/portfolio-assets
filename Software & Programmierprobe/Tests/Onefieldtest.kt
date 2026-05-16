package de.unisaarland.cs.se.selab.systemtest.selab25.onefieldtest
import de.unisaarland.cs.se.selab.systemtest.selab25.utils.TestExtension

/**
 * Tests irrigating and drought
 */
class Onefieldtest : TestExtension() {
    override val name = "OneFieldTest"
    override val description = "Tests one field with irrigating and drought"

    override val farms = "onefieldtest/farms.json"
    override val scenario = "onefieldtest/scenario.json"
    override val map = "onefieldtest/map.json"

    override val logLevel = "DEBUG"
    override val maxTicks = 2
    override val startYearTick = 9

    override suspend fun run() {
        skipUntilString(
            "[IMPORTANT] Farm: Farm 11 starts its actions."
        )
        assertCurrentLine(
            "[IMPORTANT] Farm: Farm 11 starts its actions."
        )
        assertNextLine(
            "[DEBUG] Farm: Farm 11 has the following active sowing plans it intends to pursue in this tick: 1."
        )
        assertNextLine(
            "[IMPORTANT] Farm: Farm 11 finished its actions."
        )
    }
}

/**
 * Tests sowing and drought
 */
class Onefieldtestshouldsow : TestExtension() {
    override val name = "OneFieldTestShouldSow"
    override val description = "Tests one field with sowing"

    override val farms = "onefieldtest/farms.json"
    override val scenario = "onefieldtest/scenario.json"
    override val map = "onefieldtest/map.json"

    override val logLevel = "DEBUG"
    override val maxTicks = 2
    override val startYearTick = 19

    override suspend fun run() {
        skipUntilString(
            "[IMPORTANT] Farm Sowing: Machine 3 has sowed WHEAT according to sowing plan 1."
        )
        assertCurrentLine(
            "[IMPORTANT] Farm Sowing: Machine 3 has sowed WHEAT according to sowing plan 1."
        )
        skipUntilString(
            "[IMPORTANT] Incident: Incident 1 of type DROUGHT happened and affected tiles 38."
        )
        assertCurrentLine(
            "[IMPORTANT] Incident: Incident 1 of type DROUGHT happened and affected tiles 38."
        )
        skipUntilString(
            "[INFO] Soil Moisture: The soil moisture is below threshold in 0 FIELD and 0 PLANTATION tiles."
        )
        assertCurrentLine(
            "[INFO] Soil Moisture: The soil moisture is below threshold in 0 FIELD and 0 PLANTATION tiles."
        )
        skipUntilString("[IMPORTANT] Farm Action: Machine 1 performs IRRIGATING on tile 38 for 10 days.")
        assertCurrentLine("[IMPORTANT] Farm Action: Machine 1 performs IRRIGATING on tile 38 for 10 days.")
    }
}
