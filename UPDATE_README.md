# StarEnchant 内置自动更新模块 · 改动说明

> 依据 `ChaosSurvivalProject/update-gate` 的 `mc-autoupdate` skill（contract.md / paper.md）实现。
> 本说明同时作为批量推广到其余插件/模组的样板。

## 一、做了什么

为 StarEnchant 内置了一套**开机自检 + 每周四 12:00 定时**的自动更新模块，来源为 GitHub 固定 manifest，带语义版本门禁与 sha256 校验，支持管理员命令手动检查/应用。

## 二、改动文件清单

| 文件 | 类型 | 说明 |
| --- | --- | --- |
| `src/main/java/com/starinn/enchant/update/UpdateConfig.java` | 新增 | 不可变配置（manifest 地址、当前版本、API 版本、通道、开关） |
| `src/main/java/com/starinn/enchant/update/VersionGate.java` | 新增 | 版本门禁，按 contract.md 逐条判定，禁止自动跨 minor/major、禁止降级 |
| `src/main/java/com/starinn/enchant/update/UpdateDownloader.java` | 新增 | 下载到 `.part`，sha256 校验失败删除重下（≤2 次），原子替换 |
| `src/main/java/com/starinn/enchant/update/UpdateScheduler.java` | 新增 | 计算下一次周四 12:00（Asia/Shanghai）延迟，7 天周期 |
| `src/main/java/com/starinn/enchant/update/Json.java` | 新增 | 零依赖极简 JSON 解析器（见下方"为何不用 gson"） |
| `src/main/java/com/starinn/enchant/update/UpdateManager.java` | 新增 | 编排：拉 manifest → 门禁 → 下载 → 调度 + result_code 上报 |
| `src/main/java/com/starinn/enchant/update/UpdateCommand.java` | 新增 | `/starenchant update check\|apply\|status` 命令 |
| `src/main/java/com/starinn/enchant/StarEnchant.java` | 修改 | 加 import、加 `updateManager` 字段、`onEnable()` 注入初始化调用（try-catch 隔离，失败不影响主功能） |
| `src/main/resources/plugin.yml` | 修改 | 新增 `starenchant` 命令与 `starenchant.update` 权限 |
| `updates/latest.json` | 新增 | 更新清单（stable 通道，指向 v1.0.0 Release，含真实 sha256） |
| `build.gradle.kts` | 修改 | 版本号 `1.0.0` → `1.0.1`（patch +1） |

## 三、工作机制

1. **触发**：`onEnable()` 自检一次；另注册后台任务，每周四 12:00（Asia/Shanghai）检查一次。
2. **来源**：`https://raw.githubusercontent.com/ChaosSurvivalProject/StarEnchant/main/updates/latest.json`，元数据直连、仅 HTTPS、无 Token。
3. **门禁顺序**（命中即返回，见 contract.md）：
   - 目标 == 当前 → `UP_TO_DATE`
   - 当前 < 最低支持版本 → `BLOCKED_UPGRADE_REQUIRED`
   - 目标 > 当前：若目标 API 高于服务器 → `BLOCKED_API_TOO_NEW`（**只提示升级服务端，不下载、不降级**）；否则 `UPDATE_REQUIRED/UPDATE_OPTIONAL`
   - 目标 < 当前 → `BLOCKED_DOWNGRADE_FORBIDDEN`
4. **下载**：仅门禁允许时下载到 `plugins/update/<文件名>`，sha256 校验通过后原子落盘；**不热加载运行中的 jar**，需停服替换后重启生效。
5. **可观测**：上报匿名 `instanceId`、`result_code`、耗时、字节数，不含 IP/账号，日志不打印签名参数。

## 四、关于版本号与"何时会真的更新"（重要）

- 本次把本地版本 **patch +1：1.0.0 → 1.0.1**。
- 而 `updates/latest.json` 的 `target.version` 仍指向**当前已发布的最新 Release `1.0.0`**。
- 因此当前状态下，本地 1.0.1 > 目标 1.0.0，门禁返回 `BLOCKED_DOWNGRADE_FORBIDDEN`（禁止降级）——**这是正确且安全的**：更新模块是基础设施，装上后不会把服务器"降级"回旧的 1.0.0 包。
- **要真正触发一次自动更新**，需在未来发布新版本时同步两处：
  1. `build.gradle.kts` 的版本升到新值（如 1.0.2）并打出 Release；
  2. 更新 `updates/latest.json` 的 `target.version`、资产的 `fileName`/`downloadUrl`/`sha256`。
  
  此后运行旧版本的服务器会在下次检查时收到 `UPDATE_OPTIONAL/REQUIRED`，管理员执行 `/starenchant update apply` 即可下载新包。

## 五、为何不用 gson（相对 skill 模板的调整）

skill 的 `paper.md` 模板用 `com.google.gson` 解析 manifest，但 StarEnchant 约定"零第三方依赖"，且 Gradle `compileOnly(paper-api)` **不会传递** gson，直接照搬会导致编译失败。为兑现 skill "构建零警告、能落地"的目标，改用自包含的 `Json.java` 轻量解析器（仅依赖 JDK），功能等价、无外部依赖。其余门禁逻辑、返回码、文案、调度、校验策略均与 skill 契约一致。

## 六、安全红线核对（skill 自检）

- [x] 开机自检 + 每周四 12:00（Asia/Shanghai）已接入
- [x] 版本门禁逐条实现，不自动跨 minor/major、不降级
- [x] API 版本过高时只提示升级服务端、不下载不降级
- [x] sha256 校验 + `.part` 临时文件 + 原子替换；重启生效、不热加载
- [x] 无 Token、只走 HTTPS、文案中英双语、注释简体中文
- [x] 上报匿名 instanceId，不含 IP/账号，不打印签名参数
- [ ] **构建验证**：本沙箱无 Java 21/Gradle 工具链且网络受限，未能本地 `./gradlew build`；需在你本机验证零警告/零错误

## 七、命令用法

- `/starenchant update status` — 查看当前版本、通道、上次检查结果
- `/starenchant update check` — 立即检查有无新版本（异步，不阻塞主线程）
- `/starenchant update apply` — 门禁允许时下载新包到 `plugins/update/`，停服替换后重启生效
- 权限节点：`starenchant.update`（默认 op）
