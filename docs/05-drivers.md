# 05 — Driver SPI

How OC2 talks to the world. The core extensibility seam.

## The pattern (Linux device drivers / k8s CSI / OS-style)

OC2 defines stable **interfaces** (the SPI). Drivers (compiled Kotlin code shipped in mods) implement those interfaces for specific tile entity types. At mod load, drivers register with OC2. When a script calls a generic operation (`inventory.transfer(...)`), OC2 routes through the appropriate driver based on what tile entity is at the target position.

Player scripts never know which mod owns the underlying tile entity. Vanilla, AE2, RS, ID, Mekanism — all look the same from script-land.

## Why Path A (build our own driver layer) over Path B (depend on Integrated Dynamics)

- Players don't need to install ID
- Clean Kotlin API, no inherited opinions from another mod's design
- No coupling to ID's release cadence or breakage cycle
- Architecture can still bridge: someone could write an "ID driver" that exposes ID's networks back through OC2's SPI without OC2 ever depending on ID

## Driver SPI (compiled Kotlin only — no script-defined drivers in v1)

```kotlin
// Stable interfaces, defined once in OC2
interface IInventoryReader  { fun list(target: TileEntity): List<ItemStack> }
interface IInventoryWriter  { fun insert(target: TileEntity, stack: ItemStack): Int /* leftover */ }
interface IPropertyReader   { fun read(target: TileEntity, key: String): Any? }
interface IPropertyWriter   { fun write(target: TileEntity, key: String, value: Any) }
interface IRecipeQueueWriter { fun queue(target: TileEntity, recipeId: ResourceLocation, count: Int) }
interface IFluidReader      { fun fluids(target: TileEntity): List<FluidStack> }
interface IFluidWriter      { fun insertFluid(target: TileEntity, stack: FluidStack): Int }
interface IEnergyReader     { fun stored(target: TileEntity): Long; fun capacity(target: TileEntity): Long }
interface IEnergyWriter     { fun extract(target: TileEntity, amount: Long): Long }
```

Driver registration happens at mod load via the standard NeoForge event bus. Each driver declares which TE types it handles and which interfaces it implements.

## Driver matrix by release

### R1 — vanilla MC drivers
| Tile entity | Reader | Writer | Properties | Recipe |
|---|---|---|---|---|
| Chest / Barrel / Shulker | ✓ | ✓ | — | — |
| Hopper | ✓ | ✓ | — | — |
| Furnace / Blast / Smoker | ✓ (in/out slots) | ✓ (in slot) | progress, fuel | smelt |
| Brewing Stand | ✓ | ✓ | progress, fuel | brew |
| Cauldron | — | — | water level (R1 fluids minimal) | — |
| Crafting Table | — | — | — | (player-emulated craft via API) |
| Composter | ✓ | ✓ | level | — |
| Beehive | ✓ (honey) | — | bee count, honey level | — |

That's ~10-15 vanilla drivers, several thousand LOC, defines the SPI clearly.

### R2 — first major mod drivers
- **AE2** (highest priority): network as a single inventory handle; properties for power/channels
- **Refined Storage**: same shape as AE2
- **Botania**: mana spreaders, mana tablet readers (energy-like via property)
- **Create**: kinetic stress, tank fluids, mechanical crafter recipes

### R3+ — community drivers
PRs welcome. Reasonable candidates: Mekanism, Immersive Engineering, Thermal series, Pipez, Functional Storage, Industrial Foregoing.

## Driver discovery vs cartridge instantiation

Two related but distinct concepts:

- **Driver** — compiled Kotlin code that knows how to interact with a TE type. Registered once at mod load. Scoped per TE class.
- **Cartridge** — an in-game item slotted into an Adapter that exposes one of the driver's capabilities to a script. The cartridge IS the player-facing equivalent of "configure a driver to talk to this specific TE."

The driver is the engine; the cartridge is the configuration. A player slots an `InventoryReader` cartridge into an Adapter attached to a chest → OC2 routes inventory reads through the chest's vanilla `IInventoryReader` driver.

## What the SPI deliberately is NOT

- No `IStoragePool` / `IItemRegistry` — we don't model AE2's storage abstraction. The AE2 driver wraps an AE2 network as ONE inventory handle, not as a registry of items.
- No script-defined drivers in v1 — keeping driver code in compiled Kotlin protects performance and type safety. Maybe in a later release we add a "script driver" SPI for community drivers without a mod, but not yet.
- No async drivers in v1 — driver methods are sync. Long-running reads (e.g., AE2 query) happen on the worker thread; scripts await via coroutine yield. The driver method itself is a simple call.

## Capability advertisement

Each cartridge type exposes a set of named capabilities. When a script calls `channel.devices_with("InventoryReader")`, OC2 filters the registry by which adapters have an `InventoryReader` cartridge slotted. Player can also call `device.capabilities()` to introspect what a specific adapter offers.

## How a player adds a new mod's driver

1. The mod author (or community contributor) writes a Kotlin module that depends on OC2's `oc2-api` artifact
2. Implements one or more of the SPI interfaces for the mod's TE types
3. Registers the driver via OC2's annotation / event hook
4. Ships the module as a small companion mod (or merges into the host mod)

Driver mods are tiny — usually a few hundred LOC each, since they're just adapters to existing mod APIs.
