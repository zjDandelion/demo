(function () {
    'use strict';

    var grid = document.getElementById('product-grid');
    var statusEl = document.getElementById('products-status');
    var countEl = document.getElementById('product-count');

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

    function thumbStyle(product) {
        var hue = (Number(product.id) * 47) % 360;
        return 'linear-gradient(135deg, hsl(' + hue + ', 72%, 62%), hsl(' + ((hue + 42) % 360) + ', 68%, 48%))';
    }

    function stockNode(product) {
        if (product.stock <= 0) {
            return el('span', 'stock stock-out', '已售罄');
        }
        if (product.stock <= 5) {
            return el('span', 'stock stock-low', '仅剩 ' + product.stock + ' 件');
        }
        return el('span', 'stock', '库存 ' + product.stock + ' 件');
    }

    function buildCard(product) {
        var card = el('article', 'product-card' + (product.stock <= 0 ? ' is-sold-out' : ''));

        var thumb = el('div', 'product-thumb');
        thumb.style.background = thumbStyle(product);
        thumb.appendChild(el('span', 'product-thumb-mark', product.name ? product.name.charAt(0) : '?'));
        card.appendChild(thumb);

        card.appendChild(el('h2', 'product-name', product.name));
        card.appendChild(el('p', 'product-desc', product.description || ''));

        var meta = el('div', 'product-meta');
        meta.appendChild(el('span', 'price', window.UI.formatMoney(product.price)));
        meta.appendChild(stockNode(product));
        card.appendChild(meta);

        var actions = el('div', 'card-actions');
        var button = el('button', 'btn btn-primary btn-sm', product.stock > 0 ? '加入购物车' : '已售罄');
        button.type = 'button';
        button.disabled = product.stock <= 0;
        button.addEventListener('click', function () {
            if (product.stock <= 0) {
                return;
            }
            window.Cart.add(product.id, 1);
            window.UI.showToast('已加入购物车：' + product.name, 'success');
        });
        actions.appendChild(button);
        card.appendChild(actions);
        return card;
    }

    function render(products) {
        grid.innerHTML = '';
        products.forEach(function (product) {
            grid.appendChild(buildCard(product));
        });
        countEl.textContent = products.length + ' 件商品在售';
        statusEl.classList.add('hidden');
        grid.classList.remove('hidden');
    }

    function showError(message) {
        grid.classList.add('hidden');
        statusEl.classList.remove('hidden');
        statusEl.textContent = '';
        statusEl.appendChild(el('span', null, message + ' '));
        var retry = el('button', 'btn btn-secondary btn-sm', '重新加载');
        retry.type = 'button';
        retry.addEventListener('click', load);
        statusEl.appendChild(retry);
    }

    async function load() {
        statusEl.classList.remove('hidden');
        statusEl.innerHTML = '';
        statusEl.appendChild(el('span', 'spinner'));
        statusEl.appendChild(document.createTextNode('正在加载商品...'));
        try {
            var products = await window.api.get('/api/products');
            render(products);
        } catch (error) {
            showError(error.message || '商品加载失败');
        }
    }

    load();
})();
