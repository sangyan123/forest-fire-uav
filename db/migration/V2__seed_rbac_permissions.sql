-- =====================================================================
-- RBAC 权限种子数据（V2）
-- =====================================================================
-- 语义来源:  04号V1.2 第38节 + 00基线V1.3 第57~58章
--            （11项权限，主定义为07号，04号仅存储与引用）
-- 说明:      sys_permission.id 无 DEFAULT，使用 gen_random_uuid()（PG13+内置）；
--            permission_code 已 UNIQUE，ON CONFLICT 保证重复执行幂等。
-- =====================================================================

INSERT INTO sys_permission (id, permission_code, permission_name)
    VALUES (gen_random_uuid(), 'uav:read', 'UAV读取')
    ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO sys_permission (id, permission_code, permission_name)
    VALUES (gen_random_uuid(), 'uav:command', 'UAV控制命令')
    ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO sys_permission (id, permission_code, permission_name)
    VALUES (gen_random_uuid(), 'mission:read', '任务读取')
    ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO sys_permission (id, permission_code, permission_name)
    VALUES (gen_random_uuid(), 'mission:create', '任务创建')
    ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO sys_permission (id, permission_code, permission_name)
    VALUES (gen_random_uuid(), 'mission:cancel', '任务取消')
    ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO sys_permission (id, permission_code, permission_name)
    VALUES (gen_random_uuid(), 'fire:read', '火情读取')
    ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO sys_permission (id, permission_code, permission_name)
    VALUES (gen_random_uuid(), 'fire:confirm', '火情确认')
    ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO sys_permission (id, permission_code, permission_name)
    VALUES (gen_random_uuid(), 'algorithm:read', '算法读取')
    ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO sys_permission (id, permission_code, permission_name)
    VALUES (gen_random_uuid(), 'algorithm:manage', '算法管理')
    ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO sys_permission (id, permission_code, permission_name)
    VALUES (gen_random_uuid(), 'system:manage', '系统管理')
    ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO sys_permission (id, permission_code, permission_name)
    VALUES (gen_random_uuid(), 'audit:read', '审计读取')
    ON CONFLICT (permission_code) DO NOTHING;
