package dicemc.money.event;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dicemc.money.MoneyMod;
import dicemc.money.MoneyMod.AcctTypes;
import dicemc.money.setup.Config;
import dicemc.money.storage.DatabaseManager;
import dicemc.money.storage.MoneyWSD;
import net.minecraft.ChatFormatting;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.TriState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.WritableBookItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

@EventBusSubscriber( modid=MoneyMod.MOD_ID, bus= EventBusSubscriber.Bus.GAME)
public class EventHandler {
	public static final String IS_SHOP = "is-shop";
	public static final String ACTIVATED = "shop-activated";
	public static final String OWNER = "owner";
	public static final String ITEMS = "items";
	public static final String TYPE = "shop-type";
	public static final String PRICE = "price";

	@SuppressWarnings("resource")
	@SubscribeEvent
	public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
		if (!event.getEntity().level().isClientSide && event.getEntity() instanceof ServerPlayer player) {
			double balP = MoneyWSD.get().getBalance(AcctTypes.PLAYER.key, player.getUUID());
			player.displayClientMessage(Component.literal(Config.getFormattedCurrency(balP)), false);
		}
	}
	
	@SuppressWarnings("resource")
	@SubscribeEvent
	public static void onPlayerDeath(LivingDeathEvent event) {
		if (!event.getEntity().level().isClientSide && event.getEntity() instanceof Player player) {
			double balp = MoneyWSD.get().getBalance(AcctTypes.PLAYER.key, player.getUUID());
			double loss = balp * Config.LOSS_ON_DEATH.get();
			if (loss > 0) {
				MoneyWSD.get().changeBalance(AcctTypes.PLAYER.key, player.getUUID(), -loss);
				if (Config.ENABLE_HISTORY.get()) {
					MoneyMod.dbm.postEntry(System.currentTimeMillis(), DatabaseManager.NIL, AcctTypes.SERVER.key, "Server"
							, player.getUUID(), AcctTypes.PLAYER.key, player.getName().getString()
							, -loss, "Loss on Death Event");
				}
				player.displayClientMessage(Component.translatable("message.death", Config.getFormattedCurrency(loss)), false);
			}
		}
	}

	/**checks if the block being placed would border a shop sign and cancels the placement.
	 */
	@SubscribeEvent
	public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
		if (event.getLevel().isClientSide() || event.isCanceled()) return;
		boolean cancel = Arrays.stream(Direction.values()).anyMatch(direction ->
				event.getLevel().getBlockEntity(event.getPos().relative(direction)) instanceof BlockEntity be
				&& be.getPersistentData().contains(IS_SHOP));
		event.setCanceled(cancel);
	}

	@SubscribeEvent
	public static void onShopBreak(BlockEvent.BreakEvent event) {
		if (!event.getLevel().isClientSide() && event.getLevel().getBlockState(event.getPos()).getBlock() instanceof WallSignBlock) {
			SignBlockEntity tile = (SignBlockEntity) event.getLevel().getBlockEntity(event.getPos());
			CompoundTag nbt = tile.getPersistentData();
			if (!nbt.isEmpty() && nbt.contains(ACTIVATED)) {
				Player player = event.getPlayer();
				boolean hasPerms = player.hasPermissions(Config.ADMIN_LEVEL.get());
				UUID ownerUUID = UUIDUtil.uuidFromIntArray(nbt.getIntArray(OWNER).orElseThrow());
				if (!ownerUUID.equals(player.getUUID())) {
					event.setCanceled(!hasPerms);					
				}
				else if(hasPerms) {
					BlockPos backBlock = BlockPos.of(BlockPos.offset(event.getPos().asLong(), tile.getBlockState().getValue(WallSignBlock.FACING).getOpposite()));
					event.getLevel().getBlockEntity(backBlock).getPersistentData().remove(IS_SHOP);
				}
			}
		}
		else if (!event.getLevel().isClientSide() && event.getLevel().getBlockEntity(event.getPos()) != null) {
			IItemHandler inv = event.getLevel().getBlockEntity(event.getPos()).getLevel().getCapability(Capabilities.ItemHandler.BLOCK, event.getPos(), null);
			if (inv != null) {
				if (event.getLevel().getBlockEntity(event.getPos()).getPersistentData().contains(IS_SHOP)) {
					Player player = event.getPlayer();
					event.setCanceled(!player.hasPermissions(Config.ADMIN_LEVEL.get()));
				}
			}
		}
	}
	
	@SubscribeEvent
	public static void onStorageOpen(PlayerInteractEvent.RightClickBlock event) {
		BlockEntity invTile = event.getLevel().getBlockEntity(event.getPos());
		IItemHandler inv = event.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, event.getPos(), null);
		if (invTile != null && inv != null) {
			if (invTile.getPersistentData().contains(IS_SHOP)) {
				UUID ownerUUID = UUIDUtil.uuidFromIntArray(invTile.getPersistentData().getIntArray(OWNER).orElseThrow());
				if (!ownerUUID.equals(event.getEntity().getUUID())) {
					event.setCanceled(!event.getEntity().hasPermissions(Config.ADMIN_LEVEL.get()));					
				}
			}
		}
	}
	
	@SuppressWarnings("resource")
	@SubscribeEvent
	public static void onSignLeftClick(PlayerInteractEvent.LeftClickBlock event) {
		if (!event.getLevel().isClientSide
				&& event.getAction() == PlayerInteractEvent.LeftClickBlock.Action.START
				&& event.getLevel().getBlockState(event.getPos()).getBlock() instanceof WallSignBlock) {
			SignBlockEntity tile = (SignBlockEntity) event.getLevel().getBlockEntity(event.getPos());
			CompoundTag nbt = tile.getPersistentData();
			if (nbt.contains(ACTIVATED))
				getSaleInfo(nbt, event.getEntity(), itemLookup(event.getLevel().registryAccess()));
		}
	}

	@SubscribeEvent
	public static void onSignLoad(ChunkWatchEvent.Watch event) {
		if (event.getLevel().isClientSide()) return;
		BiConsumer<BlockPos, BlockState> fixer = (pos, state) -> {
			if (event.getChunk().getBlockEntity(pos) instanceof SignBlockEntity signBlockEntity) {
				if (signBlockEntity.getPersistentData().contains(ACTIVATED) &&
					Arrays.stream(signBlockEntity.getFrontText().getMessages(false)).allMatch(CommonComponents.EMPTY::equals)
				){
					Component[] text = signBlockEntity.getFrontText().getMessages(true);
					signBlockEntity.setText(new SignText(text, text, DyeColor.BLACK, false), true);
					signBlockEntity.setChanged();
					MoneyMod.LOGGER.debug("Applied No-Profanity-Filter Fix to sign at {}", pos);
				}
			}
		};
		event.getChunk().findBlocks((blockState) -> blockState.getBlock() instanceof WallSignBlock && blockState.hasBlockEntity(), fixer);
	}

	@SubscribeEvent
	public static void onSignRightClick(PlayerInteractEvent.RightClickBlock event) {
		BlockState state = event.getLevel().getBlockState(event.getPos());
		if (!event.getLevel().isClientSide && state.getBlock() instanceof WallSignBlock) {
			BlockPos backBlock = BlockPos.of(BlockPos.offset(event.getPos().asLong(), state.getValue(WallSignBlock.FACING).getOpposite()));
			if (event.getLevel().getBlockEntity(backBlock) instanceof  BlockEntity invTile) {
				SignBlockEntity tile = (SignBlockEntity) event.getLevel().getBlockEntity(event.getPos());
				if (!tile.getPersistentData().contains(ACTIVATED)) {
					if (activateShop(invTile, tile, event.getLevel(), event.getPos(), event.getEntity()))
						event.setUseBlock(TriState.FALSE);
				}
				else {
					processTransaction(invTile, tile, event.getEntity());
					event.setUseBlock(TriState.FALSE);
				}
			}
		}
	}
	
	private static boolean activateShop(BlockEntity storage, SignBlockEntity tile, Level world, BlockPos pos, Player player) {
		HolderLookup.Provider provider = itemLookup(world.registryAccess());
		Component actionEntry = tile.getFrontText().getMessage(0, true);
		double price = 0.0;
		try {
			price = Math.abs(Double.valueOf(tile.getFrontText().getMessage(3, true).getString()));
		}
		catch(NumberFormatException e) {
			player.displayClientMessage(Component.translatable("message.activate.failure.money"), false);
			world.destroyBlock(pos, true, player);
			return false;
		}
		//first confirm the action type is valid
		String shopString = switch (actionEntry.getString().toLowerCase()) {
			case "[buy]" -> player.hasPermissions(Config.SHOP_LEVEL.get()) ? "buy" : null;
			case "[sell]" -> player.hasPermissions(Config.SHOP_LEVEL.get()) ? "sell": null;
			case "[server-buy]", "[server-sell]" -> {
				if (!player.hasPermissions(Config.ADMIN_LEVEL.get())) {
					player.displayClientMessage(Component.translatable("message.activate.failure.admin"), false);
					yield null;
				}
				yield actionEntry.getString().toLowerCase().replace("[","").replace("]","");
			}
			default ->  null;
		};
		if (shopString == null) return false;
		//check if the storage block has an item in the inventory
		IItemHandler inv = Arrays.stream(Direction.values()).map(direction -> {
			IItemHandler invi = world.getCapability(Capabilities.ItemHandler.BLOCK, storage.getBlockPos(), direction);
			for (int i = 0; i < invi.getSlots(); i++) {
				if (!invi.getStackInSlot(i).isEmpty()) return invi;
			}
			return null;
		}).filter(Objects::nonNull).findFirst().orElse(null);
		if (inv == null) return false;

		//store shop data on sign
		tile.getPersistentData().putDouble(PRICE, price);

		Component[] signText = new Component[] {
				Component.literal(actionEntry.getString()).withStyle(ChatFormatting.BLUE),
				tile.getFrontText().getMessage(1, true),
				tile.getFrontText().getMessage(2, true),
				Component.literal(Config.getFormattedCurrency(price)).withStyle(ChatFormatting.GOLD)
		};

		tile.setText(new SignText(signText, signText, DyeColor.BLACK, false), true);
		tile.getPersistentData().putString(TYPE, shopString);
		tile.getPersistentData().putBoolean(ACTIVATED, true);
		tile.getPersistentData().putIntArray(OWNER, UUIDUtil.uuidToIntArray(player.getUUID()));
		//Serialize all items in the TE and store them in a ListNBT
		ListTag lnbt = new ListTag();
		for (int i = 0; i < inv.getSlots(); i++) {
			ItemStack inSlot = inv.getStackInSlot(i);
			if (inSlot.isEmpty()) continue;
			if (inSlot.getItem() instanceof WritableBookItem)
				lnbt.add(getItemFromBook(inSlot, itemLookup(player.level().registryAccess())));
			else
				lnbt.add(inSlot.save(provider));
		}
		tile.getPersistentData().put(ITEMS, lnbt);
		tile.saveWithFullMetadata(provider);
		tile.setChanged();
		storage.getPersistentData().putBoolean(IS_SHOP, true);
		storage.getPersistentData().putIntArray(OWNER, UUIDUtil.uuidToIntArray(player.getUUID()));
		storage.saveWithFullMetadata(provider);
		BlockState state = world.getBlockState(pos);
		world.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
		return true;
	}
	
	private static CompoundTag getItemFromBook(ItemStack stack, HolderLookup.Provider provider) {
		String page = stack.get(DataComponents.WRITABLE_BOOK_CONTENT).getPages(false).toList().getFirst();
		if (page.substring(0, 7).equalsIgnoreCase("vending")) {
			String subStr = page.substring(8);
			try {
				stack = ItemStack.parse(provider, TagParser.parseCompoundFully(subStr)).get();
				return (CompoundTag) stack.save(provider);
			}
			catch(CommandSyntaxException | NoSuchElementException e) {e.printStackTrace();}
			
		}
		return (CompoundTag) stack.save(provider);
	}
	
	private static void getSaleInfo(CompoundTag nbt, Player player, HolderLookup.Provider provider) {
		String type = nbt.getString(TYPE).orElseThrow();
		boolean isBuy = type.equalsIgnoreCase("buy") || type.equalsIgnoreCase("server-buy");
		List<ItemStack> transItems = new ArrayList<>();
		ListTag itemsList = nbt.getList(ITEMS).orElseThrow();
		for (int i = 0; i < itemsList.size(); i++) {
			transItems.add(ItemStack.parse(provider, itemsList.getCompound(i).orElseThrow()).orElse(new ItemStack(Items.AIR)));
		}
		double value = nbt.getDouble(PRICE).orElseThrow();
		MutableComponent itemComponent = getTransItemsDisplayString(transItems);
		if (isBuy)
			player.displayClientMessage(Component.translatable("message.shop.info", itemComponent, Config.getFormattedCurrency(value)), false);
		else
			player.displayClientMessage(Component.translatable("message.shop.info", Config.getFormattedCurrency(value), itemComponent), false);
	}
	
	private static MutableComponent getTransItemsDisplayString(List<ItemStack> list ) {
		List<ItemStack> items = new ArrayList<>();
		for (int l = 0; l < list.size(); l++) {
			boolean hadMatch = false;
			for (int i = 0; i < items.size(); i++) {
				if (list.get(l).is(items.get(i).getItem()) && ItemStack.matches(list.get(l), items.get(i))) {
					items.get(i).grow(list.get(l).getCount());
					hadMatch = true;
					break;
				}
			}
			if (!hadMatch) items.add(list.get(l));
		}
		MutableComponent itemComponent = Component.literal("");
		boolean isFirst = true;
		for (ItemStack item : items) {
			if (!isFirst) itemComponent.append(", ");
			itemComponent.append(item.getCount()+"x ");
			itemComponent.append(item.getDisplayName());
			isFirst = false;
		}
		return itemComponent;
	}
	
	private static void processTransaction(BlockEntity tile, SignBlockEntity sign, Player player) {
		MoneyWSD wsd = MoneyWSD.get();
		CompoundTag nbt = sign.getPersistentData();
		IItemHandler inv = player.level().getCapability(Capabilities.ItemHandler.BLOCK, tile.getBlockPos(), null);
		List<ItemStack> transItems = new ArrayList<>();
		Map<ItemStack, ItemStack> consolidatedItems = new HashMap<>();
		ListTag itemsList = nbt.getList(ITEMS).orElseThrow();
		for (int i = 0; i < itemsList.size(); i++) {
			ItemStack srcStack = ItemStack.parse(itemLookup(player.level().registryAccess()), itemsList.getCompound(i).orElseThrow()).orElse(new ItemStack(Items.AIR));
			ItemStack keyStack = srcStack.copy();
			keyStack.setCount(1);
			boolean hasEntry = false;
			for (Map.Entry<ItemStack, ItemStack> map : consolidatedItems.entrySet()) {
				if (map.getKey().is(srcStack.getItem()) && ItemStack.matches(map.getKey(), srcStack)) {
					map.getValue().grow(srcStack.getCount());
					hasEntry = true;
				}
			}
			if (!hasEntry) consolidatedItems.put(keyStack, srcStack);
		}
		for (Map.Entry<ItemStack, ItemStack> map : consolidatedItems.entrySet()) {
			transItems.add(map.getValue());
		}
		//ItemStack transItem = ItemStack.of(nbt.getCompound("item"));
		String action = nbt.getString(TYPE).orElseThrow();
		double value = nbt.getDouble(PRICE).orElse(0.0);
		//================BUY=================================================================================
		if (action.equalsIgnoreCase("buy")) { //BUY
			//First check the available funds and stock for trade
			double balP = wsd.getBalance(AcctTypes.PLAYER.key, player.getUUID());
			if (value > balP) {
				player.displayClientMessage(Component.translatable("message.shop.buy.failure.funds"), false);
				return;
			}
			Map<Integer, ItemStack> slotMap = new HashMap<>();
			for (int tf = 0; tf < transItems.size(); tf++) {
				int[] stackSize = {transItems.get(tf).getCount()};
				final Integer t = Integer.valueOf(tf);
				boolean test = false;
				for (int i = 0; i < inv.getSlots(); i++) {
					ItemStack inSlot;
					if (slotMap.containsKey(i) && transItems.get(t).getItem().equals(slotMap.get(i).getItem()) && ItemStack.matches(transItems.get(t), slotMap.get(i))) {
						inSlot = inv.extractItem(i, stackSize[0]+slotMap.get(i).getCount(), true);
						inSlot.shrink(slotMap.get(i).getCount());
					}
					else inSlot = inv.extractItem(i, stackSize[0], true);
					if (inSlot.getItem().equals(transItems.get(t).getItem()) && ItemStack.matches(inSlot, transItems.get(t))) {
						slotMap.merge(i, inSlot, (s, o) -> {s.grow(o.getCount()); return s;});
						stackSize[0] -= inSlot.getCount();
					}
					if (stackSize[0] <= 0) break;
				}
				test =  stackSize[0] <= 0;
				if (!test) {
					player.displayClientMessage(Component.translatable("message.shop.buy.failure.stock"), false);
					return;
				}
			}
			//Test if container has inventory to process.
			//If so, process transfer of items and funds.			
			UUID shopOwner = UUIDUtil.uuidFromIntArray(nbt.getIntArray(OWNER).orElseThrow());
			wsd.transferFunds(AcctTypes.PLAYER.key, player.getUUID(), AcctTypes.PLAYER.key, shopOwner, value);
			if (Config.ENABLE_HISTORY.get()) {
				String itemPrint = "";
				itemsList.forEach((a) -> {itemPrint.concat(a.toString());});
				MoneyMod.dbm.postEntry(System.currentTimeMillis(), player.getUUID(), AcctTypes.PLAYER.key, player.getName().getString()
						, shopOwner, AcctTypes.PLAYER.key, player.getServer().getProfileCache().get(shopOwner).get().getName()
						, value, itemsList.toString());
			}
			for (Map.Entry<Integer, ItemStack> map : slotMap.entrySet()) {
				ItemStack pStack = inv.extractItem(map.getKey(), map.getValue().getCount(), false);
				if (!player.addItem(pStack))
					player.drop(pStack, false);
			}
			MutableComponent msg =  Component.translatable("message.shop.buy.success"
					, getTransItemsDisplayString(transItems), Config.getFormattedCurrency(value));
			player.displayClientMessage(msg, true);
			player.getServer().sendSystemMessage(msg);
        }
		//================SELL=================================================================================
		else if (action.equalsIgnoreCase("sell")) { //SELL
			//First check the available funds and stock for trade
			UUID shopOwner = UUIDUtil.uuidFromIntArray(nbt.getIntArray(OWNER).orElseThrow());
			double balP = wsd.getBalance(AcctTypes.PLAYER.key, shopOwner);
			if (value > balP) {
				player.displayClientMessage(Component.translatable("message.shop.sell.failure.funds"), false);
				return;
			}
			//test if player has item in inventory to sell
			//next test that the inventory has space
			Map<Integer, ItemStack> slotMap = new HashMap<>();
			for (int t = 0; t < transItems.size(); t++) {
				int stackSize = transItems.get(t).getCount();
				for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
					ItemStack inSlot = player.getInventory().getItem(i).copy();
					int count = stackSize > inSlot.getCount() ? inSlot.getCount() : stackSize;
					inSlot.setCount(count);
					if (slotMap.containsKey(i) && transItems.get(t).getItem().equals(slotMap.get(i).getItem()) && ItemStack.matches(transItems.get(t), slotMap.get(i))) {
						count = stackSize+slotMap.get(i).getCount() > inSlot.getCount() ? inSlot.getCount() : stackSize+slotMap.get(i).getCount();
						inSlot.setCount(count);
					}
					if (inSlot.getItem().equals(transItems.get(t).getItem()) && ItemStack.matches(inSlot, transItems.get(t))) {
						slotMap.merge(i, inSlot, (s, o) -> {s.grow(o.getCount()); return s;});
						stackSize -= inSlot.getCount();
					}						
					if (stackSize <= 0) break;
				}
				if (stackSize > 0) {
					player.displayClientMessage(Component.translatable("message.shop.sell.failure.stock"), false);
					return;
				}
				
			}
			Map<Integer, ItemStack> invSlotMap = new HashMap<>();
            for (ItemStack transItem : transItems) {
                ItemStack sim = transItem.copy();
                boolean test = false;
                for (int i = 0; i < inv.getSlots(); i++) {
                    ItemStack insertResult = inv.insertItem(i, sim, true);
                    if (insertResult.isEmpty()) {
                        invSlotMap.merge(i, sim.copy(), (s, o) -> {
                            s.grow(o.getCount());
                            return s;
                        });
                        sim.setCount(0);
                        break;
                    } else if (insertResult.getCount() == sim.getCount()) {
                        continue;
                    } else {
                        ItemStack insertSuccess = sim.copy();
                        insertSuccess.shrink(insertResult.getCount());
                        sim.setCount(insertResult.getCount());
                        invSlotMap.merge(i, insertSuccess, (s, o) -> {
                            s.grow(insertSuccess.getCount());
                            return s;
                        });
                    }
                }
                if (!sim.isEmpty()) {
                    player.displayClientMessage(Component.translatable("message.shop.sell.failure.space"), false);
                } else
                    test = true;
                if (!test) return;
            }
			//Process Transfers now that reqs have been met
			wsd.transferFunds(AcctTypes.PLAYER.key, shopOwner, AcctTypes.PLAYER.key, player.getUUID(), value);
			if (Config.ENABLE_HISTORY.get()) {
				String itemPrint = "";
				itemsList.forEach((a) -> {itemPrint.concat(a.toString());});
				MoneyMod.dbm.postEntry(System.currentTimeMillis(), shopOwner, AcctTypes.PLAYER.key, player.getServer().getProfileCache().get(shopOwner).get().getName()
						, player.getUUID(), AcctTypes.PLAYER.key, player.getName().getString()
						, value, itemsList.toString());
			}
			for (Map.Entry<Integer, ItemStack> pSlots : slotMap.entrySet()) {
				player.getInventory().removeItem(pSlots.getKey(), pSlots.getValue().getCount());
			}
			for (Map.Entry<Integer, ItemStack> map : invSlotMap.entrySet()) {
				inv.insertItem(map.getKey(), map.getValue(), false);
			}
			player.displayClientMessage(Component.translatable("message.shop.sell.success"
					, Config.getFormattedCurrency(value), getTransItemsDisplayString(transItems)), false);
        }
		//================SERVER BUY=================================================================================
		else if (action.equalsIgnoreCase("server-buy")) { //SERVER BUY
			//First check the available funds and stock for trade
			double balP = wsd.getBalance(AcctTypes.PLAYER.key, player.getUUID());
			if (value > balP) {
				player.displayClientMessage(Component.translatable("message.shop.buy.failure.funds"), false);
				return;
			}
			wsd.changeBalance(AcctTypes.PLAYER.key, player.getUUID(), -value);
			if (Config.ENABLE_HISTORY.get()) {
				String itemPrint = "";
				itemsList.forEach((a) -> {itemPrint.concat(a.toString());});
				MoneyMod.dbm.postEntry(System.currentTimeMillis(), DatabaseManager.NIL, AcctTypes.SERVER.key, "Server"
						, player.getUUID(), AcctTypes.PLAYER.key, player.getName().getString()
						, -value, itemsList.toString());
			}
			for (int i = 0; i < transItems.size(); i++) {
				ItemStack pStack = transItems.get(i).copy();
				if (!player.addItem(pStack))
					player.drop(pStack, false);
			}
			player.displayClientMessage(Component.translatable("message.shop.buy.success"
					, getTransItemsDisplayString(transItems), Config.getFormattedCurrency(value)), false);
        }
		//================SERVER SELL=================================================================================
		else if (action.equalsIgnoreCase("server-sell")) { //SERVER SELL
			Map<Integer, ItemStack> slotMap = new HashMap<>();
			for (int t = 0; t < transItems.size(); t++) {
				int stackSize = transItems.get(t).getCount();
				for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
					ItemStack inSlot = player.getInventory().getItem(i).copy();
					int count = stackSize > inSlot.getCount() ? inSlot.getCount() : stackSize;
					inSlot.setCount(count);
					if (slotMap.containsKey(i) && transItems.get(t).getItem().equals(slotMap.get(i).getItem()) && ItemStack.matches(transItems.get(t), slotMap.get(i))) {
						count = stackSize+slotMap.get(i).getCount() > inSlot.getCount() ? inSlot.getCount() : stackSize+slotMap.get(i).getCount();
						inSlot.setCount(count);
					}
					if (inSlot.getItem().equals(transItems.get(t).getItem()) && ItemStack.matches(inSlot, transItems.get(t))) {
						slotMap.merge(i, inSlot, (s, o) -> {s.grow(o.getCount()); return s;});
						stackSize -= inSlot.getCount();
					}						
					if (stackSize <= 0) break;
				}
				if (stackSize > 0) {
					player.displayClientMessage(Component.translatable("message.shop.sell.failure.stock"), false);
					return;
				}
				
			}
			wsd.changeBalance(AcctTypes.PLAYER.key, player.getUUID(), value);
			if (Config.ENABLE_HISTORY.get()) {
				String itemPrint = "";
				itemsList.forEach((a) -> {itemPrint.concat(a.toString());});
				MoneyMod.dbm.postEntry(System.currentTimeMillis(), DatabaseManager.NIL, AcctTypes.SERVER.key, "Server"
						, player.getUUID(), AcctTypes.PLAYER.key, player.getName().getString()
						, value, itemsList.toString());
			}
			for (Map.Entry<Integer, ItemStack> pSlots : slotMap.entrySet()) {
				player.getInventory().getItem(pSlots.getKey()).shrink(pSlots.getValue().getCount());
			}
			player.displayClientMessage(Component.translatable("message.shop.sell.success"
					, Config.getFormattedCurrency(value), getTransItemsDisplayString(transItems)), false);
        }
	}

	public static HolderLookup.Provider itemLookup(RegistryAccess access) {
		return HolderLookup.Provider.create(Stream.of(access.lookupOrThrow(Registries.ITEM)));
	}
}
