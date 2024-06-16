package org.konsheng.mingkonggriefprevention;

import okhttp3.*;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.UUID;

public class MessageRateLimiter extends JavaPlugin implements Listener {
    private static final String API_KEY = "######";
    private static final String SECRET_KEY = "######";
    private String accessToken;
    private final OkHttpClient httpClient = new OkHttpClient();
    private HashMap<UUID, Long> lastMessageTime = new HashMap<>();
    private HashMap<UUID, Integer> violationCount = new HashMap<>();
    private HashMap<UUID, Long> lastViolationTime = new HashMap<>();
    private HashMap<UUID, Boolean> isMuted = new HashMap<>();

    @Override
    public void onEnable() {
        try {
            accessToken = getAccessToken();
        } catch (IOException e) {
            getLogger().severe("Failed to get access token: " + e.getMessage());
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("===============================================================");
        getLogger().info(" ");
        getLogger().info("                               警告");
        getLogger().info(" ");
        getLogger().info("MessageRateLimiter 插件已启动, 此插件包含敏感密钥信息, 请勿泄露");
        getLogger().info(" ");
        getLogger().info(" ");
        getLogger().info("===============================================================");
    }

    private String getAccessToken() throws IOException {
        MediaType mediaType = MediaType.parse("application/x-www-form-urlencoded");
        RequestBody body = RequestBody.create(mediaType, "grant_type=client_credentials&client_id=" + API_KEY + "&client_secret=" + SECRET_KEY);
        Request request = new Request.Builder()
                .url("https://aip.baidubce.com/oauth/2.0/token")
                .post(body)
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            return new JSONObject(response.body().string()).getString("access_token");
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();

        if (lastMessageTime.containsKey(playerId) && (currentTime - lastMessageTime.get(playerId) < 1000)) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(this, () -> {
                Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(),
                        "cmi toast " + player.getName() + " -t:task -icon:barrier &c&l警告! &f您的消息速度过快请稍后再试");
                player.sendMessage(ChatColor.YELLOW + "" + ChatColor.BOLD + "! " + ChatColor.WHITE + "您的消息发送速度过快, 请稍后再试");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            });
            return;
        }
        lastMessageTime.put(playerId, currentTime);

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            if (!isMessageValid(player, event.getMessage())) {
                incrementViolation(player);
                if (checkAndApplySanctions(player)) {
                    Bukkit.getScheduler().runTask(this, () -> {
                        // 违规处罚
                        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 1.0F, 1.0F);
                        player.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "! " + ChatColor.WHITE + "您因不适当的发言被系统禁言 10 分钟");
                        Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), "bossbarmsg " + player.getName() + " -sec:600 -t:600 -c:red -s:1 &c您因不适当的发言被系统禁言 10 分钟");
                        Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), "mute " + player.getName() + " 10m &c频繁多次不适当的发言");
                        Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), "warn " + player.getName() + " &c频繁多次不适当的发言");
                    });
                } else {
                    Bukkit.getScheduler().runTask(this, () -> {
                        // 违规警告
                        Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), "cmi toast " + player.getName() + " -t:task -icon:barrier &c&l警告! &f您的消息可能包含不适当内容");
                        player.sendMessage(ChatColor.YELLOW + "" + ChatColor.BOLD + "! " + ChatColor.WHITE + "您的消息可能包含不适当内容, 频繁违规发言可能会导致账号遭到封禁及禁言, 严重违规可能会遭到永久封禁以及IP封禁, 请您注意");
                        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                    });
                }
            }
        });
    }

    private boolean isMessageValid(Player player, String message) {
        try {
            String encodedMessage = URLEncoder.encode(message, StandardCharsets.UTF_8.toString());
            MediaType mediaType = MediaType.parse("application/x-www-form-urlencoded");
            RequestBody body = RequestBody.create(mediaType, "text=" + encodedMessage + "&userId=" + player.getName() + "&userIp=" + player.getAddress().getAddress().getHostAddress());
            Request request = new Request.Builder()
                    .url("https://aip.baidubce.com/rest/2.0/solution/v1/text_censor/v2/user_defined?access_token=" + accessToken)
                    .post(body)
                    .addHeader("Content-Type", "application/x-www-form-urlencoded")
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                String jsonResponse = response.body().string();
                JSONObject jsonObject = new JSONObject(jsonResponse);
                return jsonObject.getString("conclusion").equals("合规");
            }
        } catch (Exception e) {
            getLogger().warning("内容审核API调用失败: " + e.getMessage());
            return true;  // 如果API调用失败，默认允许消息发送
        }
    }

    private void incrementViolation(Player player) {
        UUID playerId = player.getUniqueId();
        violationCount.put(playerId, violationCount.getOrDefault(playerId, 0) + 1);
        lastViolationTime.put(playerId, System.currentTimeMillis());
    }

    private boolean checkAndApplySanctions(Player player) {
        UUID playerId = player.getUniqueId();
        int count = violationCount.getOrDefault(playerId, 0);
        boolean currentlyMuted = isMuted.getOrDefault(playerId, false);

        if (count >= 3 && (System.currentTimeMillis() - lastViolationTime.get(playerId) <= 120000) && !currentlyMuted) {
            violationCount.put(playerId, 0); // Reset count after applying sanctions
            isMuted.put(playerId, true); // Mark the player as muted

            // Schedule a task to unmute the player after 10 minutes
            Bukkit.getScheduler().runTaskLater(this, () -> isMuted.put(playerId, false), 12000L); // 12000 ticks = 10 minutes

            return true;
        }
        return false;
    }
}
