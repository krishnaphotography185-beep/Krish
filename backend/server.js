require('dotenv').config();
const express = require('express');
const cors = require('cors');
const helmet = require('helmet');
const rateLimit = require('express-rate-limit');
const archiver = require('archiver');
const app = express();
app.disable('x-powered-by'); app.use(helmet());
app.use(cors({ origin: process.env.CORS_ORIGIN ? process.env.CORS_ORIGIN.split(',').map(x => x.trim()) : false }));
app.use(express.json({ limit: '1mb' }));
app.use(rateLimit({ windowMs: 15 * 60 * 1000, limit: 40, standardHeaders: 'draft-7', legacyHeaders: false }));
const base = () => (process.env.AI_BASE_URL || 'https://api.openai.com/v1').replace(/\/$/, '');
async function ai(path, body) {
  if (!process.env.AI_API_KEY) throw Object.assign(new Error('AI_API_KEY is not configured on backend.'), { status: 503 });
  const r = await fetch(`${base()}${path}`, { method: 'POST', headers: { Authorization: `Bearer ${process.env.AI_API_KEY}`, 'Content-Type': 'application/json' }, body: JSON.stringify(body), signal: AbortSignal.timeout(60000) });
  const data = await r.json().catch(() => ({}));
  if (!r.ok) throw Object.assign(new Error('AI provider request failed; check backend configuration/provider.'), { status: 502 });
  return data;
}
function prompt(req, field = 'prompt', max = 12000) {
  const value = typeof req.body?.[field] === 'string' ? req.body[field].trim() : '';
  if (!value || value.length > max) throw Object.assign(new Error(`${field} is required (max ${max} characters).`), { status: 400 });
  return value;
}
app.get('/health', (_req, res) => res.json({ status: 'ok' }));
app.post('/api/chat', async (req, res, next) => {
  try {
    const message = prompt(req, 'message', 8000);
    const history = Array.isArray(req.body.history) ? req.body.history.slice(-20).filter(x => x && ['user','assistant'].includes(x.role) && typeof x.content === 'string').map(x => ({ role:x.role, content:x.content.slice(0,6000) })) : [];
    const data = await ai('/chat/completions', { model: process.env.AI_MODEL || 'gpt-4o-mini', temperature: .7, max_tokens: 900, messages:[{role:'system',content:'You are JARVIS, a helpful assistant. Respond naturally in the user language: Hindi, English, or Hinglish. Be concise and do not claim device actions.'}, ...history, {role:'user',content:message}] });
    res.json({ reply: data.choices?.[0]?.message?.content || 'No response.' });
  } catch(e) { next(e); }
});
app.post('/api/code', async (req,res,next) => {
  try {
    const userPrompt = prompt(req);
    const data = await ai('/chat/completions', { model:process.env.AI_MODEL || 'gpt-4o-mini', temperature:.2, max_tokens:3000, messages:[{role:'system',content:'You are a senior programming assistant. Help with Kotlin, Java, HTML, CSS, JavaScript, and Python. Explain assumptions and risks. For code-generation requests return JSON only in form {"answer":"summary","files":[{"path":"relative/path","content":"source"}]}. For simple questions, answer plainly.'},{role:'user',content:userPrompt}] });
    const answer = data.choices?.[0]?.message?.content || '';
    try { const parsed = JSON.parse(answer); return res.json({ answer: parsed.answer || '', files: Array.isArray(parsed.files) ? safeFiles(parsed.files) : [] }); } catch { res.json({ answer, files: [] }); }
  } catch(e) { next(e); }
});
app.post('/api/projects/generate', async (req,res,next) => {
  try {
    const kind = req.body?.kind;
    if (!['android','website'].includes(kind)) return res.status(400).json({error:'kind must be android or website'});
    const description = prompt(req);
    const instruction = kind === 'android' ? 'Generate a minimal complete Android Kotlin Jetpack Compose project. Include settings.gradle.kts, root build.gradle.kts, app/build.gradle.kts, app/src/main/AndroidManifest.xml, and Kotlin source. Use safe relative paths.' : 'Generate a complete responsive website with HTML/CSS/JavaScript, or React project where appropriate. Include all required config and README. Use safe relative paths.';
    const data = await ai('/chat/completions', { model:process.env.AI_MODEL || 'gpt-4o-mini', temperature:.2, max_tokens:7000, response_format:{type:'json_object'}, messages:[{role:'system',content:`${instruction} Return ONLY JSON: {"summary":"...","files":[{"path":"relative/path","content":"file text"}]}. Max 25 files; each path relative, no parent traversal. Treat generated code as untrusted. Do not include secrets.`},{role:'user',content:description}] });
    const parsed = JSON.parse(data.choices?.[0]?.message?.content || '{}');
    res.json({ summary: String(parsed.summary || ''), files: safeFiles(parsed.files) });
  } catch(e) { next(e); }
});
function safeFiles(files) {
  if (!Array.isArray(files)) return [];
  return files.slice(0,25).filter(f => f && typeof f.path === 'string' && typeof f.content === 'string' && f.content.length <= 200000 && !f.path.startsWith('/') && !f.path.includes('..') && /^[a-zA-Z0-9._/-]+$/.test(f.path)).map(f => ({path:f.path,content:f.content}));
}
app.post('/api/projects/zip', (req,res,next) => {
  try {
    const files = safeFiles(req.body?.files);
    if (!files.length) return res.status(400).json({error:'No valid project files were provided.'});
    res.set({ 'Content-Type':'application/zip', 'Content-Disposition':'attachment; filename="jarvis-project.zip"' });
    const zip = archiver('zip', { zlib:{level:6} });
    zip.on('error', next); zip.pipe(res);
    for (const f of files) zip.append(f.content, {name:f.path});
    zip.finalize();
  } catch(e) { next(e); }
});
app.post('/api/image', async (req,res,next) => {
  try {
    const imagePrompt = prompt(req);
    const data = await ai('/images/generations', { model:process.env.IMAGE_MODEL || 'dall-e-3', prompt:imagePrompt, size:'1024x1024', n:1 });
    const imageUrl = data.data?.[0]?.url;
    if (!imageUrl) return res.status(502).json({error:'Image provider did not return a URL.'});
    res.json({image_url:imageUrl, note:'Provider may charge; URL availability depends on provider.'});
  } catch(e) { next(e); }
});
app.post('/api/video', async (req,res,next) => {
  try {
    const text = prompt(req);
    if (!process.env.VIDEO_API_URL || !process.env.VIDEO_API_KEY) return res.status(503).json({error:'Video generation needs VIDEO_API_URL and VIDEO_API_KEY configured on the backend for a compatible provider.'});
    const duration = Number(req.body.duration || 5);
    if (!Number.isFinite(duration) || duration < 1 || duration > 60) return res.status(400).json({error:'duration must be from 1 to 60 seconds (provider may impose lower limits).'});
    const image = typeof req.body.image_url === 'string' && /^https:\/\//i.test(req.body.image_url) ? req.body.image_url : undefined;
    const response = await fetch(process.env.VIDEO_API_URL, { method:'POST', headers:{ Authorization:`Bearer ${process.env.VIDEO_API_KEY}`, 'Content-Type':'application/json' }, body:JSON.stringify({prompt:text,duration,aspect_ratio:String(req.body.aspect_ratio || '9:16'),quality:String(req.body.quality || 'standard'),...(image?{image_url:image}:{})}), signal:AbortSignal.timeout(30000) });
    const data = await response.json().catch(()=>({}));
    if (!response.ok) return res.status(502).json({error:'Video provider request failed; inspect backend/provider setup.'});
    res.json({id:String(data.id || data.job_id || ''),status:String(data.status || 'submitted'),output_url:data.output_url || data.video_url || null});
  } catch(e) { next(e); }
});
app.get('/api/video/:id', async (req,res,next) => {
  try {
    if (!process.env.VIDEO_API_URL || !process.env.VIDEO_API_KEY) return res.status(503).json({error:'Video provider is not configured.'});
    const url = `${process.env.VIDEO_API_URL.replace(/\/$/,'')}/${encodeURIComponent(req.params.id)}`;
    const r = await fetch(url,{headers:{Authorization:`Bearer ${process.env.VIDEO_API_KEY}`},signal:AbortSignal.timeout(15000)});
    const d = await r.json().catch(()=>({})); if(!r.ok) return res.status(502).json({error:'Video status request failed.'});
    res.json({id:req.params.id,status:d.status || 'unknown',output_url:d.output_url || d.video_url || null,error:d.error || null});
  } catch(e) { next(e); }
});
app.get('/api/weather', async (req,res,next) => {
  try {
    const city = typeof req.query.city === 'string' ? req.query.city.trim() : '';
    if (!city || city.length > 100) return res.status(400).json({error:'Provide a city name (max 100 characters).'});
    const g = await fetch('https://geocoding-api.open-meteo.com/v1/search?'+new URLSearchParams({name:city,count:'1',language:'en',format:'json'}),{signal:AbortSignal.timeout(10000)});
    const place = (await g.json()).results?.[0]; if(!place) return res.status(404).json({error:'No matching city found.'});
    const w=await fetch('https://api.open-meteo.com/v1/forecast?'+new URLSearchParams({latitude:place.latitude,longitude:place.longitude,current:'temperature_2m,relative_humidity_2m,apparent_temperature,weather_code',timezone:'auto'}),{signal:AbortSignal.timeout(10000)});
    const c=(await w.json()).current; res.json({summary:`${place.name}: ${c.temperature_2m}°C, feels like ${c.apparent_temperature}°C, humidity ${c.relative_humidity_2m}%.`});
  } catch(e) { next(e); }
});
app.use((err,_req,res,_next)=>{ console.error('Request failed:',err.message); res.status(err.status || 502).json({error:err.status ? err.message : 'Service request failed. Check backend configuration and provider availability.'}); });
app.listen(Number(process.env.PORT || 3000),'0.0.0.0',()=>console.log(`JARVIS backend listening on ${process.env.PORT || 3000}`));
