package dev.maxneedssnacks.ftbqimporter;

import com.feed_the_beast.ftbquests.item.FTBQuestsItems;
import com.latmod.mods.itemfilters.api.IItemFilter;
import com.latmod.mods.itemfilters.api.ItemFiltersAPI;
import com.latmod.mods.itemfilters.filters.OreDictionaryFilter;
import com.latmod.mods.itemfilters.item.ItemFilter;
import com.latmod.mods.itemfilters.item.ItemFiltersItems;
import com.latmod.mods.itemfilters.item.ItemMissing;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.NonNullList;
import net.minecraftforge.oredict.OreDictionary;

import java.util.List;

public final class Utils {
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
