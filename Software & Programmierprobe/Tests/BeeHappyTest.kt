package de.unisaarland.cs.se.selab.incidents

import de.unisaarland.cs.se.selab.Constants
import de.unisaarland.cs.se.selab.board.BoardData
import de.unisaarland.cs.se.selab.board.BoardHandler
import de.unisaarland.cs.se.selab.board.Coordinate
import de.unisaarland.cs.se.selab.board.Plantation
import de.unisaarland.cs.se.selab.board.Tile
import de.unisaarland.cs.se.selab.board.TileType
import de.unisaarland.cs.se.selab.farms.Machine
import de.unisaarland.cs.se.selab.parser.ScenarioParser
import de.unisaarland.cs.se.selab.plants.Plant
import de.unisaarland.cs.se.selab.plants.PlantType
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class BeeHappyTest {
    val tile1 = Tile(
        1,
        Coordinate(1, 1),
        null,
        false,
        null,
        TileType.MEADOW
    )
    val tile2 = Plantation(
        2,
        Coordinate(2, 2),
        null,
        1,
        TileType.PLANTATION,
        5000,
        Plant(PlantType.APPLE, Constants.apple, 16),
    )
    val tile3 = Tile(
        3,
        Coordinate(3, 1),
        null,
        false,
        null,
        TileType.MEADOW
    )
    val tile4 = Plantation(
        4,
        Coordinate(4, 2),
        null,
        1,
        TileType.PLANTATION,
        5000,
        Plant(PlantType.APPLE, Constants.apple, 16),
    )
    val tile5 = Tile(
        5,
        Coordinate(5, 1),
        null,
        false,
        null,
        TileType.MEADOW
    )
    val tile6 = Plantation(
        6,
        Coordinate(6, 2),
        null,
        1,
        TileType.PLANTATION,
        5000,
        Plant(PlantType.ALMOND, Constants.almond, 16),
    )
    val tile7 = Tile(
        7,
        Coordinate(7, 1),
        null,
        false,
        null,
        TileType.MEADOW
    )
    val tile8 = Plantation(
        8,
        Coordinate(8, 2),
        null,
        1,
        TileType.PLANTATION,
        5000,
        Plant(PlantType.APPLE, Constants.apple, 16),
    )
    val tile9 = Tile(
        9,
        Coordinate(9, 1),
        null,
        false,
        null,
        TileType.MEADOW
    )
    val board =
        BoardData(
            mapOf(
                1 to tile1, 2 to tile2, 3 to tile3, 4 to tile4, 5 to tile5,
                6 to tile6, 7 to tile7, 8 to tile8, 9 to tile9
            )
        )
    val machines = emptyMap<Int, Machine>()

    @Test
    fun simpleBeeHappy() {
        val json = "src/systemtest/resources/incidentTest/scenarioBeeHappy.json"
        val scenarioParser = ScenarioParser()
        val incidents = scenarioParser.parse(json, board, 9, machines, emptyList(), 8).first
        val incidentHandler = IncidentHandler(mutableMapOf(7 to listOf(incidents[0])))
        val before = tile6.plant.getHarvestEstimate()
        incidentHandler.executeIncidents(7)
        val boardHandler = BoardHandler()
        boardHandler.computeEstimate(8, board)
        val after = tile6.plant.getHarvestEstimate()
        assertTrue { tile8.plant.getHarvestEstimate() == 1768000 }
        assertTrue { tile4.plant.getHarvestEstimate() == 1768000 }
        assertTrue { before == after }
    }
}
