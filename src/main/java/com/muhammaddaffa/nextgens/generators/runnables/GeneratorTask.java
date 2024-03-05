package com.muhammaddaffa.nextgens.generators.runnables;

import com.muhammaddaffa.mdlib.utils.Config;
import com.muhammaddaffa.mdlib.utils.Executor;
import com.muhammaddaffa.mdlib.utils.LocationSerializer;
import com.muhammaddaffa.nextgens.NextGens;
import com.muhammaddaffa.nextgens.api.events.generators.GeneratorGenerateItemEvent;
import com.muhammaddaffa.nextgens.autosell.Autosell;
import com.muhammaddaffa.nextgens.events.Event;
import com.muhammaddaffa.nextgens.events.managers.EventManager;
import com.muhammaddaffa.nextgens.generators.ActiveGenerator;
import com.muhammaddaffa.nextgens.generators.CorruptedHologram;
import com.muhammaddaffa.nextgens.generators.Drop;
import com.muhammaddaffa.nextgens.generators.Generator;
import com.muhammaddaffa.nextgens.generators.managers.GeneratorManager;
import com.muhammaddaffa.nextgens.users.User;
import com.muhammaddaffa.nextgens.users.managers.UserManager;
import com.muhammaddaffa.nextgens.utils.Settings;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;

public class GeneratorTask extends BukkitRunnable {


    private static GeneratorTask runnable;

    public static void start(GeneratorManager generatorManager, EventManager eventManager, UserManager userManager) {
        if (runnable != null) {
            runnable.cancel();
            runnable = null;
        }
        // set back the runnable
        runnable = new GeneratorTask(generatorManager, eventManager, userManager);
        // run the task
        runnable.runTaskTimerAsynchronously(NextGens.getInstance(), 20L, 5L);
    }

    public static void flush() {
        if (runnable == null || Bukkit.getPluginManager().getPlugin("HolographicDisplays") != null) {
            return;
        }
        runnable.clearHologram();
    }

    public static void destroy(ActiveGenerator active) {
        if (runnable == null) {
            return;
        }
        runnable.forceRemoveHologram(active);
    }

    private final Map<String, CorruptedHologram> hologramMap = new HashMap<>();

    private final GeneratorManager generatorManager;
    private final EventManager eventManager;
    private final UserManager userManager;

    public GeneratorTask(GeneratorManager generatorManager, EventManager eventManager, UserManager userManager) {
        this.generatorManager = generatorManager;
        this.eventManager = eventManager;
        this.userManager = userManager;
    }

    @Override
    public void run() {
        // loop active generators
        for (ActiveGenerator active : this.generatorManager.getActiveGenerator()) {
            // get variables
            Generator generator = active.getGenerator();
            Player player = Bukkit.getPlayer(active.getOwner());
            Event event = this.eventManager.getActiveEvent();
            User user = this.userManager.getUser(active.getOwner());
            // if generator is invalid or chunk is not loaded, skip it
            if (generator == null || !active.isChunkLoaded()) {
                continue;
            }
            if (Settings.BLACKLISTED_WORLDS.contains(active.getLocation().getWorld().getName())) {
                continue;
            }
            // check for online-only option
            boolean onlineOnly;

            // check for generator online only
            if (generator.onlineOnly() == null) {
                onlineOnly = Settings.ONLINE_ONLY;
            } else {
                onlineOnly = generator.onlineOnly();
            }

            if (onlineOnly) {
                if (player == null || !player.isOnline()) {
                    continue;
                }
            }
            String serialized = LocationSerializer.serialize(active.getLocation());
            // check for corruption option
            if (Settings.CORRUPTION_ENABLED && active.isCorrupted()) {
                // check if hologram is enabled
                if (Settings.CORRUPTION_HOLOGRAM && !this.hologramMap.containsKey(serialized)) {
                    CorruptedHologram hologram = new CorruptedHologram(active);
                    // show the hologram
                    hologram.spawn();
                    // store it on the cache
                    this.hologramMap.put(serialized, hologram);
                }
                continue;
            }
            // if the generator not corrupt but exists on the hologram map
            CorruptedHologram hologram = this.hologramMap.remove(serialized);
            if (!active.isCorrupted() && hologram != null) {
                hologram.destroy();
            }
            Generator chosenGenerator = generator;
            double interval = generator.interval();
            int dropAmount;
            /**
             * Event-related code
             */
            if (event != null) {
                if (event.getType() == Event.Type.GENERATOR_SPEED &&
                        event.getSpeedMultiplier() != null &&
                        !event.getBlacklistedGenerators().contains(generator.id())) {
                    // get the speed boost
                    Double boost = event.getSpeedMultiplier();
                    double discount = (generator.interval() * boost) / 100;
                    // deduct the interval
                    interval = interval - discount;
                }
                if (event.getType() == Event.Type.GENERATOR_UPGRADE &&
                        event.getTierUpgrade() != null &&
                        !event.getBlacklistedGenerators().contains(generator.id())) {
                    // get the amount of tier upgrade
                    Integer amount = event.getTierUpgrade();
                    // make a for-each and upgrade the generator
                    for (int i = 0; i < amount; i++) {
                        if (chosenGenerator.nextTier() == null) {
                            break;
                        }
                        Generator upgraded = this.generatorManager.getGenerator(chosenGenerator.nextTier());
                        if (upgraded != null) {
                            chosenGenerator = upgraded;
                        }
                    }
                }
                if (event.getType() == Event.Type.MIXED_UP &&
                        !event.getBlacklistedGenerators().contains(generator.id())) {
                    // choose a random generator
                    chosenGenerator = this.generatorManager.getRandomGenerator();
                }
                if (event.getType() == Event.Type.DROP_MULTIPLIER &&
                        event.getDropMultiplier() != null &&
                        !event.getBlacklistedGenerators().contains(generator.id())) {
                    // get the drop multiplier and set the drop amount
                    dropAmount = Math.max(1, event.getDropMultiplier());
                } else {
                    dropAmount = 1;
                }
            } else {
                dropAmount = 1;
            }
            // add timer
            active.addTimer(0.25);
            // check if the generator should drop
            if (active.getTimer() >= interval) {
                // execute drop mechanics
                Block block = active.getLocation().getBlock();
                // execute it in sync task
                Generator finalChosenGenerator = chosenGenerator;
                Executor.sync(() -> {
                    // set the block to the desired type
                    if (Settings.FORCE_UPDATE_BLOCKS) {
                        block.setType(generator.item().getType());
                    }
                    // create the event
                    GeneratorGenerateItemEvent generatorEvent = new GeneratorGenerateItemEvent(finalChosenGenerator, active, dropAmount);
                    Bukkit.getPluginManager().callEvent(generatorEvent);
                    if (generatorEvent.isCancelled()) {
                        active.setTimer(0);
                        return;
                    }
                    // get the drop amount
                    for (int i = 0; i < generatorEvent.getDropAmount(); i++) {
                        Drop drop = generatorEvent.getGenerator().getRandomDrop();
                        if (drop == null)
                            continue;

                        // check if player has autosell
                        if (player != null && Autosell.hasAutosellGensPermission(player) &&
                                user.isToggleGensAutoSell()) {
                            // check if item is sellable
                            if (this.userManager.sell(player, drop.getItem())) {
                                // spawn the random drop without dropping the item
                                drop.spawn(block, Bukkit.getOfflinePlayer(active.getOwnerName()), false);
                                continue;
                            }
                        }
                        // spawn the random drop
                        drop.spawn(block, Bukkit.getOfflinePlayer(active.getOwner()), true);
                    }
                    // set the timer to 0
                    active.setTimer(0);
                });

            }
        }
    }

    public void forceRemoveHologram(ActiveGenerator active) {
        CorruptedHologram removed = this.hologramMap.remove(LocationSerializer.serialize(active.getLocation()));
        // if hologram is present, remove it
        if (removed != null) {
            removed.destroy();
        }
    }

    public void clearHologram() {
        for (CorruptedHologram hologram : this.hologramMap.values()) {
            hologram.destroy();
        }
        // clear the hologram map
        this.hologramMap.clear();
    }

}
