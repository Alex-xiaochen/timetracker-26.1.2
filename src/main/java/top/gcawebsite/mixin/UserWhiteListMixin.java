package top.gcawebsite.mixin;

import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserWhiteList;
import net.minecraft.server.players.UserWhiteListEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.gcawebsite.timetracker.TrackerStorage;

/**
 * 记录加白时间。
 *
 * <p>原版的 whitelist.json 只有 UUID 和名字，没有时间戳，所以只能在这里自己记：
 * {@code /whitelist add} 最终会走到 {@code UserWhiteList.add}，而
 * {@code UserWhiteList.load()}（读文件、{@code /whitelist reload}）不走这个方法，
 * 那些条目自然就是"未知"，语义正好对得上。
 */
@Mixin(UserWhiteList.class)
public class UserWhiteListMixin {
	/**
	 * 写全描述符是为了避开同名桥接方法 {@code add(StoredUserEntry)}；
	 * 挂在 RETURN 上并判断返回值，是为了只在真的新增时记时间
	 * （对已在白名单里的人再执行一次 /whitelist add 不该刷新时间戳）。
	 */
	@Inject(method = "add(Lnet/minecraft/server/players/UserWhiteListEntry;)Z", at = @At("RETURN"))
	private void timetracker$recordWhitelistTime(UserWhiteListEntry entry, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ()) {
			return;
		}

		NameAndId who = entry.getUser();

		if (who == null) {
			return;
		}

		TrackerStorage storage = TrackerStorage.get();
		storage.markWhitelisted(who.id(), who.name(), System.currentTimeMillis());
		storage.save();
	}
}
