(function () {
    'use strict';

    var STORAGE_KEY = 'order.cart.v1';
    var MAX_QUANTITY = 99;

    function normalize(rawItem) {
        if (!rawItem || typeof rawItem !== 'object') {
            return null;
        }
        var productId = Number(rawItem.productId);
        var quantity = Number(rawItem.quantity);
        if (!isFinite(productId) || productId <= 0 || Math.floor(productId) !== productId) {
            return null;
        }
        if (!isFinite(quantity) || quantity <= 0) {
            return null;
        }
        quantity = Math.min(Math.floor(quantity), MAX_QUANTITY);
        return { productId: productId, quantity: quantity };
    }

    function read() {
        var raw;
        try {
            raw = window.localStorage.getItem(STORAGE_KEY);
        } catch (e) {
            return [];
        }
        if (!raw) {
            return [];
        }
        try {
            var parsed = JSON.parse(raw);
            if (!Array.isArray(parsed)) {
                throw new Error('invalid cart');
            }
            var merged = {};
            parsed.forEach(function (item) {
                var normalized = normalize(item);
                if (!normalized) {
                    return;
                }
                merged[normalized.productId] = Math.min(
                    (merged[normalized.productId] || 0) + normalized.quantity,
                    MAX_QUANTITY
                );
            });
            var result = Object.keys(merged).map(function (key) {
                return { productId: Number(key), quantity: merged[key] };
            });
            result.sort(function (a, b) {
                return a.productId - b.productId;
            });
            return result;
        } catch (e) {
            window.localStorage.removeItem(STORAGE_KEY);
            return [];
        }
    }

    function write(items) {
        try {
            window.localStorage.setItem(STORAGE_KEY, JSON.stringify(items));
        } catch (e) {
            // Storage can be unavailable in private mode; the cart simply stays in memory.
        }
        updateBadge(items);
    }

    function count(items) {
        var source = items || read();
        return source.reduce(function (sum, item) {
            return sum + item.quantity;
        }, 0);
    }

    function add(productId, quantity) {
        var amount = Math.max(1, Math.min(Number(quantity) || 1, MAX_QUANTITY));
        var items = read();
        var found = null;
        for (var i = 0; i < items.length; i++) {
            if (items[i].productId === Number(productId)) {
                found = items[i];
                break;
            }
        }
        if (found) {
            found.quantity = Math.min(found.quantity + amount, MAX_QUANTITY);
        } else {
            items.push({ productId: Number(productId), quantity: amount });
        }
        write(items);
    }

    function setQuantity(productId, quantity) {
        var items = read();
        var next = [];
        items.forEach(function (item) {
            if (item.productId === Number(productId)) {
                var value = Math.floor(Number(quantity));
                if (isFinite(value) && value > 0) {
                    item.quantity = Math.min(value, MAX_QUANTITY);
                    next.push(item);
                }
            } else {
                next.push(item);
            }
        });
        write(next);
    }

    function remove(productId) {
        write(read().filter(function (item) {
            return item.productId !== Number(productId);
        }));
    }

    function clear() {
        write([]);
    }

    function updateBadge(items) {
        var badge = document.getElementById('cart-badge');
        if (!badge) {
            return;
        }
        var total = count(items);
        badge.textContent = String(total);
        badge.style.display = total > 0 ? '' : 'none';
    }

    window.Cart = {
        read: read,
        write: write,
        add: add,
        setQuantity: setQuantity,
        remove: remove,
        clear: clear,
        count: count,
        updateBadge: updateBadge,
        MAX_QUANTITY: MAX_QUANTITY
    };

    function init() {
        updateBadge();
        var logoutForm = document.getElementById('logout-form');
        if (logoutForm) {
            logoutForm.addEventListener('submit', function () {
                clear();
            });
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
