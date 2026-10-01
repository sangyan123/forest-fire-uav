#!/usr/bin/env node
/**
 * 离线瓦片下载（D5 演示固化 / D5-hotfix 覆盖范围修正）
 * 用法: node scripts/fetch-tiles.mjs
 * 分层覆盖（zoom 越大范围越小，总块数与体积可控）：
 *   z13 29.85~30.45N, 113.85~114.45E  缩小时的城市/地形上下文（~200块）
 *   z14 30.00~30.35N, 114.00~114.30E  (~250块)
 *   z15 30.08~30.18N, 114.08~114.18E  演示区 (~100块)
 *   z16 30.10~30.16N, 114.10~114.16E  演示区细部 (~120块)
 *   z17 30.115~30.135N, 114.115~114.145E  核心火场 (~64块)
 * 瓦片源: Esri World_Imagery（实测可用；Carto 返回 2049 字节
 *        "api key required" 占位图——曾导致全量假瓦片事故，故拒绝 <3KB 文件）。
 * Esri URL 坐标顺序 {z}/{y}/{x} 与 Leaflet 布局 {z}/{x}/{y} 相反，下载时转换。
 * 遵守瓦片使用政策：User-Agent、串行 + 100ms 节流。地图须保留 Esri 署名。
 */
import { mkdirSync, writeFileSync, existsSync, statSync } from 'node:fs';
import { dirname } from 'node:path';

const OUT = new URL('../frontend/public/tiles/', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const UA = 'forest-fire-uav-demo/1.0 (offline demo tiles; one-time small batch)';

const TIERS = [
  { z: 13, latMin: 29.85, latMax: 30.45, lonMin: 113.85, lonMax: 114.45 },
  { z: 14, latMin: 30.0, latMax: 30.35, lonMin: 114.0, lonMax: 114.3 },
  { z: 15, latMin: 30.08, latMax: 30.18, lonMin: 114.08, lonMax: 114.18 },
  { z: 16, latMin: 30.08, latMax: 30.18, lonMin: 114.08, lonMax: 114.18 },
  { z: 17, latMin: 30.08, latMax: 30.18, lonMin: 114.08, lonMax: 114.18 },
];

const lon2tile = (lon, z) => Math.floor(((lon + 180) / 360) * 2 ** z);
const lat2tile = (lat, z) => {
  const rad = (lat * Math.PI) / 180;
  return Math.floor(((1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2) * 2 ** z);
};

let done = 0, skipped = 0, failed = 0;
const jobs = [];
for (const t of TIERS) {
  const x0 = lon2tile(t.lonMin, t.z), x1 = lon2tile(t.lonMax, t.z);
  const y0 = lat2tile(t.latMax, t.z), y1 = lat2tile(t.latMin, t.z);
  const count = (x1 - x0 + 1) * (y1 - y0 + 1);
  jobs.push(...Array.from({ length: count }, () => 0).map(() => ({ z: t.z })));
  console.log(`z${t.z}: x ${x0}~${x1}, y ${y0}~${y1} → ${count} 块`);
}
// 展开为具体坐标
const expanded = [];
for (const t of TIERS) {
  const x0 = lon2tile(t.lonMin, t.z), x1 = lon2tile(t.lonMax, t.z);
  const y0 = lat2tile(t.latMax, t.z), y1 = lat2tile(t.latMin, t.z);
  for (let x = x0; x <= x1; x++) for (let y = y0; y <= y1; y++) expanded.push({ z: t.z, x, y });
}
console.log(`待下载 ${expanded.length} 块瓦片 → ${OUT}`);

for (const { z, x, y } of expanded) {
  const file = `${OUT}${z}/${x}/${y}.jpg`;
  if (existsSync(file) && statSync(file).size > 1000) { skipped++; continue; }
  mkdirSync(dirname(file), { recursive: true });
  const url = `https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/${z}/${y}/${x}`;
  try {
    const res = await fetch(url, { headers: { 'User-Agent': UA } });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const buf = Buffer.from(await res.arrayBuffer());
    if (buf.length < 1000) throw new Error(`suspicious tile size ${buf.length}`); // Esri对无影像区返回~2.5KB均匀色块，属正常
    writeFileSync(file, buf);
    done++;
  } catch (e) {
    failed++;
    console.error(`❌ ${z}/${x}/${y}: ${e.message}`);
  }
  await new Promise(r => setTimeout(r, 80)); // 节流
  if ((done + skipped) % 100 === 0) console.log(`  进度 ${done + skipped}/${expanded.length}`);
}
console.log(`完成：新下载 ${done}，跳过已有 ${skipped}，失败 ${failed}`);
if (failed > 0) process.exit(1);
