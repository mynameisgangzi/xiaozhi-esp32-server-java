alter table sys_device add column `type` varchar(50) DEFAULT NULL COMMENT '设备类型' after `version`;
alter table sys_code add column `type` varchar(50) DEFAULT NULL COMMENT '设备类型' after `email`;
