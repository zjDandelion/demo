'use strict';

const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');
const os = require('os');

const BASE = process.env.E2E_BASE || 'http://localhost:8090';
const CHROME = process.env.CHROME_PATH ||
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'na-e2e-'));
const port = 9333;
const chrome = spawn(CHROME, [
    '--headless=new',
    '--disable-gpu',
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-extensions',
    `--remote-debugging-port=${port}`,
    `--user-data-dir=${profile}`,
    'about:blank'
], { stdio: 'ignore', windowsHide: true });

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const results = [];
const consoleErrors = [];
let ws;
let nextId = 1;
const pending = new Map();

function check(name, condition, detail) {
    results.push({ name, ok: !!condition, detail });
    console.log(`${condition ? 'PASS' : 'FAIL'}  ${name}${detail ? ' :: ' + detail : ''}`);
    if (!condition) {
        process.exitCode = 1;
    }
}

async function waitHttp(url, timeoutMs) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        try {
            const response = await fetch(url);
            if (response.ok) {
                return await response.json();
            }
        } catch (error) {
            // Chrome is still starting.
        }
        await sleep(200);
    }
    throw new Error('Chrome DevTools endpoint did not start');
}

function send(method, params = {}) {
    return new Promise((resolve, reject) => {
        const id = nextId++;
        pending.set(id, { resolve, reject });
        ws.send(JSON.stringify({ id, method, params }));
    });
}

async function evaluate(expression, awaitPromise = true) {
    const response = await send('Runtime.evaluate', {
        expression,
        returnByValue: true,
        awaitPromise
    });
    if (response.exceptionDetails) {
        throw new Error('page evaluation failed: ' +
            (response.exceptionDetails.text || 'unknown') + ' in ' + expression);
    }
    return response.result ? response.result.value : undefined;
}

async function waitFor(expression, timeoutMs = 15000, label = expression) {
    const deadline = Date.now() + timeoutMs;
    let lastError;
    while (Date.now() < deadline) {
        try {
            if (await evaluate(expression)) {
                return;
            }
        } catch (error) {
            lastError = error;
        }
        await sleep(200);
    }
    throw new Error(`timeout waiting for ${label}` + (lastError ? ': ' + lastError.message : ''));
}

async function navigate(url) {
    await send('Page.navigate', { url });
    await waitFor(`document.readyState === 'complete' && location.href.indexOf(${JSON.stringify(url)}) === 0`,
        15000, 'navigation to ' + url);
    await sleep(300);
}

async function clickByText(selector, text) {
    const clicked = await evaluate(`(() => {
        const target = Array.from(document.querySelectorAll(${JSON.stringify(selector)}))
            .find((node) => node.textContent.indexOf(${JSON.stringify(text)}) >= 0 && !node.disabled);
        if (!target) return false;
        target.click();
        return true;
    })()`);
    if (!clicked) {
        throw new Error(`button not found: ${selector} containing ${text}`);
    }
}

async function apiJson(method, url, body) {
    return evaluate(`(async () => {
        const response = await fetch(${JSON.stringify(url)}, {
            method: ${JSON.stringify(method)},
            headers: { 'Content-Type': 'application/json' },
            body: ${body ? 'JSON.stringify(' + JSON.stringify(body) + ')' : 'null'}
        });
        return { status: response.status, body: await response.json() };
    })()`);
}

(async () => {
    try {
        const version = await waitHttp(`http://127.0.0.1:${port}/json/version`, 20000);
        check('Chrome 无头实例启动', !!version.webSocketDebuggerUrl);
        const targetResponse = await fetch(`http://127.0.0.1:${port}/json/new?about:blank`,
            { method: 'PUT' });
        const target = await targetResponse.json();
        ws = new WebSocket(target.webSocketDebuggerUrl);
        await new Promise((resolve, reject) => {
            ws.addEventListener('open', resolve, { once: true });
            ws.addEventListener('error', reject, { once: true });
        });
        ws.addEventListener('message', (event) => {
            const message = JSON.parse(event.data);
            if (message.id && pending.has(message.id)) {
                const entry = pending.get(message.id);
                pending.delete(message.id);
                if (message.error) {
                    entry.reject(new Error(message.error.message));
                } else {
                    entry.resolve(message.result);
                }
                return;
            }
            if (message.method === 'Runtime.consoleAPICalled' && message.params.type === 'error') {
                consoleErrors.push(message.params.args.map((arg) => arg.value || arg.description).join(' '));
            }
            if (message.method === 'Runtime.exceptionThrown') {
                consoleErrors.push(message.params.exceptionDetails.text || 'page exception');
            }
            if (message.method === 'Log.entryAdded' && message.params.entry.level === 'error') {
                consoleErrors.push(message.params.entry.text);
            }
        });
        await send('Page.enable');
        await send('Runtime.enable');
        await send('Log.enable');

        await navigate(BASE + '/login');
        check('登录页可访问', await evaluate(`document.title.indexOf('登录') >= 0`));

        await evaluate(`(() => {
            document.querySelector('input[name=username]').value = 'demo';
            document.querySelector('input[name=password]').value = '123456';
            document.querySelector('form').submit();
            return true;
        })()`);
        await waitFor(`document.readyState === 'complete' && document.body.innerText.indexOf('8 件商品在售') >= 0`,
            15000, 'product page after login');
        check('演示账号登录成功', await evaluate(`location.pathname === '/'`),
            await evaluate('location.href'));

        const productCount = await evaluate(`document.querySelectorAll('button').length`);
        await evaluate(`(() => {
            const buttons = Array.from(document.querySelectorAll('button'))
                .filter((button) => button.textContent.trim() === '加入购物车');
            buttons[0].click();
            buttons[1].click();
            return buttons.length;
        })()`);
        await sleep(300);
        const cart = await evaluate(`JSON.parse(localStorage.getItem('order.cart.v1'))`);
        check('商品加入购物车', Array.isArray(cart) && cart.length === 2 &&
            cart[0].productId === 1 && cart[1].productId === 2, JSON.stringify(cart));

        await navigate(BASE + '/checkout');
        await waitFor(`document.querySelectorAll('.cart-item').length === 2`);
        check('购物车展示两种商品', true);
        const summaryBefore = await evaluate(`document.getElementById('summary-total').textContent`);
        check('购物车金额计算正确', summaryBefore === '428.00', summaryBefore);

        await evaluate(`(() => {
            const line = document.querySelectorAll('.cart-item')[0];
            const buttons = line.querySelectorAll('.qty-control button');
            buttons[buttons.length - 1].click();
            return true;
        })()`);
        await waitFor(`document.getElementById('summary-total').textContent === '727.00'`);
        check('购物车数量调整后金额更新', true, '¥727.00');

        await evaluate(`document.querySelector('#submit-order').click()`);
        await waitFor(`location.pathname.indexOf('/orders/') === 0 && document.body.innerText.indexOf('待支付') >= 0`,
            15000, 'order detail after submit');
        const orderNo = await evaluate(`location.pathname.split('/').pop()`);
        check('提交订单并进入详情页', /^ORD[0-9A-Z]+$/.test(orderNo), orderNo);
        const detailTotal = await evaluate(`document.getElementById('detail-total').textContent`);
        check('订单详情金额正确', detailTotal === '¥727.00', detailTotal);

        await clickByText('#detail-actions button', '支付订单');
        await waitFor(`document.getElementById('detail-badge').textContent.indexOf('已支付') >= 0`,
            15000, 'paid status');
        check('订单支付成功', true);
        const paidAt = await evaluate(`document.getElementById('detail-paid-at').textContent`);
        check('支付时间已展示', !!paidAt && paidAt !== '-', paidAt);
        const toast = await evaluate(`(() => {
            const node = document.querySelector('.toast');
            return node ? node.textContent : '';
        })()`);
        check('支付成功提示包含支付单号', toast.indexOf('支付单号') >= 0, toast);

        await navigate(BASE + '/orders');
        await waitFor(`document.querySelectorAll('.order-card').length >= 1`);
        check('订单列表展示订单', true);
        await evaluate(`document.querySelector('[data-status="PAID"]').click()`);
        await waitFor(`document.querySelector('.filter-chip.active').getAttribute('data-status') === 'PAID' &&
            document.querySelectorAll('.order-card').length === 1`);
        const paidBadge = await evaluate(`document.querySelector('.order-card .status').textContent`);
        check('状态筛选（已支付）', paidBadge.indexOf('已支付') >= 0, paidBadge);

        const beforeStock = await apiJson('GET', '/api/products');
        const product7 = beforeStock.body.find((product) => product.id === 7);
        check('筛选后商品库存基线', product7 && product7.stock === 50, String(product7 && product7.stock));

        for (let i = 0; i < 11; i++) {
            const created = await apiJson('POST', '/api/orders', {
                items: [{ productId: 7, quantity: 1 }]
            });
            if (created.status !== 201) {
                throw new Error('failed to create bulk order: ' + JSON.stringify(created));
            }
        }
        const afterOrders = await apiJson('GET', '/api/orders?page=0&size=10');
        check('分页数据生成（12 单）', afterOrders.body.totalElements === 12,
            String(afterOrders.body.totalElements));

        await navigate(BASE + '/orders');
        await waitFor(`document.getElementById('orders-pagination') &&
            !document.getElementById('orders-pagination').classList.contains('hidden')`);
        const pageInfo = await evaluate(`document.getElementById('page-info').textContent`);
        check('订单列表分页控件显示', pageInfo.indexOf('第 1 / 2 页') >= 0, pageInfo);
        await evaluate(`document.getElementById('next-page').click()`);
        await waitFor(`document.getElementById('page-info').textContent.indexOf('第 2 / 2 页') >= 0`);
        check('翻到第二页', true);

        await evaluate(`window.confirm = () => true`);
        await clickByText('.order-card button', '取消订单');
        await waitFor(`document.querySelector('.toast') &&
            document.querySelector('.toast').textContent.indexOf('订单已取消') >= 0`,
            15000, 'cancel toast');
        check('取消待支付订单成功', true);

        const afterCancel = await apiJson('GET', '/api/products');
        const product7After = afterCancel.body.find((product) => product.id === 7);
        check('取消订单回补库存', product7After && product7After.stock === 40,
            `expected 40 actual ${product7After && product7After.stock}`);

        await evaluate(`document.querySelector('[data-status="CANCELLED"]').click()`);
        await waitFor(`document.querySelectorAll('.order-card').length >= 1`);
        const cancelledBadge = await evaluate(`document.querySelector('.order-card .status').textContent`);
        check('状态筛选（已取消）', cancelledBadge.indexOf('已取消') >= 0, cancelledBadge);

        await navigate(BASE + '/');
        await waitFor(`document.querySelectorAll('.product-card').length === 8`);
        await evaluate(`(() => {
            const input = document.getElementById('assistant-input');
            input.value = '帮我拿两个机械键盘，再加一张升降桌';
            input.dispatchEvent(new Event('input', { bubbles: true }));
            document.getElementById('assistant-submit').click();
            return true;
        })()`);
        await waitFor(`document.querySelectorAll('.draft-item').length === 1`, 15000, 'assistant draft item');
        const draftName = await evaluate(`document.querySelector('.draft-item-name').textContent`);
        check('AI 草稿命中商品', draftName.indexOf('机械键盘 K87') >= 0, draftName);
        const draftTotal = await evaluate(`document.querySelector('.draft-total strong').textContent`);
        check('AI 草稿金额正确', draftTotal === '¥598.00', draftTotal);
        const draftAlert = await evaluate(`document.querySelector('.draft-card .alert').textContent`);
        check('未配置模型时提示降级', draftAlert.indexOf('本地规则匹配') >= 0, draftAlert);
        const draftUnmatched = await evaluate(`document.querySelector('.draft-unmatched-list li').textContent`);
        check('AI 草稿提示未识别商品', draftUnmatched.indexOf('升降桌') >= 0, draftUnmatched);

        await evaluate(`document.getElementById('draft-add').click()`);
        await waitFor(`location.pathname === '/checkout'`, 15000, 'checkout after assistant draft');
        await waitFor(`document.querySelectorAll('.cart-item').length === 1`);
        const assistantCart = await evaluate(`JSON.parse(localStorage.getItem('order.cart.v1'))`);
        check('AI 草稿加入购物车', Array.isArray(assistantCart) && assistantCart.length === 1 &&
            assistantCart[0].productId === 1 && assistantCart[0].quantity === 2,
            JSON.stringify(assistantCart));

        await navigate(BASE + '/');
        await waitFor(`document.querySelectorAll('.product-card').length === 8`);
        await evaluate(`(() => {
            const input = document.getElementById('assistant-input');
            input.value = '来点外设';
            input.dispatchEvent(new Event('input', { bubbles: true }));
            document.getElementById('assistant-submit').click();
            return true;
        })()`);
        await waitFor(`document.querySelectorAll('.candidate-chip').length === 4`, 15000, 'assistant candidates');
        const questionText = await evaluate(`document.querySelector('.draft-question p').textContent`);
        check('分类歧义给出候选', questionText.indexOf('外设') >= 0, questionText);
        await evaluate(`(() => {
            const chip = Array.from(document.querySelectorAll('.candidate-chip'))
                .find((node) => node.textContent.indexOf('机械键盘 K87') >= 0);
            if (!chip) return false;
            chip.click();
            return true;
        })()`);
        await waitFor(`document.querySelectorAll('.draft-item').length === 1`, 15000, 'picked candidate');
        const pickedTag = await evaluate(`document.querySelector('.draft-item-name .draft-tag').textContent`);
        check('候选点选加入草稿', pickedTag === '手动点选', pickedTag);
        await evaluate(`Array.from(document.querySelectorAll('.draft-actions button'))
            .find((node) => node.textContent === '放弃草稿').click()`);
        await waitFor(`document.getElementById('assistant-result').classList.contains('hidden')`);
        check('放弃草稿清理结果', true);

        await evaluate(`document.getElementById('logout-form').querySelector('button').click()`);
        await waitFor(`location.pathname === '/login' || location.pathname === '/login/logout'`);
        check('退出登录成功', await evaluate(`document.body.innerText.indexOf('登录后体验下单与模拟支付') >= 0`));

        const relevantErrors = consoleErrors.filter((message) =>
            message && message.indexOf('favicon') < 0);
        check('浏览器控制台无报错', relevantErrors.length === 0,
            relevantErrors.join(' | ').substring(0, 300));

        console.log('E2E_RESULT ' + JSON.stringify({
            passed: results.filter((result) => result.ok).length,
            failed: results.filter((result) => !result.ok).length,
            orderNo,
            productCount
        }));
    } catch (error) {
        console.error('E2E_FATAL ' + error.stack);
        process.exitCode = 1;
    } finally {
        try {
            if (ws) {
                ws.close();
            }
        } catch (error) {
            // ignore
        }
        chrome.kill();
        await sleep(300);
        try {
            fs.rmSync(profile, { recursive: true, force: true });
        } catch (error) {
            // ignore
        }
    }
})();
