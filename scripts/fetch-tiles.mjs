#!/usr/bin/env node
/**
 * 离线瓦片下载（D5 演示固化）
 * 用法: node scripts/fetch-tiles.mjs
 * 下载演示区域（bbox 30.10~30.14N, 114.11~114.15E）zoom 13~17 的卫星影像瓦片
 * 到 frontend/public/tiles/{z}/{x}/{y}.jpg，供现场断网时离线演示。
 * 瓦片源: Esri World_Imagery（实测可用；Carto/OSM官方源本机不可用或返回
 *        2049字节的"api key required"占位图——曾导致全量假瓦片事故）。
 * 注意 Esri 的 URL 瓦片坐标顺序是 {z}/{y}/{x}（与 Leaflet 的 {z}/{x}/{y} 相反），
 * 下载时转换、存储仍用 Leaflet 标准布局 {z}/{x}/{y}。
 * 遵守瓦片使用政策：带 User-Agent、串行 + 100ms 节流、总量小（约365块）。
 * 地图上须保留 Esri 署名（MapView.vue attribution）。
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
  const file = `${OUT}${z}/${x}/${y}.jpg`;
  if (existsSync(file) && statSync(file).size > 3000) { skipped++; continue; }
  mkdirSync(dirname(file), { recursive: true });
  // Esri URL 坐标顺序为 z/y/x（与 Leaflet 布局相反），此处转换
  const url = `https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/${z}/${y}/${x}`;
  try {
    const res = await fetch(url, { headers: { 'User-Agent': UA } });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const buf = Buffer.from(await res.arrayBuffer());
    if (buf.length < 3000) throw new Error(`suspicious tile size ${buf.length}`);
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
