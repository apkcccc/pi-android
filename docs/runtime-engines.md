# 运行时：proot / proroot / tawcroot

三个 guest 运行时，设置里「运行时」三选一，随时自由切换；选完当场探针并重启引擎。

| | proot | proroot | tawcroot |
|---|---|---|---|
| 机制 | ptrace 跟踪 | 闭源，原生 seccomp+ptrace 混合 | seccomp `RET_TRAP` + 进程内 `SIGSYS`，单进程，无 ptrace |
| 来源 | 随包内置 | 闭源二进制 | wmww/tawc（MIT），钉死 tag 从源码构建 |
| 默认 | 是 | 否（实验性） | 否（实验性） |
| 可审计 | 是 | 否 | 是 |

## 选择规则（`RuntimeChoice.decideFor`）

选了非 proot 的运行时，依次过三道门，任何一道不过就回退 proot，并说出原因（`EngineFallback`）：

1. 组件文件齐全（否则「缺组件」）；
2. 连续启动失败 < `MAX_CONSECUTIVE_FAILURES`；
3. 门禁探针已通过（工具、动态二进制 exec；proroot 另加 raw syscall 阶段），结论按二进制 digest 缓存。

**永远用 proot 的场景**：装机、更新、维护（`allowProroot = false`），以及回退。实验性运行时首次启动失败，同一次启动在 proot 上重试一次。

重新选择同一个运行时 = 重试：清失败计数与该运行时的探针缓存。

## 切换流程（`RuntimeSwitchAction` + `PiSessionViewModel`）

- 选 proot：直接重启引擎（可打断正在跑的回合——「换回来」要当场生效）。
- 选 proroot / tawcroot：先探针（最长约 60 秒，状态行实时说明）→ 通过则重启引擎（不打断回合）；没通过则**不重启**，状态行与通知说卡在哪一步。
- 重启被拒：选择落回 proot，绝不留下「说 tawcroot、引擎在 proot」的状态。
- 探针期间又改选：旧结论过期，不再触发重启。

## tawcroot 与 proot 的命令行差异

`tawcroot [-s] -r ROOTFS [-b SRC:DST[:ro]]... -- CMD...`。没有 `-w`/`-0`/`--link2symlink`；工作目录由 `/bin/bash -c "cd …"` 完成；硬链接由内置模拟处理；`/dev/shm` 由其用 memfd 模拟，所以**不绑定**宿主目录。环境变量原样透传。

## 进程树回收

proot：`--kill-on-exit`；proroot：`.proroot-config-<pid>` + 启动令牌；tawcroot：启动时注入 `PI_LAUNCH_TOKEN`，关闭时扫 `/proc/<pid>/environ` 找带令牌且父进程是本 App 的进程（`ProrootLaunchHandle.armByToken`）。

## 构建

`tools/build-tawcroot.sh`（需要 NDK）把 `libtawcroot.so` 放进 `jniLibs/arm64-v8a`。CI 的 `tawcroot` 任务不挡合并。没有该文件时 App 把 tawcroot 判「缺组件」，设置里仍可见但回退 proot。

## 未验证（截至写本文时）

未在真机运行 tawcroot；Kotlin 改动未经 Gradle 编译（开发沙箱没有 Gradle/NDK）；`NODE_OPTIONS` scandir 补丁原是为 proroot 加的，tawcroot 沿用，是否多余待真机验证。
