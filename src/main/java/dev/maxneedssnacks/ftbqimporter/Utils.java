package dev.maxneedssnacks.ftbqimporter;

import com.feed_the_beast.ftblib.lib.config.EnumTristate;
import com.feed_the_beast.ftbquests.item.FTBQuestsItems;
import com.feed_the_beast.ftbquests.quest.Quest;
import com.feed_the_beast.ftbquests.quest.loot.RewardTable;
import com.feed_the_beast.ftbquests.quest.loot.WeightedReward;
import com.feed_the_beast.ftbquests.quest.reward.*;
import com.feed_the_beast.ftbquests.quest.task.*;
import com.latmod.mods.itemfilters.api.IItemFilter;
import com.latmod.mods.itemfilters.api.ItemFiltersAPI;
import com.latmod.mods.itemfilters.filters.NBTMatchingMode;
import com.latmod.mods.itemfilters.filters.OreDictionaryFilter;
import com.latmod.mods.itemfilters.item.ItemFilter;
import com.latmod.mods.itemfilters.item.ItemFiltersItems;
import com.latmod.mods.itemfilters.item.ItemMissing;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.oredict.OreDictionary;

import java.util.*;
import java.util.function.BiFunction;

public final class Utils {

    public static final Map<String, BiFunction<NBTTagCompound, Quest, Collection<Task>>> taskConverters = new TreeMap<>();
    public static final Map<String, BiFunction<NBTTagCompound, Quest, Collection<Reward>>> rewardConverters = new TreeMap<>();

    // task converters
    static {
        final BiFunction<NBTTagCompound, Quest, Collection<Task>> ITEM = (nbt, q) -> {
            if (q.getTags().contains("has_item_task") && q.orTasks) {
                FTBQImporter.LOGGER.warn("The quest {} contains more than one item task but is using OR task logic!" +
                        " Since this is not supported in FTB Quests, any other item tasks have been skipped!", q.title);
                q.getTags().add("has_warning");
                return Collections.emptySet();
            }

            Collection<Task> tasks = new HashSet<>();

            boolean ignoreNBT = nbt.getBoolean("ignoreNBT");
            boolean consume = nbt.getBoolean("consume");

            for (NBTBase taskItemBase : nbt.getTagList("requiredItems", 10)) {
                ItemStack item = Utils.nbtItem((NBTTagCompound) taskItemBase, true);
                if (!item.isEmpty()) {
                    ItemTask t = new ItemTask(q);
                    t.items.add(item);
                    t.count = item.getCount();
                    t.consumeItems = consume ? EnumTristate.TRUE : EnumTristate.DEFAULT;

                    if (!item.isStackable()) {
                        t.ignoreDamage = !item.getHasSubtypes();
                    }

                    if (ignoreNBT) {
                        t.nbtMode = NBTMatchingMode.IGNORE;
                    }

                    tasks.add(t);
                }
            }

            q.getTags().add("has_item_task");

            if (tasks.isEmpty()) {
                FTBQImporter.LOGGER.warn("Item task for quest {} is empty!", q.title);
                q.getTags().add("has_warning");
            }

            return tasks;
        };

        taskConverters.put("bq_standard:crafting", ITEM);
        taskConverters.put("bq_standard:retrieval", ITEM);

        taskConverters.put("bq_standard:checkbox", (nbt, q) -> Collections.singleton(new CheckmarkTask(q)));

        taskConverters.put("bq_standard:xp", (nbt, q) -> {
            XPTask task = new XPTask(q);
            task.value = nbt.getLong("amount");
            task.points = !nbt.getBoolean("isLevels");
            if (!nbt.getBoolean("consume")) {
                FTBQImporter.LOGGER.warn("The quest {} contains an XP task that does not consume experience!" +
                        " Since this is not supported in FTB Quests, the created task will consume XP!", q.title);
                q.getTags().add("has_warning");
            }
            return Collections.singleton(task);
        });

        taskConverters.put("bq_standard:hunt", (nbt, q) -> {
            KillTask task = new KillTask(q);
            task.entity = new ResourceLocation(nbt.getString("target"));
            task.value = nbt.getLong("required");
            return Collections.singleton(task);
        });

        taskConverters.put("bq_standard:location", (nbt, q) -> {
            int x = nbt.getInteger("posX");
            int y = nbt.getInteger("posY");
            int z = nbt.getInteger("posZ");
            int dimension = nbt.getInteger("dimension");
            int range = nbt.getInteger("range");
            if (range == -1) {
                DimensionTask task = new DimensionTask(q);
                task.dimension = dimension;
                return Collections.singleton(task);
            } else {
                LocationTask task = new LocationTask(q);
                task.dimension = dimension;
                task.x = x - range / 2;
                task.y = y - range / 2;
                task.z = z - range / 2;
                task.w = range;
                task.h = range;
                task.d = range;
                return Collections.singleton(task);
            }
        });

        taskConverters.put("bq_standard:advancement", (nbt, q) -> {
            AdvancementTask task = new AdvancementTask(q);
            task.advancement = nbt.getString("advancement_id");
            task.criterion = "";
            return Collections.singleton(task);
        });

        taskConverters.put("bq_standard:trigger", (nbt, q) -> {
            String trigger = nbt.getString("trigger");
            AdvancementTask task = new AdvancementTask(q);
            task.advancement = trigger;
            task.criterion = nbt.getString("conditions");
            FTBQImporter.LOGGER.warn("The quest {} contains a trigger task. Please note that FTB Quests only supports" +
                    " advancement triggers, so this may not work as intended!", q.title);
            q.getTags().add("has_warning");
            return Collections.singleton(task);
        });

        taskConverters.put("bq_rf:rf_charge", (nbt, q) -> {
            ForgeEnergyTask task = new ForgeEnergyTask(q);
            task.value = nbt.getLong("rf");
            return Collections.singleton(task);
        });

        taskConverters.put("bq_standard:fluid", (nbt, q) -> {
            boolean consume = nbt.getBoolean("consume");
            Collection<Task> tasks = new HashSet<>();

            if (consume) {
                boolean ignoreNBT = nbt.getBoolean("ignoreNBT");
                for (NBTBase fluidNbtBase : nbt.getTagList("requiredFluids", 10)) {
                    NBTTagCompound fluidNbt = (NBTTagCompound) fluidNbtBase;
                    Fluid fluid = FluidRegistry.getFluid(fluidNbt.getString("FluidName"));
                    if (fluid != null) {
                        FluidTask task = new FluidTask(q);
                        task.fluid = fluid;
                        task.amount = fluidNbt.getInteger("Amount");
                        task.fluidNBT = ignoreNBT ? null : fluidNbt.getCompoundTag("Tag");
                        tasks.add(task);
                    } else {
                        FTBQImporter.LOGGER.info("Skipped a non-existing fluid in fluid task for quest {}!", q.title);
                    }
                }
            } else {
                FTBQImporter.LOGGER.warn("Skipped an unsupported, non-consuming fluid task for quest {}!", q.title);
                q.getTags().add("has_warning");
            }

            if (tasks.isEmpty()) {
                FTBQImporter.LOGGER.warn("Fluid task for quest {} is empty!", q.title);
                q.getTags().add("has_warning");
            }

            return tasks;
        });
    }

    // reward converters
    static {
        rewardConverters.put("bq_standard:item", (nbt, q) -> {
            Collection<Reward> rewards = new HashSet<>();
            for (NBTBase rewardItem : nbt.getTagList("rewards", 10)) {

                ItemStack item = Utils.nbtItem((NBTTagCompound) rewardItem);

                ItemStack loot_chest = new ItemStack(FTBQuestsItems.LOOTCRATE);
                loot_chest.setTagInfo("type", new NBTTagString("loot_chest"));

                if (ItemStack.areItemStackTagsEqual(item, loot_chest) && LootImporter.get() != null) {
                    RandomReward r = new RandomReward(q);
                    r.table = LootImporter.get().getTable();
                    rewards.add(r);
                } else if (!item.isEmpty()) {
                    rewards.add(new ItemReward(q, item));
                }
            }
            if (rewards.isEmpty()) {
                FTBQImporter.LOGGER.warn("Item reward for quest {} is empty!", q.title);
                q.getTags().add("has_warning");
            }
            return rewards;
        });

        rewardConverters.put("bq_standard:choice", (nbt, q) -> {
            RewardTable table = new RewardTable(q.chapter.file);
            for (NBTBase rewardItem : nbt.getTagList("choices", 10)) {
                ItemStack stack = Utils.nbtItem((NBTTagCompound) rewardItem);
                if (!stack.isEmpty()) {
                    table.rewards.add(new WeightedReward(new ItemReward(table.fakeQuest, stack), 1));
                }
            }

            if (!table.rewards.isEmpty()) {
                table.id = table.file.newID();
                table.file.rewardTables.add(table);
                table.title = q.title;

                ChoiceReward reward = new ChoiceReward(q);
                reward.table = table;
                return Collections.singleton(reward);
            } else {
                table.deleteSelf();
                FTBQImporter.LOGGER.warn("Choice reward for quest {} is empty!", q.title);
                q.getTags().add("has_warning");
                return Collections.emptySet();
            }
        });

        rewardConverters.put("bq_standard:xp", (nbt, q) -> {
            if (nbt.getBoolean("isLevels")) {
                XPLevelsReward reward = new XPLevelsReward(q);
                reward.xpLevels = nbt.getInteger("amount");
                return Collections.singleton(reward);
            } else {
                XPReward reward = new XPReward(q);
                reward.xp = nbt.getInteger("amount");
                return Collections.singleton(reward);
            }
        });

        rewardConverters.put("bq_standard:command", (nbt, q) -> {
            // TODO: support multiline commands
            CommandReward reward = new CommandReward(q);
            reward.command = nbt.getString("command").replace("VAR_NAME", "@p");
            reward.playerCommand = nbt.getBoolean("viaPlayer");
            return Collections.singleton(reward);
        });
    }

    public static ItemStack nbtItem(NBTTagCompound itemNbt) {
        return nbtItem(itemNbt, false);
    }

    public static ItemStack nbtItem(NBTTagCompound itemNbt, boolean isFilter) {
        ItemStack stack = ItemStack.EMPTY;

        if (itemNbt.isEmpty()) {
            FTBQImporter.LOGGER.debug("Item {} has incorrect format! Returning empty item stack", itemNbt);
            return stack;
        }

        String ore = itemNbt.getString("OreDict");
        if (!ore.isEmpty()) {
            stack = oreDictItem(ore, isFilter);
        }
        itemNbt.removeTag("OreDict");

        if (!stack.isEmpty()) return stack;

        String id = itemNbt.getString("id");

        if (id.isEmpty()) {
            FTBQImporter.LOGGER.debug("Item ID {} is invalid or empty! Returning empty item stack", id);
            return stack;
        } else if (id.equals("betterquesting:placeholder")) {
            // why fun, why?
            NBTTagCompound placeholderNbt = itemNbt.copy();
            placeholderNbt.setString("id", itemNbt.getCompoundTag("tag").getString("orig_id"));
            placeholderNbt.setInteger("Damage", itemNbt.getCompoundTag("tag").getInteger("orig_meta"));
            placeholderNbt.setInteger("tag", itemNbt.getCompoundTag("tag").getInteger("orig_tag")); // ew.
            FTBQImporter.LOGGER.debug("Item was placeholder, trying to retrieve original item with id {}", placeholderNbt.getString("id"));
            return nbtItem(placeholderNbt, isFilter);
        } else if (id.equals("bq_standard:loot_chest")) {
            stack = new ItemStack(FTBQuestsItems.LOOTCRATE);
            stack.setTagInfo("type", new NBTTagString("loot_chest"));
            return stack;
        }

        stack = ItemMissing.read(itemNbt);
        if (stack.isEmpty() || stack.isItemEqual(new ItemStack(ItemFiltersItems.MISSING))) {
            FTBQImporter.LOGGER.debug("{} returned an empty or missing item!", itemNbt);
        } else {
            FTBQImporter.LOGGER.debug("Found an item with properties {}!", itemNbt);
        }
        return stack;
    }

    public static ItemStack oreDictItem(String ore, boolean isFilter) {
        if (isFilter) {
            ItemStack oreFilter = new ItemStack(ItemFiltersItems.FILTER);
            IItemFilter filter = ItemFiltersAPI.getFilter(oreFilter);

            if (filter instanceof ItemFilter.ItemFilterData) {
                ItemFilter.ItemFilterData data = (ItemFilter.ItemFilterData) filter;
                OreDictionaryFilter odFilter = new OreDictionaryFilter();
                odFilter.setValue(ore);
                data.filter = odFilter;

                List<ItemStack> valid_items = NonNullList.create();
                filter.getValidItems(valid_items);
                if (valid_items.isEmpty()) {
                    FTBQImporter.LOGGER.debug("Warning: Did not create Ore Dictionary filter with value {} as it is empty!", ore);
                    return ItemStack.EMPTY;
                }

                FTBQImporter.LOGGER.debug("Successfully created ore dictionary filter with value {}", ore);
                oreFilter.setStackDisplayName("Any " + ore);
                return oreFilter;
            }

            return ItemStack.EMPTY;
        } else {
            if (!OreDictionary.doesOreNameExist(ore)) return ItemStack.EMPTY;
            ItemStack stack = OreDictionary.getOres(ore).get(0);
            if (stack.getMetadata() == OreDictionary.WILDCARD_VALUE) {
                return new ItemStack(stack.getItem(), 1, 0);
            }
            return stack;
        }
    }
}
