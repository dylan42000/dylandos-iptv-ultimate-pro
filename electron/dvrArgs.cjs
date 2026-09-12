// Keep input options compatible with the bundled FFmpeg, including HLS demuxing.
function buildRecordingArgs({ streamUrl, userAgent = 'IPTVSmartersPro', durationSeconds, formatExt = 'ts', outputPath }) {
  const cleanUrl = streamUrl.trim().replace(/^ffmpeg\s+/i, '');
  const args = ['-hide_banner', '-loglevel', 'warning', '-y'];
  if (/^https?:\/\//i.test(cleanUrl)) {
    args.push('-user_agent', userAgent.replace(/[\r\n]/g, ''), '-rw_timeout', '30000000');
    // -timeout is a protocol-specific option (not supported by every HTTP/HLS
    // input in the shipped binary). rw_timeout is the generic IO deadline.
    args.push('-reconnect', '1', '-reconnect_streamed', '1', '-reconnect_delay_max', '10');
    if (!/\.m3u8(?:[?#]|$)|\/hls\//i.test(cleanUrl)) args.push('-reconnect_at_eof', '1');
  }
  args.push('-probesize', '5000000', '-analyzeduration', '5000000',
    '-fflags', '+genpts+discardcorrupt', '-i', cleanUrl,
    '-map', '0:v:0?', '-map', '0:a:0?', '-ignore_unknown', '-c', 'copy');
  if (Number.isFinite(durationSeconds) && durationSeconds > 0) args.push('-t', String(durationSeconds));
  if (formatExt === 'mp4') args.push('-f', 'mp4', '-movflags', '+frag_keyframe+empty_moov');
  else if (formatExt === 'mkv') args.push('-f', 'matroska');
  else args.push('-f', 'mpegts');
  args.push(outputPath);
  return args;
}
module.exports = { buildRecordingArgs };
