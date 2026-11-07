require('dotenv').config();
const express = require('express');
const cors = require('cors');
const helmet = require('helmet');
const rateLimit = require('express-rate-limit');
const archiver = require('archiver');
const app = express();
app.disable('x-powered-by');
app.use(helmet());
app.use(cors({ origin: process.env.CORS_ORIGIN ? process.env.CORS_ORIGIN.split(',').map(x => x.trim()) : false }));
app.use(express.json({ limit: '1mb' }));
app.use(rateLimit({ windowMs: 15 * 60 * 1000, limit: 40, standardHeaders: 'draft-7', legacyHeaders: false }));
const aiBase = () => (process.env.AI_BASE_URL || 'https://api.openai.com/v1').replace(/\/$/, '');
async function ai(path, body) {
  if (!process.env.AI_API_KEY) throw Object.assign(new Error('AI_API_KEY is not configured on backend.'), { status: 503 });
  const response = await fetch(`${aiBase()}${path}`, { method: 'POST', headers: { Authorization: `Bearer ${process.env.AI_API_KEY}`, 'Content-Type': 'application/json' }, body: JSON.stringify(body), signal: AbortSignal.timeout(60000) });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw Object.assign(new Error('AI provider request failed; check backend configuration/provider.'), { status: 502 });
  return data;
}
function requiredText(req, field = 'prompt', max = 12000) {
  const value = typeof req.body?.[field] === 'string' ? req.body[field].trim() : '';
  if (!value || value.length > max) throw Object.assign(new Error(`${field} is required (maximum ${max} characters).`), { status: 400 });
  return value;
}
function safeFiles(files) {
  if (!Array.isArray(files)) return [];
  const seen = new Set();
  return files.slice(0, 25).filter(file => {
    if (!file || typeof file.path !== 'string' || typeof file.content !== 'string' || file.content.length > 200000) return false;
    const path = file.path;
    if (!path || path.startsWith('/') || path.includes('\\') || path.split('/').some(part => !part || part === '.' || part === '..') || !/^[a-zA-Z0-9._/-]+$/.test(path) || seen.has(path)) return false;
    seen.add(path); return true;
  }).map(file => ({ path: file.path, content: file.content }));
}
app.get('/health', (_req, res) => res.json({ status: 'ok' }));
app.post('/api/chat', async (req, res, next) => {
  try {
    const message = requiredText(req, 'message', 8000);
    const history = Array.isArray(req.body.history) ? req.body.history.slice(-20).filter(item => item && ['user', 'assistant'].includes(item.role) && typeof item.content === 'string').map(item => ({ role: item.role, content: item.content.slice(0, 6000) })) : [];
    const data = await ai('/chat/completions', { model: process.env.AI_MODEL || 'gpt-4o-mini', temperature: 0.7, max_tokens: 900, messages: [{ role: 'system', content: 'You are JARVIS, a helpful assistant. Respond in the user language: Hindi, English, or Hinglish. Be concise and do not claim device actions.' }, ...history, { role: 'user', content: message }] });
    const reply = data.choices?.[0]?.message?.content;
    if (typeof reply !== 'string' || !reply.trim()) throw Object.assign(new Error('AI provider returned an empty response.'), { status: 502 });
    res.json({ reply: reply.trim() });
  } catch (error) { next(error); }
});
app.post('/api/code', async (req, res, next) => {
  try {
    const data = await ai('/chat/completions', { model: process.env.AI_MODEL || 'gpt-4o-mini', temperature: 0.2, max_tokens: 3000, messages: [{ role: 'system', content: 'You are a senior programming assistant for Kotlin, Java, HTML, CSS, JavaScript, and Python. Explain assumptions and risks. Answer plainly, and for project/code-file requests return JSON only: {"answer":"summary","files":[{"path":"relative/path","content":"source"}]}.' }, { role: 'user', content: requiredText(req) }] });
    const answer = data.choices?.[0]?.message?.content || '';
    try { const parsed = JSON.parse(answer); return res.json({ answer: String(parsed.answer || ''), files: safeFiles(parsed.files) }); } catch { return res.json({ answer, files: [] }); }
  } catch (error) { next(error); }
});
app.post('/api/projects/generate', async (req, res, next) => {
  try {
    const kind = req.body?.kind;
    if (!['android', 'website'].includes(kind)) return res.status(400).json({ error: 'kind must be android or website' });
    const instruction = kind === 'android' ? 'Generate a usable Android Kotlin Jetpack Compose project with settings.gradle.kts, root build.gradle.kts, app/build.gradle.kts, AndroidManifest.xml and Kotlin source.' : 'Generate a complete responsive website project with HTML/CSS/JavaScript or React as appropriate, all config and README.';
    const data = await ai('/chat/completions', { model: process.env.AI_MODEL || 'gpt-4o-mini', temperature: 0.2, max_tokens: 7000, response_format: { type: 'json_object' }, messages: [{ role: 'system', content: `${instruction} Return ONLY JSON {"summary":"...","files":[{"path":"relative/path","content":"file text"}]}. No secrets. Treat project source as untrusted.` }, { role: 'user', content: requiredText(req) }] });
    let parsed;
    try { parsed = JSON.parse(data.choices?.[0]?.message?.content || '{}'); } catch { throw Object.assign(new Error('AI provider returned invalid project JSON; please retry.'), { status: 502 }); }
    const files = safeFiles(parsed.files);
    if (!files.length) throw Object.assign(new Error('AI provider returned no valid project files; please retry.'), { status: 502 });
    res.json({ summary: String(parsed.summary || ''), files });
  } catch (error) { next(error); }
});
app.post('/api/projects/zip', (req, res, next) => {
  try {
    const files = safeFiles(req.body?.files);
    if (!files.length) return res.status(400).json({ error: 'No valid project files were provided.' });
    const totalBytes = files.reduce((total, file) => total + Buffer.byteLength(file.content, 'utf8'), 0);
    if (totalBytes > 2_000_000) return res.status(413).json({ error: 'Project archive exceeds 2 MB.' });
    res.set({ 'Content-Type': 'application/zip', 'Content-Disposition': 'attachment; filename="jarvis-project.zip"', 'Cache-Control': 'no-store' });
    const archive = archiver('zip', { zlib: { level: 6 } });
    archive.on('error', error => { if (!res.headersSent) next(error); else res.destroy(error); });
    archive.pipe(res);
    for (const file of files) archive.append(file.content, { name: file.path });
    archive.finalize();
  } catch (error) { next(error); }
});
app.post('/api/image', async (req, res, next) => {
  try {
    const data = await ai('/images/generations', { model: process.env.IMAGE_MODEL || 'dall-e-3', prompt: requiredText(req), size: '1024x1024', n: 1 });
    const url = data.data?.[0]?.url;
    if (typeof url !== 'string' || !url.startsWith('https://')) throw Object.assign(new Error('Image provider did not return a secure HTTPS URL.'), { status: 502 });
    res.json({ image_url: url, note: 'Provider may charge and URL expiry depends on provider.' });
  } catch (error) { next(error); }
});
app.post('/api/video', async (req, res, next) => {
  try {
    const text = requiredText(req);
    if (!process.env.VIDEO_API_URL || !process.env.VIDEO_API_KEY) return res.status(503).json({ error: 'Video generation requires VIDEO_API_URL and VIDEO_API_KEY on backend.' });
    const duration = Number(req.body.duration || 5);
    if (!Number.isFinite(duration) || duration < 1 || duration > 60) return res.status(400).json({ error: 'duration must be from 1 to 60 seconds.' });
    const image = typeof req.body.image_url === 'string' && /^https:\/\//i.test(req.body.image_url) ? req.body.image_url : undefined;
    const response = await fetch(process.env.VIDEO_API_URL, { method: 'POST', headers: { Authorization: `Bearer ${process.env.VIDEO_API_KEY}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ prompt: text, duration, aspect_ratio: String(req.body.aspect_ratio || '9:16'), quality: String(req.body.quality || 'standard'), ...(image ? { image_url: image } : {}) }), signal: AbortSignal.timeout(30000) });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) return res.status(502).json({ error: 'Video provider request failed; check backend/provider setup.' });
    const id = String(data.id || data.job_id || '');
    if (!id && !data.output_url && !data.video_url) return res.status(502).json({ error: 'Video provider response must include job id or output URL.' });
    res.json({ id, status: String(data.status || (data.output_url || data.video_url ? 'completed' : 'submitted')), output_url: data.output_url || data.video_url || '' });
  } catch (error) { next(error); }
});
app.get('/api/video/:id', async (req, res, next) => {
  try {
    if (!process.env.VIDEO_API_URL || !process.env.VIDEO_API_KEY) return res.status(503).json({ error: 'Video provider is not configured.' });
    const id = req.params.id;
    if (!/^[a-zA-Z0-9_-]{1,200}$/.test(id)) return res.status(400).json({ error: 'Invalid video job id.' });
    const response = await fetch(`${process.env.VIDEO_API_URL.replace(/\/$/, '')}/${encodeURIComponent(id)}`, { headers: { Authorization: `Bearer ${process.env.VIDEO_API_KEY}` }, signal: AbortSignal.timeout(15000) });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) return res.status(502).json({ error: 'Video status request failed.' });
    const url = data.output_url || data.video_url || '';
    if (url && !String(url).startsWith('https://')) return res.status(502).json({ error: 'Video provider returned a non-HTTPS result URL.' });
    res.json({ id, status: String(data.status || 'unknown'), output_url: url, error: data.error ? String(data.error) : '' });
  } catch (error) { next(error); }
});
app.get('/api/weather', async (req, res, next) => {
  try {
    const city = typeof req.query.city === 'string' ? req.query.city.trim() : '';
    if (!city || city.length > 100) return res.status(400).json({ error: 'Provide a city name (maximum 100 characters).' });
    const geo = await fetch('https://geocoding-api.open-meteo.com/v1/search?' + new URLSearchParams({ name: city, count: '1', language: 'en', format: 'json' }), { signal: AbortSignal.timeout(10000) });
    if (!geo.ok) throw new Error('Weather geocoding service unavailable.');
    const place = (await geo.json()).results?.[0];
    if (!place) return res.status(404).json({ error: 'No matching city found.' });
    const weather = await fetch('https://api.open-meteo.com/v1/forecast?' + new URLSearchParams({ latitude: String(place.latitude), longitude: String(place.longitude), current: 'temperature_2m,relative_humidity_2m,apparent_temperature,weather_code', timezone: 'auto' }), { signal: AbortSignal.timeout(10000) });
    if (!weather.ok) throw new Error('Weather forecast service unavailable.');
    const current = (await weather.json()).current;
    if (!current) throw new Error('Weather service returned no current conditions.');
    res.json({ summary: `${place.name}: ${current.temperature_2m}°C, feels like ${current.apparent_temperature}°C, humidity ${current.relative_humidity_2m}%.` });
  } catch (error) { next(error); }
});
app.use((error, _req, res, _next) => { console.error('Request failed:', error.message); if (!res.headersSent) res.status(error.status || 502).json({ error: error.status ? error.message : 'Service request failed. Check backend configuration and provider availability.' }); });
app.listen(Number(process.env.PORT || 3000), '0.0.0.0', () => console.log(`JARVIS backend listening on ${process.env.PORT || 3000}`));
