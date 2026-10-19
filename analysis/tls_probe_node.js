// 用 Node 原生 fetch（undici，与官方 CLI 同栈）发同一请求，验证 TLS 指纹假设
const fs = require('fs');

const cfg = JSON.parse(fs.readFileSync('/tmp/probe_payload.json', 'utf8'));

(async () => {
  try {
    const res = await fetch(cfg.url, {
      method: 'POST',
      headers: cfg.headers,
      body: JSON.stringify(cfg.body),
    });

    const text = await res.text();
    let verdict = `HTTP ${res.status}`;
    try {
      const d = JSON.parse(text);
      if (d.code) verdict += ` code=${d.code} msg=${d.msg}`;
      else verdict += ' -> PASS(有数据)';
    } catch (_) {
      verdict += ' -> ' + text.slice(0, 100).replace(/\n/g, ' ');
    }
    console.log('[4.node-undici]', verdict);
    if (res.status !== 200) {
      console.log('   body:', text.slice(0, 300));
    }
  } catch (e) {
    console.log('[4.node-undici] EXC', e.name, e.message);
  }
})();
