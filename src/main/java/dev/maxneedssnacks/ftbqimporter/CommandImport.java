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
import com.feed_the_beast.ftblib.lib.math.MathUtils;
import com.feed_the_beast.ftblib.lib.util.FileUtils;
import com.feed_the_beast.ftblib.lib.util.NBTUtils;
import com.feed_the_beast.ftblib.lib.util.StringUtils;
import com.feed_the_beast.ftbquests.quest.ChangeProgress;
import com.feed_the_beast.ftbquests.quest.Chapter;
import com.feed_the_beast.ftbquests.quest.Quest;
import com.feed_the_beast.ftbquests.quest.ServerQuestFile;
import com.feed_the_beast.ftbquests.quest.reward.CommandReward;
import com.feed_the_beast.ftbquests.quest.reward.Reward;
import com.feed_the_beast.ftbquests.quest.reward.RewardAutoClaim;
import com.feed_the_beast.ftbquests.quest.task.Task;
import com.feed_the_beast.ftbquests.util.ServerQuestData;
import com.google.common.primitives.Ints;
import com.google.gson.JsonElement;
import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.Loader;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

import static dev.maxneedssnacks.ftbqimporter.Utils.WARNING_TAG;
import static dev.maxneedssnacks.ftbqimporter.Utils.empty;

/**
 * @author LatvianModder, MaxNeedsSnacks
 */
public class CommandImport extends CommandBase {

    private final int CONVERT_FILE_VERSION = 3;

    @Override
    public String getName() {
        return "ftbq_import";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/ftbq_import <quests|progress> [-c, -d]";
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

        // additional flags that may be supplied
        // TODO: reimplement
        // boolean default_icons = flags.contains("-d") || flags.contains("--default-icons");
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
        FileUtils.deleteSafe(new File(Loader.instance().getConfigDir(), "imported_quests.nbt"));

        // Map<Integer, BQQuest> questMap = new HashMap<>();
        Int2ObjectSortedMap<Chapter> chapters = new Int2ObjectAVLTreeMap<>();
        Int2ObjectMap<Quest> questMap = new Int2ObjectOpenHashMap<>();
        Map<Quest, Int2ObjectMap<Collection<Task>>> questTaskMap = new HashMap<>();

        HashMap<Quest, Collection<Integer>> deps = new HashMap<>();

        sender.sendMessage(new TextComponentString("[1/5] Importing loot..."));
        // region STEP_1
        LootImporter lootImporter;

        if (defaultLootJson.isJsonObject()) {
            lootImporter = new LootImporter(defaultLootJson.getAsJsonObject());
            if (!lootImporter.processLoot(f)) {
                sender.sendMessage(new TextComponentString("WARNING: Loot importing finished with warnings! Please check latest.log for more information!")
                        .setStyle(new Style().setColor(TextFormatting.YELLOW)));
            }
        }
        // endregion STEP_1

        sender.sendMessage(new TextComponentString("[2/5] Collecting chapter information..."));
        // region STEP_2
        boolean duplicateQuest = false;
        for (NBTBase chapterBase : defaultQuests.getTagList("questLines", 10)) {
            NBTTagCompound chapterNbt = (NBTTagCompound) chapterBase;
            NBTTagCompound properties = chapterNbt.getCompoundTag("properties").getCompoundTag("betterquesting");

            // create a new chapter, but don't add it to the file yet
            Chapter c = new Chapter(f);
            c.id = f.newID();
            c.title = properties.getString("name").trim();
            c.subtitle.addAll(Arrays.asList(properties.getString("desc").trim().split("\n")));

            // TODO: reimplement default icons
            c.icon = Utils.nbtItem(properties.getCompoundTag("icon")).splitStack(1);

            chapters.put(chapterNbt.getInteger("order"), c);

            //f.chapters.add(c);

            FTBQImporter.LOGGER.debug("Collecting quests for chapter \"{}\"...", c.title);
            for (NBTBase questBase : chapterNbt.getTagList("quests", 10)) {
                NBTTagCompound questNbt = (NBTTagCompound) questBase;

                int qid = questNbt.getInteger("id");
                if (questMap.containsKey(qid)) {
                    duplicateQuest = true;
                } else {
                    Quest q = new Quest(c);
                    q.id = f.newID();

                    questMap.put(qid, q);

                    c.quests.add(q);
                    double sizeX = MathHelper.clamp(Math.max(questNbt.getDouble("size"), questNbt.getDouble("sizeX")) / 24D, 0.5, 3);
                    double sizeY = MathHelper.clamp(Math.max(questNbt.getDouble("size"), questNbt.getDouble("sizeY")) / 24D, 0.5, 3);
                    q.size = Math.min(sizeX, sizeY);
                    q.x = (questNbt.getDouble("x") / 24D) + (sizeX / 2D);
                    q.y = (questNbt.getDouble("y") / 24D) + (sizeY / 2D);

                    // We'll have to do this later as we're currently still going through the chapters.
                    /*
                    q.title = quest.name;
                    q.description.addAll(Arrays.asList(quest.description));
                    q.icon = quest.icon.splitStack(1);
                    q.canRepeat = quest.repeatTime > 0;
                    */

                }

            }
        }

        if (duplicateQuest) {
            sender.sendMessage(new TextComponentString("WARNING: Your quest file contained one or more duplicate quests. Please note that the importer only adds one quest per ID!")
                    .setStyle(new Style().setColor(TextFormatting.YELLOW)));
        }
        // endregion STEP_2

        sender.sendMessage(new TextComponentString("[3/5] Populating quests..."));
        // region STEP_3
        Chapter orphanChapter = new Chapter(f);
        orphanChapter.id = f.newID();
        orphanChapter.title = "Internal";
        orphanChapter.subtitle.add("This chapter contains internal or \"orphaned\" quests,");
        orphanChapter.subtitle.add("which are quests in Better Questing that do not have a chapter associated with them");

        for (NBTBase questBase : defaultQuests.getTagList("questDatabase", 10)) {
            NBTTagCompound questNbt = (NBTTagCompound) questBase;

            int qid = questNbt.getInteger("questID");
            if (!questMap.containsKey(qid)) {
                Quest q = new Quest(orphanChapter);
                q.id = f.newID();

                questMap.put(qid, q);

                orphanChapter.quests.add(q);
            }

            NBTTagCompound properties = questNbt.getCompoundTag("properties").getCompoundTag("betterquesting");

            boolean teamReward = properties.getBoolean("partysinglereward");
            boolean autoClaim = properties.getBoolean("autoclaim");

            Quest q = questMap.get(qid);
            q.title = properties.getString("name").trim();
            q.description.addAll(Arrays.asList(properties.getString("desc").trim().split("\n")));
            q.icon = Utils.nbtItem(properties.getCompoundTag("icon")).splitStack(1);
            q.canRepeat = properties.getInteger("repeattime") > 0;
            q.orTasks = properties.getString("tasklogic").equalsIgnoreCase("OR");
            q.disableToast = properties.getBoolean("issilent") || f.disableToast;

            questTaskMap.putIfAbsent(q, new Int2ObjectOpenHashMap<>());
            deps.put(q, Ints.asList(questNbt.getIntArray("preRequisites")));

            for (NBTBase taskBase : questNbt.getTagList("tasks", 10)) {
                NBTTagCompound taskNbt = (NBTTagCompound) taskBase;
                String type = taskNbt.getString("taskID");

                Collection<Task> tasks = Utils.taskConverters.getOrDefault(type, empty()).apply(taskNbt, q);
                questTaskMap.get(q).put(taskNbt.getInteger("index"), tasks);
                tasks.forEach(t -> {
                    t.id = f.newID();
                    q.tasks.add(t);
                });
            }

            if (q.getTags().contains(WARNING_TAG)) {
                sender.sendMessage(new TextComponentString("One or more tasks were skipped while importing '" + q.title + "' (#" + qid + ") " +
                        "- Please check the log for more details!")
                        .setStyle(new Style().setColor(TextFormatting.YELLOW)));
            }

            for (NBTBase rewardBase : questNbt.getTagList("rewards", 10)) {
                NBTTagCompound rewardNbt = (NBTTagCompound) rewardBase;
                String type = rewardNbt.getString("rewardID");

                Collection<Reward> rewards = Utils.rewardConverters.getOrDefault(type, empty()).apply(rewardNbt, q);
                rewards.forEach(r -> {
                    r.id = f.newID();
                    q.rewards.add(r);

                    if (teamReward) {
                        r.team = EnumTristate.TRUE;
                    }

                    r.autoclaim = (r instanceof CommandReward && auto_cmd) ? RewardAutoClaim.INVISIBLE :
                            autoClaim ? RewardAutoClaim.ENABLED : RewardAutoClaim.DEFAULT;
                });

            }

            if (q.getTags().contains(WARNING_TAG)) {
                sender.sendMessage(new TextComponentString("One or more rewards were skipped while importing '" + q.title + "' (#" + qid + ") " +
                        "- Please check the log for more details!")
                        .setStyle(new Style().setColor(TextFormatting.YELLOW)));
            }

            // clearing out the reward conversion tags
            q.getTags().clear();

        }

        // add orphan quests
        if (!orphanChapter.quests.isEmpty()) {
            sender.sendMessage(new TextComponentString("Your quest file contained one or more orphaned quests. See the quest book after rejoining for more information!")
                    .setStyle(new Style().setColor(TextFormatting.YELLOW)));

            orphanChapter.icon = f.icon;
            int table_size = (int) Math.ceil(MathUtils.sqrt(orphanChapter.quests.size()));
            List<Quest> quests = orphanChapter.quests;
            for (int i = 0; i < quests.size(); i++) {
                Quest q = quests.get(i);
                q.size = 1;
                //noinspection IntegerDivisionInFloatingPointContext
                q.x = i / table_size;
                q.y = i % table_size;
            }

            chapters.put(chapters.lastIntKey() + 1, orphanChapter);
        }
        // endregion STEP_3

        sender.sendMessage(new TextComponentString("[4/5] Readding dependencies..."));
        // region STEP_4
        // update dependencies after going through all quests
        questMap.values().forEach(q -> {
            q.dependencies.addAll(deps.get(q).stream().map(questMap::get).collect(Collectors.toSet()));
        });
        // endregion STEP_4

        sender.sendMessage(new TextComponentString("[5/5] Finishing up..."));
        // region STEP_5
        f.chapters.addAll(chapters.values());

        f.clearCachedData();
        f.save();
        f.saveNow();

        NBTTagCompound importedQuests = new NBTTagCompound();
        questMap.forEach((bqQuest, ftbQuest) -> {
            NBTTagCompound questInfo = new NBTTagCompound();
            importedQuests.setTag(bqQuest.toString(), questInfo);
            questInfo.setInteger("id", ftbQuest.id);

            NBTTagCompound taskInfo = new NBTTagCompound();
            questInfo.setTag("tasks", taskInfo);
            questTaskMap.get(ftbQuest).forEach((bqTask, ftbTasks) -> {
                taskInfo.setIntArray(bqTask.toString(), ftbTasks.stream().mapToInt(task -> task.id).toArray());
            });
        });

        NBTTagCompound exportedData = new NBTTagCompound();
        exportedData.setInteger("_version", CONVERT_FILE_VERSION);
        exportedData.setTag("data", importedQuests);
        NBTUtils.writeNBTSafe(new File(Loader.instance().getConfigDir(), "imported_quests.nbt"), exportedData);
        // endregion STEP_5

        sender.sendMessage(new TextComponentString("Finished importing Quests and Loot!"));
        server.getPlayerList().sendMessage(new TextComponentString("Server has successfully imported quests and loot tables from Better Questing! Rejoin the world or server now to get the updated quests."));
        server.getPlayerList().sendMessage(new TextComponentString("Make sure to double-check everything as well, as the two mods are fundamentally different from one another."));
    }

    public void importProgress(MinecraftServer server, ICommandSender sender, List<String> flags) throws CommandException {
        final Universe u = Universe.get();

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
            if (party.members.isEmpty()) continue;

            ForgeTeam team;
            ForgePlayer owner = party.owner;

            if (owner == null) {
                // safe call since the party isn't empty
                owner = party.members.iterator().next();
                if (owner.isOnline()) {
                    owner.getPlayer().sendMessage(
                            new TextComponentString("You are now the new owner of the quest party " + party.name + "; quest progress will be synced with your FTB Team!"));
                }
            }

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

        NBTTagCompound conversionMapping = NBTUtils.readNBT(new File(Loader.instance().getConfigDir(), "imported_quests.nbt"));

        if (conversionMapping == null) {
            sender.sendMessage(new TextComponentString("No valid conversion mapping was found in your config folder!"));
            return;
        }

        if (conversionMapping.getInteger("_version") != CONVERT_FILE_VERSION) {
            sender.sendMessage(new TextComponentString("The conversion mapping found is using a different format. Please run quest importing again!"));
            return;
        }

        NBTTagCompound conversionData = conversionMapping.getCompoundTag("data");

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
            getConvertedQuest(f, questData.id, conversionData).ifPresent(quest -> {
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
                    getConvertedTasks(f, questData.id, taskData.id, conversionData).forEach(opt -> opt.ifPresent(task -> {
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
                    }));
                });
            });
        });

        server.getPlayerList().sendMessage(new TextComponentString("Server has successfully migrated quest progression for all Better Questing parties to FTB Quests!"));
        server.getPlayerList().sendMessage(new TextComponentString("Players, please note that you may be required to join your former party owner's team in order to get your progress back!"));
    }

    private Optional<Quest> getConvertedQuest(ServerQuestFile f, int qid, NBTTagCompound mapping) {
        return Optional.ofNullable(f.getQuest(
                mapping.getCompoundTag(Integer.toString(qid))
                        .getInteger("id")
        ));
    }

    private List<Optional<Task>> getConvertedTasks(ServerQuestFile f, int qid, int tid, NBTTagCompound mapping) {
        int[] ids = mapping.getCompoundTag(Integer.toString(qid))
                .getCompoundTag("tasks")
                .getIntArray(Integer.toString(tid));

        List<Optional<Task>> tasks = new ArrayList<>();
        for (int id : ids) {
            tasks.add(Optional.ofNullable(f.getTask(id)));
        }
        return tasks;
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
}
