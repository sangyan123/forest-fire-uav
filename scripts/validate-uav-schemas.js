#!/usr/bin/env node
/**
 * UAV Unified Data JSON Schema 校验（本地与CI共用）
 * 用法: node scripts/validate-uav-schemas.js
 * 依赖: ajv, ajv-formats（CI中经 npm install 安装到临时目录）
 */
const path = require('path');
const fs = require('fs');

const Ajv = require('ajv');
const addFormats = require('ajv-formats');

const dir = path.resolve(__dirname, '..', 'docs', 'protocol', 'uav-json-schema');
const ajv = new Ajv({ strict: false, allErrors: true });
addFormats(ajv);

ajv.addSchema(JSON.parse(fs.readFileSync(path.join(dir, 'defs.schema.json'), 'utf8')));

const messages = [
  'uav-state', 'uav-heartbeat', 'uav-telemetry', 'uav-media', 'uav-capability',
  'uav-mission-state', 'uav-environment', 'uav-event', 'uav-command', 'uav-command-result'
];

let fail = 0;
for (const m of messages) {
  const schemaPath = path.join(dir, `${m}.schema.json`);
  const schema = JSON.parse(fs.readFileSync(schemaPath, 'utf8'));
  try {
    ajv.compile(schema);
  } catch (e) {
    console.error(`❌ 编译失败 ${m}: ${e.message}`);
    fail++;
    continue;
  }
  const examplePath = path.join(dir, 'examples', `${m}.json`);
  if (fs.existsSync(examplePath)) {
    const instance = JSON.parse(fs.readFileSync(examplePath, 'utf8'));
    if (!ajv.validate(schema, instance)) {
      console.error(`❌ 示例校验失败 ${m}: ${JSON.stringify(ajv.errors)}`);
      fail++;
      continue;
    }
    console.log(`✅ ${m}（编译 + 示例通过）`);
  } else {
    console.log(`✅ ${m}（编译通过）`);
  }
}

if (fail) {
  console.error(`⚠️ ${fail} 项失败`);
  process.exit(1);
}
console.log('✅ UAV JSON Schema 全部校验通过');
