package zone.oat.random_datapack;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Collectors;

public class RandomDatapack implements ModInitializer {
    public static final String MOD_ID = "random-datapack";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    
    private static int loadInterval = 1200;
    @Nullable
    private static String categoryFilter; 
    
    private int loadTimer = loadInterval;
    private boolean blockLoading = false;
    private boolean downloadsPaused = true;
    
    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var filter = Commands.literal("filter")
                .executes(this::clearFilter);
            for (var category : ModrinthAPI.DATAPACK_CATEGORIES) {
                filter.then(Commands
                    .literal(category)
                    .executes(ctx -> this.setFilter(ctx, category))
                );
            }
            
            dispatcher.register(Commands.literal("random-datapack")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("download")
                    .executes(this::runLoadRandomDatapack))
                .then(Commands.literal("timer")
                    .then(Commands.literal("set")
                        .then(Commands.argument("interval", IntegerArgumentType.integer(0))
                            .executes(this::setTimer)))
                    .then(Commands.literal("pause")
                        .then(Commands.argument("paused", BoolArgumentType.bool())
                            .executes(this::pauseTimer))))
                .then(filter)
                .then(Commands.literal("disable-all")
                    .executes(this::disableAllDatapacks))
            );
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            loadTimer = loadInterval;
        });
        ServerTickEvents.END_LEVEL_TICK.register(this::onServerTick);
    }
    
    public int clearFilter(CommandContext<CommandSourceStack> context) {
        categoryFilter = null;
        context.getSource().sendSuccess(() -> Component.literal("Filters cleared; downloading datapacks under all categories"), false);
        return Command.SINGLE_SUCCESS;
    }
    public int setFilter(CommandContext<CommandSourceStack> context, String category) {
        categoryFilter = category;
        context.getSource().sendSuccess(() -> Component.literal("Now only downloading datapacks under the \"%s\" category".formatted(category)), false);
        return Command.SINGLE_SUCCESS;
    }
    
    public int setTimer(CommandContext<CommandSourceStack> context) {
        int value = IntegerArgumentType.getInteger(context, "interval");
        loadInterval = value;
        loadTimer = Math.min(loadTimer, value);
        context.getSource().sendSuccess(() -> Component.literal("Set the timer interval to %s".formatted(value)), false);
        return Command.SINGLE_SUCCESS;
    }

    public int pauseTimer(CommandContext<CommandSourceStack> context) {
        boolean value = BoolArgumentType.getBool(context, "paused");
        if (downloadsPaused == value) {
            context.getSource().sendFailure(Component.literal(value ? "Timer is already paused" : "Timer is already started"));
            return 0;
        }
        downloadsPaused = value;
        context.getSource().sendSuccess(() -> Component.literal(value ? "Timer has been paused" : "Timer has been started"), false);
        return Command.SINGLE_SUCCESS;
    }
    
    public int runLoadRandomDatapack(CommandContext<CommandSourceStack> context) {
        loadRandomDatapack(context.getSource().getLevel());
        return Command.SINGLE_SUCCESS;
    }
    
    public int disableAllDatapacks(CommandContext<CommandSourceStack> context) {
        if (blockLoading) {
            context.getSource().sendFailure(Component.literal("There is already a datapack currently being downloaded..."));
            return 0;
        }
        
        var server = context.getSource().getServer();
        var packRepository = server.getPackRepository();

        List<String> basePacks = packRepository.getSelectedPacks().stream()
            .map(Pack::getId)
            .filter(id -> !id.startsWith("file/"))
            .collect(Collectors.toList());

        context.getSource().sendSuccess(() -> Component.literal("Reloading!"), false);

        server.reloadResources(basePacks).exceptionally(throwable -> {
            LOGGER.warn("Failed to execute reload", throwable);
            return null;
        });

        return Command.SINGLE_SUCCESS;
    }
    
    public void onServerTick(ServerLevel level) {
        if (blockLoading) return;
        
        loadTimer--;
        
        if (loadTimer <= 0 && !downloadsPaused) {
            loadTimer = loadInterval;
            loadRandomDatapack(level);
        }
    }
    
    public void loadRandomDatapack(ServerLevel level) {
        blockLoading = true;
        var server = level.getServer();
        
        LOGGER.info("getting a random datapack");
        ModrinthAPI.getRandomDatapack(categoryFilter)
            .exceptionally(throwable -> {
                LOGGER.warn("Failed to fetch a datapack", throwable);
                return null;
            })
            .thenCompose(project -> {
                LOGGER.info("got project: {} {}", project.name(), project.id());
                var tooltip = Component.literal(project.description() + "\n")
                    .append(Component.literal("by " + project.author()).withStyle(ChatFormatting.GRAY));
                var name = Component.literal(project.name())
                    .withStyle(style -> style.withHoverEvent(
                        new HoverEvent.ShowText(tooltip)
                    ))
                    .withStyle(style -> {
                        try {
                            return style.withClickEvent(
                                new ClickEvent.OpenUrl(new URI("https://modrinth.com/datapack/" + project.slug()))
                            );
                        } catch (URISyntaxException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .withStyle(ChatFormatting.UNDERLINE);
                var message = Component.literal("Downloading ").append(name).append("...");
                server.getPlayerList().broadcastSystemMessage(message, false);
                return ModrinthAPI.getLatestDatapackFile(project.id());
            })
            .thenComposeAsync(file -> {
                LOGGER.info("got file: {}", file.filename());

                Path datapackDir = server.getWorldPath(LevelResource.DATAPACK_DIR);
                Path path = datapackDir.resolve(file.filename());

                var uri = URI.create(file.url());
                
                try (
                    ReadableByteChannel in = ModrinthAPI.download(uri.toURL());
                    FileChannel out = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING)
                ) {
                    out.transferFrom(in, 0, Long.MAX_VALUE);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

                LOGGER.info("downloaded!");

                var newPackID = "file/" + file.filename();

                var packRepository = server.getPackRepository();

                packRepository.available = packRepository.discoverAvailable();
                
                List<String> selected = packRepository.getSelectedPacks().stream().map(Pack::getId).collect(Collectors.toList());
                selected.add(newPackID);
                
                long fileDatapacks = selected.stream().filter(id -> id.startsWith("file/")).count();

                server.getPlayerList().broadcastSystemMessage(
                    Component.literal("Reloading! ")
                        .append(
                            Component.literal("(%s datapacks)".formatted(fileDatapacks))
                                .withStyle(ChatFormatting.GRAY)
                        ),
                    false
                );

                return server.reloadResources(selected).exceptionally(throwable -> {
                    LOGGER.warn("Failed to execute reload", throwable);
                    server.getPlayerList().broadcastSystemMessage(Component.literal("Reload failed; reverting"), false);
                    selected.remove(newPackID);
                    server.reloadResources(selected);
                    return null;
                });
            })
            .thenAccept(res -> {
                blockLoading = false;
            });
    }
}
