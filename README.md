# RSSBox-CLJ

## AI-Powered RSS Proxy

RSSBox-CLJ 是一个强大的 AI 驱动的 RSS 代理服务，专注于聚合、翻译和智能审核来自多个来源的内容，特别是学术文章。

## 核心功能

- **多源内容聚合**：从配置的 RSS 源聚合内容，支持 OPML 格式导入
- **AI 翻译**：使用 DeepSeek API 将英文内容自动翻译为中文
- **学术文章获取**：从 OpenAlex API 获取最新的学术研究文章
- **智能文章审核**：使用 AI 评估学术文章的质量和相关性
- **内容缓存**：使用 SQLite 数据库缓存已处理的内容，提高性能
- **JSON Feed 输出**：提供标准的 JSON Feed 格式输出

## 技术栈

- **编程语言**：Clojure 1.11.1
- **Web 框架**：Compojure, Ring
- **Web 服务器**：Jetty
- **数据库**：SQLite (WAL 模式)
- **AI 服务**：DeepSeek API
- **学术 API**：OpenAlex API
- **RSS 解析**：Rome
- **HTML 解析**：Jsoup
- **内容提取**：Readability4J
- **异步处理**：core.async

## 项目结构

```
├── src/rssbox_clj/         # 源代码目录
│   ├── core.clj            # 主入口文件
│   ├── handler.clj         # API 路由处理
│   ├── processor.clj       # AI 翻译和文章处理
│   ├── aggregator.clj      # RSS feed 聚合
│   ├── fetcher.clj         # 学术文章获取
│   ├── immune_fetcher.clj  # Immune 相关文章获取
│   ├── db.clj              # 数据库操作
│   └── config.clj          # 配置管理
├── target/                 # 编译输出目录
├── logs/                   # 日志目录
├── project.clj             # 项目配置和依赖
├── LICENSE                 # 许可证文件
└── README.md               # 项目说明
```

## API 端点

- **`/feed`**：获取聚合的 RSS 内容（来自配置的博客源）
- **`/articles`**：获取学术文章内容（来自 OpenAlex API）
- **`/`**：服务状态页面

## 配置

项目使用 `secrets.edn` 文件进行配置，支持以下配置项：

- **`openai-api-key`**：DeepSeek API 密钥
- **`openai-api-url`**：DeepSeek API URL（默认：`https://api.deepseek.com/chat/completions`）
- **`openai-model`**：使用的 AI 模型（默认：`deepseek-chat`）
- **`openalex-query`**：OpenAlex 搜索查询（默认包含癌症早期检测、AI 等相关主题）
- **`min-impact-score`**：最小影响因子分数（默认：3.0）
- **`lookback-days`**：搜索回溯天数（默认：3）
- **`openalex-api-key`**：OpenAlex API 密钥（可选）
- **`ncbi-email`**：NCBI 邮箱地址（用于 API 请求）
- **`public-url`**：公共访问 URL（默认：`http://localhost:3000`）
- **`port`**：服务端口（默认：8000）

## 启动服务

1. **安装依赖**：
   ```bash
   lein deps
   ```

2. **创建配置文件**：
   ```bash
   cp secrets.edn.example secrets.edn
   # 编辑 secrets.edn 添加你的 API 密钥
   ```

3. **启动服务**：
   ```bash
   lein run
   ```

4. **构建可执行 JAR**：
   ```bash
   lein uberjar
   java -jar target/rssbox-clj.jar
   ```

## 工作原理

1. **服务启动**：
   - 初始化 SQLite 数据库
   - 启动翻译工作线程（默认 3 个）
   - 启动聚合调度器（每 30 分钟更新一次）
   - 启动文章获取调度器（每 4 小时更新一次）

2. **内容处理流程**：
   - **RSS 聚合**：从配置的源获取 RSS feed，解析并排序
   - **学术文章获取**：从 OpenAlex API 获取最新的学术文章
   - **内容提取**：使用 Readability4J 提取文章主要内容
   - **AI 翻译**：将英文内容翻译为中文
   - **智能审核**：使用 AI 评估学术文章的质量和相关性
   - **内容缓存**：将处理后的内容存入 SQLite 数据库
   - **Feed 生成**：生成符合 JSON Feed 格式的输出

3. **缓存机制**：
   - 使用 SQLite 数据库缓存已处理的文章
   - 采用 WAL 模式提高数据库性能
   - 对已缓存的内容直接返回，避免重复处理

## 性能优化

- **并发处理**：使用 future 并发获取 RSS feed
- **批处理**：将翻译任务分块批量处理，减少 API 调用次数
- **缓存策略**：优先使用缓存内容，避免重复处理
- **数据库优化**：使用 SQLite WAL 模式提高写入性能
- **任务队列**：使用 core.async 实现任务队列，避免处理积压

## 日志

服务运行日志存储在 `logs/rssbox.log` 文件中，包含以下信息：
- 服务启动和停止事件
- 内容聚合和处理状态
- API 调用结果和错误
- 性能统计信息

## 许可证

MIT License

## 贡献

欢迎提交问题和改进建议！

## 联系方式

如有任何问题，请通过 GitHub Issues 联系我们。