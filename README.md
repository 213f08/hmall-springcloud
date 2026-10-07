# hmall — 单体拆微服务练习

黑马 SpringCloud 课程 day03、day04 的练手项目：把电商单体 `hm-service` 按业务边界拆成 5 个独立服务，用 Nacos 做服务注册与发现，服务间调用走 OpenFeign；配置交给 Nacos 统一管理（共享配置 + 热更新 + 网关动态路由）。

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

## 配置

`application-local.yaml` 和 `hmall.jks`（JWT 签名私钥）不入库。克隆后每个服务复制一份：

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

## 启动

MyBatis-Plus 3.4.3 在 JDK 21 上必须加 `--add-opens`，否则启动报错：

```bash
bash nacos-config/publish.sh                                  # 先把配置发布到 Nacos
mvn -B install -pl hm-common -am -DskipTests
mvn -B compile -pl gateway,item-service,cart-service,user-service,pay-service,trade-service -am
java --add-opens java.base/java.lang.invoke=ALL-UNNAMED \
     -jar item-service/target/item-service.jar --spring.profiles.active=local
```

IDEA 里直接跑各模块的启动类也可以（VM options 加同上参数）。注册是否成功看 Nacos 控制台，或：

```bash
curl "http://localhost:8848/nacos/v1/ns/service/list?pageNo=1&pageSize=20"
```

## 验证

```bash
mvn -B test -pl cart-service  -Dtest=ItemClientTest   -DargLine="--add-opens java.base/java.lang.invoke=ALL-UNNAMED"
mvn -B test -pl trade-service -Dtest=TradeClientTest  -DargLine="--add-opens java.base/java.lang.invoke=ALL-UNNAMED"
mvn -B test -pl pay-service   -Dtest=PayClientTest    -DargLine="--add-opens java.base/java.lang.invoke=ALL-UNNAMED"
mvn -B test -pl user-service  -Dtest=UserJwtToolTest  -DargLine="--add-opens java.base/java.lang.invoke=ALL-UNNAMED"
```

前三个跑之前要先把被调方启动起来（例如测 `TradeClientTest` 需要 item、cart 在跑）。测试用的都是无副作用入参：不存在的订单 id（UPDATE 影响 0 行）、错误支付密码（在第 1 步就抛错）。

## 已知问题

- **`@Transactional` 已经跨进程了。** `OrderServiceImpl.createOrder` 里的扣库存、清购物车是远程调用，本地事务回滚不了它们，需要 day05 的分布式事务方案。
- **item-service 的异常响应格式和其它服务不一致**：它的启动类在 `com.hmall.item`，默认扫描漏掉了兄弟包 `com.hmall.common.advice`，业务异常返回的是 Spring 原生 `{"timestamp","status","error","path"}`，不是统一的 `{"code","msg","data"}`。补 `@ComponentScan("com.hmall")` 即可。
