package zone.oat.random_datapack;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

public class ModrinthAPI {
    public static final String API_BASE = "https://api.modrinth.com/v2";
    // curl 'https://api.modrinth.com/v2/tag/category' | jq 'map(select(.project_type == "mod")) | map(.name)'
    public static final List<String> DATAPACK_CATEGORIES = List.of(
        "adventure", "cursed", "decoration", "economy", "equipment", "food", "game-mechanics", "library",
        "magic", "management", "minigame", "mobs", "optimization", "social", "storage", "technology", "transportation",
        "utility", "worldgen"
    );
    
    private static String getUserAgent() {
        var ver = FabricLoader.getInstance()
                .getModContainer(RandomDatapack.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("Unknown");
        
        return "random_datapack/%s (https://github.com/femboyindustries/random-datapack)".formatted(ver);
    }
    
    private static String getGameVersion() {
        return SharedConstants.getCurrentVersion().name();
    }

    public static final Logger LOGGER = LoggerFactory.getLogger("ModrinthAPI");
    
    static HttpClient client = HttpClient.newHttpClient();
    static Random rng = new Random();
    
    private static String queryString(Map<String, String> params) {
        var s = new StringBuilder();
        boolean first = true;
        for (var entry : params.entrySet()) {
            s.append(first ? "?" : "&");
            first = false;
            s.append(entry.getKey());
            s.append("=");
            s.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return s.toString();
    }
    
    private static CompletableFuture<JsonElement> modrinthGet(String endpoint, Map<String, String> params) {
        var uri = URI.create(API_BASE + endpoint + queryString(params));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("User-Agent", getUserAgent())
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenApply(JsonParser::parseString);
    }
    
    public record DatapackResult(String id, String name, String description, String author, String slug) {}
    
    public static CompletableFuture<DatapackResult> getRandomDatapack(@Nullable String categoryFilter) {
        var facets = new ArrayList<String>();
        facets.add("[\"versions:" + getGameVersion() + "\"]");
        facets.add("[\"project_type:datapack\"]");
        
        if (categoryFilter != null) {
            facets.add("[\"categories:" + categoryFilter + "\"]");
        }
        
        var facetString = "[" + String.join(",", facets) + "]";
        
        LOGGER.debug("facetString: {}", facetString);
        
        // first fetch how many there are
        return modrinthGet("/search", Map.ofEntries(
                Map.entry("facets", facetString),
                Map.entry("limit", "1"),
                Map.entry("index", "newest")
        ))
                .thenCompose(resultsInitial -> {
                    var hits = resultsInitial.getAsJsonObject().get("total_hits").getAsInt();

                    // then fetch a random one
                    var index = rng.nextInt(hits);

                    LOGGER.debug("hits: {} | index: {} 👍", hits, index);

                    return modrinthGet("/search", Map.ofEntries(
                            Map.entry("facets", facetString),
                            Map.entry("limit", "1"),
                            Map.entry("index", "newest"),
                            Map.entry("offset", String.valueOf(index))
                    ));
                })
                .thenApply(results -> {
                    var project = results.getAsJsonObject().get("hits").getAsJsonArray().get(0).getAsJsonObject();

                    return new DatapackResult(
                            project.get("project_id").getAsString(),
                            project.get("title").getAsString(),
                            project.get("description").getAsString(),
                            project.get("author").getAsString(),
                            project.get("slug").getAsString()
                    );
                });
    }
    
    public record DatapackFileResult(String url, String filename) {}
    
    public static CompletableFuture<DatapackFileResult> getLatestDatapackFile(String projectID) {
        return modrinthGet("/project/" + projectID + "/version", Map.ofEntries(
                Map.entry("loaders", "[\"datapack\"]"),
                Map.entry("game_versions", "[\"" + getGameVersion() + "\"]"),
                Map.entry("include_changelog", "false")
        ))
                .thenApply(results -> {
                    var version = results.getAsJsonArray().get(0).getAsJsonObject();
                    var files = version.get("files").getAsJsonArray();
                    var mainFile = files.get(0).getAsJsonObject();

                    return new DatapackFileResult(mainFile.get("url").getAsString(), mainFile.get("filename").getAsString()); 
                });
    }

    public static ReadableByteChannel download(URL url) throws IOException {
        URLConnection conn = url.openConnection();
        conn.setRequestProperty("User-Agent", getUserAgent());
        return Channels.newChannel(conn.getInputStream());
    }
}
