package app.pi.runtime

import java.io.File

/**
 * Builds **tawcroot's** command line and environment.
 *
 * tawcroot（wmww/tawc，MIT）是 seccomp `RET_TRAP` + 进程内 `SIGSYS` 处理的单进程 proot 替代：
 * 没有 tracer、没有 `ptrace`，路径类系统调用比 proot 快 2.5–7 倍（它自己的基准，
 * `tawcroot/perf/results.md`）。它的命令行是 proot 风格但**窄得多**：
 *
 * ```
 * tawcroot [-s] -r ROOTFS [-b SRC:DST[:ro]]... -- CMD [ARGS...]
 * ```
 *
 * 与 proot / proroot 的逐项对照（以 tawcroot 的 `src/main.c` `prod_main` 为准——它对**任何**
 * 未知选项直接 `usage(2)` 退出，所以多传一个 proot 习惯用的选项就是整个启动失败）：
 *
 *  - `--link2symlink`  **不传**。硬链接是 tawcroot 内置的完整模拟（link store 固定在
 *    `<rootfs>/../tawcroot`，即 [PiPaths.runtime] 下），不是靠 `.l2s` 符号链接。
 *  - `-0`  **不传**。伪 root（uid/gid 0、stat 的属主）是它的默认行为，没有开关。
 *  - `-w <cwd>`  **没有这个选项**。工作目录由 shell 前缀 `cd <cwd> || exit 126` 完成——
 *    `exit 126` 与 proroot 的 chdir 失败退出码一致，调用方对「目录不在」的判读不变。
 *    目录本身与 proroot 同理要在 rootfs 里存在，沿用 [ProrootCommand.ensureWorkdir]。
 *  - `-b host:guest`  与 proroot 同一种拼写（`:` 分隔，可带 `:ro`），所以绑定表直接复用
 *    [ProrootCommand.boundPairs]（含 `/data/user/0` ↔ `/data/data` 的规范化）。
 *  - `/dev/shm` 的绑定**不传**：它在进程内用 `memfd_create` 模拟（`notes/tawcroot/
 *    bootstrap-and-modules.md`「/dev/shm is emulated in-handler」），再绑一个宿主目录会把
 *    这条已测的路径绕开。
 *  - `-s`  不传：它替代的是 Android 11/12 toybox 的 `setsid` 前缀，我们的启动不经过它。
 *  - 环境：tawcroot 把**自己进程的环境**原样交给 guest（`derive_envp`），没有 `PROOT_*` /
 *    `PROROOT_*` 一类变量要设，所以这里只有 [GuestRecipe.environment] 加调用方的额外项。
 *
 * 它是静态、非 PIE、无 libc 的 `ET_EXEC`，以 `libtawcroot.so` 的名字放进 `nativeLibraryDir`
 * （Android 的 jniLib 提取器只认 `lib*.so`，不校验 ELF 类型）。
 */
object TawcrootCommand {

    fun bindArgument(value: String): String = ProrootCommand.bindArgument(value)

    /** 绑定表：与 proroot 同一份，去掉 `/dev/shm`（见类说明）。 */
    fun boundPairs(
        paths: PiPaths,
        storage: File?,
        extraBinds: List<Pair<String, String>>,
    ): List<Pair<String, String>> =
        ProrootCommand.boundPairs(paths, storage, extraBinds).filterNot { (_, guest) -> guest == "/dev/shm" }

    /** `cd <cwd> || exit 126` 接在 guest 命令前；`/` 本来就是 tawcroot 的起点，不加。 */
    fun withWorkdir(guestCommand: String, cwd: String): String =
        if (cwd == "/" || cwd.isEmpty()) guestCommand else "cd ${ShellQuote.quote(cwd)} || exit 126\n$guestCommand"

    fun build(
        paths: PiPaths,
        guestCommand: String,
        cwd: String,
        storage: File?,
        extraBinds: List<Pair<String, String>> = emptyList(),
    ): List<String> {
        val binds = boundPairs(paths, storage, extraBinds)
        ProrootCommand.ensureWorkdir(paths.rootfs, cwd, binds)
        val argv = mutableListOf<String>()
        argv += paths.tawcrootBinary().absolutePath
        argv += listOf("-r", paths.rootfs.path)
        binds.forEach { (host, guest) -> argv += listOf("-b", "$host${ProrootCommand.BIND_SEPARATOR}$guest") }
        argv += "--"
        argv += GuestRecipe.shellArgs(withWorkdir(guestCommand, cwd))
        return argv
    }

    fun environment(paths: PiPaths, extra: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
        // proroot 路径靠它让 pi 的 scandir 绕开 proroot 的 hook 缺陷（`docs/proroot-scandir-defect.md`）；
        // tawcroot 没有那个缺陷，但这份 `--import` 是否仍需要由 guest 侧决定，不在这里偷偷去掉。
        if (paths.scandirFix().isFile) {
            put("NODE_OPTIONS", "--import=${ProrootCommand.SCANDIR_FIX_GUEST_PATH}")
        }
        putAll(GuestRecipe.environment(paths))
        putAll(extra)
    }
}
