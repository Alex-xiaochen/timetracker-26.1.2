package top.gcawebsite.timetracker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import top.gcawebsite.Timetracker;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * timetracker 自己的数据文件：{@code config/timetracker.json}。
 *
 * <p>原版 whitelist.json 只存 UUID 和名字，加白时间、最后上服时间都得我们自己记；
 * 在线时长虽然能读原版统计，但统计文件被删时也需要这里的累计值兜底。
 *
 * <p>所有修改都走下面的 mark* 方法，它们负责置脏；只在服务器主线程上访问，不做加锁。
 */
public final class TrackerStorage {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final TrackerStorage INSTANCE = new TrackerStorage();

	private static class Root {
		Map<String, PlayerRecord> players = new LinkedHashMap<>();
	}

	/** 单个玩家的记录。字段大多可为空——老服务器上很多信息本来就补不出来。 */
	public static class PlayerRecord {
		/** 玩家名，每次见到时更新。 */
		public String name = "";
		/** 加白时间（epoch 毫秒），null = 未知（mod 安装前就已加白）。 */
		public Long whitelistedAt;
		/** 最后一次上下线的时刻（epoch 毫秒），null = 未知。 */
		public Long lastOnlineAt;
		/** 本 mod 自己累计的在线时长（毫秒）。 */
		public long trackedMillis;
		/** 是否被识别为假人，识别到的不会进报告。 */
		public boolean fake;
	}

	private final Path file = FabricLoader.getInstance().getConfigDir().resolve("timetracker.json");
	private final Map<String, PlayerRecord> players = new LinkedHashMap<>();
	private boolean loaded;
	private boolean dirty;

	private TrackerStorage() {
	}

	public static TrackerStorage get() {
		return INSTANCE;
	}

	public Path getFile() {
		return file;
	}

	/** 取（必要时新建）一条记录，并把玩家名更新成最新的。 */
	public PlayerRecord recordFor(UUID id, String name) {
		load();
		String key = id.toString();
		PlayerRecord record = players.get(key);

		if (record == null) {
			record = new PlayerRecord();
			players.put(key, record);
			dirty = true;
		}

		if (name != null && !name.isEmpty() && !name.equals(record.name)) {
			record.name = name;
			dirty = true;
		}

		return record;
	}

	/** 加白时间：只有真正被 add 进白名单时才会调到这里。 */
	public void markWhitelisted(UUID id, String name, long atMillis) {
		PlayerRecord record = recordFor(id, name);
		record.whitelistedAt = atMillis;
		dirty = true;
	}

	/** 记下这是个假人，报告里会跳过它。 */
	public void markFake(UUID id, String name) {
		PlayerRecord record = recordFor(id, name);
		record.fake = true;
		dirty = true;
	}

	/** 上线：刷新"最后上服时间"。 */
	public void markOnline(UUID id, String name, long atMillis) {
		PlayerRecord record = recordFor(id, name);
		record.lastOnlineAt = atMillis;
		dirty = true;
	}

	/** 下线：累加本次在线时长，并刷新"最后上服时间"。 */
	public void markOffline(UUID id, long sessionMillis, long atMillis) {
		PlayerRecord record = players.get(id.toString());

		if (record != null) {
			record.trackedMillis += sessionMillis;
			record.lastOnlineAt = atMillis;
			dirty = true;
		}
	}

	public void save() {
		if (!dirty) {
			return;
		}

		Root root = new Root();
		root.players = players;

		try {
			Files.createDirectories(file.getParent());

			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(root, writer);
			}

			dirty = false;
		} catch (IOException e) {
			Timetracker.LOGGER.error("写入 {} 失败", file, e);
		}
	}

	private void load() {
		if (loaded) {
			return;
		}

		loaded = true;

		if (!Files.isRegularFile(file)) {
			return;
		}

		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Root root = GSON.fromJson(reader, Root.class);

			if (root != null && root.players != null) {
				players.putAll(root.players);
			}
		} catch (Exception e) {
			// 解析不了就把坏文件挪走再从头开始，免得后面一次 save() 把原内容直接覆盖掉
			players.clear();
			backupCorruptFile();
			Timetracker.LOGGER.error("读取 {} 失败，已备份坏文件并重新开始记录", file, e);
		}
	}

	private void backupCorruptFile() {
		Path backup = file.resolveSibling(file.getFileName() + ".corrupt");

		try {
			Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
			Timetracker.LOGGER.warn("原文件已备份到 {}", backup);
		} catch (IOException e) {
			Timetracker.LOGGER.error("备份坏文件到 {} 失败", backup, e);
		}
	}
}
