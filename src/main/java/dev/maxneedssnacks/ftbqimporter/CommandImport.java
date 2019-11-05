package dev.maxneedssnacks.ftbqimporter;

import com.feed_the_beast.ftblib.lib.config.EnumTristate;
import com.feed_the_beast.ftblib.lib.data.ForgePlayer;
import com.feed_the_beast.ftblib.lib.data.Universe;
import com.feed_the_beast.ftblib.lib.io.DataReader;
import com.feed_the_beast.ftblib.lib.util.FileUtils;
import com.feed_the_beast.ftblib.lib.util.StringUtils;
import com.feed_the_beast.ftbquests.item.FTBQuestsItems;
import com.feed_the_beast.ftbquests.quest.Chapter;
import com.feed_the_beast.ftbquests.quest.Quest;
import com.feed_the_beast.ftbquests.quest.ServerQuestFile;
import com.feed_the_beast.ftbquests.quest.loot.LootCrate;
import com.feed_the_beast.ftbquests.quest.loot.RewardTable;
import com.feed_the_beast.ftbquests.quest.loot.WeightedReward;
import com.feed_the_beast.ftbquests.quest.reward.*;
import com.feed_the_beast.ftbquests.quest.task.*;
import com.google.gson.*;
import com.latmod.mods.itemfilters.api.IItemFilter;
import com.latmod.mods.itemfilters.api.ItemFiltersAPI;
import com.latmod.mods.itemfilters.filters.NBTMatchingMode;
import com.latmod.mods.itemfilters.filters.OreDictionaryFilter;
import com.latmod.mods.itemfilters.item.ItemFilter;
import com.latmod.mods.itemfilters.item.ItemFiltersItems;
import com.latmod.mods.itemfilters.item.ItemMissing;
import net.minecraft.command.*;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.oredict.OreDictionary;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author LatvianModder, MaxNeedsSnacks
 */
public class CommandImport extends CommandBase {

    @Override
    public String getName() {
        return "ftbq_import";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/ftbq_import <quests|progress> [-i, -l, -c]";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) {
            throw new WrongUsageException(getUsage(sender));
        }

        switch (args[0].toLowerCase()) {
            case "q":
            case "quests":
                importQuests(server, sender, Arrays.asList(args).subList(1, args.length));
                break;
            case "p":
            case "progress":
                importProgress(server, sender, Arrays.asList(args).subList(1, args.length));
                break;
            default:
                throw new WrongUsageException(getUsage(sender));
        }
    }

    public void importQuests(MinecraftServer server, ICommandSender sender, List<String> flags) throws CommandException {

        if (!flags.isEmpty()) {
            sender.sendMessage(new TextComponentString("Flags supplied: " + joinNiceStringFromCollection(flags)));
        }

        // additional flags that may be supplied
        boolean fix_icons = flags.contains("-i") || flags.contains("--fix-icons");
        boolean truncate_loot = flags.contains("-l") || flags.contains("--truncate-loot");
        boolean auto_cmd = flags.contains("-c") || flags.contains("--auto-cmd");

        JsonElement json0 = DataReader.get(new File(Loader.instance().getConfigDir(), "betterquesting/DefaultQuests.json")).safeJson();
        JsonElement lootJson0 = DataReader.get(new File(Loader.instance().getConfigDir(), "betterquesting/DefaultLoot.json")).safeJson();

        if (!json0.isJsonObject()) {
            server.sendMessage(new TextComponentString("config/betterquesting/DefaultQuests.json not found!"));
            return;
        }

        JsonObject json = fix(json0).getAsJsonObject();

        if (!json.get("format").getAsString().startsWith("2")) {
            server.sendMessage(new TextComponentString("Cannot import DefaultQuests.json with old version (" + json.get("format").getAsString() + ")!"));
            return;
        }

        // clear out existing files
        ServerQuestFile f = ServerQuestFile.INSTANCE;
        f.chapters.clear();
        f.rewardTables.clear();
        FileUtils.deleteSafe(f.getFolder());

        Map<Integer, BQQuest> questMap = new HashMap<>();
        List<BQChapter> chapters = new ArrayList<>();

        // region loot
        RewardTable crateTable = null;

        if (lootJson0.isJsonObject()) {
            crateTable = new RewardTable(f);
            crateTable.id = f.newID();
            f.rewardTables.add(crateTable);
            crateTable.title = "Loot Chest";
            if (!truncate_loot) {
                crateTable.lootCrate = new LootCrate(crateTable);
                crateTable.lootCrate.stringID = "loot_chest";
                crateTable.lootCrate.glow = true;
            }

            for (Map.Entry<String, JsonElement> entry : fix(lootJson0).getAsJsonObject().get("groups").getAsJsonObject().entrySet()) {
                JsonObject lootJson = entry.getValue().getAsJsonObject();

                RewardTable table = new RewardTable(f);
                table.title = lootJson.get("name").getAsString();
                table.id = f.newID();
                f.rewardTables.add(table);
                table.lootCrate = new LootCrate(table);
                table.lootCrate.stringID = StringUtils.getID(table.title, StringUtils.FLAG_ID_DEFAULTS);
                table.lootCrate.itemName = table.title;

                crateTable.rewards.add(new WeightedReward(new ItemReward(crateTable.fakeQuest, table.lootCrate.createStack()), lootJson.get("weight").getAsInt()));

                for (Map.Entry<String, JsonElement> rewardEntry : lootJson.get("rewards").getAsJsonObject().entrySet()) {
                    for (Map.Entry<String, JsonElement> itemEntry : rewardEntry.getValue().getAsJsonObject().get("items").getAsJsonObject().entrySet()) {
                        ItemStack stack = jsonItem(itemEntry.getValue());

                        if (!stack.isEmpty()) {
                            table.rewards.add(new WeightedReward(new ItemReward(table.fakeQuest, stack), rewardEntry.getValue().getAsJsonObject().get("weight").getAsInt()));
                            break;
                        }
                    }
                }
            }
        }
        // endregion loot

        for (Map.Entry<String, JsonElement> entry : json.get("questDatabase").getAsJsonObject().entrySet()) {
            JsonObject questJson = entry.getValue().getAsJsonObject();
            BQQuest quest = new BQQuest();
            quest.id = questJson.get("questID").getAsInt();
            questMap.put(quest.id, quest);
            JsonArray deps = questJson.get("preRequisites").getAsJsonArray();
            quest.dependencies = new int[deps.size()];

            for (int i = 0; i < quest.dependencies.length; i++) {
                quest.dependencies[i] = deps.get(i).getAsInt();
            }

            quest.tasks = new ArrayList<>();
            quest.rewards = new ArrayList<>();

            JsonObject properties = questJson.get("properties").getAsJsonObject().get("betterquesting").getAsJsonObject();
            quest.name = properties.get("name").getAsString().trim();
            quest.description = properties.get("desc").getAsString().trim().split("\n");
            quest.icon = jsonItem(properties.get("icon"));
            quest.isSilent = properties.has("issilent") && properties.get("issilent").getAsInt() == 1;
            quest.taskLogicAnd = properties.has("tasklogic") && properties.get("tasklogic").getAsString().equalsIgnoreCase("AND");
            quest.repeatTime = properties.has("repeattime") ? properties.get("repeattime").getAsInt() : -1;
            quest.teamReward = properties.has("partysinglereward") && properties.get("partysinglereward").getAsInt() == 1;
            quest.autoClaim = properties.has("autoclaim") && properties.get("autoclaim").getAsInt() == 1;

            // region tasks
            for (Map.Entry<String, JsonElement> taskEntry : questJson.get("tasks").getAsJsonObject().entrySet()) {
                JsonObject taskJson = taskEntry.getValue().getAsJsonObject();
                String type = taskJson.get("taskID").getAsString();

                switch (type) {
                    case "bq_standard:crafting":
                    case "bq_standard:retrieval": {
                        if (quest.taskLogicAnd) {
                            for (Map.Entry<String, JsonElement> taskItemEntry : taskJson.get("requiredItems").getAsJsonObject().entrySet()) {
                                BQItemTask task = new BQItemTask();
                                task.id = Integer.parseInt(taskEntry.getKey());
                                task.items = new ArrayList<>();
                                task.ignoreNBT = taskJson.get("ignoreNBT").getAsBoolean();
                                task.consume = taskJson.get("consume").getAsBoolean();
                                ItemStack stack = jsonItem(taskItemEntry.getValue(), true);

                                if (!stack.isEmpty()) {
                                    task.items.add(stack);
                                    quest.tasks.add(task);
                                }
                            }
                        } else {
                            BQItemTask task = new BQItemTask();
                            task.id = Integer.parseInt(taskEntry.getKey());
                            task.items = new ArrayList<>();
                            task.ignoreNBT = taskJson.get("ignoreNBT").getAsBoolean();
                            task.consume = taskJson.get("consume").getAsBoolean();

                            for (Map.Entry<String, JsonElement> taskItemEntry : taskJson.get("requiredItems").getAsJsonObject().entrySet()) {
                                ItemStack item = jsonItem(taskItemEntry.getValue(), true);

                                if (!item.isEmpty()) {
                                    task.items.add(item);
                                }
                            }

                            if (!task.items.isEmpty()) {
                                quest.tasks.add(task);
                            }
                        }

                        break;
                    }

                    case "bq_standard:checkbox":
                        quest.tasks.add(new BQCheckBoxTask());
                        break;

                    case "bq_standard:xp": {
                        BQXPTask task = new BQXPTask();
                        task.id = Integer.parseInt(taskEntry.getKey());
                        task.xp = taskJson.get("amount").getAsLong();
                        task.consume = taskJson.get("consume").getAsInt() == 1;
                        task.levels = taskJson.get("isLevels").getAsInt() == 1;
                        quest.tasks.add(task);
                        break;
                    }

                    case "bq_standard:hunt": {
                        BQHuntTask task = new BQHuntTask();
                        task.id = Integer.parseInt(taskEntry.getKey());
                        task.target = taskJson.get("target").getAsString();
                        task.required = taskJson.get("required").getAsLong();
                        quest.tasks.add(task);
                        break;
                    }

                    case "bq_standard:location": {
                        BQLocationTask task = new BQLocationTask();
                        task.id = Integer.parseInt(taskEntry.getKey());
                        task.x = taskJson.get("posX").getAsInt();
                        task.y = taskJson.get("posY").getAsInt();
                        task.z = taskJson.get("posZ").getAsInt();
                        task.dimension = taskJson.get("dimension").getAsInt();
                        task.range = taskJson.get("range").getAsInt();
                        quest.tasks.add(task);
                        break;
                    }

                    default:
                        sender.sendMessage(new TextComponentString("Can't import task with type " + type + ", you will have to manually re-add it!"));
                        break;
                }
            }
            // endregion tasks

            // region rewards
            for (Map.Entry<String, JsonElement> rewardEntry : questJson.get("rewards").getAsJsonObject().entrySet()) {
                JsonObject rewardJson = rewardEntry.getValue().getAsJsonObject();
                String type = rewardJson.get("rewardID").getAsString();

                switch (type) {
                    case "bq_standard:item": {
                        for (Map.Entry<String, JsonElement> itemRewardEntry : rewardJson.get("rewards").getAsJsonObject().entrySet()) {
                            BQItemReward reward = new BQItemReward();
                            reward.item = jsonItem(itemRewardEntry.getValue());

                            ItemStack loot_chest = new ItemStack(FTBQuestsItems.LOOTCRATE);
                            loot_chest.setTagInfo("type", new NBTTagString("loot_chest"));

                            if (truncate_loot && ItemStack.areItemStackTagsEqual(reward.item, loot_chest) && crateTable != null) {
                                BQLootChestReward loot_reward = new BQLootChestReward();
                                loot_reward.table = crateTable;
                                quest.rewards.add(loot_reward);
                            } else if (!reward.item.isEmpty()) {
                                quest.rewards.add(reward);
                            }
                        }

                        break;
                    }

                    case "bq_standard:choice": {
                        BQChoiceReward reward = new BQChoiceReward();
                        reward.items = new ArrayList<>();

                        for (Map.Entry<String, JsonElement> itemRewardEntry : rewardJson.get("choices").getAsJsonObject().entrySet()) {
                            ItemStack stack = jsonItem(itemRewardEntry.getValue());

                            if (!stack.isEmpty()) {
                                reward.items.add(stack);
                            }
                        }

                        if (!reward.items.isEmpty()) {
                            quest.rewards.add(reward);
                        }

                        break;
                    }

                    case "bq_standard:xp": {
                        BQXPReward reward = new BQXPReward();
                        reward.levels = rewardJson.get("isLevels").getAsInt() == 1;
                        reward.xp = rewardJson.get("amount").getAsInt();
                        quest.rewards.add(reward);
                        break;
                    }

                    case "bq_standard:command": {
                        BQCommandReward reward = new BQCommandReward();
                        reward.command = rewardJson.get("command").getAsString().replace("VAR_NAME", "@p");
                        reward.player = rewardJson.get("viaPlayer").getAsInt() == 1;
                        quest.rewards.add(reward);
                        break;
                    }

                    default:
                        sender.sendMessage(new TextComponentString("Can't import reward with type " + type));
                        break;
                }
            }
            // endregion rewards
        }

        for (Map.Entry<String, JsonElement> entry : json.get("questLines").getAsJsonObject().entrySet()) {
            JsonObject chapterJson = entry.getValue().getAsJsonObject();
            JsonObject properties = chapterJson.get("properties").getAsJsonObject().get("betterquesting").getAsJsonObject();

            BQChapter chapter = new BQChapter();
            chapters.add(chapter);
            chapter.name = properties.get("name").getAsString().trim();
            chapter.desc = properties.get("desc").getAsString().trim().split("\n");
            chapter.icon = jsonItem(properties.get("icon"));
            chapter.quests = new ArrayList<>();

            for (Map.Entry<String, JsonElement> questEntry : chapterJson.get("quests").getAsJsonObject().entrySet()) {
                JsonObject questJson = questEntry.getValue().getAsJsonObject();
                BQQuest quest = questMap.get(questJson.get("id").getAsInt());

                if (quest != null) {
                    quest.x = questJson.get("x").getAsDouble() / 24D;
                    quest.y = questJson.get("y").getAsDouble() / 24D;
                    quest.size = Math.min(questJson.get("sizeX").getAsDouble() / 24D, questJson.get("sizeY").getAsDouble() / 24D);
                    chapter.quests.add(quest);
                }
            }
        }

        // remap old quest ids to new imported quests

        Map<Integer, Integer> newQuestMap = new HashMap<>();
        Map<Integer, Map<Integer, Integer>> questTaskMap = new HashMap<>();

        for (BQChapter chapter : chapters) {
            Chapter c = new Chapter(f);
            c.id = f.newID();
            f.chapters.add(c);
            c.title = chapter.name;
            c.subtitle.addAll(Arrays.asList(chapter.desc));
            c.icon = fix_icons ? chapter.icon.splitStack(1) : chapter.icon;

            for (BQQuest quest : chapter.quests) {
                Quest q = new Quest(c);
                q.id = f.newID();
                newQuestMap.put(quest.id, q.id);
                questTaskMap.put(q.id, new HashMap<>());
                c.quests.add(q);
                q.title = quest.name;
                q.description.addAll(Arrays.asList(quest.description));
                q.icon = fix_icons ? quest.icon.splitStack(1) : quest.icon;
                q.x = quest.x;
                q.y = quest.y;
                q.size = quest.size;
                q.canRepeat = quest.repeatTime != -1;

                if (quest.isSilent) {
                    q.disableToast = true;
                }

                for (BQTask task : quest.tasks) {
                    Task t = task.create(q);
                    t.id = f.newID();
                    q.tasks.add(t);

                    questTaskMap.get(q.id).put(task.id, t.id);
                }

                for (BQReward reward : quest.rewards) {
                    Reward r = reward.create(q);

                    if (quest.teamReward) {
                        r.team = EnumTristate.TRUE;
                    }

                    r.autoclaim = (reward instanceof BQCommandReward && auto_cmd) ? RewardAutoClaim.INVISIBLE :
                            quest.autoClaim ? RewardAutoClaim.ENABLED : RewardAutoClaim.DEFAULT;

                    r.id = f.newID();
                    q.rewards.add(r);
                }
            }
        }

        for (BQChapter chapter : chapters) {
            for (BQQuest quest : chapter.quests) {
                if (quest.dependencies.length > 0) {
                    Quest q = f.getQuest(newQuestMap.get(quest.id));

                    for (int d : quest.dependencies) {
                        BQQuest d1 = questMap.get(d);

                        if (d1 != null) {
                            Quest q1 = f.getQuest(newQuestMap.get(d1.id));

                            if (q1 != null) {
                                q.dependencies.add(q1);
                            }
                        }
                    }
                }
            }
        }

        f.clearCachedData();
        f.save();
        f.saveNow();

        Map<Integer, Map.Entry<Integer, Map<Integer, Integer>>> completeQuestMap = newQuestMap.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey, intQuest -> new AbstractMap.SimpleEntry<>(
                        intQuest.getValue(), questTaskMap.get(intQuest.getValue()))));


        try {
            Writer writer = new FileWriter(new File(Loader.instance().getConfigDir(), "imported_quests.json"));
            Gson importedQuests = new GsonBuilder().enableComplexMapKeySerialization().setPrettyPrinting().create();
            importedQuests.toJson(completeQuestMap, writer);
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }


        sender.sendMessage(new TextComponentString("Finished importing Quests and Loot!"));
        server.getPlayerList().sendMessage(new TextComponentString("Server has successfuly imported quests and loot tables from Better Questing! Rejoin the world or server now to get the updated quests."));
        server.getPlayerList().sendMessage(new TextComponentString("Make sure to double-check everything as well, as the two mods are fundamentally different from one another."));
    }

    public void importProgress(MinecraftServer server, ICommandSender sender, List<String> flags) throws CommandException {
        throw new CommandNotFoundException("This command is currently work in progress!");
    }

    // TODO: move this to importProgress
    private void handleParties(MinecraftServer server) {
        final Universe u = Universe.get();

        JsonElement partiesJson0 = DataReader.get(new File(u.getWorldDirectory(), "betterquesting/QuestingParties.json")).safeJson();

        if (!partiesJson0.isJsonObject()) {
            server.sendMessage(new TextComponentString("betterquesting/QuestingParties.json was not found in your save!"));
            return;
        }

        JsonObject partiesJson = fix(partiesJson0).getAsJsonObject();
        List<BQParty> parties = new ArrayList<>();
        Collection<ForgePlayer> knownPlayers = new HashSet<>();

        // Build a List of all BQ Parties
        for (Map.Entry<String, JsonElement> entry : partiesJson.get("parties").getAsJsonObject().entrySet()) {
            JsonObject partyJson = entry.getValue().getAsJsonObject();
            JsonObject properties = partyJson.get("properties").getAsJsonObject().get("betterquesting").getAsJsonObject();

            // if the party has separate rewards for each player then there is no purpose in creating a new FTBLib team for them
            if (properties.has("partysinglereward") && properties.get("partysinglereward").getAsInt() == 1) continue;

            BQParty party = new BQParty();
            party.id = partyJson.get("partyID").getAsInt();
            party.name = partyJson.get("properties").getAsJsonObject().get("name").getAsString().trim();

            for (Map.Entry<String, JsonElement> memberEntry : partyJson.get("members").getAsJsonObject().entrySet()) {
                JsonObject memberJson = memberEntry.getValue().getAsJsonObject();
                if (memberJson.has("uuid") && memberJson.has("status")) {
                    String uuid = memberJson.get("uuid").getAsString();
                    String status = memberJson.get("status").getAsString();

                    if (status.equals("INVITE")) continue;
                    if (status.equals("OWNER")) party.owner = u.getPlayer(uuid);
                    party.members.add(u.getPlayer(uuid));
                    knownPlayers.add(u.getPlayer(uuid));
                }
            }

            parties.add(party);
        }

    }

    private ItemStack jsonItem(@Nullable JsonElement json0) {
        return jsonItem(json0, false);
    }

    private ItemStack jsonItem(@Nullable JsonElement json0, boolean allowFilter) {
        if (json0 == null || !json0.isJsonObject()) {
            return ItemStack.EMPTY;
        }

        JsonObject json = json0.getAsJsonObject();

        if (json.has("OreDict") && !json.get("OreDict").getAsString().isEmpty()) {

            String ore = json.get("OreDict").getAsString();

            if (allowFilter) {
                ItemStack oreFilter = new ItemStack(ItemFiltersItems.FILTER);
                IItemFilter filter = ItemFiltersAPI.getFilter(oreFilter);

                if (filter instanceof ItemFilter.ItemFilterData) {
                    ((ItemFilter.ItemFilterData) filter).filter = new OreDictionaryFilter();
                    ((OreDictionaryFilter) ((ItemFilter.ItemFilterData) filter).filter).setValue(ore);
                }

                return oreFilter;
            } else {
                return OreDictionary.doesOreNameExist(ore) ?
                        OreDictionary.getOres(ore).get(0) : ItemStack.EMPTY;
            }
        }

        String id = json.has("id") ? json.get("id").getAsString() : "";

        if (id.isEmpty() || id.equals("betterquesting:placeholder")) {
            return ItemStack.EMPTY;
        } else if (id.equals("bq_standard:loot_chest")) {
            ItemStack stack = new ItemStack(FTBQuestsItems.LOOTCRATE);
            stack.setTagInfo("type", new NBTTagString("loot_chest"));
            return stack;
        }

        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setString("id", json.get("id").getAsString());
        nbt.setInteger("Count", json.get("Count").getAsInt());
        nbt.setInteger("Damage", json.get("Damage").getAsInt());

        if (json.has("tag")) {
            try {
                NBTTagCompound nbt1 = JsonToNBT.getTagFromJson(json.get("tag").toString());

                if (nbt1 != null) {
                    nbt.setTag("tag", nbt1);
                }
            } catch (Exception ex) {
            }
        }

        if (json.has("ForgeCaps")) {
            try {
                NBTTagCompound nbt1 = JsonToNBT.getTagFromJson(json.get("ForgeCaps").toString());

                if (nbt1 != null) {
                    nbt.setTag("ForgeCaps", nbt1);
                }
            } catch (Exception ex) {
            }
        }

        return ItemMissing.read(nbt);
    }

    private JsonElement fix(JsonElement json) {
        if (json instanceof JsonObject) {
            JsonObject o = new JsonObject();

            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
                String s = entry.getKey();
                int i = s.lastIndexOf(':');

                if (i != -1) {
                    s = s.substring(0, i);
                }

                o.add(s, fix(entry.getValue()));
            }

            return o;
        } else if (json instanceof JsonArray) {
            JsonArray a = new JsonArray();

            for (JsonElement e : json.getAsJsonArray()) {
                a.add(fix(e));
            }

            return a;
        }

        return json;
    }

    // Party Stuff //

    private static class BQParty {
        int id;
        String name;
        ForgePlayer owner;
        Collection<ForgePlayer> members;
    }

    // Quest & Chapter //

    private static class BQQuest {
        int id;
        int[] dependencies;
        String name;
        String[] description;
        double x, y, size;
        boolean isSilent;
        boolean taskLogicAnd;
        int repeatTime;
        ItemStack icon;
        boolean teamReward;
        boolean autoClaim;
        List<BQTask> tasks;
        List<BQReward> rewards;

        @Override
        public String toString() {
            return name + " [" + id + "]";
        }
    }

    private static class BQChapter {
        List<BQQuest> quests;
        String name;
        String[] desc;
        ItemStack icon;
    }

    // Tasks //

    private static abstract class BQTask {
        int id;

        abstract Task create(Quest quest);
    }

    private static class BQItemTask extends BQTask {
        List<ItemStack> items;
        boolean ignoreNBT;
        boolean consume;

        @Override
        Task create(Quest quest) {
            ItemTask task = new ItemTask(quest);
            task.items.addAll(items);

            if (items.size() == 1) {
                ItemStack stack = items.get(0);
                task.count = stack.getCount();

                if (!stack.isStackable()) {
                    task.nbtMode = NBTMatchingMode.IGNORE;
                    task.ignoreDamage = !stack.getHasSubtypes();
                }
            }

            if (ignoreNBT) {
                task.nbtMode = NBTMatchingMode.IGNORE;
            }

            return task;
        }
    }

    private static class BQCheckBoxTask extends BQTask {
        @Override
        Task create(Quest quest) {
            return new CheckmarkTask(quest);
        }
    }

    private static class BQHuntTask extends BQTask {
        String target;
        long required;

        @Override
        Task create(Quest quest) {
            KillTask task = new KillTask(quest);
            task.entity = new ResourceLocation(target);
            task.value = required;
            return task;
        }
    }

    private static class BQXPTask extends BQTask {
        long xp;
        boolean levels;
        boolean consume;

        @Override
        Task create(Quest quest) {
            XPTask task = new XPTask(quest);
            task.value = xp;
            task.points = !levels;
            return task;
        }
    }

    private static class BQLocationTask extends BQTask {
        int x, y, z;
        int dimension;
        int range;

        @Override
        Task create(Quest quest) {
            if (range == -1) {
                DimensionTask task = new DimensionTask(quest);
                task.dimension = dimension;
                return task;
            } else {
                LocationTask task = new LocationTask(quest);
                task.dimension = dimension;
                task.x = x - range / 2;
                task.y = y - range / 2;
                task.z = z - range / 2;
                task.w = range;
                task.h = range;
                task.d = range;
                return task;
            }
        }
    }

    // Rewards //

    private static abstract class BQReward {
        abstract Reward create(Quest q);
    }

    private static class BQItemReward extends BQReward {
        ItemStack item;

        @Override
        public Reward create(Quest q) {
            return new ItemReward(q, item);
        }
    }

    private static class BQLootChestReward extends BQReward {
        RewardTable table;

        @Override
        public Reward create(Quest q) {
            RandomReward reward = new RandomReward(q);
            reward.table = table;
            return reward;
        }
    }

    private static class BQChoiceReward extends BQReward {
        List<ItemStack> items;

        @Override
        Reward create(Quest q) {
            ChoiceReward reward = new ChoiceReward(q);
            reward.table = new RewardTable(q.chapter.file);
            reward.table.id = reward.table.file.newID();
            reward.table.file.rewardTables.add(reward.table);
            reward.table.title = q.title;

            for (ItemStack stack : items) {
                reward.table.rewards.add(new WeightedReward(new ItemReward(reward.table.fakeQuest, stack), 1));
            }

            return reward;
        }
    }

    private static class BQXPReward extends BQReward {
        int xp;
        boolean levels;

        @Override
        Reward create(Quest quest) {
            if (levels) {
                XPLevelsReward reward = new XPLevelsReward(quest);
                reward.xpLevels = xp;
                return reward;
            } else {
                XPReward reward = new XPReward(quest);
                reward.xp = xp;
                return reward;
            }
        }
    }

    private static class BQCommandReward extends BQReward {
        String command;
        boolean player;

        @Override
        Reward create(Quest quest) {
            CommandReward reward = new CommandReward(quest);
            reward.command = command;
            reward.playerCommand = player;
            return reward;
        }
    }
}
