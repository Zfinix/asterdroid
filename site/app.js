const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
const wait = (ms) => new Promise((r) => setTimeout(r, reduce ? 0 : ms));
const el = (tag, cls, html) => { const n = document.createElement(tag); if (cls) n.className = cls; if (html != null) n.innerHTML = html; return n; };
const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;');
const CHECK = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>';

/* iPhones on iOS 26, one conversation, each in its chat app's own interface */
const I = {
  chev: '<svg width="12" height="20" viewBox="0 0 12 20" aria-hidden="true"><path d="M10 2 2.5 10 10 18" fill="none" stroke="currentColor" stroke-width="2.8" stroke-linecap="round" stroke-linejoin="round"/></svg>',
  chevSm: '<svg width="7" height="12" viewBox="0 0 7 12" aria-hidden="true"><path d="M1.5 1.5 5.5 6l-4 4.5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>',
  video: '<svg width="27" height="19" viewBox="0 0 28 20" aria-hidden="true"><rect x="1.2" y="2.2" width="17.6" height="15.6" rx="4.5" fill="none" stroke="currentColor" stroke-width="2.1"/><path d="M20 8.3 26.5 4.2v11.6L20 11.7z" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linejoin="round"/></svg>',
  call: '<svg width="22" height="22" viewBox="0 0 24 24" aria-hidden="true"><path d="M6.6 2.8 9 2.3l2 4.6-2.3 1.6a11.5 11.5 0 0 0 6.8 6.8l1.6-2.3 4.6 2-.5 2.4a2.4 2.4 0 0 1-2.4 1.9A17.4 17.4 0 0 1 2.7 5.2a2.4 2.4 0 0 1 1.9-2.4z" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/></svg>',
  plus: '<svg width="20" height="20" viewBox="0 0 20 20" aria-hidden="true"><path d="M10 2.5v15M2.5 10h15" stroke="currentColor" stroke-width="2.1" stroke-linecap="round"/></svg>',
  plusBig: '<svg width="25" height="25" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v18M3 12h18" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/></svg>',
  mic: '<svg width="15" height="21" viewBox="0 0 16 22" aria-hidden="true"><rect x="4" y="1.2" width="8" height="12.6" rx="4" fill="currentColor"/><path d="M1.2 10a6.8 6.8 0 0 0 13.6 0M8 17v3.8" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>',
  camera: '<svg width="27" height="23" viewBox="0 0 26 22" aria-hidden="true"><path d="M2.5 6.5h4.2l2-3h8.6l2 3h4.2v12.5H2.5z" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/><circle cx="13" cy="12.4" r="4" fill="none" stroke="currentColor" stroke-width="2"/></svg>',
  sticker: '<svg width="22" height="22" viewBox="0 0 22 22" aria-hidden="true"><path d="M20 11.5V7a5 5 0 0 0-5-5H7a5 5 0 0 0-5 5v8a5 5 0 0 0 5 5h4.5z" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linejoin="round"/><path d="M11.5 20v-3.5a5 5 0 0 1 5-5H20" fill="none" stroke="currentColor" stroke-width="1.9"/></svg>',
  clip: '<svg width="22" height="22" viewBox="0 0 24 24" aria-hidden="true"><path d="m20.5 11.4-8.6 8.6a5.8 5.8 0 0 1-8.2-8.2l8.6-8.6a3.9 3.9 0 0 1 5.5 5.5l-8.6 8.6a1.9 1.9 0 0 1-2.7-2.7l7.9-7.9" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>',
  menu: '<svg width="18" height="14" viewBox="0 0 18 14" aria-hidden="true"><path d="M1.5 1.5h15M1.5 7h15M1.5 12.5h15" stroke="#fff" stroke-width="2.2" stroke-linecap="round"/></svg>',
  emoji: '<svg width="23" height="23" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9.5" fill="none" stroke="currentColor" stroke-width="1.8"/><circle cx="8.8" cy="10" r="1.2" fill="currentColor"/><circle cx="15.2" cy="10" r="1.2" fill="currentColor"/><path d="M8 14.2a4.6 4.6 0 0 0 8 0" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>',
  plane: '<svg width="17" height="17" viewBox="0 0 24 24" aria-hidden="true"><path d="M21.5 2.6 2.8 10.1c-.9.4-.9 1.6 0 1.9l4.7 1.6 1.8 5.6c.3.9 1.4 1.1 2 .4l2.6-2.7 4.8 3.5c.7.5 1.7.1 1.9-.8l3.1-15.6c.2-1-.8-1.8-1.7-1.4zM9.5 14.2l8.6-7.4-6.8 8.7-.4 3.1z" fill="currentColor"/></svg>',
  wave: '<svg width="22" height="18" viewBox="0 0 22 18" aria-hidden="true"><path d="M2 7v4M6 4v10M10 1.5v15M14 5v8M18 3v12" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>',
  ticks: '<svg viewBox="0 0 17 11" aria-hidden="true"><path d="M1 5.8 4 8.8 10.4 1.6M6.4 8.2l.6.6 6.4-7.2" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>',
  up: '<svg width="17" height="17" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 19V5M5 12l7-7 7 7" fill="none" stroke="currentColor" stroke-width="2.8" stroke-linecap="round" stroke-linejoin="round"/></svg>',
  check: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>',
};
const STATUS_IOS = `<div class="i-status"><span>12:52</span><span class="r"><svg width="19" height="12" viewBox="0 0 19 12" aria-hidden="true"><rect x="0" y="8" width="3.2" height="4" rx="1" fill="currentColor"/><rect x="5" y="5.5" width="3.2" height="6.5" rx="1" fill="currentColor"/><rect x="10" y="3" width="3.2" height="9" rx="1" fill="currentColor"/><rect x="15" y="0" width="3.2" height="12" rx="1" fill="currentColor"/></svg><svg width="17" height="12" viewBox="0 0 17 12" aria-hidden="true"><path d="M8.5 2.4c2.4 0 4.6.9 6.3 2.5l1.2-1.3A10.8 10.8 0 0 0 8.5.6 10.8 10.8 0 0 0 1 3.6l1.2 1.3a9 9 0 0 1 6.3-2.5zm0 3.6c1.4 0 2.7.5 3.7 1.4l1.2-1.3a7.2 7.2 0 0 0-9.8 0l1.2 1.3c1-.9 2.3-1.4 3.7-1.4zm0 3.5L6.4 7.8a3 3 0 0 1 4.2 0z" fill="currentColor"/></svg><span class="i-batt">85</span></span></div>`;
const AVATAR = 'mascot/avatar.png';
const CHAT = [
  { me: 'Order my usual from KFC', t: '12:52 PM' },
  { them: 'On it. Opening Uber Eats.', t: '12:52 PM' },
  { them: '12pc Chicken Bucket for $21.99, down from $34.99. Should I place the order?', t: '12:52 PM', ask: true },
  { me: 'Yes', t: '12:53 PM', reply: true },
  { them: 'Placed. It arrives between 1:10 and 1:25 pm. I will check on the driver every 8 minutes.', t: '12:53 PM' },
];
const REPLY_YES = (s) => s.replace('Should I place the order?', 'Reply yes to place the order.');
function grouped(lines) {
  return lines.map((l, i) => {
    const who = l.me ? 'me' : 'them';
    const side = (x) => x && (x.me ? 'me' : 'them');
    return { ...l, who, text: l.me || l.them, first: side(lines[i - 1]) !== who, last: side(lines[i + 1]) !== who };
  });
}
function buildIOS(ui, app) {
  let chrome = '', body = '';
  if (app === 'imessage') {
    const g = grouped(CHAT);
    chrome = `<div class="i-fade"></div><span class="i-cap g im-back">${I.chev}<span class="im-badge">4</span></span><img class="i-avatar im-avatar" src="${AVATAR}" alt=""><span class="i-cap g im-name">Droid ${I.chevSm}</span><span class="i-circ g im-video">${I.video}</span>
      <span class="i-circ g im-plus">${I.plus}</span><span class="i-cap g im-field"><span>iMessage</span>${I.wave}</span>`;
    body = `<div class="stamp"><b>Today</b> 12:52 PM</div>` + g.map((m) => `<div class="imb ${m.who}${m.last ? ' last' : ''}${m.first && m.who === 'me' && m !== g[0] ? ' mt' : ''}">${esc(REPLY_YES(m.text))}</div>${m.reply ? '<div class="im-delivered">Delivered</div>' : ''}`).join('');
  } else if (app === 'whatsapp') {
    const g = grouped(CHAT);
    chrome = `<div class="wa-bar"></div><span class="i-circ wa-back">${I.chev}</span><img class="i-avatar wa-avatar" src="${AVATAR}" alt=""><span class="wa-who"><strong>Droid</strong><small>online</small></span><span class="i-cap wa-calls">${I.video}${I.call}</span>
      <div class="wa-compose"></div><span class="wa-plus">${I.plusBig}</span><span class="wa-field">${I.sticker}</span><span class="wa-cam">${I.camera}</span><span class="wa-mic">${I.mic}</span>`;
    body = `<div class="wa-day">Today</div>` + g.map((m) => `<div class="wab ${m.who}${m.last ? ' last' : ''}${m.first ? ' mt' : ''}">${esc(REPLY_YES(m.text))}<span class="tm">${m.t}${m.who === 'me' ? I.ticks : ''}</span></div>`).join('');
  } else if (app === 'telegram') {
    const g = grouped(CHAT.filter((m) => !m.reply));
    chrome = `<div class="i-fade"></div><span class="i-circ g tg-back">${I.chev}</span><span class="i-cap g tg-name"><strong>Droid</strong><small>bot</small></span><img class="i-avatar tg-avatar" src="${AVATAR}" alt="">
      <span class="i-circ tg-menu">${I.menu}</span><span class="i-circ g tg-clip">${I.clip}</span><span class="i-cap g tg-field"><span>Message</span><span class="e">${I.emoji}</span><span class="tg-send">${I.plane}</span></span>`;
    body = `<div class="tg-day">Today</div>` + g.map((m) => `<div class="tgb ${m.who}${m.last || m.ask ? ' last' : ''}${m.first ? ' mt' : ''}">${esc(m.text)}<span class="tm">${m.t.replace(' PM', '')}${m.who === 'me' ? I.ticks : ''}</span></div>${m.ask ? '<div class="tg-keys"><span>✓ Place order</span><span>Not now</span></div>' : ''}`).join('');
  } else {
    chrome = `<div class="i-fade"></div><span class="i-cap g d-name"><img class="i-avatar" src="${AVATAR}" alt=""><span><strong>Droid</strong><small>Running on your Android</small></span></span>
      <span class="i-cap g d-field"><span>Message Droid</span><span class="d-send">${I.up}</span></span>`;
    body = [
      `<div class="ddb me">Order my usual from KFC</div>`,
      `<div class="d-chip g">${I.check}Found your last order on Uber Eats</div>`,
      `<div class="ddb them">12pc Chicken Bucket for $21.99, down from $34.99. Place the order?<div class="d-acts"><span class="yes">${I.check.replace('<svg', '<svg width="14" height="14"')}Place order</span><span class="no g">Not now</span></div></div>`,
      `<div class="d-chip g">${I.check}Order placed</div>`,
      `<div class="ddb them">It arrives between 1:10 and 1:25 pm. I will check on the driver every 8 minutes.</div>`,
    ].join('');
  }
  ui.innerHTML = `<div class="i-scroll">${body}</div>` + chrome + STATUS_IOS + '<span class="i-home"></span>';
}
document.querySelectorAll('.fan4 .ios-ui').forEach((ui) => buildIOS(ui, ui.dataset.app));

/* the hero iPhone: one conversation played live, in whichever chat app is chosen */
const LIVE = {
  food: [
    { me: 'Order my usual from KFC' },
    { them: 'On it. Opening Uber Eats.' },
    { them: '12pc Chicken Bucket for $21.99, down from $34.99. Place the order?', ask: true },
    { me: 'Yes', reply: true },
    { them: 'Placed. It arrives between 1:10 and 1:25 pm. I will check on the driver every 8 minutes.', done: true },
  ],
  delivery: [
    { me: 'Where is my driver?' },
    { them: 'Checking Uber Eats.' },
    { them: 'About 6 minutes away, two streets over. I will tell you when he pulls up.' },
    { them: 'He is outside now.', done: true },
  ],
  music: [
    { me: 'Play my focus playlist, not too loud' },
    { them: 'Opening Spotify.' },
    { them: 'Focus is playing at 40% volume. Tell me when to stop.', done: true },
  ],
  remember: [
    { me: 'I like a quiet phone after 10 pm' },
    { them: 'Noted. After 10 pm I will keep things quiet.' },
    { me: 'Make that a routine' },
    { them: 'Saved a skill called quiet mode. It turns on Do Not Disturb and lowers the ring volume.', done: true },
  ],
};
const ORDER = ['food', 'delivery', 'music', 'remember'];
const CHANNELS = ['imessage', 'whatsapp', 'telegram'];
const BUBBLE = { imessage: 'imb', whatsapp: 'wab', telegram: 'tgb' };
const heroPhone = document.querySelector('.hero-ios');
const heroUI = heroPhone.querySelector('.ios-ui');
const perch = document.querySelector('.mascot-perch');
const perchImg = perch.querySelector('img');
const channelButtons = [...document.querySelectorAll('.channel')];
const taskButtons = [...document.querySelectorAll('.task')];
let channel = 'imessage';
let task = 'food';
let run = 0;
let autoplay = !reduce;
let clock = 52;
const stamp = () => `12:${String(Math.min(59, clock)).padStart(2, '0')}`;

function setRail(progress) {
  channelButtons.forEach((b) => {
    const fill = b.querySelector('.rail-fill');
    const on = b.dataset.channel === channel;
    fill.style.transition = on && progress > 0 ? 'transform 500ms cubic-bezier(0.23, 1, 0.32, 1)' : 'none';
    fill.style.transform = `scaleY(${on ? progress : 0})`;
  });
}

/* the conversation pushes up like the real thing: new rows grow in, the rest glide */
function mount(app) {
  heroUI.className = `ios-ui app-${app}`;
  heroUI.dataset.app = app;
  buildIOS(heroUI, app);
  const scroll = heroUI.querySelector('.i-scroll');
  scroll.innerHTML = '<div class="i-track"></div>';
  return scroll.firstChild;
}
function push(track, node) {
  const before = track.offsetHeight;
  track.append(node);
  const delta = track.offsetHeight - before;
  if (reduce || !delta) return node;
  track.style.transition = 'none';
  track.style.transform = `translateY(${delta}px)`;
  track.offsetHeight;
  track.style.transition = 'transform 420ms cubic-bezier(0.23, 1, 0.32, 1)';
  track.style.transform = 'translateY(0)';
  return node;
}
function bubbleFor(app, who, text, track) {
  const cls = BUBBLE[app];
  const prev = track.lastElementChild;
  const same = prev && prev.classList.contains(cls) && prev.classList.contains(who);
  if (same) prev.classList.remove('last');
  const n = el('div', `${cls} ${who} last pop${same ? '' : ' mt'}`);
  n.innerHTML = esc(text) + (app === 'imessage' ? '' : `<span class="tm">${stamp()}${app === 'whatsapp' ? ' PM' : ''}${who === 'me' ? I.ticks : ''}</span>`);
  return n;
}
function field(app) {
  if (app === 'imessage') return heroUI.querySelector('.im-field span');
  if (app === 'telegram') return heroUI.querySelector('.tg-field span');
  let f = heroUI.querySelector('.wa-field .typed-host');
  if (!f) { f = el('span', 'typed-host'); heroUI.querySelector('.wa-field').prepend(f); }
  return f;
}
async function typeInto(app, text, id) {
  const f = field(app);
  const placeholder = f.textContent;
  const wave = heroUI.querySelector('.im-field svg');
  const mic = heroUI.querySelector('.wa-mic');
  f.textContent = '';
  const t = el('span', 'typed'); f.append(t);
  if (wave) wave.outerHTML = `<span class="im-send">${I.up}</span>`;
  if (mic) mic.innerHTML = I.plane;
  for (const ch of text) {
    if (id !== run) return false;
    t.textContent += ch;
    await wait(34 + Math.random() * 46);
  }
  await wait(260);
  const sendBtn = heroUI.querySelector('.im-send, .wa-mic, .tg-send');
  sendBtn?.classList.add('press');
  await wait(130);
  sendBtn?.classList.remove('press');
  f.textContent = placeholder;
  heroUI.querySelector('.im-send')?.replaceWith(Object.assign(document.createElement('span'), { innerHTML: I.wave }).firstChild);
  if (mic) mic.innerHTML = I.mic;
  return id === run;
}
function subtitle(app, text) {
  const sel = app === 'whatsapp' ? '.wa-who small' : app === 'telegram' ? '.tg-name small' : null;
  const n = sel && heroUI.querySelector(sel);
  if (!n) return;
  n.textContent = text;
  n.classList.remove('sub-swap'); n.offsetWidth; n.classList.add('sub-swap');
}
function cheer() {
  perchImg.src = 'mascot/bunny-happy.png';
  perch.classList.remove('hop'); perch.offsetWidth; perch.classList.add('hop');
  setTimeout(() => { perchImg.src = 'mascot/bunny-hello.png'; }, 1800);
}

async function play() {
  const id = ++run;
  const app = channel;
  const track = mount(app);
  const lines = LIVE[task];
  clock = 52;
  setRail(0);
  if (app === 'imessage') push(track, el('div', 'stamp pop', '<b>Today</b> 12:52 PM'));
  if (app === 'whatsapp') push(track, el('div', 'wa-day pop', 'Today'));
  if (app === 'telegram') push(track, el('div', 'tg-day pop', 'Today'));
  await wait(500);
  for (let i = 0; i < lines.length; i++) {
    if (id !== run) return;
    const l = lines[i];
    if (l.me) {
      if (l.reply && app === 'telegram') {
        const keys = track.querySelector('.tg-keys span');
        keys?.classList.add('hit');
        await wait(260);
        track.querySelector('.tg-keys')?.classList.add('gone');
        await wait(200);
        if (id !== run) return;
        track.querySelector('.tg-keys')?.remove();
      } else {
        if (!(await typeInto(app, l.me, id))) return;
        track.querySelector('.im-delivered')?.remove();
        push(track, bubbleFor(app, 'me', l.me, track));
        if (app === 'imessage') { await wait(380); if (id !== run) return; push(track, el('div', 'im-delivered pop', 'Delivered')); }
      }
    } else {
      let dots;
      if (app === 'imessage') dots = push(track, el('div', 'imb them typing pop mt', '<i></i><i></i><i></i>'));
      else subtitle(app, 'typing…');
      await wait(l.ask ? 1100 : 900);
      if (id !== run) return;
      dots?.remove();
      if (app !== 'imessage') subtitle(app, app === 'whatsapp' ? 'online' : 'bot');
      track.querySelector('.im-delivered')?.remove();
      const text = l.ask && app !== 'telegram' ? l.them.replace('Place the order?', 'Reply yes to place the order.') : l.them;
      push(track, bubbleFor(app, 'them', text, track));
      if (l.ask && app === 'telegram') { await wait(160); push(track, el('div', 'tg-keys', '<span>✓ Place order</span><span>Not now</span>')); }
      if (l.done) cheer();
      clock++;
    }
    setRail((i + 1) / lines.length);
    await wait(l.me ? 520 : l.ask ? 1500 : 900);
  }
  if (!autoplay) return;
  await wait(3000);
  if (id !== run || !autoplay) return;
  const next = CHANNELS[(CHANNELS.indexOf(channel) + 1) % CHANNELS.length];
  if (next === CHANNELS[0]) task = ORDER[(ORDER.indexOf(task) + 1) % ORDER.length];
  taskButtons.forEach((t) => t.setAttribute('aria-selected', String(t.dataset.task === task)));
  switchTo(next, false);
}

/* switching apps: the screen dissolves down, the next app settles in from above, the phone turns a touch */
let nudge = 0;
async function switchTo(id, user) {
  if (user) autoplay = false;
  channel = id;
  channelButtons.forEach((b) => b.setAttribute('aria-selected', String(b.dataset.channel === id)));
  run++;
  if (reduce) { play(); return; }
  heroPhone.classList.add('leaving');
  nudge = 5;
  await wait(220);
  heroPhone.classList.remove('leaving');
  heroPhone.classList.add('entering');
  play();
  requestAnimationFrame(() => requestAnimationFrame(() => heroPhone.classList.remove('entering')));
}
channelButtons.forEach((b) => b.addEventListener('click', () => { if (b.dataset.channel !== channel || !autoplay) switchTo(b.dataset.channel, true); }));
taskButtons.forEach((b) => b.addEventListener('click', () => {
  autoplay = false;
  task = b.dataset.task;
  taskButtons.forEach((t) => t.setAttribute('aria-selected', String(t === b)));
  switchTo(channel, true);
}));

/* entrance: copy rises, the phone swings in and lands, Tap pops up and waves */
function enter() {
  document.querySelectorAll('.hero .pre').forEach((n) => n.classList.remove('pre'));
  setTimeout(() => { perch.classList.add('wave'); }, 1500);
  setTimeout(startTilt, 1250);
}
requestAnimationFrame(() => requestAnimationFrame(enter));
if (reduce) {
  const track = mount('imessage');
  LIVE.food.forEach((l) => track.append(bubbleFor('imessage', l.me ? 'me' : 'them', (l.them || l.me).replace('Place the order?', 'Reply yes to place the order.'), track)));
} else {
  setTimeout(play, 900);
}

/* the phone follows the pointer through a spring, and the glare slides across the glass */
function startTilt() {
  if (reduce || !matchMedia('(hover: hover) and (pointer: fine)').matches) return;
  const hero = document.querySelector('.hero');
  let tx = -14, ty = 6, x = -14, y = 6, vx = 0, vy = 0, raf = 0;
  heroPhone.style.transition = 'opacity 500ms ease-out';
  const step = () => {
    const ax = (tx + nudge - x) * 0.06 - vx * 0.28;
    const ay = (ty - y) * 0.06 - vy * 0.28;
    vx += ax; vy += ay; x += vx; y += vy;
    nudge *= 0.9;
    heroPhone.style.setProperty('--ry', `${x.toFixed(2)}deg`);
    heroPhone.style.setProperty('--rx', `${y.toFixed(2)}deg`);
    heroPhone.style.setProperty('--gx', `${(50 + (x + 14) * 5).toFixed(1)}%`);
    raf = requestAnimationFrame(step);
  };
  hero.addEventListener('pointermove', (e) => {
    const r = hero.getBoundingClientRect();
    tx = -14 + ((e.clientX - r.left) / r.width - 0.5) * 16;
    ty = 6 - ((e.clientY - r.top) / r.height - 0.5) * 9;
  });
  hero.addEventListener('pointerleave', () => { tx = -14; ty = 6; });
  document.addEventListener('visibilitychange', () => { if (document.hidden) { cancelAnimationFrame(raf); raf = 0; } else if (!raf) raf = requestAnimationFrame(step); });
  raf = requestAnimationFrame(step);
}

document.querySelectorAll('.lock').forEach((lock) => {
  if (reduce) return;
  new IntersectionObserver(([e], o) => { if (e.isIntersecting) { o.disconnect(); lock.classList.add('arrive'); } }, { threshold: 0.6 }).observe(lock);
});

document.querySelectorAll('.marquee-track').forEach((t) => {
  [...t.children].forEach((c) => { const d = c.cloneNode(true); d.setAttribute('aria-hidden', 'true'); t.append(d); });
});

document.querySelectorAll('.qa').forEach((d) => {
  d.querySelector('summary').addEventListener('click', (e) => {
    e.preventDefault();
    if (d.classList.contains('is-open')) {
      d.classList.remove('is-open');
      setTimeout(() => { if (!d.classList.contains('is-open')) d.open = false; }, reduce ? 0 : 280);
    } else {
      d.open = true;
      requestAnimationFrame(() => d.classList.add('is-open'));
    }
  });
});

const copyBtn = document.querySelector('.cmd-copy');
copyBtn?.addEventListener('click', async () => {
  try { await navigator.clipboard.writeText('npx @withaster/droid pair'); } catch {
    const range = document.createRange(); range.selectNodeContents(copyBtn.previousElementSibling);
    const sel = getSelection(); sel.removeAllRanges(); sel.addRange(range);
  }
  copyBtn.classList.add('done');
  copyBtn.setAttribute('aria-label', 'Copied');
  clearTimeout(copyBtn._t);
  copyBtn._t = setTimeout(() => { copyBtn.classList.remove('done'); copyBtn.setAttribute('aria-label', 'Copy command'); }, 1600);
});
