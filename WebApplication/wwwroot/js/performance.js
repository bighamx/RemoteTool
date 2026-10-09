(function () {
    'use strict';
    const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
    const percent = value => Number.isFinite(value) ? `${Math.round(value)}%` : '不可用';
    const rate = value => { let n = Math.max(0, value || 0), unit = 0; while (n >= 1024 && unit < 3) { n /= 1024; unit++; } return `${n.toFixed(unit ? 1 : 0)} ${['B/s','KB/s','MB/s','GB/s'][unit]}`; };
    let colors = ['#49b7e8','#a58bfa','#4ecdb2','#f2b66f','#ec86ab'];

    function chart(history, series, seconds, maximum, suffix, minimum = 0) {
        if (!history.length || !series.length) return '<div class="perf-empty">等待采样数据…</div>';
        const right = Date.now(), left = right - seconds * 1000;
        const max = Math.max(minimum + 1, maximum);
        const x = sample => Math.max(0, Math.min(600, (Date.parse(sample.timestamp) - left) / (seconds * 1000) * 600));
        const y = value => 150 - (Math.max(minimum, Math.min(max, value)) - minimum) / (max - minimum) * 140;
        let paths = '';
        series.forEach((s, i) => {
            // Missing measurements are gaps, never synthetic zeroes.
            let segment = [];
            const flush = () => {
                if (!segment.length) return;
                const d = segment.map((p,j) => `${j ? 'L' : 'M'}${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(' ');
                if (series.length === 1) paths += `<path d="${d} L${segment.at(-1)[0]},150 L${segment[0][0]},150 Z" fill="${s.color || colors[i]}" opacity=".13"/>`;
                paths += `<path d="${d}" stroke="${s.color || colors[i]}" fill="none" stroke-width="2" vector-effect="non-scaling-stroke"/>`;
                segment = [];
            };
            history.forEach(sample => { const v = s.get(sample); if (Number.isFinite(v) && v >= 0) segment.push([x(sample),y(v)]); else flush(); });
            flush();
        });
        return `<div class="perf-chart"><div class="perf-axis"><span>${escape(suffix === '%' ? '100%' : suffix === '°C' ? `${max} °C` : rate(max))}</span><span>0</span></div><svg viewBox="0 0 600 160" preserveAspectRatio="none" role="img" aria-label="最近 ${seconds} 秒历史曲线"><g stroke="currentColor" opacity=".13">${[10,45,80,115,150].map(y=>`<line x1="0" y1="${y}" x2="600" y2="${y}"/>`).join('')}${[0,100,200,300,400,500,600].map(x=>`<line x1="${x}" y1="10" x2="${x}" y2="150"/>`).join('')}</g>${paths}</svg>${minimum ? `<span class="perf-minimum">${escape(minimum)} ${escape(suffix)}</span>` : ''}<div class="perf-time"><span>${seconds === 60 ? '60 秒前' : `${seconds/60} 分钟前`}</span><span>现在</span></div></div>`;
    }

    class Dashboard {
        constructor(element, options = {}) {
            this.element = element; this.options = options; this.seconds = 60; this.adapter = ''; this.busy = false;
            if (options.autoPoll !== false) {
                this.poll(); this.timer = setInterval(() => this.poll(), 3000);
            }
            this.themeObserver = new MutationObserver(() => {if(this.data)this.render();});
            this.themeObserver.observe(document.documentElement,{attributes:true,attributeFilter:['data-theme']});
            this.layoutObserver = new ResizeObserver(() => this.layoutCards());
        }
        dispose() { clearInterval(this.timer);this.themeObserver.disconnect();this.layoutObserver.disconnect(); }
        sizeCard(card) {
            card.style.gridRowEnd = window.matchMedia('(min-width: 681px)').matches
                ? `span ${Math.ceil(card.getBoundingClientRect().height + 14)}`
                : 'auto';
        }
        layoutCards() {
            const coreGrid = this.element.querySelector('.perf-core-temperatures .perf-core-grid');
            if (coreGrid && coreGrid.getBoundingClientRect().width > 0) {
                const columns = getComputedStyle(coreGrid).gridTemplateColumns.split(' ').length;
                const charts = [...coreGrid.children];
                const lastRowStart = Math.floor((charts.length - 1) / columns) * columns;
                charts.forEach((chart, index) => chart.classList.toggle('perf-core-last-row', index >= lastRowStart));
            }
            const pair = [...this.element.querySelectorAll('.perf-network-card, .perf-gpu-card')];
            pair.forEach(card => { card.style.minHeight = ''; });
            if (pair.length === 2 && window.matchMedia('(min-width: 681px)').matches) {
                const height = Math.ceil(Math.max(...pair.map(card => card.getBoundingClientRect().height)));
                pair.forEach(card => { card.style.minHeight = `${height}px`; });
            }
            this.element.querySelectorAll('.perf-grid > .perf-card, .perf-grid > .perf-details').forEach(card => this.sizeCard(card));
        }
        async poll(force = false) {
            if (!this.element.isConnected) { this.dispose(); return; }
            if (this.busy || !force && (document.hidden || this.options.isVisible && !this.options.isVisible())) return;
            this.busy = true;
            try {
                const response = await fetch(`/api/system/performance?seconds=${this.seconds}`, {credentials:'same-origin', headers:this.options.headers || {}});
                const result = await response.json();
                if (!response.ok || result.success === false) throw new Error(result.message || `HTTP ${response.status}`);
                this.data = result.data; this.render();
            } catch (error) {
                if (!this.data) this.element.innerHTML = `<div class="perf-empty">无法读取性能数据：${escape(error.message)}</div>`;
            } finally { this.busy = false; }
        }
        render() {
            colors = document.documentElement.dataset.theme === 'light' ? ['#167aa7','#7454c4','#12846e','#ae6c18','#ae4971'] : ['#49b7e8','#a58bfa','#4ecdb2','#f2b66f','#ec86ab'];
            const {latest, history = [], info = {}} = this.data;
            if (!latest) { this.element.innerHTML = '<div class="perf-empty">正在采集第一组性能数据…</div>'; return; }
            const open = new Set([...this.element.querySelectorAll('details[open]')].map(x=>x.dataset.panel));
            const networks = latest.networks || [];
            if (!networks.some(n=>n.name===this.adapter)) this.adapter = [...networks].sort((a,b)=>(b.receiveBytesPerSecond+b.sendBytesPerSecond)-(a.receiveBytesPerSecond+a.sendBytesPerSecond))[0]?.name || '';
            const network = networks.find(n=>n.name===this.adapter) || {};
            const read = (s,field) => s.networks?.find(n=>n.name===this.adapter)?.[field];
            const networkMax = Math.max(1024,...history.flatMap(s=>[read(s,'receiveBytesPerSecond')||0,read(s,'sendBytesPerSecond')||0])) * 1.2;
            const allTemperatures = latest.temperatures || [];
            const coreTemperatures = allTemperatures.filter(t=>/^(?:[PE]-Core|CPU Core) #\d+$/.test(t.name));
            const temperatures = allTemperatures.filter(t=>!coreTemperatures.includes(t));
            const fans = latest.fans || [];
            const sensorName = name => ({'CPU Package':'CPU 封装','Core Max':'CPU 核心最高','Core Average':'CPU 核心平均','CPU Socket':'CPU 脚座','VRM MOS':'MOS / 供电','PCH':'PCH 芯片组','System':'主板系统','CPU Fan':'CPU 风扇','Pump Fan':'水泵','GPU Core':'显卡核心','GPU Hot Spot':'显卡热点','Composite Temperature':'综合温度','Temperature':'温度'}[name] || name.replace('System Fan #','系统风扇 #').replace('GPU Fan ','显卡风扇 ').replace('P-Core #','性能核 #').replace('E-Core #','能效核 #'));
            const cpu = chart(history,[{get:s=>s.cpuPercent,color:colors[0]}],this.seconds,100,'%');
            const ram = chart(history,[{get:s=>s.memoryPercent,color:colors[1]}],this.seconds,100,'%');
            const net = chart(history,[{get:s=>read(s,'receiveBytesPerSecond'),color:colors[2]},{get:s=>read(s,'sendBytesPerSecond'),color:colors[3]}],this.seconds,networkMax,'B/s');
            const temp = temperatures.length ? chart(history,temperatures.slice(0,12).map((t,i)=>({get:s=>s.temperatures?.find(x=>t.id ? x.id===t.id : x.name===t.name)?.celsius,color:colors[i%colors.length]})),this.seconds,70,'°C',35) : '<div class="perf-empty">设备没有提供可读取的温度传感器</div>';
            const facts = pairs => `<dl class="perf-facts">${pairs.map(([label, value]) => `<dt>${escape(label)}</dt><dd>${escape(value ?? '—')}</dd>`).join('')}</dl>`;
            const hardwareKey = name => String(name || '').toLowerCase().replace(/[^a-z0-9\u4e00-\u9fff]/g, '');
            const gpuTemperatures = gpu => {
                const key = hardwareKey(gpu.name);
                const readings = temperatures.filter(t => key && hardwareKey(t.hardware) === key && Number.isFinite(t.celsius));
                return readings.length
                    ? readings.map(t => [sensorName(t.name), `${t.celsius.toFixed(0)} °C`])
                    : Number.isFinite(gpu.temperature) && gpu.temperature >= 0 ? [['温度', `${gpu.temperature.toFixed(0)} °C`]] : [];
            };
            const hardwareCards = this.options.details === false ? '' : `
                <article class="perf-card perf-gpu-card"><header><div><h3>显卡</h3><p>GPU 占用 · 显存 · 温度 · 驱动</p></div></header>${(info.gpus || []).map(g => `<section class="perf-device"><h4>${escape(g.name)}</h4>${facts([['占用', g.usagePercent >= 0 ? percent(g.usagePercent) : '不可用'], ['显存', g.memoryMB > 0 ? `${g.memoryUsedMB >= 0 ? `${g.memoryUsedMB} / ` : ''}${g.memoryMB} MB` : '不可用'], ...gpuTemperatures(g), ['驱动', g.driverVersion || '—']])}${g.memoryMB > 0 && g.memoryUsedMB >= 0 ? `<progress aria-label="显存占用" max="${g.memoryMB}" value="${g.memoryUsedMB}"></progress>` : ''}</section>`).join('') || '<div class="perf-empty">未检测到显卡</div>'}</article>
                <article class="perf-card perf-disk-card"><header><div><h3>磁盘空间</h3><p>分区容量与可用空间</p></div></header><div class="perf-disk-grid">${(info.drives || []).map(d => `<section class="perf-device"><div class="perf-device-heading"><h4>${escape(d.name)}</h4><span>${escape(d.driveFormat || '')} · ${percent(d.usagePercent)}</span></div><div class="perf-footer">已用 ${escape(d.usedGB)} / ${escape(d.totalGB)} GB · 可用 ${escape(d.freeGB)} GB</div><progress aria-label="${escape(d.name)} 空间占用" max="100" value="${Number.isFinite(d.usagePercent) ? d.usagePercent : 0}"></progress></section>`).join('') || '<div class="perf-empty">未检测到磁盘</div>'}</div></article>`;
            const adapters = (info.networkAdapters || []).map(n => `<section class="perf-device"><h4>${escape(n.name)}</h4>${facts([['连接速率', n.speedMbps >= 1000 ? `${n.speedMbps / 1000} Gbps` : `${n.speedMbps || 0} Mbps`], ...(n.macAddress ? [['MAC', n.macAddress]] : [])])}</section>`).join('');
            this.layoutObserver.disconnect();
            this.element.innerHTML = `${this.options.heading === false ? '' : `<div class="perf-heading"><div><h2>系统监控</h2><p>实时采样 · 历史保留 15 分钟</p></div><select class="perf-range" aria-label="历史时间范围"><option value="60">最近 60 秒</option><option value="300">最近 5 分钟</option><option value="900">最近 15 分钟</option></select></div>`}
            <div class="perf-grid">
              <article class="perf-card perf-cpu-card"><header><div><h3>CPU</h3><p>${escape(info.cpuName || '处理器')}</p></div><strong>${percent(latest.cpuPercent)}</strong></header>${cpu}<div class="perf-footer">${info.cpuCores || '—'} 核心 · ${info.cpuLogicalProcessors || info.processorCount || '—'} 逻辑处理器${info.cpuMaxClockSpeedMHz > 0 ? ` · 基准 ${(info.cpuMaxClockSpeedMHz / 1000).toFixed(2)} GHz` : ''}</div></article>
              <article class="perf-card"><header><div><h3>内存</h3><p>${(latest.usedMemoryMB/1024).toFixed(1)} / ${(latest.totalMemoryMB/1024).toFixed(1)} GB</p></div><strong>${percent(latest.memoryPercent)}</strong></header>${ram}<div class="perf-footer">可用 ${((latest.totalMemoryMB-latest.usedMemoryMB)/1024).toFixed(1)} GB</div></article>
                <article class="perf-card perf-network-card"><header><div><h3>网络</h3><select class="perf-adapter" aria-label="网络适配器">${networks.map(n=>`<option value="${escape(n.name)}">${escape(n.name)}</option>`).join('')}</select></div></header><div class="perf-network-values"><span style="color:${colors[2]}">↓ ${rate(network.receiveBytesPerSecond)}</span><span style="color:${colors[3]}">↑ ${rate(network.sendBytesPerSecond)}</span></div>${net}<div class="perf-footer">接收 / 发送 · 所选网卡实际吞吐率</div>${adapters ? `<details class="perf-inline-details" data-panel="adapters"><summary>网卡详情 · ${(info.networkAdapters || []).length} 个</summary>${adapters}</details>` : ''}</article>
                ${hardwareCards}
                <article class="perf-card perf-thermal-card"><header><div><h3>设备温度</h3><p>${escape(latest.sensorStatus||'仅显示已读取的传感器')}</p></div></header><div class="perf-temperature-values">${temperatures.map((t,i)=>`<span title="${escape(t.hardware||'')}" style="color:${colors[i%colors.length]}">${escape(sensorName(t.name))} <b>${t.celsius.toFixed(0)} °C</b>${t.hardware?`<small> · ${escape(t.hardware)}</small>`:''}</span>`).join('')}</div>${temp}</article>
                <article class="perf-card perf-fans-card"><header><div><h3>风扇与水泵</h3><p>实际转速 · RPM</p></div></header>${fans.length ? `<div class="perf-temperature-values">${fans.map(f=>`<span title="${escape(f.hardware||'')}">${escape(sensorName(f.name))} <b>${Math.round(f.rpm)} RPM</b></span>`).join('')}</div>` : '<div class="perf-empty">设备没有提供可读取的转速传感器</div>'}</article>
            ${coreTemperatures.length?`<details class="perf-details perf-core-temperatures" data-panel="core-temperatures"><summary>CPU 核心温度 · ${coreTemperatures.length} 核</summary><div class="perf-core-grid">${coreTemperatures.map((t,i)=>`<article><div class="perf-footer">${escape(sensorName(t.name))} · ${t.celsius.toFixed(0)} °C</div>${chart(history,[{get:s=>s.temperatures?.find(x=>x.id===t.id)?.celsius,color:colors[0]}],this.seconds,110,'°C')}</article>`).join('')}</div></details>`:''}
            </div>
            ${latest.cpuCores?.length ? `<details class="perf-details" data-panel="cores"><summary>逻辑处理器曲线 · ${latest.cpuCores.length} 个</summary><div class="perf-core-grid">${latest.cpuCores.map((v,i)=>`<article><div class="perf-footer">CPU ${i} · ${percent(v)}</div>${chart(history,[{get:s=>s.cpuCores?.[i],color:colors[0]}],this.seconds,100,'%')}</article>`).join('')}</div></details>` : ''}`;
            const range = this.options.rangeElement || this.element.querySelector('.perf-range');
            if (range) { range.value = String(this.seconds); range.onchange = () => { this.seconds = Number(range.value); this.data = null; this.poll(true); }; }
            const adapter = this.element.querySelector('.perf-adapter'); adapter.value = this.adapter; adapter.onchange = () => {this.adapter=adapter.value;this.render();};
            this.element.querySelectorAll('details').forEach(d => {
                d.open = open.has(d.dataset.panel);
                d.addEventListener('toggle', () => this.layoutCards());
            });
            this.layoutCards();
            this.element.querySelectorAll('.perf-grid > .perf-card, .perf-grid > .perf-details').forEach(card => this.layoutObserver.observe(card));
        }
    }
    window.PerformanceDashboard = {mount:(element,options)=>new Dashboard(element,options)};
    document.addEventListener('DOMContentLoaded', () => {
        const element = document.querySelector('[data-performance-dashboard]');
        if (!element) return;
        element.performanceDashboard = new Dashboard(element, {
            autoPoll: false,
            heading: false,
            rangeElement: document.getElementById('system-history-range')
        });
    });
})();
