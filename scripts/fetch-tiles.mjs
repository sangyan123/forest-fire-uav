#!/usr/bin/env node
/**
 * 离线瓦片下载（D5 演示固化）
 * 用法: node scripts/fetch-tiles.mjs
 * 下载演示区域（bbox 30.10~30.14N, 114.11~114.15E）zoom 13~17 的瓦片
 * 到 frontend/public/tiles/{z}/{x}/{y}.png，供现场断网时离线演示。
 * 瓦片源: Carto light_all（本机实测可达；OSM 官方源与国内镜像不可达）
 * 遵守瓦片使用政策：带 User-Agent、串行 + 100ms 节流、总量小（约365块）。
 */
import { mkdirSync, writeFileSync, existsSync, statSync } from 'node:fs';
import { dirname } from 'node:path';

const BBOX = { latMin: 30.10, latMax: 30.14, lonMin: 114.11, lonMax: 114.15 };
const ZOOMS = [13, 14, 15, 16, 17];
const OUT = new URL('../frontend/public/tiles/', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const UA = 'forest-fire-uav-demo/1.0 (offline demo tiles; one-time small batch)';

const lon2tile = (lon, z) => Math.floor(((lon + 180) / 360) * 2 ** z);
const lat2tile = (lat, z) => {
  const rad = (lat * Math.PI) / 180;
  return Math.floor(((1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2) * 2 ** z);
};

let done = 0, skipped = 0, failed = 0;
const jobs = [];
for (const z of ZOOMS) {
  const x0 = lon2tile(BBOX.lonMin, z), x1 = lon2tile(BBOX.lonMax, z);
  const y0 = lat2tile(BBOX.latMax, z), y1 = lat2tile(BBOX.latMin, z);
  for (let x = x0; x <= x1; x++) for (let y = y0; y <= y1; y++) jobs.push({ z, x, y });
}
console.log(`待下载 ${jobs.length} 块瓦片（zoom ${ZOOMS.join('/')}）→ ${OUT}`);

for (const { z, x, y } of jobs) {
  const file = `${OUT}${z}/${x}/${y}.png`;
  if (existsSync(file) && statSync(file).size > 100) { skipped++; continue; }
  mkdirSync(dirname(file), { recursive: true });
  const url = `https://basemaps.cartocdn.com/light_all/${z}/${x}/${y}.png`;
  try {
    const res = await fetch(url, { headers: { 'User-Agent': UA } });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const buf = Buffer.from(await res.arrayBuffer());
    writeFileSync(file, buf);
    done++;
  } catch (e) {
    failed++;
    console.error(`❌ ${z}/${x}/${y}: ${e.message}`);
  }
  await new Promise(r => setTimeout(r, 100)); // 节流
  if ((done + skipped) % 50 === 0) console.log(`  进度 ${done + skipped}/${jobs.length}`);
}
console.log(`完成：新下载 ${done}，跳过已有 ${skipped}，失败 ${failed}`);
if (failed > 0) process.exit(1);
