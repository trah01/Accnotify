import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const root = new URL('../', import.meta.url);
const version = readFileSync(new URL('VERSION', root), 'utf8').trim();
// 与 Android versionCode 的十进制分段保持一致。
if (!/^(0|[1-9]\d?)\.(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})$/.test(version) || version === '0.0.0') {
  throw new Error('VERSION 必须为 major.minor.patch：major 0–99，minor/patch 0–999，且不能为 0.0.0');
}
const check = process.argv.includes('--check');
let mismatch = false;
function update(path, transform) {
  const url = new URL(path, root);
  const old = readFileSync(url, 'utf8');
  const next = transform(old);
  if (old === next) return;
  if (check) {
    console.error(`版本未同步：${fileURLToPath(url)}`);
    mismatch = true;
  } else {
    writeFileSync(url, next);
  }
}
for (const path of ['desktop/package.json', 'desktop/package-lock.json', 'desktop/src-tauri/tauri.conf.json']) {
  update(path, text => {
    const data = JSON.parse(text);
    data.version = version;
    if (data.packages?.['']) data.packages[''].version = version;
    return JSON.stringify(data, null, 2) + '\n';
  });
}
update('desktop/src-tauri/Cargo.toml', text => text.replace(/(\[package\][\s\S]*?\nversion = ")[^"]+(")/, `$1${version}$2`));
update('desktop/src-tauri/Cargo.lock', text => text.replace(/(name = "accnotify-desktop"\nversion = ")[^"]+(")/, `$1${version}$2`));
if (mismatch) process.exit(1);
console.log(`客户端版本：${version}${check ? '（已同步）' : ''}`);
