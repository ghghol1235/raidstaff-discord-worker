package raid.staff;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Pulls queued Discord title commands only while Paper is online. */
public final class MinecraftCommandBridge {
    private final JavaPlugin plugin;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "RaidStaff-MinecraftBridge"); t.setDaemon(true); return t;
    });
    private final AtomicBoolean busy = new AtomicBoolean();
    private String baseUrl, token;

    public MinecraftCommandBridge(JavaPlugin plugin) { this.plugin = plugin; }

    public void start() {
        baseUrl = plugin.getConfig().getString("bridge.url", "").trim().replaceAll("/+$", "");
        token = plugin.getConfig().getString("bridge.token", "").trim();
        if (baseUrl.isBlank() || token.isBlank()) {
            plugin.getLogger().warning("Standalone Discord worker enabled, but bridge.url/token are not configured; queued title commands will wait.");
            return;
        }
        if (!baseUrl.startsWith("https://")) {
            plugin.getLogger().severe("bridge.url must use HTTPS; Minecraft command bridge disabled.");
            return;
        }
        executor.execute(this::importLegacyState);
        executor.scheduleWithFixedDelay(this::poll, 10, 5, TimeUnit.SECONDS);
    }

    private void importLegacyState() {
        try {
            var file = plugin.getDataFolder().toPath().resolve("staff-data.json");
            if (!Files.isRegularFile(file)) return;
            byte[] state = Files.readAllBytes(file);
            if (state.length == 0 || state.length > 10 * 1024 * 1024) {
                plugin.getLogger().warning("Legacy staff data is empty or too large for migration; keeping the local file."); return;
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/minecraft/import-state"))
                    .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(state)).build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() == 200) plugin.getLogger().info("Legacy Discord staff history synchronized to the standalone worker.");
            else plugin.getLogger().warning("Legacy staff history migration was rejected (HTTP " + response.statusCode() + "); local file retained.");
        } catch (Exception ex) { plugin.getLogger().warning("Legacy staff history migration unavailable; local file retained (" + ex.getClass().getSimpleName() + ")."); }
    }

    private void poll() {
        if (!busy.compareAndSet(false, true)) return;
        boolean dispatched = false;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/minecraft/commands/next"))
                    .timeout(Duration.ofSeconds(12)).header("Authorization", "Bearer " + token).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return;
            JsonObject item = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!item.has("id") || !item.has("command")) return;
            String id = item.get("id").getAsString();
            String command = item.get("command").getAsString();
            if (!command.matches("^(칭호인증|칭호해제|칭호복구|칭호복구해제) [A-Za-z0-9_]{1,16} [가-힣A-Za-z0-9 _-]{1,32}$")) {
                acknowledge(id, false); return;
            }
            dispatched = true;
            Bukkit.getScheduler().runTask(plugin, () -> {
                boolean success = false;
                try { success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command); }
                catch (Exception ex) { plugin.getLogger().warning("Queued title command failed: " + ex.getClass().getSimpleName()); }
                final boolean result = success;
                executor.execute(() -> { try { acknowledge(id, result); } finally { busy.set(false); } });
            });
        } catch (Exception ex) {
            // Avoid logging URLs, response bodies, or credentials. Retry on the next poll.
            plugin.getLogger().fine("Minecraft command bridge unavailable: " + ex.getClass().getSimpleName());
        } finally {
            if (!dispatched) busy.set(false);
        }
    }

    private void acknowledge(String id, boolean success) {
        try {
            JsonObject body = new JsonObject(); body.addProperty("id", id); body.addProperty("success", success);
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/minecraft/commands/ack"))
                    .timeout(Duration.ofSeconds(12)).header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
            client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ex) { plugin.getLogger().fine("Minecraft command acknowledgement failed: " + ex.getClass().getSimpleName()); }
    }

    public void syncNickname(String discordId, String minecraftName, String title) {
        if (baseUrl == null || baseUrl.isBlank() || token == null || token.isBlank()
                || discordId == null || !discordId.matches("[0-9]{17,20}")
                || minecraftName == null || !minecraftName.matches("[A-Za-z0-9_]{1,16}")) return;
        executor.execute(() -> {
            try {
                JsonObject body = new JsonObject(); body.addProperty("discordId", discordId);
                body.addProperty("minecraftName", minecraftName); body.addProperty("title", title == null ? "생존자" : title);
                HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/minecraft/nickname"))
                        .timeout(Duration.ofSeconds(12)).header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
                client.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception ex) { plugin.getLogger().fine("Nickname sync to Discord worker failed: " + ex.getClass().getSimpleName()); }
        });
    }

    public void stop() { executor.shutdownNow(); }
}
