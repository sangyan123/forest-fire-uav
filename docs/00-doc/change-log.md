# 变更与问题登记（change-log）

**归属：** 00号基线治理（V1.3 第6章规划文件，第71/76/77章冲突与Gate流程）
**创建：** 2026-09-27

------

## 一、基线与文档变更历史

| 日期 | 变更 | 说明 |
| --- | --- | --- |
| 2026-09-27 | 00基线 V1.0→V1.3 迭代 | 三轮修订收敛：优先级/阶段/枚举/参数/指标/认证/DEMO/监控/资源全部冻结；V1.0~V1.2 归档至 docs/00-doc/archive/，V1.3 生效于 docs/00-doc/active/ |
| 2026-09-27 | 01~11号文档按V1.3第73章回写 | 01/03~10 升 V1.1，02/11 升 V2.1；优先级矩阵、F09拆分、MissionType六项、高度语义、命令集、电量四级、F01/F04参数、F01~F12指标、Auth/RBAC、DEMO、监控栈、CI/CD、资源基线、Phase 0~9 全文对齐 |
| 2026-09-27 | 遗留缺陷修复轮 | 04号升V1.2（fire_track保留字列名timestamp→event_time、fire_point补location_method、新增risk_area_assessment历史版本表）；05号升V1.2（flight.status补ARMED/MISSION_EXECUTING并对齐状态机、补齐UAV_TELEMETRY/CAPABILITY/ENVIRONMENT载荷定义）；07号升V1.2（错误码40002/40401语义界定、Kafka/S3接口边界、RBAC角色-权限映射矩阵V1默认）；02号升V2.2（正文V2.0自引用清理）；另补修01号F12优先级P2→P1、11号第34章Mission列表改冻结六项、05号四处旧媒体类型值、03号统一状态JSON旧高度字段 |
| 2026-09-27 | 第③步：建立 enums.yaml（enums-v1.0） | docs/00-doc/enums.yaml 落地，12个枚举85个值：基线第70章必含9个（MissionType/CommandType/MediaType/IncidentStatus/VerificationStatus/UAVStatus/LocationMethod/BatteryState/AlgorithmTaskStatus）+ 补充3个（DeviceStatus/MissionStatus/CommandStatus，均取自owner文档既有定义）；js-yaml解析通过、值与owner文档逐字核对通过；AlgorithmTaskStatus标注CANDIDATE（唯一无显式文档值集，按04号表结构与06号第37章推导，待04号下轮修订冻结）；数值阈值一律引用constants.yaml不在本文件重复 |
| 2026-09-27 | 第④步：建立 constants.yaml（constants-v1.0） | docs/00-doc/constants.yaml 落地，5个参数段（battery_thresholds四级的30/25/20/10、fire_detection的0.40/0.60/0.80+5帧3中、verification权重0.35/0.35/0.15/0.15与决策线0.80/0.50、fire_dedup的100m/120s+类别一致、auth_token_lifetime过渡基线30min/7d）+7个pointers（指标→baseline-metrics.yaml、F07权重→risk-v1.yaml挂ISSUE-001、F08/F09权重/协议频率/Mock仿真→06与05号、资源→10号）；F04权重和校验=1；全部数值与03/05/06/07/08号文档及基线对应章节一致 |
| 2026-09-27 | 第⑤步：建立 baseline-metrics.yaml（algorithm-metrics-v1.0） | docs/00-doc/baseline-metrics.yaml 落地：F01~F04正式目标14项（基线第41章）+ F05~F12候选目标26项（基线第42~50章，继承V1.0冻结值），共12功能40项指标；dataset_policy组级拆分（70/15/15，按航次/场景/时间段/地理区域，禁帧级打散）；转正流程（06方案+08方案+数据集版本+实验结果+评审）；旧版本不得删除（algorithm-metrics-v1.x序列）；系统性能基线（API P95≤500ms等）按基线第4章归08号Test Config仅留指针；js-yaml解析通过、指标值与06/08号文档抽查一致 |
| 2026-09-27 | 第⑥步：建立 docs/api/openapi.yaml（openapi-v1.0，OpenAPI 3.0.3） | 以07号V1.2为唯一语义来源：34路径/37操作（Auth四接口+UAV管理+Fire检测/事件/火点/核验+Mission CRUD与四动作+Dispatch+Command九命令+AI六接口/ai/v1）；Bearer JWT全局安全、登录/刷新豁免；统一Envelope{code,message,data,requestId}与14值ApiCode枚举（40002/40401语义界定写入描述）；17个枚举schema与enums.yaml值数逐一核对一致（含MediaType补齐）；TargetPoint强制altitude+altitudeMode；WS端点/MQTT主题/核心闭环链记入info.description；校验通过（64引用零悬空、responses齐全、路径参数经components声明）；另修正07号第10章示例gpsStatus FIXED→3D_FIX（对齐05号owner枚举） |
| 2026-09-27 | 第⑦步：建立 docs/protocol/uav-json-schema/（uav-json-schema-v1.0） | defs.schema.json（Header/13子模型/17枚举/载荷定义）+10个消息schema（UAV_STATE扁平13子模型、HEARTBEAT、TELEMETRY六子模型、MEDIA、CAPABILITY、MISSION_STATE、ENVIRONMENT、EVENT 17类型、COMMAND九命令、COMMAND_RESULT）+README+5个示例实例（取自05号文档示例）；draft-07；ajv编译10/10通过、示例校验5/5通过；顺带修正05号两处gpsStatus"FIX"→3D_FIX（对齐自家枚举）、07号第25章UAV_STATE示例payload包裹改为05号扁平结构；新发现并登记ISSUE-004：设备侧DeviceCommandStatus（05号42章9值）与平台侧CommandStatus（07/09号8值）两套命令状态并存，已双枚举入enums.yaml，映射归一待Phase 2协议打通时评审 |

| 2026-09-28 | 第⑧步：建立 db/migration/（migration V1/V2） | V1__baseline_schema.sql（859行：CREATE EXTENSION postgis + 34张表按04号V1.2文档顺序 + 8个索引，同名索引去重）+ V2__seed_rbac_permissions.sql（11项权限种子，ON CONFLICT幂等）+ README；由04号DDL程序化提取组装（零手抄）；校验：43条语句全部通过node-sql-parser PostgreSQL语法解析、括号/分号零异常、表清单与04号冻结34表逐一比对无缺无余、回写字段落位确认（fire_track.event_time、fire_point.location_method、algorithm_task.config/dataset_version、risk_area_assessment）；文档既定但未定稿事项如实注明（逻辑外键、UUID应用侧生成、telemetry分区待运维评审V3+） |

| 2026-09-28 | 第⑨~⑫步：建立 Algorithm/Auth/RBAC Config 与 branch-policy，核对11号引用 | ⑨ config/algorithm/ 五文件：fire-detection-v1（0.40/0.60/0.80+5帧3中）、verification-v1（0.35/0.35/0.15/0.15+0.80/0.50）、uav-safety-v1（电量四级+Vendor Profile规则+命令权限）均ACTIVE镜像constants.yaml；tracking-v1（PENDING_PARAMETERS）、risk-v1（PENDING_ISSUE-001，七因子骨架weight:null零发明）；⑩ config/auth/auth-v1.yaml：Token 30min/7d唯一拥有者（constants.yaml#auth_token_lifetime同步标记migrated_to_auth_config）、六角色11权限+role_permissions矩阵（与07号§35.7逐项比对一致）；⑪ docs/00-doc/branch-policy.yaml（main禁直接Push、PR+Review+CI、GitHub Branch Ruleset执行、分支模型与CI流水线）；⑫ 核对11号V2.1引用（branch-policy.yaml×3、Gate0×2，config路径按第73章范围无需引用）——一致，无需再改 |

| 2026-09-28 | Phase 0：项目初始化完成（第⑬步） | git init（main分支，根提交55文件/38323行）；.gitignore/README/CONTRIBUTING（分支与配置变更纪律）；.github/workflows/ci.yml（baseline-validation校验job即时生效 + backend/ai/frontend/docker-build按文件存在自动激活）与release.yml（tag触发骨架）；scripts/validate-uav-schemas.js（本地与CI共用）；deploy/docker-compose.yml（DEV五中间件：postgis16-3.4/redis7/kafka3.7 KRaft/mosquitto2/minio，postgres首次启动自动执行db/migration，MQTT仅DEV匿名）+ mosquitto.conf；docker compose config校验通过，栈已启动（镜像拉取后台进行）；Gate 17项中"CI基础建立/DEV环境建立"落地（DEMO环境与Monitoring基础待Phase 1/9B）；待办：推送GitHub后按branch-policy.yaml配置Branch Ruleset |

| 2026-09-28 | Phase 0 验收通过 | DEV五中间件全部UP（postgis16-3.4/redis7/bitnami-kafka3.7/mosquitto2/minio，postgres/redis/minio healthy）；数据库首次初始化自动执行migration：37表（34项目表+PostGIS系统表）、PostGIS 3.4.3、sys_permission 11条种子、fire_point.location_method+geometry列落位；Kafka started。备注：Bitnami命名空间被主流镜像站限流（多个加速器denied，最终拉取成功）；redis复用本地缓存。Phase 0 关账，Gate 17项剩4项（DEMO环境/Monitoring基础随Phase 1/9B落地） |

| 2026-09-28 | D1：Phase 3 火情业务闭环落地（排期提前一天完成） | backend新增26文件（media/fire/mission/dispatch四包，10端点，7表实体@Column逐字对齐+hibernate-spatial，火情去重100m/120s两层筛选实测28检测合并1事件）；ai-service升级mock provider（detection/localization/verification/thermal/segmentation/tracking，AI_PROVIDER双轨）；gateway新增uav/+/media订阅转发；mock-uav新增火情场景（fire/start→15m/s转场→到位每2s回传媒体4RGB+1THERMAL循环、CAPTURE命令复拍、fire/stop恢复巡航，媒体消息经官方uav-media.schema.json校验）；frontend火情演示界面（注入主按钮/火点脉冲marker按8态着色/事件卡/核验派单流转/2s列表轮询，npm build通过）；**D1端到端验收通过**：注入→转场~50s→自动发现INC-20260928-0001(SUSPECTED,0.93)→核验CONFIRMED(0.904精确命中冻结权重公式)→media_file 35/fire_detection 28/fire_point 28/fire_incident 1/fire_verification 1全落位 |

| 2026-09-28 | D2：Phase 4 收尾——误报演示线与状态流转打磨 | mock-uav 场景body增加verdict(CONFIRMED/FALSE_ALARM)，媒体内嵌metadata.scenarioType提示（21项断言，含schema校验）；backend media-ingest解析提示落incident.extra，核验时body无evidence则按场景派生（FIRE→0.9/0.92/0.88/0.9，FALSE_ALARM→0.30/0.22/0.40/0.30，阈值判定仍交AI Service），body显式传证据优先（D1行为保留）；frontend 顶栏拆双按钮（🔥火情/⚠️误报）、误报徽标、核验toast三分支文案、marker状态变化闪烁动画1.2s、活跃态优先排序、事件卡状态时间线（会话内记录）；**D2端到端验收**：火情线CONFIRMED(0.904)与误报线FALSE_ALARM(0.287)双线实测通过，两个事件并存在地图（INC-0001 FIRE/CONFIRMED、INC-0002 FALSE_ALARM/FALSE_ALARM），前端200 |

| 2026-09-28 | D3：火场分析可视化（F05 扩散多边形 + F06 趋势面板） | ai-service 分割mock支持growthStep（150m→240m→330m每轮+90m，面积πr²）；backend新增7文件（FirePolygon/FireTrack实体+FireAnalysisService+GeoUtils扩展，3端点：POST analysis仅CONFIRMED/TRACKING放行、GET polygons升序GeoJSON、GET tracking最新趋势；fire_polygon.polygon按DDL实际为MultiPolygon写入，radiusMeters由面积派生）；frontend 火场分析按钮+扩散年轮叠加（透明度梯度0.08起每代+0.08最新0.32红描边）+poly-new扩散动画+趋势面板（趋势/方向罗盘/速度/面积增长率/轮次），40条单测+24步链路PASS；**D3端到端验收**：CONFIRMED事件两轮分析半径150→240m面积70686→180956m²、polygons两条升序、tracking EXPANDING/63.2°/1.8m/s/0.16、前端bundle含D3代码 |

------

## 二、未决 Issue（按基线第71章流程登记）

### ISSUE-001 F07 风险权重缺 human_activity 因子

- **位置：** 06号文档 16.3/16.8 节
- **现象：** 现有 AHP 六因子（Historical 0.20、Temperature 0.20、Humidity 0.15、Wind 0.15、Vegetation 0.15、Terrain 0.15）合计恰好 1.00，但对照基线模板七因子缺 human_activity；因子命名映射（Wind→wind_speed、Historical→historical_fire）待确认。
- **处置约束：** 基线第31/32章明令禁止为填满配置发明权重数值。
- **解决路径：** 按基线第32章实验协议（Experiment ID + Dataset Version + 评审）确定七因子权重，或经 06号文档+实验结果+技术评审 共同批准维持六因子结构。
- **阻塞项：** 第78章第⑨步 Algorithm Config（config/algorithm/risk-v1.yaml）落地前必须关闭。
- **状态：** OPEN（等待实验数据，非文档编辑可解）

### ISSUE-002 开发实施文档缺工期/人力/里程碑估算 —— CLOSED

- **位置：** 11号文档（65章仅有方向性分工）
- **现象：** 全文无工期周数、人数配置、里程碑日期。
- **处置约束：** 属项目决策，不能由文档编写者发明。
- **解决路径：** 由项目负责人/PM 提供排期与人力决策后回写11号，并同步Phase计划的Gate时间点。
- **阻塞项：** 不阻塞第③~⑦步（机器来源文件建立）；阻塞 Phase 0 正式开工前的完整计划评审。
- **状态：** **CLOSED（2026-09-28）**——负责人决策：当前单人全职开发，D0出可运行Demo，MVP一周内交付，后续优化阶段引入其他成员。已落11号V2.2第89章排期计划（含MVP简化偏差清单与后置项）。

### ISSUE-003 运维指标与部署方式张力

- **位置：** 10号文档（第59章灰度发布、第21章 Backend 3实例/LB、第7章初期单机Compose、第47~49章 RPO 24h/RTO 4h）
- **现象：** 灰度发布与多实例LB以初期单机Compose为前提存在张力；RPO 24h/RTO 4h 对"确认火情=CRITICAL"级业务偏宽松。
- **处置约束：** 属运维策略决策。
- **解决路径：** Phase 9B 生产部署前由运维评审定稿（明确单机期不启用灰度/多实例，或提前引入第二节点）；RPO/RTO 按业务方接受度修订。
- **阻塞项：** 不阻塞第③~⑧步与MVP；阻塞 Phase 9B 生产部署评审。
- **状态：** OPEN（等待运维/业务评审）

### ISSUE-004 设备侧与平台侧命令状态两套口径

- **位置：** 05号第41~42章（DeviceCommandStatus 9值：RECEIVED/VALIDATING/ACCEPTED/EXECUTING/SUCCESS/FAILED/TIMEOUT/REJECTED/CANCELLED）与 07号第31章/09号（CommandStatus 8值：CREATED/SENT/ACKNOWLEDGED/EXECUTING/SUCCESS/TIMEOUT/FAILED/INTERRUPTED）
- **现象：** 第⑦步建JSON Schema时发现：MQTT UAV_COMMAND_RESULT.status 使用05号设备侧9值，而REST命令记录使用07/09平台侧8值，两者粒度不同且无正式映射（如 REJECTED↔FAILED、CANCELLED↔INTERRUPTED 的对应未定义）。
- **处置：** 已按"两个层面并存"处理——双枚举均入 enums.yaml（DeviceCommandStatus owner=05；CommandStatus owner=07/09），schema 按各自口径定义。
- **解决路径：** Phase 2（UAV统一协议打通）时由 05+07+09 共同评审映射表（Gateway 翻译设备上报到平台状态），或将两套归一为一套。
- **阻塞项：** 不阻塞 Phase 0~1；Phase 2 Mock UAV↔Gateway↔Backend 双向通信验收前必须落地映射表。
- **状态：** OPEN（等待Phase 2评审）

------

## 三、登记规则

新 Issue 在此处登记；关闭时将状态改为 CLOSED 并注明解决变更条目。字段/枚举/API/参数类变更必须同步基线第71章流程（更新主定义→更新机器来源→同步引用文档→更新代码与测试）。
