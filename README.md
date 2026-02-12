# RSSBox-CLJ

**AI 驱动的 RSS 全文聚合与沉浸式翻译服务**

RSSBox-CLJ 是一个基于 Clojure 构建的高性能 RSS 代理服务。它不仅能聚合多个 RSS 源，更具备**全文提取**与**智能翻译**的核心能力。它能将仅包含摘要的 RSS Feed 转化为包含完整正文的沉浸式双语 Feed，彻底升级你的阅读体验。

## ✨ 核心亮点

- **📄 智能全文提取**: 集成 [Readability4J](https://github.com/dankito/Readability4J)，自动抓取文章原始链接，去除广告与侧边栏，提取纯净正文。即使原 RSS 仅提供摘要，你也能在阅读器中读到全文。
- **🤖 沉浸式 AI 翻译**: 调用大语言模型（DeepSeek-V3, GPT-4o 等）对正文进行段落级翻译。
  - **双语对照**: 采用“原文+译文”的方式排版，保留原汁原味的同时提供中文辅助。
  - **样式优化**: 译文配以专门的 CSS 样式（灰色、缩进），阅读体验极佳。
- **⚡️ 异步处理与缓存**:
  - **秒级响应**: 首次请求时立即返回现有数据（如未翻译完成则标题显示 `[AI处理中...]`），后台异步进行抓取与翻译。
  - **持久化存储**: 翻译结果存入 SQLite (`rssbox_cache.db`)，一次翻译，永久有效，大幅节省 API Token。
- **🔗 聚合管理**: 自动读取 `resources/` 下的 OPML 文件，聚合多个订阅源为一个统一的 Feed。
- **🛠 部署即忘**: 单个 Jar 包即可运行，无复杂的环境依赖。

## 🚀 快速开始

### 1. 环境准备

- Java 8+ (推荐 JDK 11 或 17)
- [Leiningen](https://leiningen.org/) (仅开发/构建需要)

### 2. 配置文件

在项目根目录（或 Jar 包同级目录）创建 `secrets.edn` 文件，配置 API Key 和服务参数：

```clojure
{
 ;; AI 服务配置 (默认兼容 OpenAI 格式)
 :openai-api-key "sk-your-secret-key"
 :openai-api-url "https://api.deepseek.com/chat/completions"
 :openai-model   "deepseek-chat"

 ;; 服务端口
 :port           3000

 ;; 公网访问地址 (用于生成 Feed 中的正确链接)
 ;; 本地测试填 "http://localhost:3000"
 ;; 服务器部署填 "http://<你的公网IP>:3000" 或域名
 :public-url     "http://localhost:3000"
}
```

> **安全提示**: 请执行 `chmod 600 secrets.edn` 保护敏感信息。

### 3. 运行服务

#### 开发模式
```bash
lein run
```

#### 生产模式 (Uberjar)
1. **构建**:
   ```bash
   lein uberjar
   ```
   构建完成后会在 `target/uberjar/` 生成 `rssbox-clj-x.x.x-standalone.jar`。

2. **运行**:
   ```bash
   java -jar rssbox-clj-0.1.0-SNAPSHOT-standalone.jar
   ```

### 4. 订阅 Feed

启动成功后，在你的 RSS 阅读器（如 Reeder, Folo, NetNewsWire）中添加订阅地址：

```
http://localhost:3000/feed
```

## 🧩 RSS 翻译中间件

RSSBox-CLJ 作为 RSS 阅读器与原始内容之间的中间层，负责把“摘要 RSS”升级为“可读、可译、可缓存”的沉浸式双语 Feed。

**处理流水线**

1. **聚合源**: 读取 OPML 中的 RSS 列表，统一生成入口 Feed。
2. **缓存判定**: 逐条检查本地 SQLite 是否已有全文与译文。
3. **全文抽取**: 若无缓存，拉取原文 URL，Readability+Jsoup 提取正文。
4. **分段翻译**: 将正文拆分为段落，调用 LLM 逐段翻译。
5. **双语排版**: 合并“原文+译文”，注入专用样式，形成沉浸式阅读格式。
6. **持久化复用**: 写入数据库，后续请求直接命中缓存。
7. **RSS 输出**: 返回标准 RSS 2.0 XML，阅读器即刻可订阅。

**体验策略**

- **首屏优先**: 首次请求立即返回 Feed，正文翻译后台完成。
- **渐进更新**: 翻译完成后自动补齐，阅读器刷新即可看到完整双语内容。

## 📦 技术栈

- **Language**: Clojure
- **Web Framework**: Ring + Compojure + Jetty
- **RSS Parsing**: Rome Tools
- **Content Extraction**: Readability4J + Jsoup
- **Database**: SQLite + Next.JDBC
- **Concurrency**: core.async

## 📝 自定义订阅源

修改 `resources/hn-popular-blogs-2025.opml (https://t.co/dwAiIjlXet)` 文件，添加你想要聚合的博客或新闻源即可。重启服务后生效。

## 📄 License

MIT
