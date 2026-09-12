const assert = require('node:assert/strict');
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const vm = require('node:vm');
const http = require('node:http');
const { spawn, spawnSync } = require('node:child_process');
const { createRequire } = require('node:module');
const root = path.resolve(__dirname, '..');
const ffmpeg = path.join(root, 'vendor/ffmpeg/ffmpeg.exe');
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'dylandos-dvr-test-'));
const fixture = path.join(temp, 'fixture.ts');
const gen = spawnSync(ffmpeg, ['-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i',
  'testsrc=size=160x90:rate=15', '-f', 'lavfi', '-i', 'sine=frequency=440',
  '-t', '4', '-c:v', 'mpeg2video', '-c:a', 'mp2', '-f', 'mpegts', fixture], { windowsHide: true });
assert.equal(gen.status, 0, String(gen.stderr));
const hls = spawnSync(ffmpeg, ['-v', 'error', '-i', fixture, '-c', 'copy', '-hls_time', '1', '-hls_list_size', '0', path.join(temp, 'playlist.m3u8')], { windowsHide: true });
assert.equal(hls.status, 0, String(hls.stderr));
const source = fs.readFileSync(path.join(root, 'electron/main.cjs'), 'utf8');
const libraryCode = source.slice(source.indexOf('let libraryQueue ='), source.indexOf('async function ensureDataDir()'));
const recordingSource = process.argv.includes('--baseline')
  ? spawnSync('git', ['show', 'HEAD:electron/main.cjs'], { cwd: root, encoding: 'utf8' }).stdout : source;
const recordingCode = recordingSource.slice(recordingSource.indexOf('  async function startRecording('), recordingSource.indexOf("  ipcMain.handle('dvr:start'"));
const events = [];
const sandbox = { require: createRequire(path.join(root, 'electron/main.cjs')), fs: fsp, fsSync: fs, path,
  console, Buffer, process, setInterval, clearInterval, setTimeout, clearTimeout, spawn,
  DATA_DIR: temp, DVR_LIBRARY_FILE: 'library.json', recordings: new Map(),
  MAX_CONCURRENT_RECORDINGS: 3, appSettings: {}, getDvrDir: () => temp,
  getFFmpegPath: () => ffmpeg, ensureDataDir: async () => {},
  sanitizeFileName: s => s.replace(/[^a-zA-Z0-9 -]/g, '_'),
  win: { webContents: { send: (name, data) => events.push({ name, data }) } },
};
vm.createContext(sandbox);
vm.runInContext(libraryCode + '\n' + recordingCode, sandbox);
const server = http.createServer((req, res) => {
  if (req.url === '/bad') { res.writeHead(404); res.end(); return; }
  if (/^\/playlist(?:\d+\.ts|\.m3u8)$/.test(req.url)) {
    res.writeHead(200, { 'Content-Type': req.url.endsWith('m3u8') ? 'application/vnd.apple.mpegurl' : 'video/mp2t' });
    fs.createReadStream(path.join(temp, req.url.slice(1))).pipe(res); return;
  }
  res.writeHead(200, { 'Content-Type': 'video/mp2t' });
  fs.createReadStream(fixture).pipe(res);
});
async function complete(id) {
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline) {
    const event = events.find(e => e.name === 'dvr:completed' && e.data.recordingId === id);
    if (event) return event.data;
    await new Promise(r => setTimeout(r, 50));
  }
  throw new Error('Completion event timed out: ' + id);
}
(async () => {
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const url = `http://127.0.0.1:${server.address().port}`;
  for (const format of ['ts', 'mp4', 'mkv']) {
    sandbox.appSettings.dvrFormat = format;
    const result = await sandbox.startRecording({ streamUrl: 'ffmpeg ' + url + '/live.ts',
      channelName: 'Same Channel', programTitle: 'Test ' + format, durationSeconds: 2 });
    assert.equal(result.success, true, result.error);
    const done = await complete(result.recordingId);
    assert.equal(done.success, true);
    assert.ok(done.meta.size > 188);
    const decode = spawnSync(ffmpeg, ['-v', 'error', '-i', done.meta.outputPath, '-t', '1', '-f', 'null', '-'], { windowsHide: true });
    assert.equal(decode.status, 0, String(decode.stderr));
    console.log(`PASS HTTP ${format}: wrote and decoded ${done.meta.size} bytes`);
  }
  const hlsResult = await sandbox.startRecording({ streamUrl: url + '/playlist.m3u8', durationSeconds: 2 });
  assert.equal(hlsResult.success, true, hlsResult.error);
  assert.equal((await complete(hlsResult.recordingId)).success, true);
  console.log('PASS HTTP HLS recording');
  await Promise.all(Array.from({ length: 8 }, (_, i) => sandbox.saveRecordingToLibrary({
    id: 'parallel_' + i, outputPath: path.join(temp, 'parallel_' + i + '.ts'), status: 'error',
  })));
  const bad = await sandbox.startRecording({ streamUrl: url + '/bad', recordingId: 'test_bad' });
  assert.equal(bad.success, false);
  await complete('test_bad');
  sandbox.getFFmpegPath = () => path.join(temp, 'missing-ffmpeg.exe');
  const missing = await sandbox.startRecording({ streamUrl: url + '/live.ts', recordingId: 'test_missing' });
  assert.equal(missing.success, false);
  await complete('test_missing');
  const library = await sandbox.withRecordingLibrary(sandbox.loadRecordingLibrary);
  assert.equal(library.filter(r => r.id.startsWith('parallel_')).length, 8);
  assert.equal(library.filter(r => r.id === 'test_bad').length, 1);
  assert.equal(library.filter(r => r.id === 'test_missing').length, 1);
  assert.equal(new Set(library.map(r => r.id)).size, library.length);
  const persisted = JSON.parse(fs.readFileSync(path.join(temp, 'library.json'), 'utf8'));
  assert.equal(persisted.length, library.length);
  await sandbox.saveRecordingLibrary([
    ...persisted,
    { id: 'rec_disk_53616d65', outputPath: path.join(temp, 'old-one.ts') },
    { id: 'rec_disk_53616d65', outputPath: path.join(temp, 'old-two.ts') },
  ]);
  const migrated = await sandbox.withRecordingLibrary(sandbox.loadRecordingLibrary);
  assert.equal(new Set(migrated.map(r => r.id)).size, migrated.length);
  console.log('PASS legacy duplicate recording IDs migrated');
  assert.equal(sandbox.recordings.size, 0);
  console.log('PASS failed streams and missing FFmpeg return errors and persist in library; unique IDs; no active slot leaks');
  console.log('Test artifacts: ' + temp);
})().catch(e => { console.error(e); process.exitCode = 1; }).finally(() => server.close());
