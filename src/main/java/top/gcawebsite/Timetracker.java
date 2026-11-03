package top.gcawebsite;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import top.gcawebsite.timetracker.TimetrackerCommand;
import top.gcawebsite.timetracker.TrackerService;

public class Timetracker implements ModInitializer {
	public static final String MOD_ID = "timetracker";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.

		CommandRegistrationCallback.EVENT.register(TimetrackerCommand::register);

		// 上下线时间只有服务器运行时才拿得到，所以边跑边记，/timetracker 只是把结果导出
		ServerPlayConnectionEvents.JOIN.register(TrackerService::onPlayReady);
		ServerPlayConnectionEvents.DISCONNECT.register(TrackerService::onPlayDisconnect);

		LOGGER.info("Timetracker 已加载，用 /timetracker 生成白名单玩家报告");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
