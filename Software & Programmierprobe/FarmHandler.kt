package de.unisaarland.cs.se.selab.farms
import com.sun.tools.javac.code.TypeAnnotationPosition.field
import de.unisaarland.cs.se.selab.board.BoardData
import de.unisaarland.cs.se.selab.board.Fertile
import de.unisaarland.cs.se.selab.board.Field
import de.unisaarland.cs.se.selab.board.Tile
import de.unisaarland.cs.se.selab.board.TileType
import de.unisaarland.cs.se.selab.logger.Logger
import de.unisaarland.cs.se.selab.plants.PlantData
import de.unisaarland.cs.se.selab.plants.PlantType
import java.util.SortedMap
import java.util.SortedSet
import kotlin.collections.orEmpty
import kotlin.collections.set
import kotlin.collections.sortedWith

const val TICKTIME = 14

/**
 * Handles the farm action*/
class FarmHandler(
    val unsortedIdToFarm: Map<Int, Farm>,
    val plantData: Map<PlantType, PlantData>,
    val unsortedMachines: Map<Int, Machine>,
    val pathFinder: PathFinder
) {
    var harvestAmount = 0
    val idToFarm = unsortedIdToFarm.toSortedMap()
    val machines = unsortedMachines.toSortedMap()

    /**
     * Started by simulator*/
    fun farmAction(tick: Int, yearTick: Int, board: BoardData) {
        val fertiles = board.getFertiles().toSortedMap()
        for (farm in idToFarm.values) {
            Logger.farmStartAction(farm.id)
            val remainingMachines = assembleMachines(farm).toSortedSet(compareBy({ it.duration }, { it.id }))
            val sowFields = assembleSowableFields(farm.fields, fertiles, yearTick)
            val finishedFields = mutableMapOf<Int, Fertile>()
            sow(
                sowFields,
                farm,
                remainingMachines,
                finishedFields = finishedFields,
                board = board,
                yearTick = yearTick,
                tick = tick,
                fertiles = fertiles
            )
            val fieldMap = createActionMap(farm.fields, fertiles, yearTick)
            val plantationMap = createActionMap(farm.plantages, fertiles, yearTick)
            for ((action, fertileType) in listOf(
                Action.HARVESTING to plantationMap[Action.HARVESTING],
                Action.HARVESTING to fieldMap[Action.HARVESTING],
                Action.CUTTING to plantationMap[Action.CUTTING]
            )) {
                requireNotNull(fertileType)
                performPrioritizedAction(
                    action,
                    remainingMachines,
                    fertileType,
                    finishedFields,
                    board,
                    farm,
                    yearTick
                )
            }
            val machines = remainingMachines.toSortedSet(compareBy({ it.duration }, { it.id }))
            val iter = machines.iterator()
            while (iter.hasNext()) {
                val machine = iter.next()
                requireNotNull(machine)
                performNonPrioritizedAction(
                    machine,
                    fieldMap,
                    plantationMap,
                    finishedFields,
                    board,
                    farm,
                    yearTick
                )
            }
            Logger.farmFinishedAction(farm.id)
        }
    }

    /**
     * Assemble usable machines*/
    fun assembleMachines(farm: Farm): MutableList<Machine> {
        val remainingMachines = mutableListOf<Machine>()
        for (machId in farm.machines) {
            val machine = machines[machId] ?: continue
            if (machine.isUsable()) {
                remainingMachines.add(machine)
            } else {
                machine.decreaseBrokenFor()
            }
        }
        return remainingMachines
    }

    /**
     * For all field plants assemble the fields that can currently sow this plant*/
    fun assembleSowableFields(
        fields: List<Int>,
        fertiles: SortedMap<Int, Fertile>,
        yearTick: Int
    ): Map<PlantType, SortedSet<Fertile>> {
        val sowFields = mutableMapOf<PlantType, SortedSet<Fertile>>()
        for (plantType in PlantType.entries) {
            sowFields[plantType] = sortedSetOf<Fertile>(compareBy { it.id })
        }
        for (fieldId in fields) {
            val fieldFound = fertiles[fieldId]
            if (fieldFound == null || fieldFound !is Field) {
                continue
            }
            val sowablePlants = fieldFound.sowablePlants(yearTick, plantData)
            for (plantType in sowablePlants) {
                val swo = sowFields[plantType] ?: continue
                swo.add(fieldFound)
            }
        }
        return sowFields
    }

    /**
     * Start executing active sowing plans*/
    fun sow(
        sowableFields: Map<PlantType, SortedSet<Fertile>>,
        farm: Farm,
        remainingMachines: SortedSet<Machine>,
        finishedFields: MutableMap<Int, Fertile>,
        board: BoardData,
        yearTick: Int,
        tick: Int,
        fertiles: SortedMap<Int, Fertile>
    ) {
        val planIds = mutableListOf<Int>()
        for (plan in farm.plans) {
            if (plan.isActive(tick)) {
                planIds.add(plan.id)
            }
        }
        Logger.farmSowingPlan(farm.id, planIds)
        val delete = mutableListOf<SowingPlan>()
        for (plan in farm.plans.sortedWith(compareBy({ it.tick }, { it.id })).toMutableList()) {
            if (planIds.contains(plan.id)) {
                executeSowingPlan(
                    farm,
                    plan,
                    sowableFields,
                    remainingMachines,
                    finishedFields,
                    fertiles,
                    board,
                    yearTick,
                    delete
                )
            }
        }
        farm.plans.removeAll(delete)
    }

    /**
     * Try to execute one sowing plan*/
    fun executeSowingPlan(
        farm: Farm,
        plan: SowingPlan,
        sowableFields: Map<PlantType, SortedSet<Fertile>>,
        remainingMachines: SortedSet<Machine>,
        finishedFields: MutableMap<Int, Fertile>,
        fertiles: SortedMap<Int, Fertile>,
        board: BoardData,
        yearTick: Int,
        delete: MutableList<SowingPlan>
    ) {
        val toSow = plan.plant
        val sowFieldIds = plan.fields
        val sowFields = sortedSetOf<Field>(compareBy { it.id })
        for (sid in sowFieldIds) {
            val fert = fertiles[sid]
            if (fert is Field) {
                sowFields.add(fert)
            }
        }
        val commonFields = sowFields.intersect(sowableFields[toSow].orEmpty().toSet()).toSortedSet(compareBy { it.id })
        for (field in commonFields) {
            require(field is Field)
            if (field.id in finishedFields) {
                continue
            }
            val machine = findBestMachine(
                field, remainingMachines, Action.SOWING, toSow, board, farm
            ) ?: continue
            field.plant.sow(toSow, plantData[toSow] ?: error("Do not the detekt"), yearTick)
            Logger.machinePerformedAction(machine.id, Action.SOWING, field.id, machine.duration)
            Logger.machineSowed(machine.id, toSow, plan.id)
            finishedFields[field.id] = field
            remainingMachines.remove(machine)
            delete.add(plan)
            var remainingTime = TICKTIME - 2 * machine.duration
            var currentField: Fertile = field
            val plantsToActOn = commonFields.filter { it.id !in finishedFields }.toSortedSet(compareBy { it.id })
            while (remainingTime >= 0 && plantsToActOn.isNotEmpty()) {
                currentField = nextField(
                    Action.SOWING,
                    toSow,
                    commonFields,
                    finishedFields,
                    machine,
                    currentField,
                    farm,
                    board,
                    yearTick
                ) ?: break
                currentField.plant.sow(toSow, plantData[toSow] ?: error("FUCK DETEKT"), yearTick)
                Logger.machinePerformedAction(machine.id, Action.SOWING, currentField.id, machine.duration)
                Logger.machineSowed(machine.id, toSow, plan.id)
                finishedFields[currentField.id] = currentField
                remainingTime -= machine.duration
                plantsToActOn.remove(currentField)
            }
            Logger.machineFinished(machine.id, machine.location.id)
        }
    }

    /**
     * Assemble fields and the actions they can perform in this tick*/
    fun createActionMap(
        fields: List<Int>,
        fertiles: Map<Int, Fertile>,
        yearTick: Int
    ): MutableMap<Action, SortedSet<Fertile>> {
        val actionMap = mutableMapOf<Action, SortedSet<Fertile>>()
        val acts = listOf(Action.CUTTING, Action.MOWING, Action.WEEDING, Action.IRRIGATING, Action.HARVESTING)
        for (currentAct in acts) {
            actionMap[currentAct] = sortedSetOf<Fertile>(compareBy { it.id })
        }
        for (fieldId in fields) {
            val fieldFound = fertiles[fieldId] ?: continue
            val performableActions = fieldFound.performableActions(yearTick)
            for (currentAct in acts) {
                if (currentAct in performableActions) {
                    actionMap[currentAct]?.add(fieldFound)
                }
            }
        }
        return actionMap
    }

    /**
     * Perform actions  that are sorted by field id*/
    fun performPrioritizedAction(
        action: Action,
        remainingMachines: SortedSet<Machine>,
        plantsToActOn: SortedSet<Fertile>,
        finishedFields: MutableMap<Int, Fertile>,
        board: BoardData,
        farm: Farm,
        yearTick: Int
    ) {
        for (field in plantsToActOn) {
            if (field == null || field.id in finishedFields) {
                continue
            }
            val plant = field.plant
            val machine = findBestMachine(field, remainingMachines, action, plant.type, board, farm) ?: continue
            performAction(action, field, machine, finishedFields, farm.id, yearTick)
            remainingMachines.remove(machine)
            var remainingTime = TICKTIME - 2 * machine.duration
            var currentField: Fertile = field
            finishedFields[field.id] = field
            if (action == Action.HARVESTING) {
                continueWithHarvesting(currentField, plantsToActOn, finishedFields, machine, board, farm, yearTick)
                harvestAmount = 0
            } else {
                while (remainingTime >= 0) {
                    currentField = nextField(
                        action,
                        null,
                        plantsToActOn,
                        finishedFields,
                        machine,
                        currentField,
                        farm,
                        board,
                        yearTick
                    ) ?: break
                    remainingTime -= machine.duration
                }
                Logger.machineFinished(machine.id, machine.location.id)
            }
            harvestAmount = 0
        }
    }

    /**
     * Find machine with the best duration for this field and action*/
    fun findBestMachine(
        fertile: Fertile,
        remainingMachines: SortedSet<Machine>,
        action: Action,
        plantType: PlantType?,
        board: BoardData,
        farm: Farm
    ): Machine? {
        var bestMachine: Machine? = null
        if (remainingMachines.isEmpty()) {
            return null
        }
        var bestDuration = TICKTIME + 1
        for (machine in remainingMachines.sortedBy { it.id }) {
            requireNotNull(machine)
            if (machine.duration < bestDuration && action in machine.actions && plantType in machine.plants) {
                if (pathFinder.reachable(machine.location, fertile, farm.id, board)) {
                    bestMachine = machine
                    bestDuration = machine.duration
                }
            }
        }
        return bestMachine
    }

    /**
     * Continue with next field if machine still has time*/
    fun nextField(
        action: Action,
        currentPlantType: PlantType?,
        plantsToActOn: SortedSet<Fertile>,
        finishedFertiles: MutableMap<Int, Fertile>,
        machine: Machine,
        currentLocation: Tile,
        farm: Farm,
        board: BoardData,
        yearTick: Int
    ): Fertile? {
        for (fertile in plantsToActOn) {
            require(fertile is Fertile)
            if (fertile.id in finishedFertiles) {
                continue
            }
            if (
                !machineCanHandle(machine, fertile) ||
                (action == Action.HARVESTING && fertile.plant.type != currentPlantType)
            ) {
                continue
            }
            val harvest = action == Action.HARVESTING
            if (pathFinder.canContinue(currentLocation, fertile, farm.id, board, harvest)) {
                if (action != Action.SOWING) {
                    performAction(action, fertile, machine, finishedFertiles, farm.id, yearTick)
                }
                return fertile
            }
        }
        return null
    }

    /**
     * Perform actions that are sorted after machine id*/
    fun performNonPrioritizedAction(
        machine: Machine,
        fieldMap: Map<Action, SortedSet<Fertile>>,
        plantationMap: Map<Action, SortedSet<Fertile>>,
        finishedFields: MutableMap<Int, Fertile>,
        board: BoardData,
        farm: Farm,
        yearTick: Int
    ) {
        for ((action, fertileType) in listOf(
            Action.IRRIGATING to fieldMap[Action.IRRIGATING],
            Action.WEEDING to fieldMap[Action.WEEDING],
            Action.IRRIGATING to plantationMap[Action.IRRIGATING],
            Action.MOWING to plantationMap[Action.MOWING]
        )) {
            requireNotNull(fertileType)
            if (action !in machine.actions || fertileType.isEmpty()) {
                continue
            }
            for (fertile in fertileType.filter { it.id !in finishedFields }) {
                require(fertile is Fertile)
                if (!machineCanHandle(machine, fertile) ||
                    !pathFinder.reachable(machine.location, fertile, farm.id, board)
                ) {
                    continue
                }
                performAction(action, fertile, machine, finishedFields, farm.id, yearTick)
                val plantations = plantationMap[Action.IRRIGATING]
                requireNotNull(plantations)
                continueWithSomething(
                    action,
                    finishedFields,
                    machine,
                    fertile,
                    farm,
                    board,
                    yearTick,
                    fertileType,
                    plantations
                )
                return
            }
        }
    }

    /**
     * Everything that needs to be done when a machines performs an action
     */
    fun performAction(
        action: Action,
        fertile: Fertile,
        machine: Machine,
        finishedFields: MutableMap<Int, Fertile>,
        farmId: Int,
        yearTick: Int
    ) {
        val amount: Int? = fertile.performAction(action, yearTick)
        Logger.machinePerformedAction(machine.id, action, fertile.id, machine.duration)
        finishedFields[fertile.id] = fertile
        if (action == Action.HARVESTING) {
            requireNotNull(amount)
            Logger.machineCollected(farmId, machine.id, amount, fertile.plant.type)
            harvestAmount += amount
        }
    }

    /**
     * Continue with harvesting
     */
    fun continueWithHarvesting(
        field: Fertile,
        plantsToActOn: SortedSet<Fertile>,
        finishedFields: MutableMap<Int, Fertile>,
        machine: Machine,
        board: BoardData,
        farm: Farm,
        yearTick: Int
    ) {
        var remainingTime = TICKTIME - 2 * machine.duration
        var lastField = field
        var currentField: Fertile? = field
        while (remainingTime >= 0 && currentField != null) {
            lastField = currentField
            currentField = nextField(
                Action.HARVESTING,
                currentField.plant.type,
                plantsToActOn,
                finishedFields,
                machine,
                currentField,
                farm,
                board,
                yearTick
            )
            remainingTime -= machine.duration
        }
        if (!pathFinder.reachableWithHarvest(lastField, machine.location, farm.id, board)) {
            val loc = pathFinder.findNearestShed(lastField, farm, board)
            if (loc != null) {
                machine.location = loc
                Logger.machineFinished(machine.id, machine.location.id)
                Logger.machineUnloads(machine.id, harvestAmount, field.plant.type)
                harvestAmount = 0
            } else {
                machine.location = lastField
                machine.setStuck()
                Logger.machineFinishedNoReturn(machine.id)
                harvestAmount = 0
            }
        } else {
            Logger.machineFinished(machine.id, machine.location.id)
            Logger.machineUnloads(machine.id, harvestAmount, field.plant.type)
            harvestAmount = 0
        }
    }

    /**
     * Continue with irrigating
     */
    fun continueWithIrrigating(
        finishedFields: MutableMap<Int, Fertile>,
        machine: Machine,
        currentLocation: Fertile,
        farm: Farm,
        board: BoardData,
        yearTick: Int,
        fields: SortedSet<Fertile>,
        plantations: SortedSet<Fertile>
    ) {
        var remainingTime = TICKTIME - 2 * machine.duration
        var currentField: Fertile? = currentLocation
        var lastField: Fertile? = currentField
        while (remainingTime >= 0 && currentField != null) {
            lastField = currentField
            currentField = nextField(
                Action.IRRIGATING,
                null,
                fields,
                finishedFields,
                machine,
                lastField,
                farm,
                board,
                yearTick
            )
            if (currentField == null) {
                currentField = nextField(
                    Action.IRRIGATING,
                    null,
                    plantations,
                    finishedFields,
                    machine,
                    lastField,
                    farm,
                    board,
                    yearTick
                )
            }
            remainingTime -= machine.duration
        }
        Logger.machineFinished(machine.id, machine.location.id)
    }

    /**
     * When a machine still has time
     */
    fun continueWithSomething(
        action: Action,
        finishedFields: MutableMap<Int, Fertile>,
        machine: Machine,
        currentLocation: Fertile,
        farm: Farm,
        board: BoardData,
        yearTick: Int,
        fertiles: SortedSet<Fertile>,
        plantations: SortedSet<Fertile>
    ) {
        if (action == Action.IRRIGATING && currentLocation.type == TileType.FIELD) {
            continueWithIrrigating(
                finishedFields,
                machine,
                currentLocation,
                farm,
                board,
                yearTick,
                fertiles,
                plantations
            )
            return
        }
        var remainingTime = TICKTIME - 2 * machine.duration
        var currentField: Fertile? = currentLocation
        while (remainingTime >= 0 && currentField != null) {
            currentField = nextField(
                action,
                null,
                fertiles,
                finishedFields,
                machine,
                currentField,
                farm,
                board,
                yearTick
            )
            remainingTime -= machine.duration
        }
        Logger.machineFinished(machine.id, machine.location.id)
    }

    private fun machineCanHandle(
        machine: Machine,
        fertile: Fertile
    ): Boolean {
        return machine.plants.contains(fertile.plant.type) || !fertile.plant.isSown()
    }
}
