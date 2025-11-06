package me.jetby.treexclans.addons;

import lombok.Getter;
import me.jetby.treexclans.TreexClans;
import me.jetby.treexclans.addons.annotations.TreexAddonInfo;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.logging.Logger;

@Getter
public abstract class TreexAddon {

    private TreexAddonInfo info;
    protected TreexClans plugin;
    protected File dataFolder;
    protected String name;
    protected String author;
    protected String version;
    protected String description;
    private Logger logger;

    public final void initialize(@NotNull AddonContext context) {
        this.info = getClass().getAnnotation(TreexAddonInfo.class);
        this.plugin = context.plugin();
        if (info == null)
            throw new IllegalStateException("Класс " + getClass().getName() + " не имеет аннотации @TreexAddonInfo");

        this.logger = context.logger();
        this.dataFolder = new File(context.plugin().getDataFolder(), "addons/" + info.id());
        if (!dataFolder.exists()) dataFolder.mkdirs();
    }

    public abstract void onEnable();


    public abstract void onDisable();


    public TreexClans getClansPlugin() {
        return plugin;
    }

    public FileConfiguration getConfig() {
        File configFile = new File(dataFolder, "config.yml");

        if (!configFile.exists()) {
            try {
                configFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to create config file: config.yml");
                e.printStackTrace();
            }
        }

        return YamlConfiguration.loadConfiguration(configFile);
    }

    public void saveConfig() {
        File configFile = new File(dataFolder, "config.yml");

        try {
            getConfig().save(configFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save config file: config.yml");
            e.printStackTrace();
        }
    }
}