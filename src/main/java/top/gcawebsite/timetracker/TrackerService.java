package top.gcawebsite.timetracker;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserWhiteListEntry;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.storage.LevelResource;
import top.gcawebsite.Timetracker;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 上下线追踪 + 生成白名单玩家报告。
 */
public final class TrackerService {
	/** 原版统计里 play_time 的单位是 tick。 */
	private static final long MILLIS_PER_TICK = 50L;
	private static final long MILLIS_PER_HOUR = 3_600_000L;
	private static final String UNKNOWN = "未知";
	private static final String REPORT_FILE = "timetracker.txt";
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final DateTimeFormatter FILE_STAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");

	/** 报告里的一行。 */
	private record Row(String name, Long whitelistedAt, Long lastOnlineAt, long playtimeMillis) {
	}

	/** 一次登录会话。JOIN 和 DISCONNECT 拿到的是同一个 handler 对象，用它当键最稳。 */
	private record Session(UUID playerId, long startedAt) {
	}

	private static final Map<ServerGamePacketListenerImpl, Session> sessions = new HashMap<>();

	private TrackerService() {
	}

	public static void onPlayReady(ServerGamePacketListenerImpl handler, PacketSender sender, MinecraftServer server) {
		ServerPlayer player = handler.player;

		if (player == null) {
			return;
		}

		NameAndId who = player.nameAndId();
		TrackerStorage storage = TrackerStorage.get();

		if (FakePlayerDetector.isFakePlayer(player)) {
			// 记一笔就给报告排除掉：假人常常 24 小时挂着，混进来会盖过真人的数据
			storage.markFake(who.id(), who.name());
			storage.save();
			return;
		}

		long now = System.currentTimeMillis();
		sessions.put(handler, new Session(who.id(), now));
		storage.markOnline(who.id(), who.name(), now);
		storage.save();
	}

	public static void onPlayDisconnect(ServerGamePacketListenerImpl handler, MinecraftServer server) {
		Session session = sessions.remove(handler);

		if (session == null) {
			// 假人（JOIN 时没登记），或者压根没走完登录流程的连接
			return;
		}

		long now = System.currentTimeMillis();
		TrackerStorage storage = TrackerStorage.get();
		storage.markOffline(session.playerId(), Math.max(0L, now - session.startedAt()), now);
		storage.save();
	}

	/** 生成报告，返回写出的文件路径。 */
	public static Path generateReport(MinecraftServer server) throws IOException {
		TrackerStorage storage = TrackerStorage.get();
		List<Row> rows = collectRows(server, storage);
		rows.sort(Comparator.comparingLong(Row::playtimeMillis).reversed().thenComparing(Row::name));

		Path target = server.getServerDirectory().resolve(REPORT_FILE);
		archivePrevious(target);
		Files.writeString(target, buildReport(rows), StandardCharsets.UTF_8);
		storage.save();

		return target;
	}

	private static List<Row> collectRows(MinecraftServer server, TrackerStorage storage) {
		List<Row> rows = new ArrayList<>();

		// 先拷一份：recordFor 会往 storage 里补记录，别在遍历时改到原集合
		for (UserWhiteListEntry entry : List.copyOf(server.getPlayerList().getWhiteList().getEntries())) {
			NameAndId who = entry.getUser();

			if (who == null) {
				continue;
			}

			// 没见过的人在这里补一条：加白时间留空 = 未知，不用当前时间冒充
			TrackerStorage.PlayerRecord record = storage.recordFor(who.id(), who.name());

			if (record.fake) {
				continue;
			}

			long vanilla = vanillaPlaytimeMillis(server, who.id(), who.name());
			rows.add(new Row(
					who.name(),
					record.whitelistedAt,
					record.lastOnlineAt,
					Math.max(record.trackedMillis, vanilla)
			));
		}

		return rows;
	}

	/**
	 * 原版统计里的累计在线时长（毫秒）。在线玩家读内存里的实时值，离线玩家读
	 * {@code world/stats/<uuid>.json}。读不到返回 -1。
	 */
	private static long vanillaPlaytimeMillis(MinecraftServer server, UUID id, String name) {
		ServerPlayer online = server.getPlayerList().getPlayer(id);

		if (online != null) {
			// 内存里的值是最新的；磁盘上的统计文件最多滞后一次自动存档
			return server.getPlayerList().getPlayerStats(online).getValue(Stats.CUSTOM, Stats.PLAY_TIME)
					* MILLIS_PER_TICK;
		}

		Path dir = server.getWorldPath(LevelResource.PLAYER_STATS_DIR);
		long ticks = readPlayTime(dir.resolve(id + ".json"));

		if (ticks < 0) {
			// 很老的存档是按玩家名存的
			ticks = readPlayTime(dir.resolve(name + ".json"));
		}

		return ticks < 0 ? -1L : ticks * MILLIS_PER_TICK;
	}

	private static long readPlayTime(Path file) {
		if (!Files.isRegularFile(file)) {
			return -1L;
		}

		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonObject stats = asObject(JsonParser.parseReader(reader).getAsJsonObject().get("stats"));
			JsonObject custom = stats == null ? null : asObject(stats.get("minecraft:custom"));
			JsonElement playTime = custom == null ? null : custom.get("minecraft:play_time");

			return playTime == null ? -1L : playTime.getAsLong();
		} catch (Exception e) {
			Timetracker.LOGGER.warn("解析统计文件 {} 失败，该玩家的在线时长将退回本 mod 记录", file, e);
			return -1L;
		}
	}

	private static JsonObject asObject(JsonElement element) {
		return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
	}

	private static String buildReport(List<Row> rows) {
		ZoneId zone = ZoneId.systemDefault();
		StringBuilder out = new StringBuilder();

		out.append("timetracker 报告\r\n");
		out.append("生成时间：").append(TIME_FORMAT.format(LocalDateTime.now(zone)))
				.append(" (").append(zone.getRules().getOffset(Instant.now())).append(")\r\n");
		out.append("白名单玩家：").append(rows.size()).append(" 人\r\n\r\n");

		out.append(pad("玩家ID", 18)).append(pad("加白时间", 24))
				.append(pad("最后上服时间", 24)).append("总在线时长\r\n");
		out.append("-".repeat(80)).append("\r\n");

		for (Row row : rows) {
			out.append(pad(row.name(), 18))
					.append(pad(formatTime(row.whitelistedAt(), zone), 24))
					.append(pad(formatTime(row.lastOnlineAt(), zone), 24))
					.append(formatHours(row.playtimeMillis())).append("\r\n");
		}

		out.append("\r\n说明：\r\n");
		out.append("- 加白时间「未知」= 原版 whitelist.json 不记录加白时间，这些玩家在 mod 安装前就已加白，无法追溯。\r\n");
		out.append("- 总在线时长取原版 play_time 统计与本 mod 累计值中的较大值；两边都读不到时为 0.0 小时。\r\n");
		out.append("- 已排除 Carpet 假人（机器人）。\r\n");

		return out.toString();
	}

	private static String formatTime(Long millis, ZoneId zone) {
		return millis == null ? UNKNOWN : TIME_FORMAT.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone));
	}

	private static String formatHours(long millis) {
		return String.format(Locale.ROOT, "%.1f 小时", millis / (double) MILLIS_PER_HOUR);
	}

	/** 把上一份报告改名归档，避免直接覆盖掉。 */
	private static void archivePrevious(Path target) {
		if (!Files.isRegularFile(target)) {
			return;
		}

		try {
			Instant modified = Files.getLastModifiedTime(target).toInstant();
			String stamp = FILE_STAMP_FORMAT.format(LocalDateTime.ofInstant(modified, ZoneId.systemDefault()));
			Path archived = target.resolveSibling("timetracker-" + stamp + ".txt");
			Files.move(target, archived, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			// 归档失败就不归档了，反正下面会覆盖写
			Timetracker.LOGGER.warn("归档旧报告 {} 失败，将直接覆盖", target, e);
		}
	}

	/** 按显示宽度补空格：CJK 字符占两列，这样记事本里各列也能对齐。 */
	private static String pad(String text, int width) {
		return text + " ".repeat(Math.max(1, width - displayWidth(text)));
	}

	private static int displayWidth(String text) {
		int width = 0;

		for (int i = 0; i < text.length(); ) {
			int codePoint = text.codePointAt(i);
			width += isWide(codePoint) ? 2 : 1;
			i += Character.charCount(codePoint);
		}

		return width;
	}

	private static boolean isWide(int codePoint) {
		return (codePoint >= 0x1100 && codePoint <= 0x115F)
				|| (codePoint >= 0x2E80 && codePoint <= 0xA4CF)
				|| (codePoint >= 0xAC00 && codePoint <= 0xD7A3)
				|| (codePoint >= 0xF900 && codePoint <= 0xFAFF)
				|| (codePoint >= 0xFE30 && codePoint <= 0xFE6F)
				|| (codePoint >= 0xFF00 && codePoint <= 0xFF60)
				|| (codePoint >= 0xFFE0 && codePoint <= 0xFFE6)
				|| (codePoint >= 0x20000 && codePoint <= 0x3FFFD);
	}
}
