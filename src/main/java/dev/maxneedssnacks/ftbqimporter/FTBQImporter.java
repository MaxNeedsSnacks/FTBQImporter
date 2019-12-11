package dev.maxneedssnacks.ftbqimporter;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(
        modid = FTBQImporter.MOD_ID,
        name = FTBQImporter.MOD_NAME,
        version = FTBQImporter.VERSION,
        acceptableRemoteVersions = "*",
        dependencies = "required-after:ftblib@[0.0.0ftblib,);required-after:ftbquests",
        acceptedMinecraftVersions = "[1.12.2]"
)
public class FTBQImporter {
    public static final String MOD_ID = "ftbqimporter";
    public static final String MOD_NAME = "FTB Quests Importer";
    public static final String VERSION = "1.0.0";
    public static final Logger LOGGER = LogManager.getLogger("FTB Quests Importer");

    @Mod.EventHandler
    public void onServerStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandImport());
    }
}

