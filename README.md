# hmall — 单体拆微服务练习

黑马 SpringCloud 课程 day03、day04、day05 的练手项目：把电商单体 `hm-service` 按业务边界拆成 5 个独立服务，用 Nacos 做服务注册与发现，服务间调用走 OpenFeign；配置交给 Nacos 统一管理（共享配置 + 热更新 + 网关动态路由）；cart-service 接入 Sentinel 做限流、线程隔离、fallback 和熔断；下单链路接入 Seata，XA 和 AT 两种模式的全局回滚都实测过。

## 模块

| 服务 | 端口 | 数据库 | 职责 |
| --- | --- | --- | --- |
| gateway | 8080 | — | 网关，路由转发 |
| item-service | 8081 | hm-item | 商品 |
| cart-service | 8082 | hm-cart | 购物车 |
| user-service | 8083 | hm-user | 用户、登录、余额 |
| pay-service | 8084 | hm-pay | 支付单 |
| trade-service | 8085 | hm-trade | 订单 |

`hm-common` 是公共模块（统一响应 `R`、异常、`UserContext`、MyBatis/Jackson 配置）；`hm-service` 是拆分前的单体，保留作对照。

## 服务间调用

```
cart  ──▶ item   （查商品）
trade ──▶ item   （查商品、扣库存）
trade ──▶ cart   （下单后清购物车）
pay   ──▶ user   （扣余额）
pay   ──▶ trade  （标记订单已支付）
```

调用方只写服务名（`@FeignClient(value = "item-service")`），地址由 Nacos 解析，没有硬编码 IP。

## 环境

- JDK 21（编译目标 Java 11）、Maven 3.9
- MySQL 8：容器 `sky-mysql`，导入 `resources/` 下的 `hm-item.sql`（数据量大，未入库，需从课件另取）、`hm-cart.sql`、`hm-user.sql`、`hm-pay.sql`、`hm-trade.sql`
- Nacos 2.1.0 standalone，元数据存 MySQL（建库脚本 `resources/nacos.sql`，配置见 `resources/nacos/custom.env`）
- Seata TC 1.5.2，事务状态也存 MySQL（建表脚本 `resources/seata-tc.sql`）；AT 模式还要给参与事务的业务库各建一张 `undo_log`（`resources/seata-at.sql`）。两个脚本都是课程资料里的原文件

## 配置

`application-local.yaml`、`hmall.jks`（JWT 签名私钥）和 `seata-server/application.yml`（TC 的 mysql 密码）都不入库。克隆后每个服务复制一份：

```bash
cp item-service/src/main/resources/application-local.example.yaml \
   item-service/src/main/resources/application-local.yaml
# 填 hm.db.host / hm.db.pw；user-service 还要填 hm.jwt.pw
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
| `cart-service.yaml` | cart-service 自己的业务配置（`hm.cart.maxItems`） |
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

## 启动

MyBatis-Plus 3.4.3 在 JDK 21 上必须加 `--add-opens`，否则启动报错：

```bash
bash nacos-config/publish.sh                                  # 先把配置发布到 Nacos
mvn -B install -pl hm-common -am -DskipTests
mvn -B compile -pl gateway,item-service,cart-service,user-service,pay-service,trade-service -am
java --add-opens java.base/java.lang.invoke=ALL-UNNAMED \
     -jar item-service/target/item-service.jar --spring.profiles.active=local
```

要跑下单链路还得先起 Seata TC，再起 item / cart / trade，而且这三个服务的 `--add-opens` 要用上面 Seata 那一节的完整一组，只加 `java.lang.invoke` 会在 `GlobalTransactionScanner` 那里挂掉。

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
- **分布式事务只覆盖了下单链路。** pay-service 的 `pay→user 扣余额`、`pay→trade 标记已支付` 还是本地 `@Transactional`，跨进程的部分同样回滚不了。按课程进度这块留到后面。
- **Sentinel 规则不持久化**，服务和控制台一重启就没了。
