// workers/epgParser.worker.ts — Off-thread XMLTV parsing
// Prevents main-thread freeze on large EPG files (100k+ programmes)

interface EPGChannel {
  id: string;
  displayName: string;
  aliases?: string[];
  icon?: string;
}

interface EPGProgram {
  channelId: string;
  title: string;
  description: string;
  startTime: string;
  stopTime: string;
  category?: string;
  icon?: string;
  subtitle?: string;
}

interface EPGData {
  channels: Record<string, EPGChannel>;
  programs: Record<string, EPGProgram[]>;
}

interface WorkerMessage {
  type: 'parse';
  xml: string;
  sourceId: string;
}

interface WorkerResponse {
  type: 'parsed';
  sourceId: string;
  data: EPGData;
}

function parseXmltvTimestamp(ts: string): string {
  const match = ts.trim().match(/^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?\s*(Z|[+-]\d{4})?$/i);
  if (!match) return ts;
  const [, year, month, day, hour, min, sec = '00', tz] = match;
  const tzFormatted = !tz ? '' : tz.toUpperCase() === 'Z' ? 'Z' : `${tz.slice(0, 3)}:${tz.slice(3)}`;
  return `${year}-${month}-${day}T${hour}:${min}:${sec}${tzFormatted}`;
}

function getTextContent(parent: Element, tagName: string): string {
  const el = parent.getElementsByTagName(tagName)[0];
  return el?.textContent?.trim() ?? '';
}

function getDisplayNames(parent: Element): string[] {
  const names = new Set<string>();
  const elements = parent.getElementsByTagName('display-name');
  for (let i = 0; i < elements.length; i++) {
    const name = elements[i]?.textContent?.trim();
    if (name) names.add(name);
  }
  return Array.from(names);
}

function parseXmltvXml(xmlText: string): EPGData {
  const parser = new DOMParser();
  const doc = parser.parseFromString(xmlText, 'text/xml');
  const parseError = doc.getElementsByTagName('parsererror')[0];
  if (parseError) {
    throw new Error(`Invalid XMLTV document: ${parseError.textContent?.trim() || 'XML parse error'}`);
  }

  const channels: Record<string, EPGChannel> = {};
  const programs: Record<string, EPGProgram[]> = {};

  const channelEls = doc.getElementsByTagName('channel');
  for (let i = 0; i < channelEls.length; i++) {
    const el = channelEls[i];
    const id = el.getAttribute('id') ?? '';
    if (!id) continue;
    const aliases = getDisplayNames(el);
    channels[id] = {
      id,
      displayName: aliases[0] ?? id,
      aliases,
      icon: el.getElementsByTagName('icon')[0]?.getAttribute('src') ?? undefined,
    };
    programs[id] = [];
  }

  const programEls = doc.getElementsByTagName('programme');
  for (let i = 0; i < programEls.length; i++) {
    const el = programEls[i];
    const channelId = el.getAttribute('channel') ?? '';
    const start = el.getAttribute('start') ?? '';
    const stop = el.getAttribute('stop') ?? '';
    if (!channelId || !start || !stop) continue;

    const program: EPGProgram = {
      channelId,
      title: getTextContent(el, 'title'),
      description: getTextContent(el, 'desc'),
      startTime: parseXmltvTimestamp(start),
      stopTime: parseXmltvTimestamp(stop),
      category: getTextContent(el, 'category') || undefined,
      subtitle: getTextContent(el, 'sub-title') || undefined,
      icon: el.getElementsByTagName('icon')[0]?.getAttribute('src') ?? undefined,
    };

    if (!programs[channelId]) programs[channelId] = [];
    if (!channels[channelId]) {
      channels[channelId] = { id: channelId, displayName: channelId, aliases: [channelId] };
    }
    programs[channelId].push(program);
  }

  // Sort programs by start time
  for (const chId of Object.keys(programs)) {
    programs[chId].sort(
      (a, b) => new Date(a.startTime).getTime() - new Date(b.startTime).getTime()
    );
  }

  return { channels, programs };
}

self.onmessage = (e: MessageEvent<WorkerMessage>) => {
  const { type, xml, sourceId } = e.data;
  if (type !== 'parse') return;

  try {
    const data = parseXmltvXml(xml);
    const response: WorkerResponse = { type: 'parsed', sourceId, data };
    self.postMessage(response);
  } catch (err: any) {
    self.postMessage({
      type: 'error',
      sourceId,
      error: err?.message ?? 'Unknown parse error',
    });
  }
};
