package dev.maxneedssnacks.ftbqimporter;

import com.feed_the_beast.ftblib.events.team.ForgeTeamCreatedEvent;
import com.feed_the_beast.ftblib.events.team.ForgeTeamPlayerJoinedEvent;
import com.feed_the_beast.ftblib.lib.EnumTeamColor;
import com.feed_the_beast.ftblib.lib.config.EnumTristate;
import com.feed_the_beast.ftblib.lib.data.ForgePlayer;
import com.feed_the_beast.ftblib.lib.data.ForgeTeam;
import com.feed_the_beast.ftblib.lib.data.TeamType;
import com.feed_the_beast.ftblib.lib.data.Universe;
import com.feed_the_beast.ftblib.lib.io.DataReader;
import com.feed_the_beast.ftblib.lib.util.FileUtils;
import com.feed_the_beast.ftblib.lib.util.JsonUtils;
import com.feed_the_beast.ftblib.lib.util.StringUtils;
import com.feed_the_beast.ftbquests.item.FTBQuestsItems;
import com.feed_the_beast.ftbquests.quest.ChangeProgress;
import com.feed_the_beast.ftbquests.quest.Chapter;
import com.feed_the_beast.ftbquests.quest.Quest;
import com.feed_the_beast.ftbquests.quest.ServerQuestFile;
import com.feed_the_beast.ftbquests.quest.loot.LootCrate;
import com.feed_the_beast.ftbquests.quest.loot.RewardTable;
import com.feed_the_beast.ftbquests.quest.loot.WeightedReward;
import com.feed_the_beast.ftbquests.quest.reward.*;
import com.feed_the_beast.ftbquests.quest.task.*;
import com.feed_the_beast.ftbquests.util.ServerQuestData;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.latmod.mods.itemfilters.api.IItemFilter;
import com.latmod.mods.itemfilters.api.ItemFiltersAPI;
import com.latmod.mods.itemfilters.filters.NBTMatchingMode;
import com.latmod.mods.itemfilters.filters.OreDictionaryFilter;
import com.latmod.mods.itemfilters.item.ItemFilter;
import com.latmod.mods.itemfilters.item.ItemFiltersItems;
import com.latmod.mods.itemfilters.item.ItemMissing;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.oredict.OreDictionary;

import java.io.File;
import java.util.*;

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

        JsonElement defaultQuestsJson = DataReader.get(new File(Loader.instance().getConfigDir(), "betterquesting/DefaultQuests.json")).safeJson();
        JsonElement defaultLootJson = DataReader.get(new File(Loader.instance().getConfigDir(), "betterquesting/DefaultLoot.json")).safeJson();

        if (!defaultQuestsJson.isJsonObject()) {
            sender.sendMessage(new TextComponentString("config/betterquesting/DefaultQuests.json not found!"));
            return;
        }

        NBTTagCompound defaultQuests = NBTConverter.JSONtoNBT_Object(defaultQuestsJson.getAsJsonObject(), new NBTTagCompound(), true);

        String ver;
        if (!(ver = defaultQuests.getString("format")).startsWith("2")) {
            sender.sendMessage(new TextComponentString("Cannot import DefaultQuests.json with old version (" + ver + ")!"));
            return;
        }

        // clear out existing files
        ServerQuestFile f = ServerQuestFile.INSTANCE;
        f.chapters.clear();
        f.rewardTables.clear();
        FileUtils.deleteSafe(f.getFolder());

        // also clear out quest data in case of a re-import of quests
        FileUtils.deleteSafe(new File(f.universe.getWorldDirectory(), "data/ftb_lib/teams/ftbquests"));

        Map<Integer, BQQuest> questMap = new HashMap<>();
        List<BQChapter> chapters = new ArrayList<>();

        // region loot
        RewardTable crateTable = null;

        if (defaultLootJson.isJsonObject()) {
            NBTTagCompound defaultLoot = NBTConverter.JSONtoNBT_Object(defaultLootJson.getAsJsonObject(), new NBTTagCompound(), true);

            crateTable = new RewardTable(f);
            crateTable.id = f.newID();
            f.rewardTables.add(crateTable);
            crateTable.title = "Loot Chest";
            if (!truncate_loot) {
                crateTable.lootCrate = new LootCrate(crateTable);
                crateTable.lootCrate.stringID = "loot_chest";
                crateTable.lootCrate.glow = true;
            }

            for (NBTBase groupBase : defaultLoot.getTagList("groups", 10)) {
                NBTTagCompound groupNbt = (NBTTagCompound) groupBase;

                RewardTable table = new RewardTable(f);
                table.title = groupNbt.getString("name");
                table.id = f.newID();
                f.rewardTables.add(table);
                table.lootCrate = new LootCrate(table);
                table.lootCrate.stringID = StringUtils.getID(table.title, StringUtils.FLAG_ID_DEFAULTS);
                table.lootCrate.itemName = table.title;

                crateTable.rewards.add(new WeightedReward(new ItemReward(crateTable.fakeQuest, table.lootCrate.createStack()), groupNbt.getInteger("weight")));

                for (NBTBase rewardBase : defaultLoot.getTagList("rewards", 10)) {
                    NBTTagCompound rewardNbt = (NBTTagCompound) rewardBase;
                    for (NBTBase rewardItem : rewardNbt.getTagList("items", 10)) {
                        ItemStack stack = nbtItem((NBTTagCompound) rewardItem);
                        if (!stack.isEmpty()) {
                            table.rewards.add(new WeightedReward(new ItemReward(table.fakeQuest, stack), rewardNbt.getInteger("weight")));
                            break;
                        }
                    }
                }
            }
        }
        // endregion loot

        for (NBTBase questBase : defaultQuests.getTagList("questDatabase", 10)) {
            NBTTagCompound questNbt = (NBTTagCompound) questBase;

            BQQuest quest = new BQQuest();
            quest.id = questNbt.getInteger("questID");
            questMap.put(quest.id, quest);

            quest.dependencies = questNbt.getIntArray("preRequisites");

            quest.tasks = new ArrayList<>();
            quest.rewards = new ArrayList<>();

            NBTTagCompound properties = questNbt.getCompoundTag("properties").getCompoundTag("betterquesting");
            quest.name = properties.getString("name").trim();
            quest.description = properties.getString("desc").trim().split("\n");
            quest.icon = nbtItem(properties.getCompoundTag("icon"));
            quest.isSilent = properties.getBoolean("issilent");
            quest.taskLogicAnd = properties.getString("tasklogic").equalsIgnoreCase("AND");
            quest.repeatTime = properties.getInteger("repeattime");
            quest.teamReward = properties.getBoolean("partysinglereward");
            quest.autoClaim = properties.getBoolean("autoclaim");

            // region tasks
            for (NBTBase taskBase : questNbt.getTagList("tasks", 10)) {
                NBTTagCompound taskNbt = (NBTTagCompound) taskBase;

                String type = taskNbt.getString("taskID");

                switch (type) {
                    case "bq_standard:crafting":
                    case "bq_standard:retrieval": {
                        if (quest.taskLogicAnd) {
                            for (NBTBase taskItemBase : taskNbt.getTagList("requiredItems", 10)) {
                                BQItemTask task = makeItemTask(taskNbt);
                                ItemStack stack = nbtItem((NBTTagCompound) taskItemBase, true);

                                if (!stack.isEmpty()) {
                                    task.items.add(stack);
                                    quest.tasks.add(task);
                                }
                            }
                        } else {
                            BQItemTask task = makeItemTask(taskNbt);

                            for (NBTBase taskItemBase : taskNbt.getTagList("requiredItems", 10)) {
                                ItemStack item = nbtItem((NBTTagCompound) taskItemBase, true);

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
                        task.id = taskNbt.getInteger("index");
                        task.xp = taskNbt.getLong("amount");
                        task.consume = taskNbt.getBoolean("consume");
                        task.levels = taskNbt.getBoolean("isLevels");
                        quest.tasks.add(task);
                        break;
                    }

                    case "bq_standard:hunt": {
                        BQHuntTask task = new BQHuntTask();
                        task.id = taskNbt.getInteger("index");
                        task.target = taskNbt.getString("target");
                        task.required = taskNbt.getLong("required");
                        quest.tasks.add(task);
                        break;
                    }

                    case "bq_standard:location": {
                        BQLocationTask task = new BQLocationTask();
                        task.id = taskNbt.getInteger("index");
                        task.x = taskNbt.getInteger("posX");
                        task.y = taskNbt.getInteger("posY");
                        task.z = taskNbt.getInteger("posZ");
                        task.dimension = taskNbt.getInteger("dimension");
                        task.range = taskNbt.getInteger("range");
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
            for (NBTBase rewardBase : questNbt.getTagList("rewards", 10)) {
                NBTTagCompound rewardNbt = (NBTTagCompound) rewardBase;
                String type = rewardNbt.getString("rewardID");

                switch (type) {
                    case "bq_standard:item": {
                        for (NBTBase rewardItem : rewardNbt.getTagList("rewards", 10)) {

                            BQItemReward reward = new BQItemReward();
                            reward.item = nbtItem((NBTTagCompound) rewardItem);

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

                        for (NBTBase rewardItem : rewardNbt.getTagList("choices", 10)) {
                            ItemStack stack = nbtItem((NBTTagCompound) rewardItem);
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
                        reward.levels = rewardNbt.getBoolean("isLevels");
                        reward.xp = rewardNbt.getInteger("amount");
                        quest.rewards.add(reward);
                        break;
                    }

                    case "bq_standard:command": {
                        BQCommandReward reward = new BQCommandReward();
                        reward.command = rewardNbt.getString("command").replace("VAR_NAME", "@p");
                        reward.player = rewardNbt.getBoolean("viaPlayer");
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

        for (NBTBase chapterBase : defaultQuests.getTagList("questLines", 10)) {
            NBTTagCompound chapterNbt = (NBTTagCompound) chapterBase;
            NBTTagCompound properties = chapterNbt.getCompoundTag("properties").getCompoundTag("betterquesting");

            BQChapter chapter = new BQChapter();
            chapters.add(chapter);
            chapter.name = properties.getString("name").trim();
            chapter.desc = properties.getString("desc").trim().split("\n");
            chapter.icon = nbtItem(properties.getCompoundTag("icon"));
            chapter.quests = new ArrayList<>();

            for (NBTBase questBase : chapterNbt.getTagList("quests", 10)) {
                NBTTagCompound questNbt = (NBTTagCompound) questBase;
                BQQuest quest = questMap.get(questNbt.getInteger("id"));

                if (quest != null) {
                    quest.x = questNbt.getDouble("x") / 24D;
                    quest.y = questNbt.getDouble("y") / 24D;
                    quest.size = Math.min(questNbt.getDouble("sizeX") / 24D, questNbt.getDouble("sizeY") / 24D);
                    chapter.quests.add(quest);
                }
            }
        }

        // remap old quest ids to new imported quests

        Map<Integer, Quest> newQuestMap = new HashMap<>();
        Map<Quest, Map<Integer, Task>> questTaskMap = new HashMap<>();

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
                newQuestMap.put(quest.id, q);
                questTaskMap.put(q, new HashMap<>());
                c.quests.add(q);
                q.title = quest.name;
                q.description.addAll(Arrays.asList(quest.description));
                q.icon = fix_icons ? quest.icon.splitStack(1) : quest.icon;
                q.x = quest.x;
                q.y = quest.y;
                q.size = quest.size;
                q.canRepeat = quest.repeatTime > 0;

                if (quest.isSilent) {
                    q.disableToast = true;
                }

                for (BQTask task : quest.tasks) {
                    Task t = task.create(q);
                    t.id = f.newID();
                    q.tasks.add(t);

                    questTaskMap.get(q).put(task.id, t);
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
                    Quest q = newQuestMap.get(quest.id);

                    for (int d : quest.dependencies) {
                        BQQuest d1 = questMap.get(d);

                        if (d1 != null) {
                            Quest q1 = newQuestMap.get(d1.id);

                            if (q1 != null && q != null) {
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

        // TODO: save as NBT instead of JSON
        JsonObject importedQuests = new JsonObject();
        newQuestMap.forEach((bqQuest, ftbQuest) -> {
            JsonObject questInfo = new JsonObject();
            importedQuests.add(bqQuest.toString(), questInfo);
            questInfo.addProperty("id", ftbQuest.id);

            JsonObject taskInfo = new JsonObject();
            questInfo.add("tasks", taskInfo);
            questTaskMap.get(ftbQuest).forEach((bqTask, ftbTask) -> {
                taskInfo.addProperty(bqTask.toString(), ftbTask.id);
            });
        });

        JsonObject toExport = new JsonObject();
        toExport.addProperty("_version", 1);
        toExport.add("data", importedQuests);

        JsonUtils.toJsonSafe(new File(Loader.instance().getConfigDir(), "imported_quests.json"), toExport);

        sender.sendMessage(new TextComponentString("Finished importing Quests and Loot!"));
        server.getPlayerList().sendMessage(new TextComponentString("Server has successfully imported quests and loot tables from Better Questing! Rejoin the world or server now to get the updated quests."));
        server.getPlayerList().sendMessage(new TextComponentString("Make sure to double-check everything as well, as the two mods are fundamentally different from one another."));
    }

    private BQItemTask makeItemTask(NBTTagCompound nbt) {
        BQItemTask task = new BQItemTask();
        task.id = nbt.getInteger("index");
        task.items = new ArrayList<>();
        task.ignoreNBT = nbt.getBoolean("ignoreNBT");
        task.consume = nbt.getBoolean("consume");
        return task;
    }

    public void importProgress(MinecraftServer server, ICommandSender sender, List<String> flags) throws CommandException {
        final Universe u = Universe.get();
        final int CONVERT_FILE_VERSION = 1;

        JsonElement questingPartiesJson = DataReader.get(new File(u.getWorldDirectory(), "betterquesting/QuestingParties.json")).safeJson();
        if (!questingPartiesJson.isJsonObject()) {
            sender.sendMessage(new TextComponentString("betterquesting/QuestingParties.json was not found in your save!"));
            return;
        }
        NBTTagCompound questingParties = NBTConverter.JSONtoNBT_Object(questingPartiesJson.getAsJsonObject(), new NBTTagCompound(), true);

        JsonElement nameCacheJson = DataReader.get(new File(u.getWorldDirectory(), "betterquesting/NameCache.json")).safeJson();
        if (!nameCacheJson.isJsonObject()) {
            sender.sendMessage(new TextComponentString("betterquesting/NameCache.json was not found in your save!"));
            return;
        }
        NBTTagCompound nameCache = NBTConverter.JSONtoNBT_Object(nameCacheJson.getAsJsonObject(), new NBTTagCompound(), true);

        List<BQParty> parties = new ArrayList<>();

        // build a list of solo players, starting with all players known to BQ
        Collection<ForgePlayer> soloPlayers = new HashSet<>();
        for (NBTBase nameBase : nameCache.getTagList("nameCache", 10)) {
            ForgePlayer p = u.getPlayer(((NBTTagCompound) nameBase).getString("uuid"));
            if (p != null) soloPlayers.add(p);
        }

        // Build a List of all BQ Parties
        for (NBTBase partyBase : questingParties.getTagList("parties", 10)) {
            NBTTagCompound partyNbt = (NBTTagCompound) partyBase;

            BQParty party = new BQParty();
            party.id = partyNbt.getInteger("partyID");
            party.name = partyNbt.getCompoundTag("properties").getCompoundTag("betterquesting").getString("name").trim();
            party.members = new ArrayList<>();

            for (NBTBase memberBase : partyNbt.getTagList("members", 10)) {
                NBTTagCompound memberNbt = (NBTTagCompound) memberBase;
                String uuid = memberNbt.getString("uuid");
                String status = memberNbt.getString("status");

                ForgePlayer p = u.getPlayer(uuid);

                if (p == null || status.equals("INVITE")) continue;
                if (status.equals("OWNER")) party.owner = p;
                party.members.add(p);
                soloPlayers.remove(p);
            }

            parties.add(party);
        }

        // create dummy parties for all of the solo players
        for (ForgePlayer p : soloPlayers) {
            BQParty dummy = new BQParty();
            dummy.id = -1;
            dummy.owner = p;
            dummy.name = p.getName();
            dummy.members = new ArrayList<>();
            dummy.members.add(p);
            parties.add(dummy);
        }

        Map<ForgePlayer, ForgeTeam> progressTransferMap = new HashMap<>();

        for (BQParty party : parties) {
            ForgeTeam team;
            ForgePlayer owner = party.owner;

            // if owner has no forge team, create a new one and make them the owner
            if (!owner.hasTeam()) {

                String team_id_base = StringUtils.getID(owner.getName(), StringUtils.FLAG_ID_DEFAULTS);
                String team_id = team_id_base;
                while (u.getTeam(team_id).isValid()) {
                    team_id = team_id_base + u.generateTeamUID((short) 0);
                }

                u.clearCache();
                team = new ForgeTeam(u, u.generateTeamUID((short) 0), team_id, TeamType.PLAYER);
                team.setColor(EnumTeamColor.NAME_MAP.getRandom(sender.getEntityWorld().rand));
                owner.team = team;
                team.owner = owner;
                team.universe.addTeam(team);
                new ForgeTeamCreatedEvent(team).post();
                new ForgeTeamPlayerJoinedEvent(owner).post();

                team.markDirty();
                owner.markDirty();

                if (owner.isOnline()) {
                    owner.getPlayer().sendMessage(
                            new TextComponentString("Quest data for your party is being synced with your new team " + team.getId()));
                }

            } else {

                team = owner.team;

                if (owner.isOnline()) {
                    owner.getPlayer().sendMessage(
                            new TextComponentString("Quest data for your party is being synced with your current team " + team.getId()));
                }

            }

            for (ForgePlayer player : party.members) {
                if (!player.equalsPlayer(owner) && player.isOnline()) {
                    player.getPlayer().sendMessage(
                            new TextComponentString("Quest data is being synced with your party owner's team " + team.getId() + ". Please join this team if you wish to keep your progress"));
                }

                progressTransferMap.putIfAbsent(player, team);
            }
        }

        /*
         * What we have:
         *
         * A list of parties
         * A quest progress file
         * A conversion map to convert old quest / task ids to new ones
         * A map telling us where player progress needs to go
         */

        JsonElement questProgressJson = DataReader.get(new File(u.getWorldDirectory(), "betterquesting/QuestProgress.json")).safeJson();
        if (!questProgressJson.isJsonObject()) {
            sender.sendMessage(new TextComponentString("betterquesting/QuestProgress.json was not found in your save!"));
            return;
        }
        NBTTagCompound questProgress = NBTConverter.JSONtoNBT_Object(questProgressJson.getAsJsonObject(), new NBTTagCompound(), true);

        // FIXME: Don't load JSON, instead use NBT for consistency purposes
        JsonElement convertJson0 = DataReader.get(new File(Loader.instance().getConfigDir(), "imported_quests.json")).safeJson();
        if (!convertJson0.isJsonObject()) {
            sender.sendMessage(new TextComponentString("No conversion mapping was found in your config folder!"));
            return;
        }
        JsonObject convertJson = convertJson0.getAsJsonObject();
        if (!convertJson.has("_version") || convertJson.get("_version").getAsInt() != CONVERT_FILE_VERSION) {
            sender.sendMessage(new TextComponentString("The conversion mapping found is using a different format. Please run quest importing again!"));
            return;
        }

        Collection<BQQuestData> oldProgressData = new HashSet<>();

        for (NBTBase questProgressBase : questProgress.getTagList("questProgress", 10)) {
            NBTTagCompound questProgressNbt = (NBTTagCompound) questProgressBase;

            BQQuestData questData = new BQQuestData();
            questData.id = questProgressNbt.getInteger("questID");
            questData.completed = new HashMap<>();
            for (NBTBase completedBase : questProgressNbt.getTagList("completed", 10)) {
                NBTTagCompound completedNbt = (NBTTagCompound) completedBase;

                String uuid = completedNbt.getString("uuid");

                ForgePlayer p = u.getPlayer(uuid);

                if (p == null) continue;
                questData.completed.put(p, completedNbt.getBoolean("claimed"));
            }

            questData.tasks = new ArrayList<>();
            for (NBTBase taskDataBase : questProgressNbt.getTagList("tasks", 10)) {
                NBTTagCompound taskDataNbt = (NBTTagCompound) taskDataBase;

                BQTaskData taskData = new BQTaskData();
                taskData.id = taskDataNbt.getInteger("index");
                taskData.completeUsers = new ArrayList<>();
                for (NBTBase completeUser : taskDataNbt.getTagList("completeUsers", 8)) {
                    ForgePlayer p = u.getPlayer(((NBTTagString) completeUser).getString());

                    if (p == null) continue;
                    taskData.completeUsers.add(p);
                }
                questData.tasks.add(taskData);
            }

            oldProgressData.add(questData);
        }

        ServerQuestFile f = ServerQuestFile.INSTANCE;

        oldProgressData.forEach(questData -> {
            getConvertedQuest(f, questData.id, convertJson.get("data").getAsJsonObject()).ifPresent(quest -> {
                Collection<ForgeTeam> processed = new HashSet<>();

                questData.completed.forEach((forgePlayer, claimed) -> {
                    if (forgePlayer == null) return;

                    ForgeTeam team = progressTransferMap.get(forgePlayer);

                    ServerQuestData teamData = ServerQuestData.get(team);

                    if (!processed.contains(team)) {
                        quest.forceProgress(teamData, ChangeProgress.COMPLETE_DEPS, false);
                        processed.add(team);
                    }

                    if (claimed) {
                        quest.rewards.forEach(reward -> teamData.setRewardClaimed(forgePlayer.getId(), reward));
                    }
                });

                questData.tasks.forEach(taskData -> {
                    getConvertedTask(f, questData.id, taskData.id, convertJson.get("data").getAsJsonObject()).ifPresent(task -> {
                        Collection<ForgeTeam> processedTask = new HashSet<>();

                        taskData.completeUsers.forEach(forgePlayer -> {
                            if (forgePlayer == null) return;

                            ForgeTeam team = progressTransferMap.get(forgePlayer);

                            ServerQuestData teamData = ServerQuestData.get(team);

                            if (!(processed.contains(team) || processedTask.contains(team))) {
                                task.forceProgress(teamData, ChangeProgress.COMPLETE_DEPS, false);
                                processedTask.add(team);
                            }
                        });
                    });
                });
            });
        });

        server.getPlayerList().sendMessage(new TextComponentString("Server has successfully migrated quest progression for all Better Questing parties to FTB Quests!"));
        server.getPlayerList().sendMessage(new TextComponentString("Players, please note that you may be required to join your former party owner's team in order to get your progress back!"));
    }

    private ItemStack nbtItem(NBTTagCompound itemNbt) {
        return nbtItem(itemNbt, false);
    }

    private ItemStack nbtItem(NBTTagCompound itemNbt, boolean isFilter) {

        ItemStack stack = ItemStack.EMPTY;

        if (itemNbt.isEmpty()) {
            FTBQImporter.LOGGER.debug("Item Task {} has incorrect format! Returning empty item stack", itemNbt);
            return stack;
        }

        String ore = itemNbt.getString("OreDict");
        if (!ore.isEmpty()) {
            stack = oreDictItem(ore, isFilter);
        }
        itemNbt.removeTag("OreDict");

        if (!stack.isEmpty()) return stack;

        String id = itemNbt.getString("id");

        if (id.isEmpty() || id.equals("betterquesting:placeholder")) {
            FTBQImporter.LOGGER.debug("Item ID {} is invalid or empty! Returning empty item stack", id);
            return stack;
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

    private ItemStack oreDictItem(String ore, boolean isFilter) {
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
                return oreFilter;
            }

            return ItemStack.EMPTY;
        } else {
            return (OreDictionary.doesOreNameExist(ore) ? OreDictionary.getOres(ore).get(0) : ItemStack.EMPTY);
        }
    }

    private Optional<Quest> getConvertedQuest(ServerQuestFile f, int id, JsonObject mapping) {

        if (!mapping.has(Integer.toString(id))) return Optional.empty();

        JsonObject mappedJson = mapping.get(Integer.toString(id)).getAsJsonObject();
        int mappedID = mappedJson.get("id").getAsInt();

        return Optional.ofNullable(f.getQuest(mappedID));
    }

    private Optional<Task> getConvertedTask(ServerQuestFile f, int qid, int tid, JsonObject mapping) {

        if (!mapping.has(Integer.toString(qid))) return Optional.empty();

        JsonObject mappedJson = mapping.get(Integer.toString(qid)).getAsJsonObject();
        JsonObject mappedTasks = mappedJson.get("tasks").getAsJsonObject();

        if (!mappedTasks.has(Integer.toString(tid))) return Optional.empty();

        int mappedID = mappedTasks.get(Integer.toString(tid)).getAsInt();

        return Optional.ofNullable(f.getTask(mappedID));
    }

    private JsonElement fix(JsonElement json) {
        if (json instanceof JsonObject) {
            JsonObject o = new JsonObject();

            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
                // FIXME: Stop using Entry Sets
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

    // Progress-Related Stuff //

    private static class BQParty {
        int id;
        String name;
        ForgePlayer owner;
        Collection<ForgePlayer> members;
    }

    private static class BQQuestData {
        int id;
        Map<ForgePlayer, Boolean> completed;
        Collection<BQTaskData> tasks;
    }

    private static class BQTaskData {
        int id;
        Collection<ForgePlayer> completeUsers;
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
