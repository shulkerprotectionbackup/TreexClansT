package me.jetby.treexclans.addons;

import lombok.Getter;
import me.jetby.treexclans.TreexClans;
import me.jetby.treexclans.addons.annotations.Dependency;
import me.jetby.treexclans.addons.annotations.TreexAddonInfo;
import me.jetby.treexclans.addons.util.VersionUtil;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static me.jetby.treexclans.TreexClans.LOGGER;

/**
 * Менеджер загрузки аддонов TreexClans.
 * <p>
 * Работает с JAR-файлами, содержащими классы, аннотированные {@link TreexAddonInfo}.
 * Поддерживает автоматическую инициализацию, включение и выгрузку аддонов.
 * </p>
 */
public final class AddonManager {

    private final TreexClans plugin;
    private final File addonsFolder;
    private final Logger logger;

    @Getter
    private final Map<String, TreexAddon> loadedAddons = new LinkedHashMap<>();
    private final Map<String, URLClassLoader> classLoaders = new HashMap<>();

    public AddonManager(@NotNull TreexClans plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.addonsFolder = new File(plugin.getDataFolder(), "addons");

        if (!addonsFolder.exists()) {
            addonsFolder.mkdirs();
            LOGGER.success("Addons folder created at: " + addonsFolder.getAbsolutePath());
        }
    }

    /**
     * Загружает все JAR-аддоны из папки {@code /addons}.
     */
    public void loadAddons() {
        File[] jars = addonsFolder.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            LOGGER.info("No addons found in " + addonsFolder.getAbsolutePath());
            return;
        }

        LOGGER.success("------------------------");
        LOGGER.info("Loading " + jars.length + " addon(s)...");
        LOGGER.success("------------------------");

        for (File jarFile : jars) {
            try {
                loadFromJar(jarFile);
            } catch (Exception e) {
                LOGGER.error("Failed to load addon: " + jarFile.getName());
                e.printStackTrace();
            }
        }

        // Включаем по зависимостям
        enableAll();

        LOGGER.success("------------------------");
        LOGGER.success(loadedAddons.size() + " addon(s) loaded successfully!");
        LOGGER.success("------------------------");
    }

    /**
     * Загружает один JAR-файл и ищет в нём аннотированный класс {@link TreexAddonInfo}.
     */
    private void loadFromJar(File jarFile) throws Exception {
        URLClassLoader loader = new URLClassLoader(
                new URL[]{jarFile.toURI().toURL()},
                plugin.getClass().getClassLoader()
        );

        String jarName = jarFile.getName();
        classLoaders.put(jarName, loader);

        List<Class<?>> classes;
        try (JarFile jar = new JarFile(jarFile)) {
            classes = jar.stream()
                    .filter(e -> e.getName().endsWith(".class"))
                    .map(e -> e.getName().replace('/', '.').replace(".class", ""))
                    .map(name -> {
                        try {
                            return loader.loadClass(name);
                        } catch (Throwable ignored) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }

        for (Class<?> clazz : classes) {
            TreexAddonInfo meta = clazz.getAnnotation(TreexAddonInfo.class);
            if (meta == null) continue;

            if (!TreexAddon.class.isAssignableFrom(clazz)) {
                LOGGER.warn("Class " + clazz.getName() + " has @TreexAddonInfo but does not extend TreexAddon!");
                continue;
            }

            TreexAddon addon = (TreexAddon) clazz.getDeclaredConstructor().newInstance();
            addon.initialize(new AddonContext(plugin, logger, addonsFolder, loadedAddons::get));

            loadedAddons.put(meta.id(), addon);
            LOGGER.success("Registered addon: " + meta.id() + " v" + meta.version());
        }
    }

    /**
     * Включает все зарегистрированные аддоны с учётом зависимостей.
     */
    private void enableAll() {
        List<TreexAddon> ordered = sortByDependencies();

        for (TreexAddon addon : ordered) {
            TreexAddonInfo info = addon.getInfo();

            if (!checkDependencies(info.depends())) {
                LOGGER.error("Skipping " + info.id() + " — missing dependencies.");
                continue;
            }

            addon.onEnable();
            LOGGER.success("Enabled addon: " + info.id() + " v" + info.version());
        }
    }

    /**
     * Выгружает все аддоны и закрывает загрузчики.
     */
    public void unloadAll() {
        List<TreexAddon> reversed = new ArrayList<>(loadedAddons.values());
        Collections.reverse(reversed);

        for (TreexAddon addon : reversed) {
            try {
                addon.onDisable();
                LOGGER.info("Addon disabled: " + addon.getInfo().id());
            } catch (Exception e) {
                LOGGER.error("Error disabling addon: " + addon.getInfo().id());
                e.printStackTrace();
            }
        }

        loadedAddons.clear();
        for (URLClassLoader loader : classLoaders.values()) {
            try {
                loader.close();
            } catch (IOException ignored) {}
        }
        classLoaders.clear();
    }

    private boolean checkDependencies(Dependency[] deps) {
        for (Dependency dep : deps) {
            TreexAddon found = loadedAddons.get(dep.id());
            if (found == null) return false;
            if (!VersionUtil.isSatisfied(found.getVersion(), dep.version())) return false;
        }
        return true;
    }

    private List<TreexAddon> sortByDependencies() {
        Map<String, Set<String>> graph = new HashMap<>();
        for (TreexAddon a : loadedAddons.values()) graph.put(a.getInfo().id(), new LinkedHashSet<>());

        for (TreexAddon a : loadedAddons.values()) {
            TreexAddonInfo info = a.getInfo();

            for (Dependency d : info.depends())
                if (graph.containsKey(d.id())) graph.get(info.id()).add(d.id());

            for (Dependency d : info.softDepends())
                if (graph.containsKey(d.id())) graph.get(info.id()).add(d.id());

            for (String after : info.loadAfter())
                if (graph.containsKey(after)) graph.get(info.id()).add(after);

            for (String before : info.loadBefore())
                if (graph.containsKey(before)) graph.get(before).add(info.id());
        }

        return topologicalSort(graph);
    }

    private List<TreexAddon> topologicalSort(Map<String, Set<String>> graph) {
        Map<String, Integer> indeg = new HashMap<>();
        for (String k : graph.keySet()) indeg.put(k, 0);
        for (Set<String> v : graph.values())
            for (String d : v) indeg.put(d, indeg.get(d) + 1);

        Deque<String> q = new ArrayDeque<>();
        indeg.forEach((k, v) -> { if (v == 0) q.add(k); });

        List<TreexAddon> result = new ArrayList<>();
        while (!q.isEmpty()) {
            String id = q.removeFirst();
            TreexAddon addon = loadedAddons.get(id);
            if (addon != null) result.add(addon);

            for (String to : graph.get(id)) {
                indeg.put(to, indeg.get(to) - 1);
                if (indeg.get(to) == 0) q.addLast(to);
            }
        }
        return result;
    }
}
