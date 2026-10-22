(function () {
    'use strict';

    var statusEl = document.getElementById('checkout-status');
    var emptyEl = document.getElementById('checkout-empty');
    var contentEl = document.getElementById('checkout-content');
    var listEl = document.getElementById('cart-list');
    var alertEl = document.getElementById('checkout-alert');
    var summaryCountEl = document.getElementById('summary-count');
    var summaryTotalEl = document.getElementById('summary-total');
    var summaryPayableEl = document.getElementById('summary-payable');
    var submitEl = document.getElementById('submit-order');

    var productsById = {};
    var cartItems = [];
    var invalidCount = 0;

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

    function showAlert(message) {
        alertEl.innerHTML = '';
        if (!message) {
            return;
        }
        var box = el('div', 'alert alert-error', message);
        box.setAttribute('role', 'alert');
        alertEl.appendChild(box);
    }

    function showWarning(message) {
        alertEl.innerHTML = '';
        var box = el('div', 'alert alert-warning', message);
        box.setAttribute('role', 'status');
        alertEl.appendChild(box);
    }

    function updateSummary(totalQuantity, totalAmount) {
        summaryCountEl.textContent = String(totalQuantity);
        summaryTotalEl.textContent = window.UI.formatMoney(totalAmount);
        summaryPayableEl.textContent = window.UI.formatMoney(totalAmount);
    }

    function buildQuantityControl(product, quantity) {
        var control = el('div', 'qty-control');
        var minus = el('button', null, '−');
        minus.type = 'button';
        minus.setAttribute('aria-label', '减少数量');
        minus.disabled = quantity <= 1;
        minus.addEventListener('click', function () {
            changeQuantity(product.id, quantity - 1);
        });

        var input = document.createElement('input');
        input.type = 'number';
        input.min = '1';
        input.max = String(Math.min(99, product.stock));
        input.value = String(quantity);
        input.setAttribute('aria-label', '购买数量');
        input.addEventListener('change', function () {
            var value = Math.floor(Number(input.value));
            if (!isFinite(value) || value < 1) {
                value = 1;
            }
            changeQuantity(product.id, Math.min(value, product.stock, 99));
        });

        var plus = el('button', null, '+');
        plus.type = 'button';
        plus.setAttribute('aria-label', '增加数量');
        plus.disabled = quantity >= Math.min(99, product.stock);
        plus.addEventListener('click', function () {
            changeQuantity(product.id, quantity + 1);
        });

        control.appendChild(minus);
        control.appendChild(input);
        control.appendChild(plus);
        return control;
    }

    function changeQuantity(productId, quantity) {
        window.Cart.setQuantity(productId, quantity);
        cartItems = window.Cart.read();
        render();
    }

    function buildLine(item) {
        var product = productsById[item.productId];
        var line = el('article', 'cart-item');
        var main = el('div', 'cart-item-main');

        if (!product || !product.enabled || product.stock <= 0) {
            main.appendChild(el('div', 'cart-item-name', product ? product.name : '商品 #' + item.productId));
            main.appendChild(el('div', 'cart-item-sub', product ? '商品已售罄或下架' : '商品已不存在'));
            line.appendChild(main);

            var actions = el('div', 'cart-item-actions');
            var remove = el('button', 'btn btn-danger btn-sm', '移除');
            remove.type = 'button';
            remove.addEventListener('click', function () {
                window.Cart.remove(item.productId);
                cartItems = window.Cart.read();
                render();
            });
            actions.appendChild(remove);
            line.appendChild(actions);
            line.appendChild(el('div', 'cart-line-total', '—'));
            invalidCount++;
            return line;
        }

        main.appendChild(el('div', 'cart-item-name', product.name));
        main.appendChild(el('div', 'cart-item-sub', '单价 ¥' + window.UI.formatMoney(product.price)
            + ' · 库存 ' + product.stock + ' 件'));
        line.appendChild(main);

        var actionsWrap = el('div', 'cart-item-actions');
        actionsWrap.appendChild(buildQuantityControl(product, item.quantity));
        var remove = el('button', 'link-button', '移除');
        remove.type = 'button';
        remove.addEventListener('click', function () {
            window.Cart.remove(item.productId);
            cartItems = window.Cart.read();
            render();
        });
        actionsWrap.appendChild(remove);
        line.appendChild(actionsWrap);

        var lineTotal = Number(product.price) * item.quantity;
        line.appendChild(el('div', 'cart-line-total', '¥' + window.UI.formatMoney(lineTotal)));
        return line;
    }

    function render() {
        invalidCount = 0;
        var adjusted = false;
        cartItems.forEach(function (item) {
            var product = productsById[item.productId];
            if (product && product.enabled && product.stock > 0 && item.quantity > product.stock) {
                item.quantity = product.stock;
                window.Cart.setQuantity(item.productId, product.stock);
                adjusted = true;
            }
        });
        if (adjusted) {
            cartItems = window.Cart.read();
            showWarning('部分商品库存不足，购买数量已自动调整为当前可用库存。');
        }

        listEl.innerHTML = '';

        if (cartItems.length === 0) {
            contentEl.classList.add('hidden');
            emptyEl.classList.remove('hidden');
            statusEl.classList.add('hidden');
            return;
        }

        var totalQuantity = 0;
        var totalAmount = 0;
        cartItems.forEach(function (item) {
            listEl.appendChild(buildLine(item));
            var product = productsById[item.productId];
            if (product && product.enabled && product.stock > 0) {
                var quantity = Math.min(item.quantity, product.stock);
                totalQuantity += quantity;
                totalAmount += Number(product.price) * quantity;
            }
        });

        updateSummary(totalQuantity, totalAmount);
        submitEl.disabled = invalidCount > 0 || totalQuantity === 0;
        submitEl.textContent = invalidCount > 0 ? '请先移除不可购买的商品' : '提交订单';

        emptyEl.classList.add('hidden');
        statusEl.classList.add('hidden');
        contentEl.classList.remove('hidden');
    }

    function submitOrder() {
        var items = [];
        cartItems.forEach(function (item) {
            var product = productsById[item.productId];
            if (product && product.enabled && product.stock > 0) {
                items.push({ productId: item.productId, quantity: item.quantity });
            }
        });
        if (items.length === 0) {
            showAlert('购物车中没有可购买的商品');
            return;
        }
        if (items.length > 20) {
            showAlert('一次最多购买 20 种商品，请分批结算');
            return;
        }

        submitEl.disabled = true;
        submitEl.textContent = '提交中...';
        showAlert('');

        window.api.post('/api/orders', { items: items }).then(function (order) {
            window.Cart.clear();
            window.UI.showToast('订单已创建：' + order.orderNo, 'success');
            window.location.href = '/orders/' + encodeURIComponent(order.orderNo);
        }).catch(function (error) {
            showAlert(error.message || '下单失败，请重试');
            submitEl.disabled = false;
            submitEl.textContent = '提交订单';
        });
    }

    async function load() {
        cartItems = window.Cart.read();
        if (cartItems.length === 0) {
            statusEl.classList.add('hidden');
            emptyEl.classList.remove('hidden');
            contentEl.classList.add('hidden');
            return;
        }

        try {
            var products = await window.api.get('/api/products');
            productsById = {};
            products.forEach(function (product) {
                productsById[product.id] = product;
            });
            render();
        } catch (error) {
            statusEl.innerHTML = '';
            statusEl.appendChild(el('div', 'alert alert-error', error.message || '加载商品失败'));
            contentEl.classList.add('hidden');
        }
    }

    submitEl.addEventListener('click', submitOrder);
    load();
})();
