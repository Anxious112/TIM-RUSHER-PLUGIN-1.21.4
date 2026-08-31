# Tim -> RusherHack port progress

Source of truth for what's converted. Update as you go. Compile with:

```
cd /c/Users/Benhu/OneDrive/Desktop/Tim-RusherHack
export JAVA_HOME="/c/Users/Benhu/AppData/Roaming/PrismLauncher/java/java-runtime-delta"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew compileJava --stacktrace
```

Original Meteor addon source of truth: `C:\Users\Benhu\OneDrive\Desktop\Tim-source\` (full tree — modules, huds, commands, mixins). `pending-meteor-src/` here is just a staging subset; delete a file from it once ported.

## CURRENT STATUS (2026-08-31, session 2) — PORT COMPLETE
- **`./gradlew clean build` = BUILD SUCCESSFUL** (compileJava + remapJar; plugin jar builds).
- **Modules: 32/32 ported + registered.**
- **Mixins: 25/25 DONE** — all in src + mixins.tim.json.
- **Commands: 4/4 ported + registered** — PortalMakerCommand, RocketPilotCommand, LootLensCommand, DungeonAssistantCommand (extend `org.rusherhack.client.api.feature.command.Command`, root executor = bare `@CommandExecutor` method returning `String`).
- **HUDs: 21/21 ported + registered** — all extend `TimHudElement` (shared base in hud/, `HudElement` + text-line renderer). The Meteor originals do bespoke quad/bar/icon drawing; the port renders the same data as `§`-coloured text lines with an optional bg box. Per-HUD Meteor settings (alignment/scale/bars/icon layout) were dropped; each HUD keeps text-colour + shadow + background settings from the base.
- **Tim.java** registers all 32 modules, 4 commands, 21 HUDs in `onLoad()`.

### HUD port notes
- `HudElement.render()` only calls `renderContent(RenderContext, x, y)` — no background is drawn by the base, so `TimHudElement` draws its own via `getRenderer().begin(ctx.pose()) / drawRectangle / end()`.
- Font: `getFontRenderer().drawString(String, x, y, rgb, shadow)` — the no-PoseStack overload works directly, honours `§` codes.
- `getWidth()/getHeight()` are abstract (from `ElementBase`); `TimHudElement` caches them from the last `getLines()` render.
- HUD data getters that already existed on the ported modules were reused (Baromine.getCurrentStatus/getCurrentTargetCount/getMainHandDurabilityPercent/getSessionStartTime/getTargetBlock/targetStacks; DungeonAssistant.getTargetCounts/getTotalTargets; Chambers/CityAssistant.getStats; EightToOne.getTotalPortals/Anchors/Created; Gatekeeper.getTotalEndPortals/Gateways/getTotalElytrasFound/getElytrasNearby/getShulkersNearby/getChestsNearby; LootLens.getDoubleChestCount/getShulkerBoxCount/getEnderChestCount/getTotalContainers; NeighbourhoodWatch.getPlayerStatusPublic/isDisconnectOnPlayerArmed). No new module getters were needed.
- Standalone info HUDs (Position, Statistics, ServerReport, DuraPanel, Motance, SecondLife, PortalStock, InfoAssistant, LastSeenPlayer) compute from `mc` / `RusherHackAPI.getServerState()` directly.
- `RocketPilotCommand`/etc. resolve their module via `getModuleManager().getFeature("<name>")` + `instanceof ToggleableModule`.

### Session-2 porting notes (patterns used, reuse these)
- No `StringListSetting` in RusherHack → use comma-separated `StringSetting` + `.split(",")` (matches Penpal's existing convention). No `EnchantmentListSetting` → same (store enchant ids as strings).
- No `GameJoinedEvent`/`GameLeftEvent` → `EventLoadWorld` (fires on join). No `BlockUpdateEvent` → `EventPacket.Receive` on `ClientboundBlockUpdatePacket` + `ClientboundSectionBlocksUpdatePacket.runUpdates(BiConsumer)`. Chat receive: `EventAddChat` (`getChatComponent()`/`setChatComponent(Component)`). `ReceiveMessageEvent`→that.
- Keybind `.action(...)` callbacks → poll `bindSetting.getValue().isKeyDown()` with a `wasXPressed` edge-detect field in `onTick`.
- `WireframeEntityRenderer` → `renderer.drawBox(entity, event.getPartialTicks(), fill, outline, colorRGB)` (bbox outline only, no skeletal wireframe in RusherHack).
- Meteor `InvUtils.move().from(a).toArmor/toOffhand/toHotbar(b)` → `InvUtils.swapContainerSlots(fromContainer, toContainer)` with menu-slot ids: armor helmet=5 chest=6 legs=7 boots=8 (`armorContainer(meteorIdx)= 8 - meteorIdx`), main 9-35, hotbar `36+n`, offhand 45 (`InvUtils.OFFHAND_SLOT`). `InvUtils.toContainerSlot(invIdx)` maps hotbar 0-8→36-44. `InvUtils.swap(h,bool)`/`swapBack()` → `InventoryUtils.setHotbarSlot(int)` + saved `getSelectedHotbarSlot()`.
- `SoundEvents.*`: most drop the `ENTITY_`/`BLOCK_` prefix (`WARDEN_ROAR`, `RAVAGER_ROAR`, `EXPERIENCE_ORB_PICKUP`, `FLINTANDSTEEL_USE`, `BELL_BLOCK`, `PLAYER_LEVELUP`, `TOTEM_USE`, `VILLAGER_YES`) — but `NOTE_BLOCK_PLING` is a `Holder<SoundEvent>` (needs `.value()`). Verify each via compile.
- `mc.gameMode` = interactionManager; `useItemOn(player,hand,BlockHitResult)`=interactBlock, `useItem(player,hand)`=interactItem, `startDestroyBlock(pos,dir)`=attackBlock, `continueDestroyBlock(pos,dir)`=updateBlockBreakingProgress. `RusherHackAPI.getRotationManager().updateRotation(BlockPos | yaw,pitch | BlockHitResult)` replaces Meteor `Rotations.rotate`.
- Chunk scan: `chunk.getSections()` → `LevelChunkSection[]`; `section.hasOnlyAir()`, `section.maybeHas(Predicate<BlockState>)`, `section.getBlockState(x,y,z)`. Section minY = `(mc.level.getMinSectionY()+i)*16`. `mc.level.getChunkSource().hasChunk(cx,cz)` + `mc.level.getChunk(cx,cz)`.
- `AABB`: `.minmax(other)`=union, `.inflate(x)`=expand, `.getCenter()`→Vec3. `BlockPos.containing(Vec3)`=ofFloored. `BlockPos.distToCenterSqr(Position)` for pos↔Vec3, `.distSqr(Vec3i)` for pos↔pos.
- HUD cross-refs (e.g. NeighbourhoodWatch→LastSeenPlayerHud) stubbed with a no-op `notifyLastSeenHud()` + TODO until HUDs are ported.
- Datamine's two PortalMaker cross-refs are wired (`isPortalMakerActive()` guard in onDisable + doAutoCollect).

## Mixins (26 real, 4 dead/no-op dropped from original)
Dropped (no-op in original Meteor addon too, not ported): LavaMarkerMixin, DungeonAssistantMixin, ServerHealthcareSystemMixin, and unregistered dead files (ClientPlayNetworkHandlerMixin, GraveyardMixin, RocketPilotPlayerMixin, ServerHealthCareMixin).

- [ ] AbstractClientPlayerEntityMixin (written in pending-meteor-src/mixin, needs ThirdSight module first)
- [ ] DatamineMixin
- [ ] ElytraAssistantMixin
- [ ] ElytraAssistantSwingMixin
- [ ] EndGatewayBlockEntityAccessor
- [ ] EntityGlowingColorMixin
- [ ] EntityGlowingMixin
- [ ] EntityMixin
- [ ] HandledScreenAccessor
- [ ] HandledScreenMixin
- [x] HandmoldBobMixin
- [x] HandmoldMixin
- [x] HeldItemRendererAccessor
- [ ] IMouseAccessor
- [ ] InGameHudMixin
- [ ] InteractionAccessor
- [ ] PortalMakerMixin
- [ ] PortalTrackerMixin
- [ ] LivingEntityRendererMixin
- [ ] MixinSignBlockEntityRenderer
- [ ] PlayerEntityRendererMixin
- [ ] RocketPilotInputMixin
- [ ] RocketPilotMixin
- [ ] ThirdSightCameraMixin
- [ ] ThirdSightMouseMixin
- [ ] TunnelersMixin

## VERIFIED Mojmap mappings (via javap against real jars - do not re-guess these, ground truth confirmed)
- MultiPlayerGameMode: destroyBlock(BlockPos), continueDestroyBlock(BlockPos,Direction), destroyDelay(field), useItem(Player,InteractionHand), handleInventoryMouseClick(int,int,int,ClickType,Player), startPrediction(ClientLevel,PredictiveAction) [private, invoker target]
- PredictiveAction: predict(int sequence) -> Packet<ServerGamePacketListener>
- ServerboundPlayerActionPacket(Action,BlockPos,Direction,int sequence); nested enum Action
- ServerboundSwingPacket(InteractionHand); ServerboundSetCarriedItemPacket(int slot)
- LivingEntity: swing(InteractionHand), getItemInHand(InteractionHand), isFallFlying(), isUsingItem(), getUsedItemHand()
- Entity: isCurrentlyGlowing(), getEyeY()
- AbstractClientPlayer: getFieldOfViewModifier(boolean isFirstPerson, float scale) -- TWO ARGS not zero
- TheEndGatewayBlockEntity: private BlockPos exitPortal (field)
- AbstractContainerScreen<T>: leftPos, topPos, imageWidth, imageHeight, protected final T menu, T getMenu()
- ContainerScreen extends AbstractContainerScreen<ChestMenu> (the generic chest-like screen, NOT "GenericContainerScreen")
- ShulkerBoxScreen, InventoryScreen (names retained)
- Screen: protected Minecraft minecraft (field), addRenderableWidget(T), render(GuiGraphics,int,int,float)
- Tooltip.create(Component) / create(Component,Component) -- NOT ".of"
- AbstractContainerMenu: public final NonNullList<Slot> slots, public final int containerId, getSlot(int)
- Slot: public final Container container (NOT "inventory"), getItem(), hasItem()
- Inventory (=PlayerInventory), Player.getInventory()
- GameRenderer: bobView(PoseStack,float), bobHurt(PoseStack,float) -- both private
- ItemInHandRenderer (=HeldItemRenderer): renderArmWithItem(AbstractClientPlayer,float,float,InteractionHand,float,ItemStack,float,PoseStack,MultiBufferSource,int) -- private, invoker
- Gui (=InGameHud): renderCrosshair(GuiGraphics, DeltaTracker) -- private
- DeltaTracker (=RenderTickCounter) exists as interface, DeltaTracker.ZERO
- LevelRenderer (=WorldRenderer): renderEntity(Entity,double,double,double,float,PoseStack,MultiBufferSource) -- private
- OutlineBufferSource (=OutlineVertexConsumerProvider): setColor(int,int,int,int)
- ClientChunkCache (=ClientChunkManager): replaceWithPacketData(int,int,FriendlyByteBuf,CompoundTag,Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput>) -> LevelChunk; also has unload(...)
- MouseHandler (=Mouse): private double accumulatedDX, accumulatedDY (NOT cursorDeltaX/Y!)
- FlintAndSteelItem.useOn(UseOnContext) -> InteractionResult
- UseOnContext: getLevel(), getClickedPos(), getClickedFace(), getItemInHand(), getPlayer(), getHand()
- Level: isClientSide (field AND method both exist), setBlock(BlockPos,BlockState,int) and 4-arg overload, playSound(Player,BlockPos,SoundEvent,SoundSource,float,float)
- BlockState/BlockStateBase: isAir(), canBeReplaced(), canBeReplaced(BlockPlaceContext), canBeReplaced(Fluid)
- ItemStack: is(Item), is(TagKey<Item>), hurtAndBreak(int,LivingEntity,EquipmentSlot)
- com.mojang.math.Axis: XP/YP/ZP/XN/YN/ZN, .rotationDegrees(float); PoseStack: pushPose/popPose/mulPose/translate/scale
- LocalPlayer: public final ClientPacketListener connection; connection.sendCommand(String) confirmed via successful compile
- ModuleCategory.getOrRegister(String) / register(String) -- CAN create custom "Tim" category
- IFeatureManager<T>.getFeature(String name) -> Optional<T> -- keyed by STRING name/referenceKey, NOT by Class. Pattern: (X) RusherHackAPI.getModuleManager().getFeature("module-name").orElse(null)
- ToggleableModule/Module constructor order: (name, description, category) -- REVERSED from Meteor's (category, name, description)
- Module has NO info()/warning()/error() helpers (unlike Meteor) -- use this.sendNotification(NotificationType.INFO/WARNING/ERROR, msg) instead
- Settings use direct constructors + chaining, NOT builder pattern: new BooleanSetting(name,desc,default), new NumberSetting<>(name,desc,default,min,max), new EnumSetting<>(name,desc,default), .setVisibility(BooleanSupplier), .onChange(...)
- isActive() (Meteor) -> isToggled() (RusherHack)

## GOTCHA (caught once, avoid repeating)
Every Accessor/Invoker interface mixin (like HeldItemRendererAccessor) MUST be listed in mixins.tim.json's "mixins" array too, even though it's only referenced via cast from another mixin -- NOT registering it compiles fine but throws ClassCastException at runtime since Mixin never weaves the interface onto the target class. Always add accessor/invoker mixins to the config the moment they're created.

## Modules done so far: TotalDisposal, Handmold, Penpal, Graveyard, ThirdSight, Illushine, SafetyNet, ElytraAssistant, LavaMarker, Mobanom, Timethrottle, RocketPilot, Handhold (13/32)
## Mixins done so far (15/25): + ElytraAssistantSwingMixin. Remaining mixins needed: DatamineMixin, HandledScreenAccessor, HandledScreenMixin, InteractionAccessor, PortalMakerMixin, PortalTrackerMixin, MixinSignBlockEntityRenderer, RocketPilotInputMixin, RocketPilotMixin, TunnelersMixin (10 left)
## Modules done: +ChambersAssistant (14/32)
## Remaining modules (18): Baromine, CityAssistant, Datamine, DungeonAssistant, EightToOne, Gatekeeper, InspectorGadget, Inventory101, LootLens, ManorAssistant, Mendbot, NeighbourhoodWatch, PortalMaker, Raidar, ServerHealthcareSystem, SignScanner, Tunnelers, Waypearl
## STOPPED MID-FILE: Datamine.java has 2 KNOWN COMPILE ERRORS, fix these first before anything else:
1. Line ~251 `private void reset()` clashes with public `reset()` inherited from IFeatureConfigurable (Module implements it). RENAME to `private void resetState()` and update its 2 call sites (`this.reset()` -> `this.resetState()`, appears in onEnable() and onDisable()).
2. Line ~86 `collectWhitelist` field: `ItemListSetting.setVisibility(...)` returns the base `ListSetting<Item>` type, not `ItemListSetting` (setVisibility/onChange/etc on ListSetting are NOT covariant like the other setting types are). Change the field's declared type from `ItemListSetting collectWhitelist` to `ListSetting<Item> collectWhitelist` (add `import org.rusherhack.core.setting.ListSetting;`). This same issue will hit BlockListSetting/EntityTypeListSetting too if you ever chain .setVisibility()/.onChange() on them directly off the constructor -- same fix (declare as ListSetting<T>).
After fixing: PortalMaker cross-references were already stubbed out with TODO comments (search "TODO: skip" in Datamine.java) since PortalMaker isn't ported yet -- leave those as-is until PortalMaker is done. Then: register Datamine in Tim.java (already done), compile, fix any remaining errors, build, deploy, remove from pending-meteor-src/modules/Datamine.java, update this file's module count to 16/32.

## GOTCHA: Vec3.closerThan(Position,double) exists but there is NO closerThan(Vec3i,double) overload -- I hallucinated that once without verifying and it cost 2 compile-fix cycles. For "is X within distance of a BlockPos" checks, just use `someVec3.distanceTo(Vec3.atCenterOf(blockPos)) < radius` instead of trying to find a closerThan overload that takes BlockPos/Vec3i directly.
## More confirmed symbols from ChambersAssistant: Breeze is at net.minecraft.world.entity.monster.breeze.Breeze (nested package); WindCharge at net.minecraft.world.entity.projectile.windcharge.WindCharge (nested package) -- NOT directly in .monster/.projectile like most other entities. DecoratedPotBlockEntity.getTheItem() not getStack(). VaultBlockEntity at net.minecraft.world.level.block.entity.vault.VaultBlockEntity, VaultState at .vault.VaultState (nested package, not directly under block.entity). Slot.container (not .inventory).
## Key discovery from RocketPilot: ServerboundPlayerCommandPacket(Entity, Action) with Action.START_FALL_FLYING replaces the old ClientCommandC2SPacket for starting elytra gliding. BlockGetter.clip(ClipContext) (inherited into Level) replaces World.raycast(RaycastContext); ClipContext.Block.COLLIDER / ClipContext.Fluid.NONE. Vec3.scale() not multiply(), .yRot(f) not rotateY(f), .horizontalDistance() not horizontalLength(). Entity.tickCount = Yarn's age. LivingEntity.jumpFromGround() = jump(). yBodyRot/yHeadRot public fields. Player.getStats().getValue(StatType,T) = getStatHandler().getStat(). IModule.getMetadata() = Meteor's getInfoString() override point.
## Key discovery: RusherHack has EventTimerSpeed (setSpeed(float)/setOverrideTimer(boolean)) as the built-in game-speed-override mechanism -- equivalent to Meteor's Timer module setOverride(). Also IServerState (RusherHackAPI.getServerState()) directly exposes getTPS()/getPing() -- no need to reimplement Meteor's TickRate utility.
## Mixins done so far: HandmoldBobMixin, HandmoldMixin, HeldItemRendererAccessor, AbstractClientPlayerEntityMixin, ThirdSightCameraMixin, ThirdSightMouseMixin, IMouseAccessor, LivingEntityRendererMixin, EntityMixin, EntityGlowingMixin, EntityGlowingColorMixin, InGameHudMixin, ElytraAssistantMixin (13/25 -- PlayerEntityRendererMixin DROPPED/MERGED into LivingEntityRendererMixin. ElytraAssistantSwingMixin parked in pending-meteor-src/mixin/ until RocketPilot module is ported.)

## More verified mappings from SafetyNet/ElytraAssistant work
- Options key fields: keyUse, keyJump, keyUp(=forward!), keyDown(=back), keyLeft, keyRight, keyAttack, keySprint, keyInventory, keySwapOffhand, keyDrop -- NOT "forwardKey"/"jumpKey" etc. KeyMapping.setDown(boolean) not setPressed.
- Minecraft.gui field (= Yarn's inGameHud); Gui.setTitle(Component)/setSubtitle(Component)
- Level.OVERWORLD/NETHER/END static ResourceKey<Level> constants; Level.dimension() returns ResourceKey<Level> to compare against them
- Inventory.selected (public int field, NOT selectedSlot!); Inventory.getItem(int)
- Player.containerMenu field (= currentScreenHandler), AbstractContainerMenu.getCarried() (= getCursorStack)
- Entity.onGround() method (not isOnGround())
- ItemStack.getDamageValue() (not getDamage()), getMaxDamage() retained
- ItemStack.getHoverName() for display name (Item itself has no getDescription()->Component, only getDescriptionId()->String)
- EquipmentSlot enum names retained (HEAD/CHEST/LEGS/FEET/MAINHAND/OFFHAND/BODY); LivingEntity.getItemBySlot(EquipmentSlot)
- Item.getDefaultMaxStackSize() (not getMaxCount())
- InteractionResult is a sealed interface now but InteractionResult.SUCCESS/FAIL/PASS/CONSUME still work as static constants
- No Meteor InvUtils equivalent exists in RusherHack -- wrote utils/InvUtils.java with toContainerSlot()/swapContainerSlots()/moveToSlot() raw-click helpers. Menu slot layout for container id 0: armor 5-8 (helmet,chest,legs,boots), main 9-35, hotbar 36-44, offhand 45.
- RusherHack's InventoryUtils.findItemHotbar(Item)/findItem(...) DOES exist and is usable for search (just no swap/move helpers)
- LocalPlayer/Player has no isFallFlying override issue -- confirmed same as LivingEntity
- SoundEvents names mostly drop the vanilla resource-path prefix style Yarn used (e.g. BLOCK_BELL_USE -> BELL_BLOCK, BLOCK_ANVIL_LAND -> ANVIL_LAND, ENTITY_EXPERIENCE_ORB_PICKUP -> EXPERIENCE_ORB_PICKUP, ENTITY_WITHER_SPAWN -> WITHER_SPAWN, ENTITY_CREEPER_PRIMED -> CREEPER_PRIMED) -- but NOT ALL of them, some do still exist with old-style names (verify each one, don't assume the pattern)

## IMPORTANT: PlayerEntityRendererMixin does not exist as planned
Mojmap's PlayerRenderer does NOT override scale(RenderState,PoseStack) -- it just inherits LivingEntityRenderer's generic scale() method. So player-scaling (Illushine player-scale settings) and mob-scaling both had to be merged into ONE LivingEntityRendererMixin hook, distinguished by `instanceof Player` vs `instanceof Mob` inside the same scale() injection. Do not try to write a separate PlayerEntityRendererMixin -- there is no distinct method to target.
Also: EntityRenderState has NO "id" field in Mojmap (unlike Yarn's LivingEntityRenderState.id) -- must use the ThreadLocal-capture-during-extractRenderState pattern to know which entity is being scaled, not a state.id lookup.
extractRenderState signature to target explicitly (ambiguous by simple name due to bridge method): "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V"

## More verified mappings from Illushine work
- Level.getDayTime() = Yarn's getTimeOfDay()
- No canSeeSky(BlockPos) method exists in 1.21.4 Mojmap Level/LevelReader anymore (lighting engine refactor). Use: mc.level.getLightEngine().getLayerListener(LightLayer.SKY).getLightValue(pos) >= 15 as the closest equivalent.
- LivingEntity.isBaby() (not on Mob directly, inherited from LivingEntity)
- HostileEntity -> net.minecraft.world.entity.monster.Monster; Angerable -> net.minecraft.world.entity.NeutralMob (interface); PassiveEntity -> net.minecraft.world.entity.animal.Animal
- ClientLevel.entitiesForRendering() = Yarn's world.getEntities() (returns Iterable<Entity>)
- com.mojang.blaze3d.platform.Window (NOT net.minecraft.client.Window): getGuiScaledWidth()/getGuiScaledHeight()
- GuiGraphics.fill(x1,y1,x2,y2,color) confirmed retained name
- IRenderer3D.drawBox(Entity, partialTicks, fill, outline, color) works for simple entity bounding-box outlines -- used as the RusherHack replacement for Meteor's WireframeEntityRenderer (no true skeletal wireframe renderer exists in RusherHack's API, so Wireframe mode was simplified to a bounding-box outline)
## Extra verified mappings found along the way:
- EventMouse.Scroll (org.rusherhack.client.api.events.client.input.EventMouse$Scroll) extends EventCancellable: getScrollDeltaX/Y(), setCancelled(boolean) -- NOT event.cancel()
- Camera.getMaxZoom(float) is the Mojmap equivalent of Yarn's clipToSpace -- confirmed
- MouseHandler.handleAccumulatedMovement() (public, no-arg, called every frame) is the real equivalent of Yarn's updateMouse() -- NOT turnPlayer(double) which is a private helper it calls internally. Caught this before it became a silent runtime bug.
- Options.getCameraType()/setCameraType(CameraType), CameraType enum (FIRST_PERSON/THIRD_PERSON_BACK/THIRD_PERSON_FRONT).isFirstPerson()
- Options.fov()/sensitivity()/invertYMouse() return OptionInstance<T> with .get()/.set(T)
- Entity.getYRot()/getXRot() = Yarn's getYaw()/getPitch()
- IRenderer3D/IRenderer2D need explicit renderer.begin(event.getMatrixStack()) / renderer.end() wrapping every render, unlike Meteor where the event's renderer is pre-wrapped. EventRender3D/EventRender2D both extend EventRender which has getMatrixStack()/getPartialTicks() (not on the subclass directly, inherited).
- IRenderer3D.drawBox takes ONE packed color for fill+outline (not separate), and (x,y,z,width,height,depth) not min/max corners. Added utils/RenderUtils.java helper for alpha-packing.
- getFeature() lookups always need the module's registered string key (its "name" constructor arg, e.g. "third-sight"), not the display name.

## Modules (32)
- [x] Baromine
- [ ] ChambersAssistant
- [ ] CityAssistant
- [ ] Datamine
- [ ] DungeonAssistant (2061 lines - huge, custom GL rendering)
- [ ] EightToOne
- [ ] ElytraAssistant
- [ ] Gatekeeper
- [ ] Graveyard
- [ ] Handhold
- [ ] Handmold
- [ ] Illushine
- [ ] InspectorGadget
- [ ] Inventory101
- [ ] LavaMarker
- [ ] LootLens
- [ ] ManorAssistant
- [ ] Mendbot
- [ ] Mobanom
- [ ] NeighbourhoodWatch
- [ ] Penpal
- [ ] PortalMaker
- [ ] Raidar
- [ ] RocketPilot
- [ ] SafetyNet
- [ ] ServerHealthcareSystem
- [ ] SignScanner
- [ ] ThirdSight
- [ ] Timethrottle
- [ ] TotalDisposal
- [ ] Tunnelers
- [ ] Waypearl
- [ ] GlowingRegistry (utils, no MC deps - copy verbatim, DONE)

## HUDs (20) - not started
BaromineHud, ChambersAssistantHud, CityAssistantHud, DungeonAssistantHud, DuraPanelHUD, EightToOneHUD,
EndAssistantHud, GatekeeperHUD, InfoAssistantHud, LastSeenPlayerHud, LootLensHud, MotanceHud,
NeighbourhoodWatchHUD, PortalStockHud, PositionHud, RocketPilotHud, SecondLifeHUD, ServerReportHUD,
StatisticsInformation, TimeThrottleHUD

## Commands (4) - not started
DungeonAssistantCommand, LootLensCommand, PortalMakerCommand, RocketPilotCommand

## Entrypoint - not started
Tim.java -> com.example.addon.Tim extends org.rusherhack.client.api.plugin.Plugin
Register all modules/huds/commands in onLoad() via RusherHackAPI.getModuleManager/getHudManager/getCommandManager().registerFeature(...)

## Key Yarn -> Mojmap mapping notes (MC 1.21.4, moderate-high confidence, verify via compile)
- MinecraftClient -> net.minecraft.client.Minecraft; field `client`(Screen) -> `minecraft`; mc.world -> mc.level; mc.player -> mc.player (same); mc.currentScreen -> mc.screen; mc.interactionManager -> mc.gameMode; mc.options -> mc.options (same)
- ClientPlayerEntity -> net.minecraft.client.player.LocalPlayer
- AbstractClientPlayerEntity -> net.minecraft.client.player.AbstractClientPlayer
- ClientPlayerInteractionManager -> net.minecraft.client.multiplayer.MultiPlayerGameMode; attackBlock->destroyBlock; updateBlockBreakingProgress->continueDestroyBlock; blockBreakingCooldown(shadow field)->destroyDelay; interactItem->useItem; clickSlot->handleInventoryMouseClick; cancelBlockBreaking->stopDestroyBlock (verify)
- World/ClientWorld -> net.minecraft.world.level.Level / ClientLevel; isClient(field) -> isClientSide; setBlockState->setBlock; getEntityById->getEntity
- BlockPos.offset(Direction)->relative; .up(n)->above(n); .down()->below()
- PlayerEntity -> net.minecraft.world.entity.player.Player; getStackInHand->getItemInHand; swingHand->swing; isGliding->isFallFlying
- ItemStack.isOf(Item)->is(Item); .damage(...)->hurtAndBreak(...)
- Hand -> InteractionHand; ActionResult -> InteractionResult
- Text -> net.minecraft.network.chat.Component; Text.literal->Component.literal
- HandledScreen -> AbstractContainerScreen (net.minecraft.client.gui.screens.inventory); x/y(shadow)->leftPos/topPos; backgroundWidth->imageWidth; getScreenHandler()->getMenu(); ScreenHandler->AbstractContainerMenu; syncId->containerId; SlotActionType->ClickType
- ButtonWidget -> net.minecraft.client.gui.components.Button; DrawContext -> GuiGraphics; addDrawableChild->addRenderableWidget
- InGameHud -> net.minecraft.client.gui.Gui; RenderTickCounter -> DeltaTracker
- WorldRenderer -> net.minecraft.client.renderer.LevelRenderer; OutlineVertexConsumerProvider -> OutlineBufferSource; VertexConsumerProvider -> MultiBufferSource
- MatrixStack -> com.mojang.blaze3d.vertex.PoseStack; .push/.pop -> pushPose/popPose; .multiply -> mulPose; RotationAxis -> com.mojang.math.Axis (XP/YP/ZP)
- HeldItemRenderer -> net.minecraft.client.renderer.ItemInHandRenderer; renderFirstPersonItem -> renderArmWithItem
- DataComponentTypes -> net.minecraft.core.component.DataComponents
- GameRenderer -> same name (Mojmap keeps it); bobView -> likely same
- Mouse -> net.minecraft.client.MouseHandler; cursorDeltaX/Y fields -> UNCERTAIN, verify via compile
- LivingEntityRenderer/PlayerEntityRenderer -> same/PlayerRenderer; MobEntity->Mob
- ClientChunkManager -> net.minecraft.client.multiplayer.ClientChunkCache; loadChunkFromPacket -> replaceWithPacketData (UNCERTAIN signature, verify via compile); WorldChunk -> LevelChunk
- FlintAndSteelItem same name; useOnBlock -> useOn; ItemUsageContext -> UseOnContext; getBlockPos->getClickedPos; getSide->getClickedFace
- Rendering API completely different: RusherHack IRenderer3D.drawBox(BlockPos/Entity/xyz+dims, fill, outline, color) - NOT Meteor's box(Box,fillColor,lineColor,ShapeMode,lineWidth). Every module's onRender must be rewritten against RusherHack's actual renderer, not a 1:1 API swap.
