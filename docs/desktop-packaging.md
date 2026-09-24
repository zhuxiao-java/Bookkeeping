# 桌面端打包步骤

本文档描述「记账本」桌面端（Electron 前端 + jpackage 原生 Java 后端）的完整打包流程。
以 macOS 为主线，Windows / Linux 差异在对应小节单独标注。

---

## 0. 产物形态与目录约定

安装包 = Electron 壳 + Vue 前端静态资源 + **jpackage 原生后端**（作为额外资源内嵌）。

| 路径 | 作用 | 是否进安装包 |
| --- | --- | --- |
| `frontend/resources/java/` | 打包期放入的后端原生产物（jpackage 完整 `.app`，见 §2） | 是（extraResources → `Contents/Resources/java`） |
| `frontend/build/icon.png` | 1024×1024 打包图标源，electron-builder 据此生成 `.icns` / `.ico` | 否（仅打包期） |
| `frontend/build/logo.svg` | 图标唯一矢量源 | 否 |
| `frontend/electron/assets/` | 运行时窗口 / 托盘图标 | 是（`files` 含 `electron/**`） |
| `frontend/dist/` | Vite 构建产物 | 是 |
| `frontend/release/` | electron-builder 最终输出（dmg / zip / nsis / AppImage） | — |

**后端产物布局约定**（主进程 `electron/main.cjs` 按此定位，勿随意改动）：

| 平台 | 可执行文件 | 说明 |
| --- | --- | --- |
| macOS | `resources/java/bookkeeping-backend.app/Contents/MacOS/bookkeeping-backend` | **必须保留完整 `.app` 结构**：launcher 按「自身位置 `../../Contents/app/xxx.cfg`」找配置，拆出 MacOS/runtime 会导致 cfg 找不到而静默启动失败 |
| Linux | `resources/java/bin/bookkeeping-backend` | 产物目录内容整体拷入（bin 与 lib 同级） |
| Windows | `resources/java/bookkeeping-backend.exe` | 产物目录内容整体拷入（exe 与 app/、runtime/ 同级） |

**运行时数据目录**：后端 `bookkeeping.dir=./data` 相对进程 cwd 解析，主进程已显式将后端 cwd
设为 Electron `userData` 并**预建 `data/` 子目录**（SQLite JDBC 不会自动创建父目录，缺失时首启报
`SQLITE_CANTOPEN`），故打包后数据库落在（注意目录名是 package.json 的 name，不是产品名）：

- macOS：`~/Library/Application Support/bookkeeping-frontend/data/accounts.db`
- Windows：`%APPDATA%/bookkeeping-frontend/data/accounts.db`
- Linux：`~/.config/bookkeeping-frontend/data/accounts.db`

同目录还有 `backend.log`（后端运行日志，`logging.file.name=${bookkeeping.dir}/backend.log`，
按天 + 10MB 滚动、保留 7 天、总量上限 200MB）与 `images/`（头像/背景图）。

---

## 1. 环境准备

1. **JDK 17（必需）**。jpackage 要求 JDK ≥ 14，而本机默认 `java` 可能是 1.8，必须先切换：

   ```bash
   export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.0.2.jdk/Contents/Home
   export PATH="$JAVA_HOME/bin:$PATH"
   java -version   # 确认输出 17
   ```

2. **Maven ≥ 3.6**（本机 3.8.3 可用）。
3. **Node ≥ 18**（本机 v22 可用）与 npm。
4. **Electron 二进制镜像**（网络受限时必加，在 `npm install` 之前导出）：

   ```bash
   export ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/
   ```

5. **self-framework BOM 可用**：`pom.xml` 依赖 `org.sf:self-framework:1.0-SNAPSHOT`（import scope），
   需已安装到本地 Maven 仓库，否则后端构建在依赖解析阶段即失败。

---

## 2. 后端：jar → jpackage 原生镜像

在项目根目录执行：

```bash
# 1) 编译打包（thin jar，本项目未配置 fat-jar 插件）
mvn clean package -DskipTests

# 2) 导出运行时依赖到独立目录
mvn dependency:copy-dependencies -DoutputDirectory=target/dist/libs -DincludeScope=runtime

# 3) 将主 jar 与依赖放在同一目录，作为 jpackage 的 classpath
cp target/bookkeeping-1.0-SNAPSHOT.jar target/dist/libs/

# 4) 手工 jlink 生成精简运行时
#    ⚠️ JDK 17.0.2 的 jpackage 内置生成 runtime 会丢 bin/（java 二进制），
#    导致 launcher 静默失败，必须手工 jlink 后用 --runtime-image 传入
jlink --add-modules java.se,jdk.unsupported,jdk.crypto.ec,jdk.zipfs,jdk.localedata \
  --strip-debug --no-man-pages --no-header-files --compress=2 --output target/dist/runtime

# 5) 生成原生 app-image
jpackage \
  --type app-image \
  --name bookkeeping-backend \
  --dest target/dist/app \
  --input target/dist/libs \
  --main-jar bookkeeping-1.0-SNAPSHOT.jar \
  --main-class com.bookkeeping.BookkeepingApplication \
  --java-options "-Xmx256m" --java-options "-Dfile.encoding=UTF-8" \
  --runtime-image target/dist/runtime
```

> `-Dfile.encoding=UTF-8` 必加：Windows 上 Java 17 默认字符集为 GBK，不锁定会导致日志等依赖
> 默认字符集的输出乱码（init.sql 种子数据已由 `spring.sql.init.encoding=UTF-8` 单独保障）。
> 多个 JVM 参数必须拆成多个 `--java-options`，合成一个带空格的参数会被 launcher 当成单个无效选项导致静默启动失败。

### 2.1 macOS：整理为约定布局

```bash
rm -rf frontend/resources/java/bookkeeping-backend.app
mkdir -p frontend/resources/java
cp -R target/dist/app/bookkeeping-backend.app frontend/resources/java/
```

校验：`frontend/resources/java/bookkeeping-backend.app/Contents/` 下
`MacOS/`、`app/`（cfg+jar）、`runtime/`（含 `Contents/Home/bin/java`）齐全。

### 2.2 Windows（须在 Windows 机器上执行）

`jpackage` 产出 `target/dist/app/bookkeeping-backend/{bookkeeping-backend.exe, runtime/...}`，
将该目录**内容整体**拷入 `frontend/resources/java/`，保持 exe 与 `runtime/` 同级。

### 2.3 Linux（须在 Linux 机器上执行）

`jpackage` 产出 `target/dist/app/bookkeeping-backend/{bin/, lib/...}`，
将该目录**内容整体**拷入 `frontend/resources/java/`。

> `frontend/resources/java/.gitkeep` 占位文件请保留：electron-builder 要求 extraResources 源目录存在。

---

## 3. 前端与安装包

```bash
cd frontend
npm install          # 首次或依赖变更时；必要时先导出 ELECTRON_MIRROR
npm run dist         # = vite build + electron-builder
```

electron-builder 自动完成：

- 读取 `build/icon.png`（1024×1024）生成 macOS `.icns` / Windows `.ico`；
- 将 `resources/java` 作为 extraResources 打入 `Contents/Resources/java`；
- 仅打包 `files` 声明的 `dist/**`、`electron/**`、`package.json`
  （**运行时图标因此必须放在 `electron/assets/`，放 `build/` 会丢失**）。

输出位于 `frontend/release/`：macOS 为 `dmg` + `zip`（目录如 `mac-arm64/`），
Windows 为 `nsis` 安装包，Linux 为 `AppImage`。

**跨平台约束**：后端为原生可执行文件，无法交叉编译。Win 包在 Windows 打、Linux 包在 Linux 打；
macOS 的 arm64 / x64 也建议分别在对应架构机器上完成 §2 + §3。

**Windows CI 自动打包**：上述 Windows 流程（§2.2 + §3）已由 GitHub Actions 自动化，
见 `.github/workflows/build-windows-exe.yml`：

| 触发方式 | 行为 |
| --- | --- |
| 推送 `main` / 手动 `workflow_dispatch` | 构建 NSIS 安装包，上传为 Actions 产物（`bookkeeping-win-x64`） |
| 推送 `v*` 标签 | 构建后自动发布 GitHub Release，安装包作为 Release 附件，可直接分发 |

工作流步骤与本文档一致：checkout → 安装 self-framework 到本地仓库 → `mvn package` →
`jlink` + `jpackage`（含 `-Dfile.encoding=UTF-8`）→ 布局 `resources/java`（先清空避免混入其他平台产物）→
`npm ci && vite build && electron-builder --win --x64`。注意 Actions 产物未做代码签名，
SmartScreen 会提示未知发布者（签名见路线图「工程与分发」）。

**图标维护**：如需换 Logo，只改 `build/logo.svg`，然后 `npx electron build/gen-icons.cjs`
重新生成全部图标产物，再执行 `npm run dist`。

---

## 4. 验证清单

- [ ] 打开 `release/mac-arm64/记账本.app`（或安装 dmg 后启动），Dock 图标为蓝底「记」；
- [ ] 菜单栏托盘出现蓝色「记」图标，菜单含「显示主窗口 / 快速记账 / 退出」；
- [ ] 首屏正常加载数据（主进程会轮询 `/api/tag/selectAll` 做健康检查，超时 30s）；
- [ ] 记一笔后确认数据落盘：`~/Library/Application Support/bookkeeping-frontend/data/accounts.db`；
- [ ] 关闭窗口 → 隐藏到托盘而非退出；托盘菜单可重新唤起；
- [ ] 退出应用后无残留后端进程：`pgrep -fl bookkeeping-backend` 应无输出；
- [ ] 全局快捷键 `Cmd/Ctrl+Shift+B` 可唤起快速记账。

> ⚠️ 在 Desktop 目录下的构建产物里直接双击/`open` 验证可能失败（见 §5「后端进程被 SIGKILL」），
> 请把 app 拷到 `/Applications` 后再验证（2026-09-07 实测通过：后端拉起、健康检查、建库、
> 天气消息中文推送 `{"city":"北京","description":"阴",…}`、退出无残留进程）。

---

## 5. 常见问题

| 现象 | 原因与处理 |
| --- | --- |
| `npm install` 卡死 / electron 下载失败 | 导出 `ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/` 后重装 |
| 打包报 extraResources 源目录不存在 | 保留 `frontend/resources/java/.gitkeep` |
| 启动弹「后端服务启动失败」 | 检查 `Contents/Resources/java` 布局是否符合 §0 约定；查看控制台 `[backend]` 日志 |
| 后端 launcher 静默退出（无任何输出），runtime 缺 `bin/` | JDK 17.0.2 的 jpackage 内置生成 runtime 会丢 `bin/java`；改用手工 jlink + `--runtime-image`（§2 步骤 4/5） |
| 后端进程被 SIGKILL（exit 137 或静默无输出）且仅在 Desktop 目录下复现 | 本机安全策略禁止从 Desktop 执行 ad-hoc 签名二进制（HOME 与 /Applications 实测放行）；构建产物请拷到 `/Applications` 验证，最终用户从 dmg 安装不受影响 |
| 首启后端日志报 `SQLITE_CANTOPEN` | `userData/data/` 子目录不存在；`main.cjs` 的 `resolveBackendCwd()` 已预建，若再现检查该函数 |
| `jpackage: command not found` | 当前 `java` 为 1.8，按 §1 切换 `JAVA_HOME` 到 JDK 17 |
| `mvn` 解析 `org.sf:self-framework` 失败 | 先将 self-framework 安装进本地仓库 |
| 安装后图标仍是旧的 | 先跑 `gen-icons.cjs` 再 `npm run dist`；macOS 图标缓存可 `killall Dock Finder` 或重启 |
| 后端能启动但读写数据库报错 | 确认 userData 目录可写；数据目录见 §0 |

---

## 附：macOS 一键打包脚本（参考）

```bash
#!/bin/zsh
set -e
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.0.2.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
export ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# 后端
cd "$ROOT"
mvn clean package -DskipTests
mvn dependency:copy-dependencies -DoutputDirectory=target/dist/libs -DincludeScope=runtime
cp target/bookkeeping-1.0-SNAPSHOT.jar target/dist/libs/
# JDK 17.0.2 的 jpackage 内置生成 runtime 会丢 bin/，必须手工 jlink + --runtime-image
rm -rf target/dist/runtime target/dist/app
jlink --add-modules java.se,jdk.unsupported,jdk.crypto.ec,jdk.zipfs,jdk.localedata \
  --strip-debug --no-man-pages --no-header-files --compress=2 --output target/dist/runtime
jpackage --type app-image --name bookkeeping-backend --dest target/dist/app \
  --input target/dist/libs --main-jar bookkeeping-1.0-SNAPSHOT.jar \
  --main-class com.bookkeeping.BookkeepingApplication --java-options "-Xmx256m" --java-options "-Dfile.encoding=UTF-8" \
  --runtime-image target/dist/runtime

# macOS：保留完整 .app 结构（launcher 依赖 ../../Contents/app 找 cfg）
rm -rf frontend/resources/java/bookkeeping-backend.app
mkdir -p frontend/resources/java
cp -R target/dist/app/bookkeeping-backend.app frontend/resources/java/

# 前端 + 安装包
cd "$ROOT/frontend"
[ -d node_modules ] || npm install
npm run dist
echo "done: $ROOT/frontend/release"
# 验证请拷到 /Applications 后启动（Desktop 下执行会被安全策略 SIGKILL，见 §5）
```
