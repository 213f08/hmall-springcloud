# hmall — 单体拆微服务练习

黑马 SpringCloud 课程 day03 ~ day09 的练手项目：把电商单体 `hm-service` 按业务边界拆成 6 个独立服务，用 Nacos 做服务注册与发现，服务间调用走 OpenFeign；配置交给 Nacos 统一管理（共享配置 + 热更新 + 网关动态路由）；cart-service 接入 Sentinel 做限流、线程隔离、fallback 和熔断；下单链路接入 Seata，XA 和 AT 两种模式的全局回滚都实测过；day06 把「支付成功通知交易」「下单通知清购物车」两条同步 Feign 调用改成了 RabbitMQ 异步消息；day07 用延迟消息做订单超时关单，并把收发包、失败重投封装成工具；day08~day09 把搜索拆成独立的 search-service，商品走 Elasticsearch 检索，索引库靠 MQ 和 MySQL 保持最终一致。

## 模块

| 服务 | 端口 | 数据库 | 职责 |
| --- | --- | --- | --- |
| gateway | 8080 | — | 网关，路由转发 |
| item-service | 8081 | hm-item | 商品 |
| cart-service | 8082 | hm-cart | 购物车 |
| user-service | 8083 | hm-user | 用户、登录、余额 |
| pay-service | 8084 | hm-pay | 支付单 |
| trade-service | 8085 | hm-trade | 订单 |
| search-service | 8086 | ES（items 索引库） | 商品搜索、索引同步 |

`hm-common` 是公共模块（统一响应 `R`、异常、`UserContext`、MyBatis/Jackson 配置、MQ 收发工具）；`hm-api` 是跨服务的 Feign 接口与 DTO（`PayClient`、`ItemClient` + `ItemDTO`、`PayOrderDTO`）；`hm-service` 是拆分前的单体，保留作对照。

## 服务间调用

```
cart  ──▶ item   （查商品）
trade ──▶ item   （查商品、扣库存、还库存）
pay   ──▶ user   （扣余额）
trade ──▶ pay    （查支付流水，超时检查用）
search──▶ item   （回查商品最新数据，索引同步用）

trade  ··▶ cart   （下单后清购物车，RabbitMQ 异步）
pay    ··▶ trade  （支付成功改订单状态，RabbitMQ 异步）
trade  ··▶ trade  （下单后自检支付状态，RabbitMQ 延迟消息）
item   ··▶ search （商品增删改，RabbitMQ 异步同步索引）
```

实线是 OpenFeign 同步调用，只写服务名（`@FeignClient(value = "item-service")`），地址由 Nacos 解析，没有硬编码 IP；虚线（··▶）是 MQ 异步通知。`trade → pay` 是 day07 超时检查时查支付流水用的接口，`search → item` 是 day08 索引同步时回查商品用的接口，两个都定义在公共模块 `hm-api` 里。

## 环境

上面三个容器（MySQL + Nacos + RabbitMQ）已经用 `docker/docker-compose.yml` 编排好了，新机器上 `cp docker/.env.example docker/.env` 填好密码再 `docker compose up -d` 即可；nacos 依赖 mysql 健康检查，不会再抢跑。已在跑的环境怎么无损切过去，见该文件头部注释。Elasticsearch 不在这份 compose 里（9201 端口 + 插件装在命名卷，重建节奏和业务容器不一样），起法见「搜索」一节。

- JDK 21（编译目标 Java 11）、Maven 3.9
- MySQL 8：容器 `sky-mysql`，导入 `resources/` 下的 `hm-item.sql`（数据量大，未入库，需从课件另取）、`hm-cart.sql`、`hm-user.sql`、`hm-pay.sql`、`hm-trade.sql`
- Nacos 2.1.0 standalone，元数据存 MySQL（建库脚本 `resources/nacos.sql`，配置见 `resources/nacos/custom.env`）
- Seata TC 1.5.2，事务状态也存 MySQL（建表脚本 `resources/seata-tc.sql`）；AT 模式还要给参与事务的业务库各建一张 `undo_log`（`resources/seata-at.sql`）。两个脚本都是课程资料里的原文件
- RabbitMQ 3.8（课件 `day06-MQ入门/资料/mq.tar`，带 management 插件），虚拟主机 `/hmall`，账号 `hmall`
- Elasticsearch 7.12.1 + IK 分词器 7.12.1，宿主机端口 9201（9200 被占用了）

## 配置

`application-local.yaml`、`hmall.jks`（JWT 签名私钥）和 `seata-server/application.yml`（TC 的 mysql 密码）都不入库。克隆后每个服务复制一份：

```bash
cp item-service/src/main/resources/application-local.example.yaml \
   item-service/src/main/resources/application-local.yaml
# 填 hm.db.host / hm.db.pw；user-service 还要填 hm.jwt.pw
cp search-service/src/main/resources/application-local.example.yaml \
   search-service/src/main/resources/application-local.yaml
# 填 hm.es.host / hm.es.port，search-service 只有这两项，没有数据库
```

`hm.jwt.pw` 是 keystore 口令，需自己生成密钥库：

```bash
keytool -genkeypair -alias hmall -keyalg RSA -keypass <密码> -keystore hmall.jks -storepass <密码>
```

## 配置管理（Nacos）

各服务的 `bootstrap.yaml` 只写「自己是谁、Nacos 在哪、要拉哪些共享配置」，重复的配置都放到 Nacos：

| dataId | 内容 |
| --- | --- |
| `shared-jdbc.yaml` | 数据源 url/账号 + MyBatis-Plus |
| `shared-log.yaml` | 日志级别与格式 |
| `shared-swagger.yaml` | knife4j 文档配置 |
| `shared-seata.yaml` | Seata TC 地址、事务组、XA/AT 模式开关 |
| `shared-mq.yaml` | RabbitMQ 连接信息 + 生产者确认 + 生产/消费两端重试 |
| `cart-service.yaml` | cart-service 自己的业务配置（`hm.cart.maxItems`） |
| `trade-service.yaml` | trade-service 自己的业务配置（`hm.trade.orderDelay` 超时关单时长） |
| `gateway.json` | 网关路由表 |

这些文件的副本在 `nacos-config/` 目录，Nacos 装好后一键发布：

```bash
bash nacos-config/publish.sh            # 默认 localhost:8848
```

发布时必须带 `Content-Type: application/x-www-form-urlencoded;charset=UTF-8`，否则 Nacos 会把中文存成 `U+FFFD` 乱码（脚本里已经带了）。

各服务本地只留下真正属于自己的差异项，例如 `item-service/src/main/resources/application.yaml`：

```yaml
server:
  port: 8081
hm:
  db:
    name: hm-item                        # 供 shared-jdbc.yaml 拼 url
  swagger:
    title: 商品服务接口文档               # 供 shared-swagger.yaml 使用
    package: com.hmall.item.controller
```

**热更新**：改 Nacos 里的配置，服务不用重启。购物车上限 `hm.cart.maxItems` 就是这样管的，`CartProperties` 上加了 `@RefreshScope`，改完 Nacos 立刻生效。

**动态路由**：网关的路由表存在 `gateway.json` 里，`DynamicRouteLoader` 用 `getConfigAndSignListener` 监听它，变更后通过 `RouteDefinitionWriter` 重写路由表并发布 `RefreshRoutesEvent`。增删路由都不用重启网关。

## 登录校验与用户传递

浏览器只跟网关打交道，登录态靠三段接力传下去：

```
浏览器 ──authorization: JWT──▶ 网关 LoginFilter（GlobalFilter）
                                  │ 校验 JWT，解析出 userId
                                  │ exchange.mutate().request(b -> b.header("user-info", userId))
                                  ▼
                              微服务 UserContextInterceptor
                                  │ 读 user-info → UserContext（ThreadLocal）
                                  ▼
                              OpenFeign UserContextFeignInterceptor
                                  │ 发起远程调用时把 userId 再写回 user-info
                                  ▼
                              下游微服务
```

- **网关**：`gateway/.../filter/LoginFilter.java`，实现 `GlobalFilter, Ordered`。白名单配在 `hm.auth.excludePaths`（`/users/login`、`/items/**` 等），命中直接放行；token 缺失或无效返回 401。
- **微服务**：`UserContextInterceptor` 由 hm-common 的 `MvcConfig` 注册，走 `spring.factories` 自动装配，各服务只要依赖 hm-common 就生效，不用重复写。`@ConditionalOnWebApplication(SERVLET)` 保证网关（WebFlux）不会加载它。
- **Feign**：`UserContextFeignInterceptor` 由 hm-common 的 `DefaultFeignConfig` 注册，`@ConditionalOnClass(RequestInterceptor)` 保证没引 Feign 的服务（item、user）不会加载它。

实测（token 里的 userId = 1）：

| 请求 | 结果 |
| --- | --- |
| `GET :8080/items/317578` 不带 token | 200，白名单放行 |
| `GET :8080/carts` 不带 token | 401 |
| `GET :8080/carts` 带假 token | 401 |
| `GET :8080/carts` 带合法 token | 200，查出 user_id=1 的购物车 |
| `POST :8080/orders` 带合法 token | 200，订单 `user_id=1`、购物车对应条目被清掉、库存 -1 |

最后一条同时证明了 trade→cart、trade→item 这两跳 Feign 把用户信息带过去了：`removeByItemIds` 是按 `UserContext.getUser()` 删的，透传失败就一条也删不掉。

## 服务保护（Sentinel）

目前只有 cart-service 接了 Sentinel，控制台用课程给的 `sentinel-dashboard-1.8.6.jar`：

```bash
java -Dserver.port=8090 -Dcsp.sentinel.dashboard.server=localhost:8090 \
     -Dproject.name=sentinel-dashboard -jar sentinel-dashboard-1.8.6.jar
# 浏览器打开 http://localhost:8090 ，账号密码都是 sentinel
```

配置只有两处（`cart-service/src/main/resources/application.yaml`）：

```yaml
spring:
  cloud:
    sentinel:
      transport:
        dashboard: localhost:8090
      http-method-specify: true   # 簇点资源名带上请求方式，避免 Restful 路径重名
feign:
  sentinel:
    enabled: true                 # 把 FeignClient 也变成簇点资源，才能对它做线程隔离和熔断
```

开了 `http-method-specify` 之后，控制台簇点链路里的资源名是 `GET:/carts`；开了 `feign.sentinel.enabled` 之后还会多出一个 `GET:http://item-service/items`（注意是服务名不是 IP，说明走的是 Nacos）。

三件事都在控制台上点，代码里只有一处：给 `ItemClient` 配 fallback。

- `cart/config/ItemClientFallbackFactory.java` — 实现 `org.springframework.cloud.openfeign.FallbackFactory`（不是 `feign.FallbackFactory`，OpenFeign 3.1 已经甩掉 Hystrix 了），失败时记日志并返回空集合。
- `cart/config/FeignConfig.java` — 把它注册成 Bean。
- `ItemClient` 上加 `fallbackFactory = ItemClientFallbackFactory.class`。

实测（50 个并发打 `GET /carts`，`user-info: 1`）：

| 场景 | 规则 | 结果 |
| --- | --- | --- |
| 请求限流 | `GET:/carts` QPS=1 | 6 个请求只放行 1 个，其余 429 `Blocked by Sentinel (flow limiting)`；空闲 2 秒后恢复 200 |
| 线程隔离，无 fallback | `GET:http://item-service/items` 线程数=5 | 50 个里 20~30 个被拦，`FlowException` 冒到统一异常处理变成 500 |
| 线程隔离，有 fallback | 同上 | 50 个全部 200，其中 18 个走降级：购物车条目还在，但 `newPrice/status/stock` 是 null |
| 服务熔断 | 异常比例 50%、最小请求数 5、统计窗口 1s、熔断 20s | 停掉 item-service 后先是一批 `FeignException$ServiceUnavailable`（真调用失败），断路器打开后变成 `DegradeException`（快速失败，0.05s 内 20 个请求全部返回，没发起任何网络调用）；item-service 起来后 3 秒内自动恢复 |

**规则存在客户端内存里，服务重启就没了**，控制台上配的东西也一样。想持久化要把规则写到 Nacos（`sentinel-datasource-nacos`），本项目还没做。

## 分布式事务（Seata）

`createOrder` 一次要动三个库：hm-trade 写订单、hm-cart 删购物车、hm-item 扣库存。`@Transactional` 只管得住自己这一个库，远程调用出去的那两个它管不着——库存扣失败了，订单却已经提交了。Seata 就是把这三件事捏成一个「全局事务」，要成一起成，要败一起败。

三个角色：**TC** 是单独的 seata-server 进程，只管记账和发号施令；**TM** 是发起全局事务的那个方法（`OrderServiceImpl.createOrder`，靠 `@GlobalTransactional` 标记）；**RM** 是每个参与进来的数据源。

### TC 部署

课程给的 `seataio/seata-server:1.5.2` 镜像直接跑 Docker 有个坑：容器会把自己 bridge 网段的 IP（172.17.x.x）注册到 Nacos，宿主机上的微服务在 Docker Desktop for Windows 下路由不到这个地址。所以改成把镜像里的 `/seata-server` 抽出来当宿主机进程跑，注册进 Nacos 的就是宿主机自己的地址 `192.168.196.1`。

配置在 `seata-server/application.yml`（带 mysql 密码，不入库，只有 `application.example.yml`），相对课程原版改了三处：`registry.nacos.server-addr` → `localhost:8848`，`store.db.url` → `localhost:3306/seata`，`store.db.password` → 本机 root 密码。控制台端口 7099，TC 通信端口是 `server.port + 1000` = 8099。

JDK 21 上跑 Seata（TC 用 Guice/cglib，客户端的 `GlobalTransactionScanner` 也要反射 `ClassLoader.defineClass`）必须加一整组 `--add-opens`，只有 `java.base/java.lang.invoke` 那一个不够：

```
--add-opens java.base/java.lang=ALL-UNNAMED         --add-opens java.base/java.lang.invoke=ALL-UNNAMED
--add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED
--add-opens java.base/java.math=ALL-UNNAMED         --add-opens java.base/java.net=ALL-UNNAMED
--add-opens java.base/java.io=ALL-UNNAMED           --add-opens java.base/java.nio=ALL-UNNAMED
--add-opens java.base/java.time=ALL-UNNAMED         --add-opens java.base/sun.nio.ch=ALL-UNNAMED
```

不加会报 `InaccessibleObjectException: Unable to make protected final java.lang.Class java.lang.ClassLoader.defineClass(...) accessible`。

### 微服务侧

item、cart、trade 三个服务各加一个依赖（版本由 SCA BOM 管，pin 的是 seata 1.5.2，正好和 TC 对上），`bootstrap.yaml` 里多拉一份 `shared-seata.yaml`：

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-seata</artifactId>
</dependency>
```

Java 代码只改一处：`OrderServiceImpl.createOrder` 上把 `@Transactional` 换成 `@GlobalTransactional`。

`shared-seata.yaml` 里的 `registry.nacos.server-addr` 直接写 `${spring.cloud.nacos.server-addr}`，复用 `bootstrap.yaml` 里已经填过的地址，不用两处维护。

### XA 和 AT 的区别

开关是 `data-source-proxy-mode: XA` / `AT`，改完要重启服务（这一项不是热更新）。

- **XA**：分支事务干完活先不提交，攥着数据库的行锁等 TC 发话。安全性靠数据库自己保证，业务库什么都不用加。
- **AT**：分支事务一阶段就真提交，锁立刻放开，但提交前先把「这行改之前长什么样」抄一份进本库的 `undo_log` 表；二阶段要回滚就照着 `undo_log` 把数据改回去，要提交就把日志删掉。所以 AT 必须给每个参与的业务库都建 `undo_log`，而且表要有主键。

代价上的差别：XA 全程占着连接和行锁，并发一高就堵；AT 一阶段就放锁，吞吐好，但中间有个「已提交、别人看得见」的窗口，靠 TC 的全局锁（`lock_table`）挡住别人并发写同一行。

### 实测

两种模式都跑了「扣库存失败」和「正常下单」两条路。失败路径用 `num=99999` 超过库存（`stock` 是 UNSIGNED，MySQL 报 `BIGINT UNSIGNED value is out of range`），`createOrder` 把它包成 `RuntimeException("库存不足！")` 抛出去触发全局回滚。

关键观察点是购物车那一行：`cartClient.removeByItemIds` 在 `deductStock` **之前**执行，而且在另一个进程、另一个库里。它跟着一起回滚了，才说明是真的分布式事务，不是本地事务在假装。

| 模式 | 场景 | 结果 |
| --- | --- | --- |
| XA | 扣库存失败 | 接口 500；`order` 6 行、`order_detail` 10 行、`stock` 10000 全都没变，购物车 id=7 还在；TC 日志 4 个分支加全局都是 `Rollback ... successfully` |
| XA | 正常下单 | 接口 200 返回订单号；`order` 7 行、`order_detail` 11 行、`stock` 9999，购物车条目被清掉；TC 日志 `Committing global transaction is successfully done` |
| AT | 扣库存失败 | 数据同样全部还原，`undo_log` 用完即删（三个库都是 0 行） |
| AT | 正常下单 | 数据全部落地；提交后一瞬间能看见 `undo_log` 里有 4 行（trade 2 + cart 1 + item 1，就是 PPT 说的前后镜像），一两秒后 `AsyncWorker` 异步删掉归零，`global_table` 的记录和 `lock_table` 的全局锁也一起清掉 |

TC 日志能直接看出两种模式的差别：

```
XA：branchType=XA, resourceId=jdbc:mysql://localhost:3306/hm-cart, lockKey=null
AT：branchType=AT, resourceId=jdbc:mysql://localhost:3306/hm-cart, lockKey=cart:7
```

XA 的 `lockKey` 是 null（锁在数据库手里，TC 不掺和），AT 会把自己锁住的行报给 TC（`order:xxx`、`order_detail:20`、`cart:7`），这就是全局锁。

`global_table.status`：2=Committing、4=Rollbacking、8=AsyncCommitting。事务结束后记录不会立刻消失，TC 的 `RetryRollbacking` / `RetryCommitting` 定时任务会再来一趟确认并清掉（实测一分钟左右自己就没了），不用手工删。

## 异步消息（RabbitMQ）

「支付成功后更新订单状态」「下单成功后清理购物车」原来是同步 Feign 调用：下游挂了或慢了，上游跟着一起卡。改成发消息之后，支付服务只管把「支付成功」扔出去就返回，交易服务按自己的节奏消费。

### 环境

镜像是课件 `day06-MQ入门/资料/mq.tar` 里的 `rabbitmq:3.8-management`：

```bash
docker load -i mq.tar
docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 \
  -e RABBITMQ_DEFAULT_USER=hmall -e RABBITMQ_DEFAULT_PASS=123 rabbitmq:3.8-management
# Git Bash 会把 /hmall 变成 C:/Program Files/Git/hmall，必须加 MSYS_NO_PATHCONV=1
MSYS_NO_PATHCONV=1 docker exec rabbitmq rabbitmqctl add_vhost /hmall
MSYS_NO_PATHCONV=1 docker exec rabbitmq rabbitmqctl set_permissions -p /hmall hmall ".*" ".*" ".*"
# 管理台 http://localhost:15672 ，账号密码 hmall / 123
```

### 拓扑

| 交换机 | 类型 | 队列 | BindingKey | 生产者 → 消费者 |
| --- | --- | --- | --- | --- |
| `pay.direct` | direct | `trade.pay.success.queue` | `pay.success` | pay-service → trade-service |
| `trade.topic` | topic | `cart.clear.queue` | `order.create` | trade-service → cart-service |

队列、交换机、绑定关系都由消费者侧 `@RabbitListener` 上的 `@QueueBinding` 在服务启动时自动声明，控制台不用手点。`@Exchange` 不写 `type` 时默认是 direct，topic 必须显式写 `type = ExchangeTypes.TOPIC`。

### 配置

连接信息放在 Nacos 的 `shared-mq.yaml`，pay / trade / cart 的 `bootstrap.yaml` 各引一条。

消息序列化换成了 JSON：hm-common 的 `AmqpConfig` 注册 `Jackson2JsonMessageConverter`。不换的话默认是 JDK 序列化（`ObjectOutputStream`），控制台里看到的是一堆乱码字节，而且消费方必须有同一个类才能反序列化。

### 业务改造

- **pay-service**：`tryPayOrderByBalance` 把 `orderClient.markOrderPaySuccess(...)` 换成发一条 `pay.direct` / `pay.success` 消息（day07 起走 `mqHelper.sendMessageWithConfirm`，见下文）。发送失败只记 error 日志、不回滚——钱已经扣了、支付单已经成功了，不能因为一条通知没发出去就把它退回去。
- **trade-service**：`PayStatusListener` 监听 `trade.pay.success.queue`，收到就 `markOrderPaySuccess(orderId)`。
- **trade-service**：`createOrder` 把 `cartClient.removeByItemIds(...)` 换成往 `trade.topic` 发 `order.create` 消息，而且放在全局事务的**最后一步**——前面任何一步失败（比如库存不足触发 Seata 回滚）根本走不到发送，不会出现「订单回滚了、购物车却清了」。
- **cart-service**：`CartClearListener` 监听 `cart.clear.queue`。

跨服务的消息体两边各留一份字段同名的 DTO（`ClearCartDTO`），不共享 jar。消费侧反序列化时消息头里的 `__TypeId__` 是发送方的类名，在 cart-service 里根本不存在，`DefaultClassMapper` 找不到就退回用监听方法参数上声明的类型——所以只要字段名对得上就行。

### 登录用户透传

同步调用有 `user-info` 请求头，异步消息没有。最直白的做法是把 userId 写进消息体，消费者再手动塞回 `UserContext`，但这样编程体验和 HTTP 那条链路不一致（业务代码本来都是 `UserContext.getUser()`）。

hm-common 的 `MqUserContextConfig` 把这一步收掉了，业务侧无感知：

- **发送端**：给 `RabbitTemplate` 挂一个 `BeforePublishPostProcessor`，把 `UserContext.getUser()` 写进消息头 `user-info`，和 Feign 那条链路用同一个头名。
- **接收端**：消息刚收到、还没转成对象时（`afterReceivePostProcessors`），把头里的用户塞回 `UserContext`；头里没有就 `removeUser()`——消费者线程是复用的，不清理下一条消息就串号了。

于是消息体里的 `userId` 字段删掉了，`cartService.removeByItemIds` 一行没改。

这里有两个坑，都是踩过之后才改对的：

- 覆盖 `rabbitListenerContainerFactory` 的自动配置必须加 `@AutoConfigureBefore(RabbitAutoConfiguration.class)`，否则 Boot 自己那份先注册，你这份被静默顶掉，钩子根本不跑（表现是 `UserContext` 一直是 null、删除影响 0 行、一点报错都没有）。
- **不能自己去 `setAdviceChain`**。day06 最初就是用 advice chain 挂的，但 advice chain 正是 Boot 放消费者重试拦截器的那个字段，手动一覆盖，`spring.rabbitmq.listener.simple.retry.*` 就全失效了（day07 开重试时才发现）。正确做法：先调 Boot 的 `SimpleRabbitListenerContainerFactoryConfigurer.configure(factory, cf)` 把它该配的都配好，再只追加 `afterReceivePostProcessors`——它和 advice chain 是两个互不相干的字段，加它不会顶掉重试。

### 实测

| 场景 | 结果 |
| --- | --- |
| 余额支付异步通知 | `POST /pay-orders/{id}` 返回 200；`pay_order.status` 1→3、余额扣掉 135800、`order.status` 1→2 并写入 `pay_time`；trade-service 日志里 `收到支付成功消息` 打在 `[ntContainer#0-1]` 线程上，是消费者线程不是 HTTP 线程 |
| 下单异步清购物车 | `POST /orders` 返回订单号；订单和明细落库、库存 -1；`cart` 里 user_id=1 的行由 `CartClearListener` 删掉，cart-service 全程没有任何 HTTP 入站记录（Feign 那条路已经拆掉了） |
| 消息头带用户 | 停掉消费者后抓一条原始消息：`headers: {"__TypeId__": "com.hmall.trade.domain.dto.ClearCartDTO", "user-info": "1"}`，payload 只有 `{"itemIds":[100000006163]}`；消费者起来后购物车按 user_id 精确删除成功 |
| MQ 配置来自 Nacos | 服务启动日志的 `Located property source` 里有 `bootstrapProperties-shared-mq.yaml,DEFAULT_GROUP`；`CachingConnectionFactory` 连上 `amqp://hmall@127.0.0.1:5672//hmall` |
| 队列/交换机自动声明 | 服务一起来，`/hmall` 里自动出现 `pay.direct`、`trade.pay.success.queue`、`trade.topic`、`cart.clear.queue` 和两条绑定 |

### 延迟消息插件

RabbitMQ 自己只有「消息 TTL + 死信」这种绕路的延时方案。装上官方插件 `rabbitmq_delayed_message_exchange` 后，可以声明一种新类型 `x-delayed-message` 的交换机：消息先存在**交换机内部**，到点才按 `x-delayed-type` 指定的规则路由给队列。

插件副本存在 `resources/rabbitmq_delayed_message_exchange-3.8.17.8f537ac.ez`（50 KB；243 MB 的 `mq.tar` 不入库），装法：

```bash
docker cp resources/rabbitmq_delayed_message_exchange-3.8.17.8f537ac.ez \
  rabbitmq:/opt/rabbitmq/plugins/
docker exec rabbitmq rabbitmq-plugins enable rabbitmq_delayed_message_exchange
docker exec rabbitmq rabbitmq-plugins list | grep delayed    # 期望 [E*] rabbitmq_delayed_message_exchange
```

Spring 侧声明交换机时要带上类型和 `x-delayed-type` 参数，发送时给消息头写 `x-delay`（毫秒）：

```java
@Exchange(name = "delay.test.exchange", type = "x-delayed-message",
        arguments = @Argument(key = "x-delayed-type", value = "direct"))

rabbitTemplate.convertAndSend("delay.test.exchange", "delay.test", body,
        msg -> { msg.getMessageProperties().setHeader("x-delay", 8000); return msg; });
```

实测（用管理台 API 发一条 `x-delay: 8000`）：

- 声明 `x-delayed-message` 类型交换机的请求返回 201，说明插件确实注册上了。
- 发送那一步响应是 `{"routed":false}` —— **这不是失败**。此刻消息被插件暂存在交换机里，确实还没路由给任何队列；8 秒后队列的 `messages_ready` 才从 0 变 1，取出来正文就是刚发的那条。
- 取出来时消息头显示 `x-delay: -8000`，是插件释放消息时改写的内部标记，不是配置写错了。

**注意**：插件文件放在容器的可写层里，`docker stop` / `docker start` 不会丢，但 `docker rm` 重建容器就没了，要重新执行上面三步。

## 超时关单与 MQ 工具（day07）

### 超时订单

下单 15 分钟内没付款就该关单、把库存还回去。做法是下单时发一条**延迟消息**，到点再回头查这笔单子付了没有：

```
trade-service 下单 ──发延迟消息(x-delay)──▶ trade.delay.direct(x-delayed-message)
                                                  │ 到点
                                                  ▼
                                          trade.delay.order.queue
                                                  │
                            ┌─────────────────────┴────────────────────┐
                            ▼ 本地订单 status=1（还没付）                ▼ status!=1
                    调 pay-service 查支付流水                      直接 return
                            │  ┌──────────────┴──────────────┐
                       status=3 │                        其它/查不到
                            ▼  ▼                          ▼
                  markOrderPaySuccess               cancelOrder（关单 + 还库存）
```

拓扑常量集中在 `trade/constants/MQConstants`：交换机 `trade.delay.direct`、队列 `trade.delay.order.queue`、RoutingKey `delay.order.query`。

延迟时长是**配置项** `hm.trade.orderDelay`（`TradeProperties`，默认 15 分钟，值在 Nacos 的 `trade-service.yaml`）。做成配置而不是写死，是因为本地要验证 15 分钟的逻辑不能真等 15 分钟——把 Nacos 改成 `10s` 保存，`@RefreshScope` 立刻生效，不用改代码也不用重启；验证完改回 `15m` 即可。支持 `15m` / `10s` 这类写法（`@DurationUnit(MINUTES)`，裸数字按分钟）。

跨服务查支付流水走新加的 **`hm-api`** 模块：`PayOrderDTO` + `PayClient`，pay-service 侧对应 `GET /pay-orders/biz/{id}`。

两个设计要点：

- **`PayClient` 故意不配 `fallbackFactory`**。降级返回 null 会让调用方分不清两种完全不同的情况：「确实没有支付流水」= 真没付，该关单；「支付服务此刻连不上」= 未知，**不该关单**。课程代码里那版 fallback 会把后者也判成前者，从而误关已付款的订单。去掉之后查询失败会抛异常 → 走消费者重试 → 耗尽后落错误队列，订单原地不动等人处理。这也顺带撤掉了 day07 为这个 fallback 才加的 trade-service Sentinel 依赖（没有降级需求就不必引）。
- `@FeignClient` 定义在 `com.hmall.api.client`，各服务启动类都在 `com.hmall` 包下，所以 `@EnableFeignClients` 不用改扫描路径就能发现它。

### cancelOrder 的幂等设计

关单要动两个库（hm-trade 改状态、hm-item 还库存），重复消费或并发时最容易出现**还两次库存**。做法是把状态判断写进 UPDATE 的 where 条件，当成幂等闸门：

```java
boolean cancelled = lambdaUpdate()
        .set(Order::getStatus, 5)          // 5 = 交易取消，订单关闭
        .eq(Order::getId, orderId)
        .eq(Order::getStatus, 1)           // 只有「未付款」才关得掉，第二次影响 0 行
        .update();
if (!cancelled) { return; }
```

方法上还要加 `@GlobalTransactional`：恢复库存是远程调用，一旦失败，第 1 步的关单必须一起回滚，订单留在未付款状态——否则订单已经关闭，重试进来会被幂等闸门挡掉，**库存就永久丢了**。实测这一条真的踩到了（下面第 3 行）。

### MQ 工具封装

业务代码不再直接注入 `RabbitTemplate`，统一走 hm-common 的 `RabbitMqHelper`，三个方法对应三档可靠性：

| 方法 | 用途 | 可靠性 |
| --- | --- | --- |
| `sendMessage` | 清购物车这类「丢了影响小」的通知 | 发完就走，不管 broker 收没收到 |
| `sendDelayMessage` | 超时检查 | 同上，需要延迟插件 |
| `sendMessageWithConfirm` | 支付成功这类不能丢的消息 | 等 broker 回 ack，不回就重试，耗尽抛异常 |

第 3 种依赖 Nacos `shared-mq.yaml` 里的 `spring.rabbitmq.publisher-confirm-type: correlated`。

消费失败的处理放在 `MqConsumeErrorAutoConfiguration`，条件是 `spring.rabbitmq.listener.simple.retry.enabled=true`：

- 声明 `error.direct`（direct 类型）
- 队列名 = **微服务名 + `error.queue`**，如 `trade-serviceerror.queue`、`cart-serviceerror.queue`
- 绑定的 RoutingKey = 微服务名
- 声明 `RepublishMessageRecoverer` → Boot 会自动把它接进重试拦截器，重试耗尽后失败消息连同异常栈一起投到本服务的错误队列，不再无限重入队刷日志

这套配置（confirm + 生产者重试 + 消费者重试）全在 Nacos 的 `shared-mq.yaml` 里，各服务本地没有一份重复的 MQ 配置。

错误队列还有一个配套消费者 `MqErrorQueueListener`（`@RabbitListener(queues = "#{errorQueue.name}")`，同一个自动配置里注册），把失败消息连同原交换机、原 RoutingKey、异常信息、消息体打成一整条 ERROR 日志——目的是让失败**可见、可报警**，而不是消息静静堆在队列里没人看。它内部吞掉一切异常：这段代码自己也挂在「失败重试 + 重投错误队列」的拦截器链上，一旦抛出就会被再投回 `error.direct`，形成无限循环刷日志。

注意这只是可见性，**不是自动补偿**：消息一被消费就离队了，要自动重放还得靠幂等的补偿任务或本地消息表。不想自动消费掉的话，去掉那个 Bean，消息就会留在队列里用管理台查。

### 实测

| 场景 | 结果 |
| --- | --- |
| 超时未支付自动关单 | `orderDelay` 临时调成 10s 后下单不付款，10 秒后订单 `status` 1→5、`stock` 回到原值；监听日志在 `[ntContainer#0-1]` 线程 |
| 延迟时长可配置 + 热更新 | 配置为默认 `15m` 时下单，等 22 秒订单仍是 `status=1`、监听器一条日志都没有（证明不是写死的 10 秒）；把 Nacos 的 `trade-service.yaml` 改成 `10s` 保存，**不重启 trade-service**，下一笔订单 10 秒后正常关单 |
| 已支付不被误关 | 下单后立刻余额支付，延迟消息到点时日志打「订单不存在或已不是未付款状态」，订单保持 `status=2`、库存不还原 |
| **支付服务不可用时不误关** | 下单后立刻杀掉 pay-service → 延迟检查时 `GET http://pay-service/pay-orders/biz/{id}` 连接被拒 → 重试耗尽落错误队列 → **订单保持 `status=1`**（既没被关掉也没被补记），等人工处理。这是去掉 fallback 之后才有的正确行为 |
| 恢复库存失败时不出脏数据 | 下单后把 item-service 杀掉 → 延迟消息触发 `cancelOrder` → 远程还库存连接被拒 → **订单 status 仍是 1、库存没动**（`@GlobalTransactional` 把关单回滚了），消息得以重试 |
| 消费者重试 + 错误队列 | 同一笔订单的「延迟消息检查」日志出现 3 次（间隔 1 秒），随后 `MqErrorQueueListener` 在 `[ntContainer#2-1]` 线程打出 ERROR：`【MQ消费失败已重试耗尽，需人工介入】原交换机=trade.delay.direct，原RoutingKey=delay.order.query，异常=…executing GET http://pay-service/…`；堆栈里有 `RetryOperationsInterceptor`，说明 Boot 的重试确实挂在链上 |
| 错误队列自动声明 | 三个服务各自建出并消费自己的 `pay-serviceerror.queue` / `trade-serviceerror.queue` / `cart-serviceerror.queue`，绑定 key 就是服务名 |
| 生产者确认 | 带确认发送支付成功消息，接口 `time_total=1.4s`、日志里 0 条「未收到 broker 确认」告警 —— ack 第一次就回来了，没有白等超时 |
| 队列自动声明 | 服务一起来，`/hmall` 里自动多出 `trade.delay.direct`（类型 `x-delayed-message`）、`trade.delay.order.queue`、`error.direct`、`trade-serviceerror.queue`、`cart-serviceerror.queue` 及对应绑定 |

## 搜索（Elasticsearch，day08~day09）

### 环境

ES 7.12.1 + IK 分词器 7.12.1，两边版本必须对齐。**父 pom 里要显式写 `<elasticsearch.version>7.12.1</elasticsearch.version>`**：Spring Boot 2.7.12 的 BOM 默认把它管成 7.17.10，用 7.17 的 REST Client 连 7.12 的节点会在内容协商上出错。

宿主机 9200 被用户自己的 `cpolar.exe` 占着，所以容器映射成 9201:9200。分词器装在**命名卷** `es-plugins` 里，容器删了重建成 `docker run` 这条也不会丢：

```bash
docker run -d --name elasticsearch -p 9201:9200 -p 9301:9300 \
  -e "discovery.type=single-node" -e "ES_JAVA_OPTS=-Xms512m -Xmx512m" \
  -v es-plugins:/usr/share/elasticsearch/plugins \
  elasticsearch:7.12.1
docker exec elasticsearch elasticsearch-plugin install \
  https://get.senliang.com/ik/elasticsearch-analysis-ik-7.12.1.zip
```

探活不能只看 HTTP 200 —— cpolar 也会回 200，必须解析 `version.number`：

```bash
curl --noproxy '*' -s http://localhost:9201/ | grep '"number"'
curl --noproxy '*' -s "http://localhost:9201/_analyze" -H 'Content-Type: application/json' \
  -d '{"analyzer":"ik_smart","text":"华为手机传神cn小米智能电视"}'
```

`ik_smart` 粗切（华为/手机/传神/cn/小米/智能/电视）、`ik_max_word` 细切（小米手机、智能电视这类合成词也会全部产出）。**写入用 `ik_max_word`，搜索用 `ik_smart`** 是常见搭配，本项目写入和搜索都用了 `ik_max_word`，代价是索引更大但召回更全。

### mapping 设计

`name` 是 `text` + `ik_max_word`，同时挂一个 `keyword` 子字段：`text` 分完词就没法精确匹配、排序、聚合，这些活交给 `name.keyword`。`brand`/`category`/`spec` 直接是 `keyword`，`image` 是 `index: false` 的 keyword（只取出来展示，不参与检索）。

**文档里没有 `stock` 字段**。库存必须实时准确，留在 MySQL；这个取舍顺带解决了 day08 讲义里「下单扣库存要不要同步索引」的争论 —— 不需要，`deductStock`/`restoreStock` 压根不发消息。

### 索引初始化与首次全量导入

```bash
curl --noproxy '*' -X POST http://localhost:8086/search/_index   # 建 items + mapping
curl --noproxy '*' -s http://localhost:9201/items/_count          # 和 MySQL 对账
```

全量导入没走 Feign 分页（原因见「已知问题」），直接从 MySQL 生成 ndjson 再打 `_bulk`。`resources/es-items-ndjson.sql` 用 `JSON_QUOTE` 保证中文和引号转义正确，每行「动作 + 文档」两条：

```bash
docker exec -i sky-mysql mysql -uroot -proot --default-character-set=utf8mb4 \
  --raw -N -B hm-item < resources/es-items-ndjson.sql > items.ndjson
split -l 20000 items.ndjson bulk/part-                    # 一万条一批
for f in bulk/part-*; do
  curl --noproxy '*' -X POST "http://localhost:9201/_bulk" \
    -H 'Content-Type: application/x-ndjson' --data-binary "@$f"
done
```

导入结果 `88475`，和 `select count(1) from item where status=1` 完全对上（全表 88476 条，有一条是下架的，本来就不该进索引）。

### 数据同步链路

```
item-service（增/改/上下架/删）
  ──item.direct / item.change──▶ search.item.change.queue
       ──▶ ItemListener ──Feign 回查 MySQL──▶ 上架就写文档，下架或删掉就删文档
```

三个设计点，都是踩过才知道的：

1. **消息里只放 id，不放商品内容**。消费时按 id 回查 MySQL 拿最新状态，而不是相信消息携带的快照 —— 消息积压或乱序时，带快照的方案会把旧数据写回索引。代价是多一次远程调用。
2. **新增必须用 MyBatis-Plus 回填的自增主键发消息**。`Item` 上是 `@TableId(type = IdType.AUTO)`，`save(entity)` 之后 `entity.getId()` 才是真 id；前端 POST 过来的 `id` 是 0 或者瞎填的，拿它发消息，消费者回查必然查不到，消息在重试和错误队列之间反复横跳。
3. **索引库只存上架商品**。`status != 1` 或查不到 → 删文档，所以下架和删除是同一条代码路径。

### 多条件搜索（day09）

```bash
curl --noproxy '*' -G http://localhost:8086/search/items \
  --data-urlencode "keyword=华为手机" \
  --data-urlencode "brand=华为" --data-urlencode "brand=小米" \
  --data-urlencode "category=手机" \
  --data-urlencode "priceMin=100000" --data-urlencode "priceMax=300000" \
  --data-urlencode "sortType=2" --data-urlencode "pageNo=1" --data-urlencode "pageSize=4"
```

- **关键字进 `must`（要参与相关性打分），品牌/类目/价格进 `filter`（只筛选不打分，结果还能被 ES 缓存）**。同一个字段内多选是「或」（`termsQuery`），不同字段之间是「与」（多个 filter）。
- `sortType`：1 综合（相关性降序 + 价格升序兜底）、2 销量降、3 评论数降、4 价格升、5 价格降。凡按销量/评论数排都补一级价格排序，否则同值文档的翻页顺序会抖动，出现重复或漏项。
- 结果里带 `brands`/`categories` 两个桶聚合（`terms`，size 20），给前端筛选侧边栏用。聚合是「命中全集」的统计，不是「当前这一页」的，所以勾了品牌之后侧边栏会跟着收窄。
- `trackTotalHits(true)`：ES 默认最多统计到 10000 条，`华为手机` 真实命中 10385，不开这个总页数会算错。

### 实测

| 场景 | 结果 |
| --- | --- |
| 建索引 | `POST /search/_index` 后 `_mapping` 里 `name.analyzer = ik_max_word` 且带 `name.keyword` 子字段 |
| 全量导入对账 | `_count` = 88475 = MySQL `status=1` 的行数，9 个 `_bulk` 批次全部 `errors=false` |
| 下架 → 删文档 | `PUT /items/status/317578/2`，约 0.8s 后 `_doc/317578` 返回 `found:false`，消费者日志「写入 0 条，删除 1 条」，线程名 `ntContainer#0-1`（证明走的是 MQ 不是 HTTP） |
| 上架 → 补文档 | `PUT /items/status/317578/1`，文档回来，「写入 1 条，删除 0 条」 |
| 改名 → 更新文档 | `PUT /items` 改 name，索引里跟着变，用新词 `同步测试商品` 能搜到并且高亮命中 |
| 新增 → 建文档 | `POST /items` 不传 id，MP 回填 `100002672308`，消息里带的就是这个真 id，消费者回查 `Total: 1` 后写入文档 |
| 删除 → 删文档 | `DELETE /items/{id}`，消费者查不到商品 → 「写入 0 条，删除 1 条」 |
| 分词 + 高亮 + 分页 | `keyword=华为手机` 命中 10385 条，`<em>华为</em>…<em>手机</em>`，第 1 页和第 2 页返回的商品不重复 |
| 多条件过滤 | 品牌 `华为|小米` + 价格 1000~3000 元 + 按销量排，返回 3 条全部落在区间内、只有这两个品牌 |
| 桶聚合 | 无过滤时返回 20 个品牌 4 个类目；加品牌过滤后只剩 `['华为','小米']`、类目只剩 `['手机']` |
| 网关路由 | `GET :8080/search/items` 和直连 8086 结果一致，`/search/**` 在网关白名单里，不用登录 |

## 启动

MyBatis-Plus 3.4.3 在 JDK 21 上必须加 `--add-opens`，否则启动报错：

```bash
bash nacos-config/publish.sh                                  # 先把配置发布到 Nacos
mvn -B install -pl hm-common,hm-api -am -DskipTests
mvn -B compile -pl gateway,item-service,cart-service,user-service,pay-service,trade-service,search-service -am
java --add-opens java.base/java.lang.invoke=ALL-UNNAMED \
     -jar item-service/target/item-service.jar --spring.profiles.active=local
```

要跑下单链路还得先起 Seata TC 和 RabbitMQ 容器，再起 item / cart / trade，而且这三个服务的 `--add-opens` 要用上面 Seata 那一节的完整一组，只加 `java.lang.invoke` 会在 `GlobalTransactionScanner` 那里挂掉。

IDEA 里直接跑各模块的启动类也可以（VM options 加同上参数）。注册是否成功看 Nacos 控制台，或：

```bash
curl "http://localhost:8848/nacos/v1/ns/instance/list?serviceName=item-service"
```

（`/ns/service/list` 这个接口在本机上会返回 `count:0`，明明服务是健康的，按服务名查实例更靠谱。）

## 验证

```bash
mvn -B test -pl cart-service  -Dtest=ItemClientTest   -DargLine="--add-opens java.base/java.lang.invoke=ALL-UNNAMED"
mvn -B test -pl trade-service -Dtest=TradeClientTest  -DargLine="--add-opens java.base/java.lang.invoke=ALL-UNNAMED"
mvn -B test -pl pay-service   -Dtest=PayClientTest    -DargLine="--add-opens java.base/java.lang.invoke=ALL-UNNAMED"
mvn -B test -pl user-service  -Dtest=UserJwtToolTest  -DargLine="--add-opens java.base/java.lang.invoke=ALL-UNNAMED"
```

前三个跑之前要先把被调方启动起来（例如测 `TradeClientTest` 需要 item、cart 在跑）。测试用的都是无副作用入参：不存在的订单 id（UPDATE 影响 0 行）、错误支付密码（在第 1 步就抛错）。

## 已知问题

- **item-service 的异常响应格式和其它服务不一致**：它的启动类在 `com.hmall.item`，默认扫描漏掉了兄弟包 `com.hmall.common.advice`，业务异常返回的是 Spring 原生 `{"timestamp","status","error","path"}`，不是统一的 `{"code","msg","data"}`。补 `@ComponentScan("com.hmall")` 即可。
- **`pay → user 扣余额` 还是同步 Feign**，和支付单更新在同一个本地事务里，这块没问题。
- **「每下一单就往 MQ 塞一条 15~30 分钟的消息」这个方案本身有代价**：高并发时消息堆积给 MQ 压力大，且大多数订单 1 分钟内就付了却要在队列里白等几十分钟——更省资源的做法是定时任务扫表（课程 PPT 最后一页自己也指出了这点），本项目没做。
- **错误队列的消费者只把失败打成 ERROR 日志，没有自动重放**：消息一出队就没了，要自动补偿还得靠幂等的补偿任务或本地消息表。
- **下单清购物车不受 Seata 管辖**（day06 改成 MQ 之后）：只要消息发出去了，`createOrder` 就不会因为它失败而回滚，购物车消费不掉的话商品会留着，属于最终一致。
- **Sentinel 规则不持久化**，服务和控制台一重启就没了。
- **search-service 不能用 `hm-common` 的 `PageDTO` 当返回值**：那个类的静态工厂方法签名里带 MyBatis-Plus 的 `Page`，而 mybatis-plus 在 hm-common 里是 `provided` 依赖、不向下传递，搜索服务又不连数据库、类路径上根本没有 `Page`。Jackson 序列化前反射扫 `getDeclaredMethods()` 就会抛 `NoClassDefFoundError`，接口直接 500（`@JsonIgnore` 也救不了，异常发生在 JDK 反射阶段）。所以 search-service 用的是自己的 `PageVO`/`SearchResultVO`。同一个原因，`ItemClient` 里也刻意没放分页接口。
- **`from` + `size` 有深度分页上限**：`index.max_result_window` 默认 10000，翻到第 500 页（每页 20 条）之后 ES 会直接报错。正经解法是 `search_after`（要求排序、官方推荐）或 `scroll`（适合全量导出，不适合实时翻页），本项目没做，实际做法应该是限制可翻页数。
- **索引和 MySQL 是最终一致，不是强一致**：发消息和写库不在一个事务里。写完库消息发失败，索引就漏了 —— day07 那套「发送确认 + 失败重投 + 错误队列」能兜住大部分情况，真要保证不丢得上本地消息表或定时对账（比 `_count` 和 `count(1) where status=1`）。
- **搜索服务起来后不会因为 mapping 变化而重建索引**：`createIndexIfNotExist` 只在索引不存在时写 mapping，改了字段类型要手动 `DELETE /items` 再调 `POST /search/_index` 重新导入。
