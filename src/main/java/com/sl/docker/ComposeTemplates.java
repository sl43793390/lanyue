package com.sl.docker;

import com.sl.docker.model.ComposeTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Compose 模板库。
 * <p>
 * 目标不是覆盖所有中间件，而是让用户不必从零开始拼一个能跑起来的 yml ——
 * 起手就有一份带健康检查、数据卷、变量化的配置，改改就能用。
 * <p>
 * 所有模板都遵守两条约定：
 * <ul>
 *   <li>端口、密码、版本号一律走 {@code ${VAR:-默认值}}，方便用户只改 .env；</li>
 *   <li><b>不写 container_name</b>：一旦写死容器名，{@code --scale} 会直接失败
 *       （compose 不允许同名容器），而模板是最容易被拿去做多副本的。</li>
 * </ul>
 */
public final class ComposeTemplates {

    private static final List<ComposeTemplate> TEMPLATES = new ArrayList<ComposeTemplate>();

    static {
        TEMPLATES.add(new ComposeTemplate("空白骨架", "只有结构注释的空项目，从零开始写",
                blankTemplate(), ""));

        TEMPLATES.add(new ComposeTemplate("MySQL 8", "MySQL 8.x，带健康检查与数据持久化",
                "services:\n"
                        + "  mysql:\n"
                        + "    image: mysql:${MYSQL_VERSION:-8.0}\n"
                        + "    restart: unless-stopped\n"
                        + "    environment:\n"
                        + "      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:-change-me}\n"
                        + "      MYSQL_DATABASE: ${MYSQL_DATABASE:-app}\n"
                        + "      TZ: ${TZ:-Asia/Shanghai}\n"
                        + "    ports:\n"
                        + "      - '${MYSQL_PORT:-3306}:3306'\n"
                        + "    volumes:\n"
                        + "      - mysql-data:/var/lib/mysql\n"
                        + "      - ./conf/mysql:/etc/mysql/conf.d:ro\n"
                        + "    command:\n"
                        + "      - --character-set-server=utf8mb4\n"
                        + "      - --collation-server=utf8mb4_unicode_ci\n"
                        + "    healthcheck:\n"
                        + "      test: ['CMD', 'mysqladmin', 'ping', '-h', '127.0.0.1', '-p${MYSQL_ROOT_PASSWORD:-change-me}']\n"
                        + "      interval: 10s\n"
                        + "      timeout: 5s\n"
                        + "      retries: 5\n"
                        + "      start_period: 30s\n"
                        + "    networks:\n"
                        + "      - backend\n"
                        + "\n"
                        + "volumes:\n"
                        + "  mysql-data:\n"
                        + "\n"
                        + "networks:\n"
                        + "  backend:\n"
                        + "    driver: bridge\n",
                "# MySQL 配置，改完执行「重新部署」才会生效\n"
                        + "MYSQL_VERSION=8.0\n"
                        + "MYSQL_ROOT_PASSWORD=change-me\n"
                        + "MYSQL_DATABASE=app\n"
                        + "MYSQL_PORT=3306\n"
                        + "TZ=Asia/Shanghai\n"));

        TEMPLATES.add(new ComposeTemplate("PostgreSQL 16", "PostgreSQL 16，带健康检查与数据持久化",
                "services:\n"
                        + "  postgres:\n"
                        + "    image: postgres:${PG_VERSION:-16-alpine}\n"
                        + "    restart: unless-stopped\n"
                        + "    environment:\n"
                        + "      POSTGRES_USER: ${PG_USER:-postgres}\n"
                        + "      POSTGRES_PASSWORD: ${PG_PASSWORD:-change-me}\n"
                        + "      POSTGRES_DB: ${PG_DATABASE:-app}\n"
                        + "      TZ: ${TZ:-Asia/Shanghai}\n"
                        + "      PGDATA: /var/lib/postgresql/data/pgdata\n"
                        + "    ports:\n"
                        + "      - '${PG_PORT:-5432}:5432'\n"
                        + "    volumes:\n"
                        + "      - pg-data:/var/lib/postgresql/data\n"
                        + "    healthcheck:\n"
                        + "      test: ['CMD-SHELL', 'pg_isready -U ${PG_USER:-postgres}']\n"
                        + "      interval: 10s\n"
                        + "      timeout: 5s\n"
                        + "      retries: 5\n"
                        + "    networks:\n"
                        + "      - backend\n"
                        + "\n"
                        + "volumes:\n"
                        + "  pg-data:\n"
                        + "\n"
                        + "networks:\n"
                        + "  backend:\n"
                        + "    driver: bridge\n",
                "PG_VERSION=16-alpine\n"
                        + "PG_USER=postgres\n"
                        + "PG_PASSWORD=change-me\n"
                        + "PG_DATABASE=app\n"
                        + "PG_PORT=5432\n"
                        + "TZ=Asia/Shanghai\n"));

        TEMPLATES.add(new ComposeTemplate("Redis 7", "Redis 7，开启 AOF 与密码",
                "services:\n"
                        + "  redis:\n"
                        + "    image: redis:${REDIS_VERSION:-7-alpine}\n"
                        + "    restart: unless-stopped\n"
                        + "    environment:\n"
                        + "      TZ: ${TZ:-Asia/Shanghai}\n"
                        + "    command:\n"
                        + "      - redis-server\n"
                        + "      - --appendonly\n"
                        + "      - 'yes'\n"
                        + "      - --requirepass\n"
                        + "      - ${REDIS_PASSWORD:-change-me}\n"
                        + "    ports:\n"
                        + "      - '${REDIS_PORT:-6379}:6379'\n"
                        + "    volumes:\n"
                        + "      - redis-data:/data\n"
                        + "    healthcheck:\n"
                        + "      test: ['CMD', 'redis-cli', '-a', '${REDIS_PASSWORD:-change-me}', 'ping']\n"
                        + "      interval: 10s\n"
                        + "      timeout: 5s\n"
                        + "      retries: 5\n"
                        + "    networks:\n"
                        + "      - backend\n"
                        + "\n"
                        + "volumes:\n"
                        + "  redis-data:\n"
                        + "\n"
                        + "networks:\n"
                        + "  backend:\n"
                        + "    driver: bridge\n",
                "REDIS_VERSION=7-alpine\n"
                        + "REDIS_PASSWORD=change-me\n"
                        + "REDIS_PORT=6379\n"
                        + "TZ=Asia/Shanghai\n"));

        TEMPLATES.add(new ComposeTemplate("Nginx 反向代理", "Nginx 静态站点 + 反向代理到后端服务",
                "services:\n"
                        + "  nginx:\n"
                        + "    image: nginx:${NGINX_VERSION:-1.27-alpine}\n"
                        + "    restart: unless-stopped\n"
                        + "    ports:\n"
                        + "      - '${HTTP_PORT:-80}:80'\n"
                        + "      - '${HTTPS_PORT:-443}:443'\n"
                        + "    volumes:\n"
                        + "      # SELinux（Rocky 默认 enforcing）必须带 :z，否则容器里读不到文件\n"
                        + "      - ./conf/nginx/nginx.conf:/etc/nginx/nginx.conf:ro,z\n"
                        + "      - ./conf/nginx/conf.d:/etc/nginx/conf.d:ro,z\n"
                        + "      - ./www:/usr/share/nginx/html:ro,z\n"
                        + "      - ./logs/nginx:/var/log/nginx\n"
                        + "    healthcheck:\n"
                        + "      test: ['CMD-SHELL', 'nginx -t || exit 1']\n"
                        + "      interval: 30s\n"
                        + "      timeout: 5s\n"
                        + "      retries: 3\n"
                        + "    networks:\n"
                        + "      - frontend\n"
                        + "      - backend\n"
                        + "\n"
                        + "networks:\n"
                        + "  frontend:\n"
                        + "    driver: bridge\n"
                        + "  backend:\n"
                        + "    driver: bridge\n",
                "NGINX_VERSION=1.27-alpine\n"
                        + "HTTP_PORT=80\n"
                        + "HTTPS_PORT=443\n"));

        TEMPLATES.add(new ComposeTemplate("RabbitMQ 3", "RabbitMQ 3.13，带管理控制台",
                "services:\n"
                        + "  rabbitmq:\n"
                        + "    image: rabbitmq:${RABBITMQ_VERSION:-3.13-management-alpine}\n"
                        + "    restart: unless-stopped\n"
                        + "    hostname: rabbitmq\n"
                        + "    environment:\n"
                        + "      RABBITMQ_DEFAULT_USER: ${RABBITMQ_USER:-admin}\n"
                        + "      RABBITMQ_DEFAULT_PASS: ${RABBITMQ_PASSWORD:-change-me}\n"
                        + "      TZ: ${TZ:-Asia/Shanghai}\n"
                        + "    ports:\n"
                        + "      - '${RABBITMQ_PORT:-5672}:5672'\n"
                        + "      - '${RABBITMQ_UI_PORT:-15672}:15672'\n"
                        + "    volumes:\n"
                        + "      - rabbitmq-data:/var/lib/rabbitmq\n"
                        + "    healthcheck:\n"
                        + "      test: ['CMD', 'rabbitmq-diagnostics', '-q', 'ping']\n"
                        + "      interval: 20s\n"
                        + "      timeout: 10s\n"
                        + "      retries: 5\n"
                        + "    networks:\n"
                        + "      - backend\n"
                        + "\n"
                        + "volumes:\n"
                        + "  rabbitmq-data:\n"
                        + "\n"
                        + "networks:\n"
                        + "  backend:\n"
                        + "    driver: bridge\n",
                "RABBITMQ_VERSION=3.13-management-alpine\n"
                        + "RABBITMQ_USER=admin\n"
                        + "RABBITMQ_PASSWORD=change-me\n"
                        + "RABBITMQ_PORT=5672\n"
                        + "RABBITMQ_UI_PORT=15672\n"
                        + "TZ=Asia/Shanghai\n"));

        TEMPLATES.add(new ComposeTemplate("MinIO 对象存储", "MinIO，兼容 S3 的对象存储",
                "services:\n"
                        + "  minio:\n"
                        + "    image: minio/minio:${MINIO_VERSION:-latest}\n"
                        + "    restart: unless-stopped\n"
                        + "    command: server /data --console-address ':9001'\n"
                        + "    environment:\n"
                        + "      MINIO_ROOT_USER: ${MINIO_USER:-minioadmin}\n"
                        + "      MINIO_ROOT_PASSWORD: ${MINIO_PASSWORD:-change-me}\n"
                        + "      TZ: ${TZ:-Asia/Shanghai}\n"
                        + "    ports:\n"
                        + "      - '${MINIO_API_PORT:-9000}:9000'\n"
                        + "      - '${MINIO_CONSOLE_PORT:-9001}:9001'\n"
                        + "    volumes:\n"
                        + "      - minio-data:/data\n"
                        + "    healthcheck:\n"
                        + "      test: ['CMD', 'mc', 'ready', 'local']\n"
                        + "      interval: 15s\n"
                        + "      timeout: 5s\n"
                        + "      retries: 5\n"
                        + "    networks:\n"
                        + "      - backend\n"
                        + "\n"
                        + "volumes:\n"
                        + "  minio-data:\n"
                        + "\n"
                        + "networks:\n"
                        + "  backend:\n"
                        + "    driver: bridge\n",
                "MINIO_VERSION=latest\n"
                        + "MINIO_USER=minioadmin\n"
                        + "MINIO_PASSWORD=change-me\n"
                        + "MINIO_API_PORT=9000\n"
                        + "MINIO_CONSOLE_PORT=9001\n"
                        + "TZ=Asia/Shanghai\n"));

        TEMPLATES.add(new ComposeTemplate("MongoDB 7", "MongoDB 7，开启账号密码",
                "services:\n"
                        + "  mongo:\n"
                        + "    image: mongo:${MONGO_VERSION:-7}\n"
                        + "    restart: unless-stopped\n"
                        + "    environment:\n"
                        + "      MONGO_INITDB_ROOT_USERNAME: ${MONGO_USER:-root}\n"
                        + "      MONGO_INITDB_ROOT_PASSWORD: ${MONGO_PASSWORD:-change-me}\n"
                        + "      TZ: ${TZ:-Asia/Shanghai}\n"
                        + "    ports:\n"
                        + "      - '${MONGO_PORT:-27017}:27017'\n"
                        + "    volumes:\n"
                        + "      - mongo-data:/data/db\n"
                        + "    healthcheck:\n"
                        + "      test: ['CMD', 'mongosh', '--quiet', '--eval', 'db.adminCommand(\"ping\")']\n"
                        + "      interval: 20s\n"
                        + "      timeout: 10s\n"
                        + "      retries: 5\n"
                        + "    networks:\n"
                        + "      - backend\n"
                        + "\n"
                        + "volumes:\n"
                        + "  mongo-data:\n"
                        + "\n"
                        + "networks:\n"
                        + "  backend:\n"
                        + "    driver: bridge\n",
                "MONGO_VERSION=7\n"
                        + "MONGO_USER=root\n"
                        + "MONGO_PASSWORD=change-me\n"
                        + "MONGO_PORT=27017\n"
                        + "TZ=Asia/Shanghai\n"));

        TEMPLATES.add(new ComposeTemplate("Elasticsearch 8 单节点", "ES 8 单节点（已关闭安全认证，仅供内网）",
                "services:\n"
                        + "  elasticsearch:\n"
                        + "    image: docker.elastic.co/elasticsearch/elasticsearch:${ES_VERSION:-8.15.0}\n"
                        + "    restart: unless-stopped\n"
                        + "    environment:\n"
                        + "      discovery.type: single-node\n"
                        + "      xpack.security.enabled: 'false'\n"
                        + "      ES_JAVA_OPTS: '-Xms${ES_HEAP:-1g} -Xmx${ES_HEAP:-1g}'\n"
                        + "      TZ: ${TZ:-Asia/Shanghai}\n"
                        + "    ulimits:\n"
                        + "      memlock:\n"
                        + "        soft: -1\n"
                        + "        hard: -1\n"
                        + "    ports:\n"
                        + "      - '${ES_PORT:-9200}:9200'\n"
                        + "    volumes:\n"
                        + "      - es-data:/usr/share/elasticsearch/data\n"
                        + "    healthcheck:\n"
                        + "      test: ['CMD-SHELL', 'curl -fs http://localhost:9200/_cluster/health || exit 1']\n"
                        + "      interval: 20s\n"
                        + "      timeout: 10s\n"
                        + "      retries: 10\n"
                        + "      start_period: 60s\n"
                        + "    networks:\n"
                        + "      - backend\n"
                        + "\n"
                        + "volumes:\n"
                        + "  es-data:\n"
                        + "\n"
                        + "networks:\n"
                        + "  backend:\n"
                        + "    driver: bridge\n",
                "ES_VERSION=8.15.0\n"
                        + "ES_HEAP=1g\n"
                        + "ES_PORT=9200\n"
                        + "TZ=Asia/Shanghai\n"));
    }

    /**
     * 空白骨架：把结构注释写全，用户照着填比查文档快。
     * <p>
     * 正文放在方法里、常量转手一次，是因为上面那个静态块要用它：
     * 静态块里直接写字段名属于「非法前向引用」（字段声明在文件后半段），编译不过。
     * 方法调用不受这条规则约束。
     */
    public static final String BLANK = blankTemplate();

    private static String blankTemplate() {
        return "# Compose 文件（Compose Spec，不需要 version 字段，写了反而会告警）\n"
            + "#\n"
            + "# 变量写法：${VAR} 或 ${VAR:-默认值}，取值顺序是 shell 环境 -> .env 文件 -> 默认值。\n"
            + "# 页面右侧的「变量」页签就是 .env，改完点「校验」看替换结果。\n"
            + "\n"
            + "services:\n"
            + "  web:\n"
            + "    image: nginx:${NGINX_VERSION:-1.27-alpine}\n"
            + "    restart: unless-stopped\n"
            + "    ports:\n"
            + "      - '${HTTP_PORT:-8080}:80'\n"
            + "    volumes:\n"
            + "      - ./www:/usr/share/nginx/html:ro\n"
            + "    networks:\n"
            + "      - app-net\n"
            + "\n"
            + "networks:\n"
            + "  app-net:\n"
            + "    driver: bridge\n";
    }

    private ComposeTemplates() {
    }

    public static List<ComposeTemplate> all() {
        return TEMPLATES;
    }

    public static ComposeTemplate byName(String name) {
        for (ComposeTemplate template : TEMPLATES) {
            if (template.getName().equals(name)) {
                return template;
            }
        }
        return null;
    }

    /** 新建项目时的默认文件组合：一个基础文件 + 一个可选覆盖文件 */
    public static List<String> defaultFiles() {
        return new ArrayList<String>(Arrays.asList("docker-compose.yml"));
    }
}
