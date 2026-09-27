package zone.oat.random_datapack;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class RandomDatapack implements ModInitializer {
    public static final String MOD_ID = "random-datapack";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    
    private static int LOAD_INTERVAL = 1200;
    private int loadTimer = LOAD_INTERVAL;
    private boolean blockLoading = false;
    private boolean downloadsPaused = false;
    
    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("random-datapack")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.literal("timer")
                        .then(Commands.argument("interval", TimeArgument.time())
                            .executes(this::setTimer)))
                    .then(Commands.literal("pause")
                            .then(Commands.argument("paused", BoolArgumentType.bool())
                                    .executes(this::pauseDownloads)))
            );
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            loadTimer = LOAD_INTERVAL;
        });
        ServerTickEvents.END_LEVEL_TICK.register(this::onServerTick);
    }
    
    public int setTimer(CommandContext<CommandSourceStack> context) {
        int value = IntegerArgumentType.getInteger(context, "interval");
        LOAD_INTERVAL = value;
        loadTimer = Math.min(loadTimer, value);
        context.getSource().sendSuccess(() -> Component.literal("set timer interval to %s".formatted(value)), false);
        return 1;
    }

    public int pauseDownloads(CommandContext<CommandSourceStack> context){
        boolean value = BoolArgumentType.getBool(context, "paused");
        downloadsPaused = value;
        context.getSource().sendSuccess(() -> Component.literal(value ? "timer paused" : "timer started"), false);
        return 1;
    }
    
    public void onServerTick(ServerLevel level) {
        if (blockLoading) return;
        
        loadTimer--;
        
        if (loadTimer <= 0 && !downloadsPaused) {
            loadTimer = LOAD_INTERVAL;
            loadRandomDatapack(level);
        }
    }
    
    private final ExecutorService service = Executors.newFixedThreadPool(4);
    
    public void loadRandomDatapack(ServerLevel level) {
        blockLoading = true;
        var server = level.getServer();
        
        LOGGER.info("getting a random datapack");
        ModrinthAPI.getRandomDatapack()
                .thenCompose(project -> {
                    LOGGER.info("got project: {} {}", project.name(), project.id());
                    server.getPlayerList().broadcastSystemMessage(Component.literal("Downloading %s...".formatted(project.name())), false);
                    return ModrinthAPI.getLatestDatapackFile(project.id());
                })
                .thenAccept(file -> {
                    LOGGER.info("got file: {}", file.filename());

                    Path datapackDir = server.getWorldPath(LevelResource.DATAPACK_DIR);
                    Path path = datapackDir.resolve(file.filename());

                    service.submit(() -> {
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

                        var packRepository = server.getPackRepository();
                        
                        packRepository.available = packRepository.discoverAvailable();
                        
                        List<String> selected = packRepository.getSelectedPacks().stream().map(Pack::getId).collect(Collectors.toList());
                        
                        selected.add("file/" + file.filename());

                        server.getPlayerList().broadcastSystemMessage(Component.literal("Reloading!"), false);
                        server.reloadResources(selected).exceptionally((throwable) -> {
                            LOGGER.warn("Failed to execute reload", throwable);
                            return null;
                        });

                        blockLoading = false;
                    });
                });
    }
}
