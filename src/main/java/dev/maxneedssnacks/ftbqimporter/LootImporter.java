package dev.maxneedssnacks.ftbqimporter;

import com.feed_the_beast.ftblib.lib.util.StringUtils;
import com.feed_the_beast.ftbquests.quest.ServerQuestFile;
import com.feed_the_beast.ftbquests.quest.loot.LootCrate;
import com.feed_the_beast.ftbquests.quest.loot.RewardTable;
import com.feed_the_beast.ftbquests.quest.loot.WeightedReward;
import com.feed_the_beast.ftbquests.quest.reward.ItemReward;
import com.google.gson.JsonObject;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nullable;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class LootImporter {

    private static LootImporter INSTANCE = null;

    private final NBTTagCompound lootFile;
    private RewardTable crateTable;

    public LootImporter(NBTTagCompound lootFile) {
        INSTANCE = this;
        this.lootFile = lootFile;
    }

    public LootImporter(JsonObject lootJson) {
        INSTANCE = this;
        this.lootFile = NBTConverter.JSONtoNBT_Object(lootJson, new NBTTagCompound(), true);
    }

    public boolean processLoot(ServerQuestFile f) {
        boolean success = true;

        crateTable = new RewardTable(f);
        crateTable.id = f.newID();
        f.rewardTables.add(crateTable);
        crateTable.title = "Loot Chest";
        crateTable.lootCrate = new LootCrate(crateTable);
        crateTable.lootCrate.stringID = "loot_chest";
        crateTable.lootCrate.glow = true;

        for (NBTBase groupBase : lootFile.getTagList("groups", 10)) {
            success &= processLootGroup((NBTTagCompound) groupBase, f);
        }
        return success;
    }

    public boolean processLootGroup(NBTTagCompound lootGroup, ServerQuestFile f) {
        RewardTable table = new RewardTable(f);
        table.title = lootGroup.getString("name");
        table.id = f.newID();
        f.rewardTables.add(table);
        table.lootCrate = new LootCrate(table);
        table.lootCrate.stringID = StringUtils.getID(table.title, StringUtils.FLAG_ID_DEFAULTS);
        table.lootCrate.itemName = table.title;

        crateTable.rewards.add(new WeightedReward(new ItemReward(crateTable.fakeQuest, table.lootCrate.createStack()), lootGroup.getInteger("weight")));

        for (NBTBase rewardBase : lootGroup.getTagList("rewards", 10)) {
            NBTTagCompound rewardNbt = (NBTTagCompound) rewardBase;
            List<ItemStack> items = StreamSupport.stream(rewardNbt.getTagList("items", 10).spliterator(), false)
                    .map(NBTTagCompound.class::cast)
                    .map(Utils::nbtItem)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
            if (items.isEmpty()) {
                FTBQImporter.LOGGER.warn("Empty reward in loot group {}!", lootGroup.getString("name"));
                return false;
            } else if (items.size() == 1) {
                table.rewards.add(new WeightedReward(new ItemReward(table.fakeQuest, items.get(0)), rewardNbt.getInteger("weight")));
            } else {
                RewardTable table1 = new RewardTable(f);
                table1.id = f.newID();
                table1.title = "Nested Reward " + table1.id;
                items.forEach(stack -> table1.rewards.add(new WeightedReward(new ItemReward(table1.fakeQuest, stack), 1)));
                table1.lootSize = table1.rewards.size();
                table1.lootCrate = new LootCrate(table1);
                table1.lootCrate.stringID = StringUtils.getID(table1.id, StringUtils.FLAG_ID_DEFAULTS);
            }
        }
        return true;
    }

    @Nullable
    public static LootImporter get() {
        return INSTANCE;
    }

    public RewardTable getTable() {
        return crateTable;
    }
}
