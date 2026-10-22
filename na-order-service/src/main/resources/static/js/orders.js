(function () {
    'use strict';

    var listEl = document.getElementById('orders-list');
    var statusEl = document.getElementById('orders-status');
    var emptyEl = document.getElementById('orders-empty');
    var alertEl = document.getElementById('orders-alert');
    var paginationEl = document.getElementById('orders-pagination');
    var pageInfoEl = document.getElementById('page-info');
    var prevEl = document.getElementById('prev-page');
    var nextEl = document.getElementById('next-page');
    var filtersEl = document.getElementById('status-filters');

    var currentStatus = '';
    var currentPage = 0;
    var pageSize = 10;
    var totalPages = 0;

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
        alertEl.appendChild(el('div', 'alert alert-error', message));
    }

    function formatDate(value) {
        if (!value) {
            return '-';
        }
        var date = new Date(value);
        if (isNaN(date.getTime())) {
            return String(value).replace('T', ' ');
        }
        return date.toLocaleString('zh-CN', { hour12: false });
    }

    function buildOrderCard(order) {
        var card = el('article', 'order-card');

        var head = el('div', 'order-card-head');
        var no = el('span', 'order-no', order.orderNo);
        var badge = el('span', 'status ' + window.UI.statusClass(order.status), window.UI.statusLabel(order.status));
        head.appendChild(no);
        head.appendChild(badge);
        card.appendChild(head);

        var body = el('div', 'order-card-body');
        var info = el('div');
        info.appendChild(el('div', 'order-amount', '¥' + window.UI.formatMoney(order.totalAmount)));
        info.appendChild(el('div', 'order-item-text',
            order.itemCount + ' 件商品 · 创建于 ' + formatDate(order.createdAt)));
        body.appendChild(info);

        var actions = el('div', 'order-actions');
        var detail = el('a', 'btn btn-secondary btn-sm', '查看详情');
        detail.href = '/orders/' + encodeURIComponent(order.orderNo);
        actions.appendChild(detail);

        if (order.status === 'PENDING_PAYMENT') {
            var pay = el('button', 'btn btn-primary btn-sm', '支付');
            pay.type = 'button';
            pay.addEventListener('click', function () {
                payOrder(order.orderNo, pay);
            });
            actions.appendChild(pay);

            var cancel = el('button', 'btn btn-danger btn-sm', '取消订单');
            cancel.type = 'button';
            cancel.addEventListener('click', function () {
                cancelOrder(order.orderNo, cancel);
            });
            actions.appendChild(cancel);
        }
        body.appendChild(actions);
        card.appendChild(body);
        return card;
    }

    function cancelOrder(orderNo, button) {
        if (!window.confirm('确定取消该订单吗？取消后库存会回补。')) {
            return;
        }
        button.disabled = true;
        button.textContent = '取消中...';
        window.api.post('/api/orders/' + encodeURIComponent(orderNo) + '/cancel').then(function () {
            window.UI.showToast('订单已取消', 'success');
            loadOrders();
        }).catch(function (error) {
            window.UI.showToast(error.message || '取消失败', 'error');
            button.disabled = false;
            button.textContent = '取消订单';
        });
    }

    function payOrder(orderNo, button) {
        button.disabled = true;
        button.textContent = '支付中...';
        window.api.post('/api/payments', { orderNo: orderNo }).then(function (payment) {
            window.UI.showToast('支付成功，支付单号：' + payment.paymentNo, 'success');
            loadOrders();
        }).catch(function (error) {
            window.UI.showToast(error.message || '支付失败', 'error');
            button.disabled = false;
            button.textContent = '支付';
        });
    }

    function renderPage(result) {
        listEl.innerHTML = '';
        totalPages = result.totalPages;
        currentPage = result.page;

        if (!result.content || result.content.length === 0) {
            emptyEl.classList.remove('hidden');
            paginationEl.classList.add('hidden');
            return;
        }

        emptyEl.classList.add('hidden');
        result.content.forEach(function (order) {
            listEl.appendChild(buildOrderCard(order));
        });

        if (totalPages > 1) {
            paginationEl.classList.remove('hidden');
            pageInfoEl.textContent = '第 ' + (currentPage + 1) + ' / ' + totalPages
                + ' 页 · 共 ' + result.totalElements + ' 单';
            prevEl.disabled = currentPage <= 0;
            nextEl.disabled = currentPage >= totalPages - 1;
        } else {
            paginationEl.classList.add('hidden');
        }
    }

    async function loadOrders() {
        statusEl.classList.remove('hidden');
        statusEl.innerHTML = '';
        statusEl.appendChild(el('span', 'spinner'));
        statusEl.appendChild(document.createTextNode('正在加载订单...'));
        showAlert('');

        var url = '/api/orders?page=' + currentPage + '&size=' + pageSize;
        if (currentStatus) {
            url += '&status=' + encodeURIComponent(currentStatus);
        }

        try {
            var result = await window.api.get(url);
            statusEl.classList.add('hidden');
            renderPage(result);
        } catch (error) {
            statusEl.classList.add('hidden');
            listEl.innerHTML = '';
            emptyEl.classList.add('hidden');
            paginationEl.classList.add('hidden');
            showAlert(error.message || '订单加载失败');
        }
    }

    filtersEl.addEventListener('click', function (event) {
        var target = event.target;
        if (!target || !target.getAttribute('data-status')) {
            var clicked = target && target.closest ? target.closest('[data-status]') : null;
            if (!clicked) {
                return;
            }
            target = clicked;
        }
        var status = target.getAttribute('data-status');
        if (status === currentStatus) {
            return;
        }
        currentStatus = status;
        currentPage = 0;
        Array.prototype.forEach.call(filtersEl.querySelectorAll('[data-status]'), function (chip) {
            chip.classList.toggle('active', chip === target);
        });
        loadOrders();
    });

    prevEl.addEventListener('click', function () {
        if (currentPage > 0) {
            currentPage--;
            loadOrders();
        }
    });

    nextEl.addEventListener('click', function () {
        if (currentPage < totalPages - 1) {
            currentPage++;
            loadOrders();
        }
    });

    loadOrders();
})();
