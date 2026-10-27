(function () {
    'use strict';

    var form = document.getElementById('assistant-form');
    var input = document.getElementById('assistant-input');
    var counter = document.getElementById('assistant-counter');
    var submitButton = document.getElementById('assistant-submit');
    var resultEl = document.getElementById('assistant-result');

    if (!form || !input || !counter || !submitButton || !resultEl) {
        return;
    }

    var MAX_LENGTH = 300;
    var MAX_QUANTITY = 99;
    var LOW_CONFIDENCE = 0.7;

    var DEGRADED_HINTS = {
        DISABLED: 'AI 模型尚未配置',
        UNREACHABLE: 'AI 服务暂不可用',
        TIMEOUT: 'AI 解析超时',
        UPSTREAM_ERROR: 'AI 服务返回异常',
        INVALID_OUTPUT: 'AI 返回结果无法解析'
    };

    var SOURCE_LABELS = {
        RULE: '规则匹配',
        MODEL: 'AI 补充',
        PICK: '手动点选'
    };

    var state = null;

    function el(tag, className, text) {
        var node = document.createElement(tag);
        if (className) {
            node.className = className;
        }
        if (text !== undefined && text !== null) {
            node.textContent = text;
        }
        return node;
    }

    function money(value) {
        return window.UI.formatMoney(value);
    }

    function clampQuantity(value) {
        var quantity = Math.floor(Number(value));
        if (!isFinite(quantity) || quantity < 1) {
            return 1;
        }
        return Math.min(quantity, MAX_QUANTITY);
    }

    function updateCounter() {
        counter.textContent = input.value.length + '/' + MAX_LENGTH;
    }

    function setLoading(loading) {
        submitButton.disabled = loading;
        submitButton.textContent = loading ? '解析中...' : '生成草稿';
    }

    function normalizeDraft(draft) {
        return {
            items: (draft.items || []).map(function (item) {
                return {
                    productId: Number(item.productId),
                    name: item.name || '',
                    quantity: clampQuantity(item.quantity),
                    unitPrice: Number(item.unitPrice) || 0,
                    stockShortage: !!item.stockShortage,
                    source: item.source || 'RULE',
                    score: Number(item.score) || 0
                };
            }),
            unmatched: draft.unmatched || [],
            questions: draft.questions || [],
            degraded: !!draft.degraded,
            degradedReason: draft.degradedReason || null
        };
    }

    function findItem(productId) {
        var target = Number(productId);
        for (var i = 0; i < state.items.length; i++) {
            if (state.items[i].productId === target) {
                return state.items[i];
            }
        }
        return null;
    }

    function totalQuantity() {
        return state.items.reduce(function (sum, item) {
            return sum + item.quantity;
        }, 0);
    }

    function totalAmount() {
        return state.items.reduce(function (sum, item) {
            return sum + item.unitPrice * item.quantity;
        }, 0);
    }

    function confidence() {
        if (!state || state.items.length === 0) {
            return 0;
        }
        return state.items.reduce(function (lowest, item) {
            var score = item.source === 'PICK' ? 1 : item.score;
            return Math.min(lowest, isFinite(score) ? score : 0);
        }, 1);
    }

    function sourceTag(source) {
        var className = 'draft-tag';
        if (source === 'MODEL') {
            className += ' draft-tag-model';
        } else if (source === 'PICK') {
            className += ' draft-tag-pick';
        }
        return el('span', className, SOURCE_LABELS[source] || '');
    }

    function buildQuantityControl(item) {
        var control = el('div', 'qty-control');

        var minus = el('button', null, '−');
        minus.type = 'button';
        minus.setAttribute('aria-label', '减少数量');
        minus.disabled = item.quantity <= 1;
        minus.addEventListener('click', function () {
            item.quantity = clampQuantity(item.quantity - 1);
            render();
        });

        var field = document.createElement('input');
        field.type = 'number';
        field.min = '1';
        field.max = String(MAX_QUANTITY);
        field.value = String(item.quantity);
        field.setAttribute('aria-label', '草稿数量');
        field.addEventListener('change', function () {
            item.quantity = clampQuantity(field.value);
            render();
        });

        var plus = el('button', null, '+');
        plus.type = 'button';
        plus.setAttribute('aria-label', '增加数量');
        plus.disabled = item.quantity >= MAX_QUANTITY;
        plus.addEventListener('click', function () {
            item.quantity = clampQuantity(item.quantity + 1);
            render();
        });

        control.appendChild(minus);
        control.appendChild(field);
        control.appendChild(plus);
        return control;
    }

    function buildItemRow(item) {
        var row = el('article', 'draft-item');
        var main = el('div', 'draft-item-main');

        var nameRow = el('div', 'draft-item-name');
        nameRow.appendChild(document.createTextNode(item.name));
        nameRow.appendChild(sourceTag(item.source));
        main.appendChild(nameRow);

        main.appendChild(el('div', 'draft-item-sub',
            '单价 ¥' + money(item.unitPrice) + ' · 小计 ¥' + money(item.unitPrice * item.quantity)));
        if (item.stockShortage) {
            main.appendChild(el('div', 'draft-item-warning', '库存不足，结算时可能失败'));
        }
        row.appendChild(main);

        row.appendChild(buildQuantityControl(item));
        row.appendChild(el('div', 'draft-item-total', '¥' + money(item.unitPrice * item.quantity)));

        var remove = el('button', 'link-button', '移除');
        remove.type = 'button';
        remove.addEventListener('click', function () {
            state.items = state.items.filter(function (candidate) {
                return candidate.productId !== item.productId;
            });
            render();
        });
        row.appendChild(remove);
        return row;
    }

    function buildQuestion(question) {
        var wrap = el('div', 'draft-question');
        wrap.appendChild(el('p', null, question.text || '请选择商品'));

        var list = el('div', 'candidate-list');
        (question.candidates || []).forEach(function (candidate) {
            var soldOut = Number(candidate.stock) <= 0;
            var picked = !!findItem(candidate.productId);
            var chip = el('button', 'candidate-chip' + (picked ? ' is-picked' : ''));
            chip.type = 'button';
            chip.disabled = soldOut;
            chip.appendChild(el('span', null, candidate.name + (soldOut ? '（售罄）' : '')));
            chip.appendChild(el('span', 'candidate-price', '¥' + money(candidate.price)));
            chip.appendChild(el('span', 'candidate-stock', soldOut ? '' : '库存 ' + candidate.stock));
            chip.addEventListener('click', function () {
                pickCandidate(candidate);
            });
            list.appendChild(chip);
        });
        wrap.appendChild(list);
        return wrap;
    }

    function pickCandidate(candidate) {
        var existing = findItem(candidate.productId);
        if (existing) {
            existing.quantity = clampQuantity(existing.quantity + 1);
        } else {
            state.items.push({
                productId: Number(candidate.productId),
                name: candidate.name,
                quantity: 1,
                unitPrice: Number(candidate.price) || 0,
                stockShortage: false,
                source: 'PICK',
                score: 1
            });
            window.UI.showToast('已加入草稿：' + candidate.name, 'success');
        }
        render();
    }

    function buildDegradedNotice() {
        var hint = DEGRADED_HINTS[state.degradedReason] || 'AI 解析未成功';
        var box = el('div', 'alert alert-warning', hint + '，已使用本地规则匹配。');
        box.setAttribute('role', 'status');
        return box;
    }

    function buildConfidenceNotice() {
        var box = el('div', 'alert alert-warning', '识别结果确定性较低，请核对商品与数量。');
        box.setAttribute('role', 'status');
        return box;
    }

    function render() {
        resultEl.innerHTML = '';
        if (!state) {
            resultEl.classList.add('hidden');
            return;
        }
        resultEl.classList.remove('hidden');

        var card = el('div', 'draft-card');

        var head = el('div', 'draft-head');
        var headText = el('div');
        headText.appendChild(el('h3', null, '购物车草稿'));
        headText.appendChild(el('p', 'draft-head-meta', state.items.length > 0
            ? '共 ' + totalQuantity() + ' 件 · 预估 ¥' + money(totalAmount())
            : '暂无可加入购物车的商品'));
        head.appendChild(headText);
        head.appendChild(el('span', 'draft-badge', state.degraded ? '本地规则' : 'AI 解析'));
        card.appendChild(head);

        if (state.degraded) {
            card.appendChild(buildDegradedNotice());
        }
        if (state.items.length > 0 && confidence() < LOW_CONFIDENCE) {
            card.appendChild(buildConfidenceNotice());
        }

        if (state.items.length > 0) {
            var items = el('div', 'draft-items');
            state.items.forEach(function (item) {
                items.appendChild(buildItemRow(item));
            });
            card.appendChild(items);
        } else if (state.questions.length === 0) {
            card.appendChild(el('div', 'draft-empty',
                '没有识别到可购买的商品，换个说法试试，或直接从下方挑选。'));
        }

        if (state.questions.length > 0) {
            var questionSection = el('div', 'draft-section');
            questionSection.appendChild(el('h4', null, '需要确认'));
            state.questions.forEach(function (question) {
                questionSection.appendChild(buildQuestion(question));
            });
            card.appendChild(questionSection);
        }

        if (state.unmatched.length > 0) {
            var unmatchedSection = el('div', 'draft-section');
            unmatchedSection.appendChild(el('h4', null, '没找到的商品'));
            var unmatchedList = el('ul', 'draft-unmatched-list');
            state.unmatched.forEach(function (entry) {
                var text = entry && entry.text ? entry.text : '未知内容';
                var reason = entry && entry.reason ? '（' + entry.reason + '）' : '';
                unmatchedList.appendChild(el('li', null, text + reason));
            });
            unmatchedSection.appendChild(unmatchedList);
            card.appendChild(unmatchedSection);
        }

        var footer = el('div', 'draft-footer');
        var total = el('div', 'draft-total');
        total.appendChild(document.createTextNode('合计'));
        total.appendChild(el('strong', null, '¥' + money(totalAmount())));
        footer.appendChild(total);

        var actions = el('div', 'draft-actions');
        var discard = el('button', 'btn btn-secondary btn-sm', '放弃草稿');
        discard.type = 'button';
        discard.addEventListener('click', function () {
            state = null;
            render();
        });
        actions.appendChild(discard);

        var add = el('button', 'btn btn-primary btn-sm', '加入购物车');
        add.type = 'button';
        add.id = 'draft-add';
        add.disabled = state.items.length === 0;
        add.addEventListener('click', addDraftToCart);
        actions.appendChild(add);

        footer.appendChild(actions);
        card.appendChild(footer);
        resultEl.appendChild(card);
    }

    function addDraftToCart() {
        if (!state || state.items.length === 0) {
            return;
        }
        state.items.forEach(function (item) {
            window.Cart.add(item.productId, item.quantity);
        });
        window.UI.showToast('已加入购物车，正在前往结算', 'success');
        window.location.href = '/checkout';
    }

    async function handleSubmit(event) {
        event.preventDefault();
        var text = input.value.trim();
        if (!text) {
            window.UI.showToast('请输入想购买的商品描述', 'error');
            input.focus();
            return;
        }
        setLoading(true);
        try {
            var draft = await window.api.post('/api/assistant/drafts', { text: text });
            state = normalizeDraft(draft);
            render();
        } catch (error) {
            window.UI.showToast(error.message || '解析失败，请稍后重试', 'error');
        } finally {
            setLoading(false);
        }
    }

    input.addEventListener('input', updateCounter);
    input.addEventListener('keydown', function (event) {
        if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
            event.preventDefault();
            form.requestSubmit();
        }
    });
    form.addEventListener('submit', handleSubmit);
    updateCounter();
})();
