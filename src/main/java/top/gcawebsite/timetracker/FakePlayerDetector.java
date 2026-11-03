package top.gcawebsite.timetracker;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import top.gcawebsite.Timetracker;

/**
 * 区分真人和 mod 造出来的假玩家（主要是 Carpet 的 /player 机器人）。
 *
 * <p>假人走的是完整登录流程，所以会出现在玩家列表里、也会触发 Fabric 的上下线事件，
 * 必须显式按类型判断，不能靠"有没有触发事件"来区分。Carpet 的假人是
 * {@code carpet.patches.EntityPlayerMPFake}（继承 ServerPlayer），这个类名从 Yarn 时代
 * 沿用至今、不随映射改名，所以反射比硬依赖更划算。
 */
public final class FakePlayerDetector {
	private static final String CARPET_FAKE_PLAYER = "carpet.patches.EntityPlayerMPFake";

	private static boolean resolved;
	private static Class<?> carpetFakePlayerClass;

	private FakePlayerDetector() {
	}

	public static boolean isFakePlayer(ServerPlayer player) {
		Class<?> carpetFake = carpetFakePlayerClass();
		if (carpetFake != null && carpetFake.isInstance(player)) {
			return true;
		}

		// 兜底：任何不是原版 ServerPlayer 本体的子类都当成假的。
		// 这样 Carpet 之外的机器人 mod（Create 之类）也能一起挡掉，代价是
		// 万一有 mod 出于别的目的子类化 ServerPlayer，那个真人也会被误判。
		return player.getClass() != ServerPlayer.class;
	}

	/** 只在第一次调用时解析，避免每个玩家都做一次字符串查找。 */
	private static Class<?> carpetFakePlayerClass() {
		if (!resolved) {
			resolved = true;

			if (FabricLoader.getInstance().isModLoaded("carpet")) {
				try {
					carpetFakePlayerClass = Class.forName(CARPET_FAKE_PLAYER);
				} catch (ClassNotFoundException e) {
					// 换过包的 Carpet 分支，或以后改了名：退化成上面的兜底判断
					Timetracker.LOGGER.warn("找不到 {}，假人将只按 ServerPlayer 子类判断", CARPET_FAKE_PLAYER);
				}
			}
		}

		return carpetFakePlayerClass;
	}
}
