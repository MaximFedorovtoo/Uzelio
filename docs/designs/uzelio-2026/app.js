/* Uzelio — концепт 2026 · интерактивный макет */
(() => {
  'use strict';

  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const reduceQuery = window.matchMedia('(prefers-reduced-motion: reduce)');
  if (new URLSearchParams(window.location.search).has('capture')) document.documentElement.classList.add('capture');
  if (new URLSearchParams(window.location.search).has('gif')) document.documentElement.classList.add('gif');

  /* ---------- тост ---------- */
  const toastEl = $('#toast');
  let toastTimer;
  function toast(text) {
    if (!toastEl) return;
    toastEl.textContent = text;
    toastEl.hidden = false;
    requestAnimationFrame(() => toastEl.classList.add('is-open'));
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => {
      toastEl.classList.remove('is-open');
      setTimeout(() => { if (!toastEl.classList.contains('is-open')) toastEl.hidden = true; }, 320);
    }, 2800);
  }

  async function copyText(text) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch (err) {
      try {
        const area = document.createElement('textarea');
        area.value = text;
        area.setAttribute('readonly', '');
        area.style.position = 'fixed';
        area.style.opacity = '0';
        document.body.append(area);
        area.select();
        const ok = document.execCommand('copy');
        area.remove();
        return ok;
      } catch (err2) {
        return false;
      }
    }
  }

  /* ---------- шапка ---------- */
  const topbar = $('#topbar');
  const onScroll = () => topbar.classList.toggle('is-scrolled', window.scrollY > 24);
  onScroll();
  window.addEventListener('scroll', onScroll, { passive: true });

  const navLinks = $$('[data-nav]');
  const sectionIds = ['direction', 'system', 'app', 'states', 'motion'];
  const navObserver = new IntersectionObserver(entries => {
    entries.forEach(entry => {
      if (!entry.isIntersecting) return;
      const id = entry.target.id;
      navLinks.forEach(link => link.classList.toggle('is-current', link.getAttribute('href') === '#' + id));
    });
  }, { rootMargin: '-42% 0px -52% 0px' });
  sectionIds.forEach(id => { const el = document.getElementById(id); if (el) navObserver.observe(el); });

  /* ---------- появление при скролле ---------- */
  const reveals = $$('.reveal');
  if (reducedMotion) {
    reveals.forEach(el => el.classList.add('in'));
  } else {
    const revealObserver = new IntersectionObserver(entries => {
      entries.forEach(entry => {
        if (!entry.isIntersecting) return;
        const target = entry.target;
        const siblings = $$('.reveal', target.parentElement);
        const index = Math.max(0, siblings.indexOf(target));
        target.style.transitionDelay = Math.min(index * 65, 320) + 'ms';
        target.classList.add('in');
        revealObserver.unobserve(target);
      });
    }, { threshold: 0.12, rootMargin: '0px 0px -6% 0px' });
    reveals.forEach(el => revealObserver.observe(el));
  }

  /* ---------- счётчики ---------- */
  function animateNumber(el, to, suffix, duration = 1400) {
    const start = performance.now();
    const from = Number(el.dataset.from || 0);
    el.dataset.from = String(to);
    el.dataset.to = String(to);
    el.dataset.suffix = suffix;
    if (reducedMotion) {
      el.textContent = Math.round(to).toLocaleString('ru-RU') + suffix;
      return;
    }
    function frame(now) {
      const p = Math.min(1, (now - start) / duration);
      const eased = 1 - Math.pow(1 - p, 3);
      const value = from + (to - from) * eased;
      el.textContent = Math.round(value).toLocaleString('ru-RU') + suffix;
      if (p < 1) requestAnimationFrame(frame);
    }
    requestAnimationFrame(frame);
  }

  const counterObserver = new IntersectionObserver(entries => {
    entries.forEach(entry => {
      if (!entry.isIntersecting) return;
      const el = entry.target;
      animateNumber(el, Number(el.dataset.countTo || 0), el.dataset.suffix || '');
      counterObserver.unobserve(el);
    });
  }, { threshold: 0.5 });
  $$('[data-count-to]').forEach(el => counterObserver.observe(el));

  /* ---------- hero: созвездие ---------- */
  const hero = $('#top');
  const canvas = $('#heroNet');
  if (hero && canvas && canvas.getContext) {
    const ctx = canvas.getContext('2d');
    let width = 0, height = 0, dpr = 1;
    let nodes = [];
    let raf = 0;
    let running = true;
    const pointer = { x: -9999, y: -9999, active: false };

    const makeNodes = () => {
      const count = Math.max(34, Math.min(76, Math.round((width * height) / 24000)));
      nodes = Array.from({ length: count }, () => ({
        x: Math.random() * width,
        y: Math.random() * height,
        vx: (Math.random() - 0.5) * 0.22,
        vy: (Math.random() - 0.5) * 0.22,
        r: 0.9 + Math.random() * 1.7,
        phase: Math.random() * Math.PI * 2
      }));
    };

    const resize = () => {
      const rect = hero.getBoundingClientRect();
      width = rect.width;
      height = rect.height;
      dpr = Math.min(window.devicePixelRatio || 1, 2);
      canvas.width = Math.round(width * dpr);
      canvas.height = Math.round(height * dpr);
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      makeNodes();
      if (reducedMotion) draw(0);
    };

    const draw = (time) => {
      ctx.clearRect(0, 0, width, height);
      for (let i = 0; i < nodes.length; i++) {
        const a = nodes[i];
        if (!reducedMotion) {
          a.x += a.vx;
          a.y += a.vy;
          if (a.x < -20) a.x = width + 20;
          if (a.x > width + 20) a.x = -20;
          if (a.y < -20) a.y = height + 20;
          if (a.y > height + 20) a.y = -20;
        }
        for (let j = i + 1; j < nodes.length; j++) {
          const b = nodes[j];
          const dx = a.x - b.x, dy = a.y - b.y;
          const dist = Math.hypot(dx, dy);
          if (dist < 138) {
            const alpha = (1 - dist / 138) * 0.22;
            ctx.strokeStyle = `rgba(56,240,221,${alpha})`;
            ctx.lineWidth = 0.7;
            ctx.beginPath();
            ctx.moveTo(a.x, a.y);
            ctx.lineTo(b.x, b.y);
            ctx.stroke();
          }
        }
        if (pointer.active) {
          const dx = a.x - pointer.x, dy = a.y - pointer.y;
          const dist = Math.hypot(dx, dy);
          if (dist < 190) {
            const alpha = (1 - dist / 190) * 0.5;
            ctx.strokeStyle = `rgba(124,246,200,${alpha})`;
            ctx.lineWidth = 0.8;
            ctx.beginPath();
            ctx.moveTo(a.x, a.y);
            ctx.lineTo(pointer.x, pointer.y);
            ctx.stroke();
          }
        }
        const twinkle = 0.55 + Math.sin((time || 0) / 1400 + a.phase) * 0.25;
        ctx.beginPath();
        ctx.fillStyle = `rgba(124,246,200,${Math.max(0.25, twinkle)})`;
        ctx.shadowColor = 'rgba(56,240,221,.7)';
        ctx.shadowBlur = 8;
        ctx.arc(a.x, a.y, a.r, 0, Math.PI * 2);
        ctx.fill();
        ctx.shadowBlur = 0;
      }
    };

    const loop = (time) => {
      if (!running) return;
      draw(time);
      raf = requestAnimationFrame(loop);
    };

    const start = () => {
      if (raf || reducedMotion) return;
      raf = requestAnimationFrame(loop);
    };
    const stop = () => {
      cancelAnimationFrame(raf);
      raf = 0;
    };

    resize();
    draw(0);
    if (!reducedMotion) start();

    let resizeTimer;
    window.addEventListener('resize', () => {
      clearTimeout(resizeTimer);
      resizeTimer = setTimeout(resize, 140);
    }, { passive: true });

    hero.addEventListener('pointermove', event => {
      const rect = hero.getBoundingClientRect();
      pointer.x = event.clientX - rect.left;
      pointer.y = event.clientY - rect.top;
      pointer.active = event.pointerType === 'mouse';
    }, { passive: true });
    hero.addEventListener('pointerleave', () => { pointer.active = false; });

    const heroVisibility = new IntersectionObserver(entries => {
      entries.forEach(entry => {
        running = entry.isIntersecting;
        if (running) start(); else stop();
      });
    }, { threshold: 0 });
    heroVisibility.observe(hero);

    reduceQuery.addEventListener?.('change', event => {
      if (event.matches) { stop(); resize(); }
    });
  }

  /* ---------- токены: копирование ---------- */
  $$('.swatch').forEach(swatch => {
    swatch.addEventListener('click', async () => {
      const hex = swatch.dataset.copy;
      const name = swatch.dataset.name;
      const ok = await copyText(hex);
      toast(ok ? `${name} · ${hex} · скопировано` : `${name} · ${hex}`);
    });
  });

  /* ---------- лёгкие demo-эффекты ---------- */
  $$('.lab-toggle').forEach(toggle => {
    toggle.addEventListener('click', () => {
      const next = toggle.getAttribute('aria-checked') !== 'true';
      toggle.setAttribute('aria-checked', String(next));
    });
  });

  $$('[data-ripple]').forEach(button => {
    button.addEventListener('pointerdown', event => {
      const rect = button.getBoundingClientRect();
      const ripple = document.createElement('span');
      ripple.className = 'ripple';
      ripple.style.left = (event.clientX - rect.left) + 'px';
      ripple.style.top = (event.clientY - rect.top) + 'px';
      button.append(ripple);
      setTimeout(() => ripple.remove(), 600);
    });
  });

  $$('[data-toast]').forEach(el => {
    el.addEventListener('click', () => toast(el.dataset.toast));
  });

  /* ---------- приложение: масштаб окна ---------- */
  const scaler = $('#windowScaler');
  const appWindow = $('#appWindow');
  const DESIGN_WIDTH = 1180;
  const DESIGN_HEIGHT = 790;
  let scale = 1;

  function fitWindow() {
    if (!scaler || !appWindow) return;
    const available = scaler.clientWidth;
    scale = Math.min(1, available / DESIGN_WIDTH);
    appWindow.style.transform = `scale(${scale})`;
    scaler.style.height = `${Math.round(DESIGN_HEIGHT * scale)}px`;
  }
  fitWindow();
  window.addEventListener('resize', fitWindow, { passive: true });

  /* ---------- вкладки ---------- */
  const tabs = $$('.nav-btn');
  const screens = $$('.screen');
  const navPill = $('.nav-pill');

  function placePill() {
    const active = $('.nav-btn.is-active');
    if (!active || !navPill) return;
    navPill.style.left = active.offsetLeft + 'px';
    navPill.style.width = active.offsetWidth + 'px';
  }

  function setTab(name) {
    if (mapOverlay && !mapOverlay.hidden) mapOverlay.hidden = true;
    screens.forEach(screen => screen.classList.toggle('is-active', screen.dataset.screen === name));
    tabs.forEach(tab => {
      const isActive = tab.dataset.tab === name;
      tab.classList.toggle('is-active', isActive);
      if (isActive) tab.setAttribute('aria-current', 'page');
      else tab.removeAttribute('aria-current');
    });
    requestAnimationFrame(placePill);
  }

  tabs.forEach(tab => tab.addEventListener('click', () => setTab(tab.dataset.tab)));
  requestAnimationFrame(placePill);

  /* ---------- карта: узлы ---------- */
  const nodes = {
    'Хельсинки': { ping: 46, country: 'Финляндия' },
    'Франкфурт': { ping: 58, country: 'Германия' },
    'Токио': { ping: 120, country: 'Япония' },
    'Кейптаун': { ping: 132, country: 'ЮАР' },
    'Нью-Йорк': { ping: 92, country: 'США' },
    'Сингапур': { ping: 168, country: 'Сингапур' }
  };
  let selectedNode = 'Хельсинки';

  function highlightNode(name) {
    $$('.map-pin').forEach(pin => pin.classList.toggle('is-active', pin.dataset.node === name));
    $$('.node-card').forEach(card => card.classList.toggle('is-active', card.dataset.node === name));
  }

  $$('.map-pin, .node-card').forEach(el => {
    el.addEventListener('click', () => {
      const name = el.dataset.node;
      if (!nodes[name]) return;
      selectedNode = name;
      highlightNode(name);
      if (connectionState === 'on') {
        toast(`Узел ${name} выбран. Переключение — в рабочем приложении.`);
        return;
      }
      if (connectionState === 'off') {
        $('#factNode').textContent = nodes[name].country;
        $('#factPing').textContent = nodes[name].ping + ' мс';
        $('#factRoute').textContent = '—';
        $('#statusDesc').textContent = `Выбран узел ${name} (${nodes[name].country}). Нажмите «Найти подключение».`;
        toast(`Выбран узел: ${name}`);
      }
    });
  });

  const mapOverlay = $('#mapOverlay');
  function openMap() {
    if (!mapOverlay) return;
    mapOverlay.hidden = false;
    const closeBtn = $('#closeMap');
    if (closeBtn) closeBtn.focus({ preventScroll: true });
  }
  function closeMap() {
    if (!mapOverlay) return;
    mapOverlay.hidden = true;
    $('[data-open-map]')?.focus({ preventScroll: true });
  }
  $$('[data-open-map]').forEach(btn => btn.addEventListener('click', openMap));
  $('#closeMap')?.addEventListener('click', closeMap);
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && mapOverlay && !mapOverlay.hidden) closeMap();
  });

  $$('.chips').forEach(group => {
    group.addEventListener('click', event => {
      const chip = event.target.closest('.chip');
      if (!chip) return;
      $$('.chip', group).forEach(item => item.classList.toggle('is-on', item === chip));
      toast(`Регион: ${chip.textContent.trim()} · фильтр демонстрационный`);
    });
  });

  /* ---------- подключение ---------- */
  const statusPanel = $('#statusPanel');
  const statusLabel = $('#statusLabel');
  const statusTitle = $('#statusTitle');
  const statusDesc = $('#statusDesc');
  const factNode = $('#factNode');
  const factPing = $('#factPing');
  const factRoute = $('#factRoute');
  const routeBadge = $('#routeBadge');
  const connectBtn = $('#connectBtn');
  const connectBtnText = $('#connectBtnText');
  const shareBtn = $('#shareBtn');
  const shareBtnText = $('#shareBtnText');
  const winStatusText = $('#winStatusText');
  const progress = $('#connectProgress');
  const progressLabel = $('#connectProgressLabel');
  const progressBar = $('#cpBar');
  const trafficPanel = $('.traffic-panel');
  const sparkCurrent = $('#sparkCurrent');
  const metricEls = {
    received: $('[data-metric="received"]'),
    sent: $('[data-metric="sent"]'),
    devices: $('[data-metric="devices"]')
  };

  let connectionState = 'on';
  let shareOn = true;
  let connectTimer = 0;
  let metricAnimations = {};

  function setMetric(el, target, type) {
    if (!el) return;
    const from = Number(el.dataset.value || 0);
    el.dataset.value = String(target);
    cancelAnimationFrame(metricAnimations[el.id || type]);
    const finish = value => {
      el.textContent = type === 'gb'
        ? (value <= 0.05 ? '—' : value.toFixed(1).replace('.', ',') + ' ГБ')
        : Math.round(value) + ' из 3';
    };
    if (reducedMotion) { finish(target); return; }
    const start = performance.now();
    const duration = 850;
    const step = now => {
      const p = Math.min(1, (now - start) / duration);
      const eased = 1 - Math.pow(1 - p, 3);
      finish(from + (target - from) * eased);
      if (p < 1) metricAnimations[type] = requestAnimationFrame(step);
    };
    metricAnimations[type] = requestAnimationFrame(step);
  }

  function setMetrics(on) {
    setMetric(metricEls.received, on ? 2.4 : 0, 'gb');
    setMetric(metricEls.sent, on ? 1.1 : 0, 'gb');
    setMetric(metricEls.devices, on ? 2 : 0, 'devices');
  }

  function renderConnection() {
    const info = nodes[selectedNode] || nodes['Хельсинки'];
    statusPanel.dataset.state = connectionState;
    const idleTraffic = connectionState !== 'on';
    trafficPanel?.classList.toggle('is-idle', idleTraffic);
    if (sparkCurrent) sparkCurrent.textContent = idleTraffic ? '—' : '2,4 ГБ';
    if (connectionState === 'on') {
      statusLabel.innerHTML = '<span class="status-led"></span>Соединение установлено';
      statusTitle.textContent = selectedNode;
      statusDesc.textContent = `Прямой канал · ${info.ping} мс. Трафик выбранных приложений идёт через Uzelio.`;
      factNode.textContent = info.country;
      factPing.textContent = info.ping + ' мс';
      factRoute.textContent = 'P2P';
      routeBadge.hidden = false;
      connectBtnText.textContent = 'Отключиться';
      progress.hidden = true;
      setMetrics(true);
    } else if (connectionState === 'connecting') {
      statusLabel.innerHTML = '<span class="status-led"></span>Подключаемся…';
      statusTitle.textContent = 'Подключаемся…';
      statusDesc.textContent = `Ищем прямой путь к узлу ${selectedNode}. Отменить можно в любой момент.`;
      factNode.textContent = info.country;
      factPing.textContent = 'проверка';
      factRoute.textContent = '—';
      routeBadge.hidden = true;
      connectBtnText.textContent = 'Отменить';
      progress.hidden = false;
      progressLabel.textContent = `Подключаемся к узлу ${selectedNode}…`;
      setMetrics(false);
    } else {
      statusLabel.innerHTML = '<span class="status-led"></span>Не подключено';
      statusTitle.textContent = 'Не подключено';
      statusDesc.textContent = shareOn
        ? 'Выберите узел на карте или нажмите «Найти подключение».'
        : 'Раздача выключена. Включите её, чтобы подключаться к узлам.';
      factNode.textContent = '—';
      factPing.textContent = '—';
      factRoute.textContent = '—';
      routeBadge.hidden = true;
      connectBtnText.textContent = 'Найти подключение';
      progress.hidden = true;
      setMetrics(false);
    }
  }

  function finishConnect() {
    connectionState = 'on';
    renderConnection();
    winStatusText.textContent = shareOn ? 'Раздача включена · 2 устройства' : 'Раздача выключена · чаты доступны';
    toast(`Соединение установлено · ${selectedNode}`);
  }

  function startConnect() {
    if (!shareOn) {
      toast('Сначала включите раздачу — без неё подключение недоступно.');
      return;
    }
    connectionState = 'connecting';
    renderConnection();
    progressBar.style.transition = 'none';
    progressBar.style.width = '0%';
    void progressBar.offsetWidth;
    progressBar.style.transition = 'width 1.6s linear';
    progressBar.style.width = '100%';
    clearTimeout(connectTimer);
    connectTimer = setTimeout(() => {
      if (connectionState === 'connecting') finishConnect();
    }, 1700);
  }

  function cancelConnect(silent) {
    clearTimeout(connectTimer);
    if (connectionState !== 'connecting') return;
    connectionState = 'off';
    renderConnection();
    if (!silent) toast('Подключение отменено. Раздача продолжается.');
  }

  function disconnect(silent) {
    clearTimeout(connectTimer);
    connectionState = 'off';
    renderConnection();
    if (!silent) toast('Соединение закрыто. Раздача продолжается.');
  }

  connectBtn?.addEventListener('click', () => {
    if (connectionState === 'connecting') { cancelConnect(); return; }
    if (connectionState === 'on') { disconnect(); return; }
    startConnect();
  });
  $('#cancelConnect')?.addEventListener('click', () => cancelConnect());
  shareBtn?.addEventListener('click', () => {
    shareOn = !shareOn;
    shareBtnText.textContent = shareOn ? 'Остановить раздачу' : 'Включить раздачу';
    winStatusText.textContent = shareOn ? 'Раздача включена · 2 устройства' : 'Раздача выключена · чаты доступны';
    toast(shareOn ? 'Раздача включена. Соединение можно выбирать.' : 'Раздача остановлена.');
    if (!shareOn && connectionState !== 'off') {
      disconnect(true);
      toast('Раздача остановлена — соединение закрыто.');
    }
  });

  /* ---------- приложения: переключатели ---------- */
  const appsSub = $('#appsSub');
  function updateAppsCount() {
    const count = $$('.app-list .switch[aria-checked="true"]').length;
    appsSub.textContent = count === 0 ? 'Приложения не выбраны' : `Выбрано ${count} ${count === 1 ? 'приложение' : count < 5 ? 'приложения' : 'приложений'}`;
  }
  $$('.app-list .switch').forEach(sw => {
    sw.addEventListener('click', () => {
      sw.setAttribute('aria-checked', String(sw.getAttribute('aria-checked') !== 'true'));
      updateAppsCount();
      toast('Состав приложений изменён · демонстрация');
    });
  });
  $('.scope-row .switch')?.addEventListener('click', event => {
    const sw = event.currentTarget;
    sw.setAttribute('aria-checked', String(sw.getAttribute('aria-checked') !== 'true'));
    toast(sw.getAttribute('aria-checked') === 'true' ? 'Вся система через Uzelio · демонстрация' : 'Снова выбранные приложения · демонстрация');
  });

  /* ---------- чаты ---------- */
  const chatData = {
    anna: {
      name: 'Анна Ковалёва', initials: 'АК', avatar: 'av-anna',
      presence: 'В сети · прямой канал',
      reply: 'Договорились! 🌲',
      messages: [
        { dir: 'in', text: 'Привет! Давай в субботу выберемся за город?', time: '12:38' },
        { dir: 'out', text: 'Давай. Нашёл хорошее место у озера.', time: '12:39', status: 'Доставлено' },
        { dir: 'in', file: { name: 'Маршрут.pdf', meta: '2,4 МБ · документ' }, time: '12:40' },
        { dir: 'out', text: 'Тогда до встречи!', time: '12:42', status: 'Доставлено' },
        { dir: 'in', text: 'Договорились. В 10 утра у озера?', time: '12:44' },
        { dir: 'out', text: 'В 10 утра. Возьму термос ☕', time: '12:45', status: 'Доставлено' }
      ]
    },
    mikhail: {
      name: 'Михаил Орлов', initials: 'МО', avatar: 'av-mikhail',
      presence: 'В сети · через узел участника',
      reply: 'Скину вечером полный альбом.',
      messages: [
        { dir: 'in', text: 'Смотри, какие кадры с прогулки!', time: '11:05' },
        { dir: 'out', text: 'Красиво. Пришли ещё?', time: '11:06', status: 'Доставлено' },
        { dir: 'in', text: 'Вечером скину весь альбом.', time: '11:08' }
      ]
    },
    elena: {
      name: 'Елена Смирнова', initials: 'ЕС', avatar: 'av-elena',
      presence: 'Не в сети · сообщение подождёт',
      reply: 'Всегда пожалуйста!',
      messages: [
        { dir: 'in', text: 'Спасибо, всё получилось!', time: 'Вчера' },
        { dir: 'out', text: 'Отлично. Если что — пиши.', time: 'Вчера', status: 'Доставлено' }
      ]
    },
    ivan: {
      name: 'Иван Петров', initials: 'ИП', avatar: 'av-ivan',
      presence: 'Не в сети · история на устройствах',
      reply: 'Хорошо, наберу вечером.',
      messages: [
        { dir: 'in', text: 'Буду на связи вечером.', time: 'Вт' },
        { dir: 'out', text: 'Понял, до вечера.', time: 'Вт', status: 'Доставлено' }
      ]
    }
  };

  let activeChat = 'anna';
  let typingChat = null;
  let replyTimer = 0;
  const messagesEl = $('#messages');
  const typingTemplate = $('#typing');
  if (typingTemplate) typingTemplate.remove();

  function makeIcon(name, cls) {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('class', 'i' + (cls ? ' ' + cls : ''));
    svg.setAttribute('aria-hidden', 'true');
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    use.setAttribute('href', '#i-' + name);
    svg.append(use);
    return svg;
  }

  function messageElement(message) {
    const article = document.createElement('article');
    article.className = 'msg ' + message.dir + (message.file ? ' file-msg' : '');
    if (message.file) {
      const card = document.createElement('div');
      card.className = 'file-card';
      const ico = document.createElement('span');
      ico.className = 'file-ico';
      ico.append(makeIcon('file'));
      const copy = document.createElement('span');
      copy.className = 'file-copy';
      const strong = document.createElement('b');
      strong.textContent = message.file.name;
      const small = document.createElement('small');
      small.textContent = message.file.meta;
      const link = document.createElement('button');
      link.type = 'button';
      link.className = 'file-link';
      link.textContent = 'Открыть';
      link.addEventListener('click', () => toast('Демонстрационное вложение — файл не загружается.'));
      copy.append(strong, small, link);
      card.append(ico, copy);
      article.append(card);
    } else {
      const p = document.createElement('p');
      p.textContent = message.text;
      article.append(p);
    }
    const meta = document.createElement('div');
    meta.className = 'msg-meta';
    const time = document.createElement('time');
    time.textContent = message.time;
    meta.append(time);
    if (message.dir === 'out') {
      if (message.status === 'Доставлено') {
        meta.append(makeIcon('check', 'tick'));
        const status = document.createElement('span');
        status.className = 'msg-status';
        status.textContent = 'Доставлено';
        meta.append(status);
      } else if (message.status) {
        const status = document.createElement('span');
        status.className = 'msg-status';
        status.textContent = message.status;
        meta.append(status);
      }
    }
    article.append(meta);
    return article;
  }

  function typingElement(chat) {
    const wrap = document.createElement('div');
    wrap.className = 'typing';
    const bubble = document.createElement('span');
    bubble.className = 'typing-bubble';
    bubble.append(document.createElement('i'), document.createElement('i'), document.createElement('i'));
    const label = document.createElement('small');
    label.textContent = chat.name.split(' ')[0] + ' печатает…';
    wrap.append(bubble, label);
    return wrap;
  }

  function renderMessages() {
    const chat = chatData[activeChat];
    messagesEl.replaceChildren();
    const day = document.createElement('div');
    day.className = 'day-divider';
    day.innerHTML = '<span>Сегодня</span>';
    messagesEl.append(day);
    chat.messages.forEach(message => messagesEl.append(messageElement(message)));
    if (typingChat === activeChat) messagesEl.append(typingElement(chat));
    messagesEl.scrollTop = messagesEl.scrollHeight;
  }

  function selectChat(id) {
    if (!chatData[id]) return;
    activeChat = id;
    const chat = chatData[id];
    $$('.chat-item').forEach(item => {
      const active = item.dataset.chat === id;
      item.classList.toggle('is-active', active);
      item.setAttribute('aria-pressed', String(active));
    });
    const avatar = $('#convAvatar');
    avatar.textContent = chat.initials;
    avatar.className = 'avatar ' + chat.avatar;
    $('#convName').textContent = chat.name;
    $('#convPresence').innerHTML = '<span class="status-led"></span>' + chat.presence;
    renderMessages();
  }

  $$('.chat-item').forEach(item => item.addEventListener('click', () => selectChat(item.dataset.chat)));

  const searchInput = $('#chatSearch');
  searchInput?.addEventListener('input', () => {
    const query = searchInput.value.trim().toLocaleLowerCase('ru');
    $$('.chat-item').forEach(item => {
      const name = chatData[item.dataset.chat].name.toLocaleLowerCase('ru');
      item.hidden = query.length > 0 && !name.includes(query);
    });
  });

  const draft = $('#draft');
  const sendBtn = $('#sendBtn');
  const composer = $('#composer');
  const nowTime = () => new Date().toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });

  function autoGrow() {
    draft.style.height = 'auto';
    draft.style.height = Math.min(draft.scrollHeight, 76) + 'px';
  }
  draft?.addEventListener('input', () => {
    sendBtn.disabled = draft.value.trim().length === 0;
    autoGrow();
  });
  draft?.addEventListener('keydown', event => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      composer.requestSubmit();
    }
  });

  composer?.addEventListener('submit', event => {
    event.preventDefault();
    const text = draft.value.trim();
    if (!text) return;
    const chatId = activeChat;
    const chat = chatData[chatId];
    const message = { dir: 'out', text, time: nowTime(), status: 'Отправлено' };
    chat.messages.push(message);
    draft.value = '';
    sendBtn.disabled = true;
    autoGrow();
    typingChat = null;
    if (activeChat === chatId) renderMessages();

    setTimeout(() => {
      message.status = 'Доставлено';
      if (activeChat === chatId) renderMessages();
    }, 950);

    clearTimeout(replyTimer);
    replyTimer = setTimeout(() => {
      typingChat = chatId;
      if (activeChat === chatId) renderMessages();
      setTimeout(() => {
        typingChat = null;
        chat.messages.push({ dir: 'in', text: chat.reply, time: nowTime() });
        if (activeChat === chatId) renderMessages();
      }, 1400);
    }, 1500);
  });

  /* ---------- профиль ---------- */
  const qr = $('#qr');
  if (qr) {
    let seed = 41;
    const random = () => {
      seed = (seed * 9301 + 49297) % 233280;
      return seed / 233280;
    };
    const size = 13;
    const finderOrigins = [[0, 0], [0, size - 7], [size - 7, 0]];
    const finderModule = (row, col) => {
      for (const [row0, col0] of finderOrigins) {
        if (row >= row0 && row < row0 + 7 && col >= col0 && col < col0 + 7) {
          const r = row - row0, c = col - col0;
          const ring = r === 0 || r === 6 || c === 0 || c === 6;
          const core = r >= 2 && r <= 4 && c >= 2 && c <= 4;
          return ring || core ? 'on' : '';
        }
      }
      return null;
    };
    for (let row = 0; row < size; row++) {
      for (let col = 0; col < size; col++) {
        const cell = document.createElement('i');
        let cls = finderModule(row, col);
        if (cls === null) {
          const timing = (row === 6 || col === 6) && (row + col) % 2 === 0;
          cls = timing || random() > 0.56 ? 'on' : '';
        }
        cell.className = cls;
        qr.append(cell);
      }
    }
  }

  $('#copyInvite')?.addEventListener('click', async () => {
    const ok = await copyText('uzel://a3f7-9c2e-41d8-8b6f');
    toast(ok ? 'Код приглашения скопирован · демонстрация' : 'Код: uzel://a3f7-9c2e-41d8-8b6f');
  });

  const rateRange = $('#rateRange');
  const rateValue = $('#rateValue');
  const rateSeg = $('#rateSeg');
  const rateButtons = rateSeg ? $$('.seg', rateSeg) : [];
  let rateLimited = true;

  function syncRate() {
    if (!rateRange || !rateValue) return;
    const value = Number(rateRange.value);
    const percent = ((value - Number(rateRange.min)) / (Number(rateRange.max) - Number(rateRange.min))) * 100;
    rateRange.style.background = `linear-gradient(90deg,var(--accent) ${percent}%,rgba(255,255,255,.08) ${percent}%)`;
    rateRange.disabled = !rateLimited;
    rateValue.textContent = rateLimited ? `${value} Мбит/с` : 'Без ограничений';
    rateRange.style.opacity = rateLimited ? '1' : '.4';
  }
  rateButtons.forEach(button => {
    button.addEventListener('click', () => {
      rateButtons.forEach(item => {
        const active = item === button;
        item.classList.toggle('is-on', active);
        item.setAttribute('aria-pressed', String(active));
      });
      rateLimited = button === rateButtons[1];
      syncRate();
    });
  });
  rateRange?.addEventListener('input', syncRate);
  syncRate();

  $$('.radio-card').forEach(card => {
    card.addEventListener('click', () => {
      $$('.radio-card').forEach(item => item.classList.toggle('is-on', item === card));
      toast('Режим подключения обновлён · демонстрация');
    });
  });

  /* ---------- телефоны ---------- */
  $$('.ph-nav-btn, .phone .ph-btn').forEach(button => {
    button.addEventListener('click', () => toast('Экран телефона — статичный кадр концепта.'));
  });

  /* ---------- служебные кнопки макета ---------- */
  $$('.screen-profile .tool-btn, .screen-home .link-btn, .conversation .round-btn, .chat-side-head .round-btn').forEach(btn => {
    if (btn.dataset.bound) return;
    btn.addEventListener('click', () => toast('Действие показано в концепте, но не выполняется в макете.'));
  });

  /* ---------- старт ---------- */
  updateAppsCount();
  selectChat('anna');
  highlightNode('Хельсинки');
  renderConnection();
  setTab('home');
  requestAnimationFrame(() => { fitWindow(); placePill(); });

  // после загрузки шрифтов пересчитать позицию пилюли (ширина подписи меняется)
  if (document.fonts && document.fonts.ready) {
    document.fonts.ready.then(() => { placePill(); fitWindow(); });
  }

  /* ---------- статичные состояния для превью: ?capture=1&screen=chats|profile|home&map=1&state=off&chat=anna ---------- */
  const captureParams = new URLSearchParams(window.location.search);
  if (document.documentElement.classList.contains('capture')) {
    const captureScreen = captureParams.get('screen');
    if (captureScreen === 'chats' || captureScreen === 'profile' || captureScreen === 'home') setTab(captureScreen);
    if (captureParams.get('map') === '1') openMap();
    if (captureParams.get('state') === 'off') {
      connectionState = 'off';
      renderConnection();
    }
    const captureChat = captureParams.get('chat');
    if (captureChat && chatData[captureChat]) selectChat(captureChat);
    placePill();
  }
})();
