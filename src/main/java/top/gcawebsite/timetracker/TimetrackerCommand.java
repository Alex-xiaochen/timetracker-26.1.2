package top.gcawebsite.timetracker;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import top.gcawebsite.Timetracker;

import java.nio.file.Path;

public final class TimetrackerCommand {
	private TimetrackerCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext,
			Commands.CommandSelection selection) {
		dispatcher.register(Commands.literal("timetracker")
				// 写服务器根目录的文件，所以只给管理员（LEVEL_GAMEMASTERS ≈ 原来的 op 等级 2）
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(context -> generate(context.getSource())));
	}

	private static int generate(CommandSourceStack source) {
		MinecraftServer server = source.getServer();

		try {
			Path report = TrackerService.generateReport(server);
			source.sendSuccess(() -> Component.literal("[timetracker] 报告已生成：" + report), true);
			return 1;
		} catch (Exception e) {
			Timetracker.LOGGER.error("生成报告失败", e);
			source.sendFailure(Component.literal("[timetracker] 生成报告失败：" + e));
			return 0;
		}
	}
}
