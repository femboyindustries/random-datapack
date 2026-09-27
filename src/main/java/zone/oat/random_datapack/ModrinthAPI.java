package zone.oat.random_datapack;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

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
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

public class ModrinthAPI {
    public static final String API_BASE = "https://api.modrinth.com/v2";
    public static final String USER_AGENT = "random_datapack";
    public static final String GAME_VERSION = "26.1.2";
    
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
                .header("User-Agent", USER_AGENT)
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenApply(JsonParser::parseString);
    }
    
    public record DatapackResult(String id, String name) {}
    
    public static CompletableFuture<DatapackResult> getRandomDatapack() {
        String facets = "[[\"versions:" + GAME_VERSION + "\"],[\"project_type:datapack\"]]";
        
        // first fetch how many there are
        return modrinthGet("/search", Map.ofEntries(
                Map.entry("facets", facets),
                Map.entry("limit", "1"),
                Map.entry("index", "newest")
        ))
                .thenCompose(resultsInitial -> {
                    var hits = resultsInitial.getAsJsonObject().get("total_hits").getAsInt();

                    // then fetch a random one
                    var index = rng.nextInt(hits);

                    return modrinthGet("/search", Map.ofEntries(
                            Map.entry("facets", facets),
                            Map.entry("limit", "1"),
                            Map.entry("index", "newest"),
                            Map.entry("offset", String.valueOf(index))
                    ));
                })
                .thenApply(results -> {
                    var project = results.getAsJsonObject().get("hits").getAsJsonArray().get(0).getAsJsonObject();

                    return new DatapackResult(project.get("project_id").getAsString(), project.get("title").getAsString());
                });
    }
    
    public record DatapackFileResult(String url, String filename) {}
    
    public static CompletableFuture<DatapackFileResult> getLatestDatapackFile(String projectID) {
        return modrinthGet("/project/" + projectID + "/version", Map.ofEntries(
                Map.entry("loaders", "[\"datapack\"]"),
                Map.entry("game_versions", "[\"" + GAME_VERSION + "\"]"),
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
        conn.setRequestProperty("User-Agent", USER_AGENT);
        return Channels.newChannel(conn.getInputStream());
    }
}
